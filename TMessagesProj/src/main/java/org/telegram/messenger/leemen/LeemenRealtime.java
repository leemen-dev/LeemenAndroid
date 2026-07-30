package org.telegram.messenger.leemen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

/**
 * Phase 4 — Realtime push (notify-only). Per bound account, opens a Supabase Realtime websocket DIRECTLY
 * (not via api.leemen.app) and subscribes to channel sync:&lt;sync_account_id&gt;. On a blob_changed broadcast
 * it triggers a normal GET→merge via LeemenSync.onRemoteChanged.
 *
 * Purely advisory: correctness comes from the GET+CAS backstop (foreground / reconnect / 409), so a missed
 * or spoofed notify is harmless (just a no-op GET). The sole client-originated event is an account-generation
 * invalidation hint after the delete endpoint has succeeded; receivers still verify it through authenticated
 * REST before logging out.
 *
 * Threading: OkHttp delivers listener callbacks on its own thread; all access to the per-account arrays is
 * marshalled onto the UI thread, and WebSocket.send() is itself thread-safe.
 */
public final class LeemenRealtime {

    private LeemenRealtime() {}

    private static final int N = UserConfig.MAX_ACCOUNT_COUNT;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final OkHttpClient BROADCAST_HTTP = HTTP.newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long JOIN_TIMEOUT_MS = 15000;
    private static final int MAX_RECONNECT_DELAY_MS = 10_000;
    private static final String EVENT_ACCOUNT_GENERATION_INVALIDATED = "account_generation_invalidated";
    private static final AtomicLong NEXT_REF = new AtomicLong();

    private static final WebSocket[] sockets = new WebSocket[N];
    private static final boolean[] joined = new boolean[N];
    private static final Runnable[] heartbeats = new Runnable[N];
    private static final Runnable[] joinTimeouts = new Runnable[N];
    private static final Runnable[] reconnects = new Runnable[N];
    private static final int[] backoffMs = new int[N];

    public static void connectAll() {
        for (int a = 0; a < N; a++) {
            try { if (ready(a)) connect(a); } catch (Throwable ignore) {}
        }
    }

    /** Foreground / network-restored: reconcile once, drop any pending backoff and reconnect immediately for
     *  accounts whose socket is down. Idempotent — a live subscription is left alone (connect() no-ops).
     *  We also reset the backoff so the NEXT drop starts the 2s→60s climb over rather than resuming the
     *  old cap; without this, a long outage leaves recovery up to 60s away even after the network is back.
     *  Called from LaunchActivity.onResume and on ConnectionStateConnected (per-account, but iterates all). */
    public static void reconnectAllNow() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < N; a++) {
                try {
                    if (!ready(a)) continue;
                    backoffMs[a] = 0;
                    // Realtime is notify-only and broadcasts are not replayed. A GET on foreground/network
                    // recovery is the correctness backstop for an event missed while the process was asleep.
                    LeemenSync.onRemoteChanged(a);
                    connect(a); // cancels the pending delayed reconnect, then connects iff socket == null
                } catch (Throwable ignore) {}
            }
        });
    }

    public static void disconnectAll() {
        for (int a = 0; a < N; a++) disconnect(a);
    }

    /** Open (or keep) the Realtime subscription for an account. Idempotent. UI thread. */
    public static void connect(final int account) {
        if (!ready(account)) return;
        if (sockets[account] != null) return; // already connected/connecting
        final String syncId = LeemenAccount.getSyncAccountId(account);
        if (syncId == null) return;
        cancel(reconnects, account);
        joined[account] = false;
        String url = LeemenConfig.supabaseRealtimeUrl()
                + "/websocket?apikey=" + LeemenConfig.SUPABASE_ANON_KEY + "&vsn=1.0.0";
        try {
            Request req = new Request.Builder().url(url).build();
            sockets[account] = HTTP.newWebSocket(req, new Listener(account, syncId));
        } catch (Throwable e) {
            FileLog.e(e);
            scheduleReconnect(account);
        }
    }

    public static void disconnect(int account) {
        if (!inRange(account)) return;
        cancel(reconnects, account);
        cancel(heartbeats, account);
        cancel(joinTimeouts, account);
        WebSocket ws = sockets[account];
        sockets[account] = null;
        joined[account] = false;
        if (ws != null) {
            try { ws.close(1000, "bye"); } catch (Throwable ignore) {}
        }
    }

    /**
     * Notify other live clients that this account generation was deleted. The event itself is deliberately
     * non-authoritative: receivers only use it to trigger {@link LeemenSessionGuard#checkNow(int)}.
     *
     * Called on the UI thread after /account/delete returned success. Use Realtime's HTTP Broadcast endpoint
     * instead of the deleting client's WebSocket: deletion must still wake sibling devices when that socket
     * has not joined yet, is between reconnects, or is about to be torn down by local logout.
     */
    public static boolean broadcastAccountGenerationInvalidated(int account) {
        if (!inRange(account)) return false;
        String syncId = LeemenAccount.getSyncAccountId(account);
        if (syncId == null) return false;
        try {
            enqueueAccountInvalidationBroadcast(LeemenConfig.syncChannel(syncId), 0);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void enqueueAccountInvalidationBroadcast(final String channel, final int attempt) {
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host(LeemenConfig.SUPABASE_REF + ".supabase.co")
                .addPathSegments("realtime/v1/api/broadcast")
                .addPathSegment(channel)
                .addPathSegment("events")
                .addPathSegment(EVENT_ACCOUNT_GENERATION_INVALIDATED)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("apikey", LeemenConfig.SUPABASE_ANON_KEY)
                .post(RequestBody.create(JSON, "{}"))
                .build();
        BROADCAST_HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                retryAccountInvalidationBroadcast(channel, attempt, "transport");
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean accepted = response.isSuccessful();
                int code = response.code();
                response.close();
                if (accepted) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: realtime account-generation invalidation accepted");
                    }
                } else {
                    retryAccountInvalidationBroadcast(channel, attempt, "http " + code);
                }
            }
        });
    }

    private static void retryAccountInvalidationBroadcast(String channel, int attempt, String reason) {
        if (attempt >= 1) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: realtime account-generation invalidation failed: " + reason);
            }
            return;
        }
        // Keep the channel captured: local logout is allowed to clear the account binding before this retry.
        AndroidUtilities.runOnUIThread(
                () -> enqueueAccountInvalidationBroadcast(channel, attempt + 1),
                250
        );
    }

    private static final class Listener extends WebSocketListener {
        private final int account;
        private final String topic;
        private String joinRef;

        Listener(int account, String syncId) {
            this.account = account;
            this.topic = "realtime:" + LeemenConfig.syncChannel(syncId);
        }

        @Override public void onOpen(final WebSocket ws, Response response) {
            joinRef = nextRef();
            boolean sent = false;
            try {
                // Carry an explicit join_ref so the channel acknowledgment and later channel lifecycle events
                // can be correlated independently from transport-level WebSocket open/close callbacks.
                sent = ws.send(phx(topic, "phx_join", joinPayload(), joinRef, joinRef).toString());
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (!sent) {
                dropSocket(account, ws);
                return;
            }
            if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: realtime socket opened account " + account);
            AndroidUtilities.runOnUIThread(() -> {
                if (sockets[account] == ws) scheduleJoinTimeout(account, ws);
            });
        }

        @Override public void onMessage(WebSocket ws, String text) {
            try {
                JsonObject m = JsonParser.parseString(text).getAsJsonObject();
                String event = optStr(m, "event");
                String messageTopic = optStr(m, "topic");

                if ("phx_reply".equals(event) && joinRef != null && joinRef.equals(optStr(m, "ref"))) {
                    JsonObject payload = object(m, "payload");
                    if (payload != null && "ok".equals(optStr(payload, "status"))) {
                        markJoined(account, ws, joinRef);
                    } else {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("Leemen: realtime join rejected account " + account
                                    + ": " + joinFailureReason(payload));
                        }
                        dropSocket(account, ws);
                    }
                    return;
                }

                if (topic.equals(messageTopic) && ("phx_error".equals(event) || "phx_close".equals(event))) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: realtime channel dropped account " + account + ": " + event);
                    }
                    dropSocket(account, ws);
                    return;
                }

                if (topic.equals(messageTopic) && "broadcast".equals(event)) {
                    JsonObject payload = m.has("payload") && m.get("payload").isJsonObject() ? m.getAsJsonObject("payload") : null;
                    String broadcastEvent = payload != null ? optStr(payload, "event") : null;
                    if (EVENT_ACCOUNT_GENERATION_INVALIDATED.equals(broadcastEvent)) {
                        // Never trust a public-channel broadcast as proof of deletion. It only wakes the
                        // authenticated generation guard, which logs out solely on the backend's 401/404.
                        LeemenSessionGuard.checkNow(account);
                    } else if ("account_state_changed".equals(broadcastEvent)) {
                        // Notify-only: confirm Premium/privacy mode and consent with authenticated GET /me.
                        LeemenAccountState.onRemoteChanged(account);
                    } else if ("blob_changed".equals(broadcastEvent)) {
                        // Supabase broadcast nests the data one level deeper: payload.payload = {table, version}.
                        // Pass them so LeemenSync can ignore the echo of our own write (no push→echo→push loop).
                        JsonObject data = payload.has("payload") && payload.get("payload").isJsonObject()
                                ? payload.getAsJsonObject("payload") : null;
                        String table = data != null ? optStr(data, "table") : null;
                        long version = data != null && data.has("version") && data.get("version").isJsonPrimitive()
                                ? data.get("version").getAsLong() : -1;
                        LeemenSync.onRemoteChanged(account, table, version); // marshals to UI + GET→merge backstop
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        @Override public void onClosed(WebSocket ws, int code, String reason) { handleDrop(ws); }

        @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
            if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: realtime failure account " + account + ": " + t.getMessage());
            dropSocket(account, ws);
        }

        private void handleDrop(final WebSocket ws) { dropSocket(account, ws); }
    }

    private static void scheduleHeartbeat(final int account, final WebSocket ws) {
        cancel(heartbeats, account);
        heartbeats[account] = new Runnable() {
            @Override public void run() {
                if (sockets[account] != ws || !joined[account]) return; // stale/unsubscribed socket
                try {
                    ws.send(phx("phoenix", "heartbeat", new JsonObject(), nextRef(), null).toString());
                } catch (Throwable ignore) {}
                AndroidUtilities.runOnUIThread(this, 25000);
            }
        };
        AndroidUtilities.runOnUIThread(heartbeats[account], 25000);
    }

    private static void scheduleJoinTimeout(final int account, final WebSocket ws) {
        cancel(joinTimeouts, account);
        joinTimeouts[account] = () -> {
            joinTimeouts[account] = null;
            if (sockets[account] == ws && !joined[account]) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: realtime join timeout account " + account);
                }
                dropSocket(account, ws);
            }
        };
        AndroidUtilities.runOnUIThread(joinTimeouts[account], JOIN_TIMEOUT_MS);
    }

    private static void markJoined(final int account, final WebSocket ws, final String joinRef) {
        AndroidUtilities.runOnUIThread(() -> {
            if (sockets[account] != ws) return;
            cancel(joinTimeouts, account);
            joined[account] = true;
            backoffMs[account] = 0;
            scheduleHeartbeat(account, ws);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: realtime subscribed account " + account);
            }
            // Close the race between the initial GET and the moment this channel became live: any broadcast
            // sent in that interval was legitimately missed, so reconcile once after every successful join.
            LeemenSync.onRemoteChanged(account);
            LeemenAccountState.onRemoteChanged(account);
            // Broadcasts are not replayed. If account deletion happened while this socket was down, the
            // authoritative status check makes the reconnect itself terminate the stale generation.
            LeemenSessionGuard.checkNow(account);
        });
    }

    private static void dropSocket(final int account, final WebSocket ws) {
        AndroidUtilities.runOnUIThread(() -> {
            if (sockets[account] != ws) return;
            sockets[account] = null;
            joined[account] = false;
            cancel(joinTimeouts, account);
            cancel(heartbeats, account);
            try { ws.cancel(); } catch (Throwable ignore) {}
            scheduleReconnect(account);
        });
    }

    private static void scheduleReconnect(final int account) {
        cancel(reconnects, account);
        int delay = backoffMs[account] <= 0 ? 2000 : Math.min(backoffMs[account] * 2, MAX_RECONNECT_DELAY_MS);
        backoffMs[account] = delay;
        reconnects[account] = () -> { reconnects[account] = null; connect(account); };
        AndroidUtilities.runOnUIThread(reconnects[account], delay);
    }

    private static JsonObject phx(String topic, String event, JsonObject payload, String ref, String joinRef) {
        JsonObject o = new JsonObject();
        o.addProperty("topic", topic);
        o.addProperty("event", event);
        o.add("payload", payload);
        o.addProperty("ref", ref);
        if (joinRef != null) o.addProperty("join_ref", joinRef);
        return o;
    }

    private static JsonObject joinPayload() {
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("ack", false);
        broadcast.addProperty("self", false);
        JsonObject presence = new JsonObject();
        presence.addProperty("enabled", false);
        JsonObject config = new JsonObject();
        config.add("broadcast", broadcast);
        config.add("presence", presence);
        config.add("postgres_changes", new JsonArray());
        config.addProperty("private", false);
        JsonObject payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("access_token", LeemenConfig.SUPABASE_ANON_KEY);
        return payload;
    }

    private static String nextRef() {
        return Long.toString(NEXT_REF.incrementAndGet());
    }

    private static boolean inRange(int account) {
        return account >= 0 && account < N;
    }

    private static boolean ready(int account) {
        return inRange(account)
                && UserConfig.getInstance(account).isClientActivated()
                && LeemenAccount.hasBinding(account)
                && !LeemenAccount.isDisabled(account);
    }

    private static String optStr(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; } catch (Throwable e) { return null; }
    }

    private static JsonObject object(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String joinFailureReason(JsonObject payload) {
        JsonObject response = object(payload, "response");
        String reason = response != null ? optStr(response, "reason") : null;
        if (reason == null && response != null) reason = optStr(response, "error");
        return reason != null ? reason : "unknown";
    }

    private static void cancel(Runnable[] arr, int account) {
        if (arr[account] != null) {
            AndroidUtilities.cancelRunOnUIThread(arr[account]);
            arr[account] = null;
        }
    }
}
