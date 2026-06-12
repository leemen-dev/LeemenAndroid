package org.telegram.messenger.leemen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.concurrent.TimeUnit;

/**
 * Phase 4 — Realtime push (notify-only). Per bound account, opens a Supabase Realtime websocket DIRECTLY
 * (not via api.leemen.app) and subscribes to channel sync:&lt;sync_account_id&gt;. On a blob_changed broadcast
 * it triggers a normal GET→merge via LeemenSync.onRemoteChanged.
 *
 * Purely advisory: correctness comes from the GET+CAS backstop (foreground / reconnect / 409), so a missed
 * or spoofed notify is harmless (just a no-op GET). The client only ever SUBSCRIBES, never broadcasts.
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

    private static final WebSocket[] sockets = new WebSocket[N];
    private static final Runnable[] heartbeats = new Runnable[N];
    private static final Runnable[] reconnects = new Runnable[N];
    private static final int[] backoffMs = new int[N];

    public static void connectAll() {
        for (int a = 0; a < N; a++) {
            try { if (ready(a)) connect(a); } catch (Throwable ignore) {}
        }
    }

    /** Foreground / network-restored: drop any pending backoff and reconnect immediately for accounts
     *  whose socket is down. Idempotent — a live socket is left alone (connect() no-ops when already up).
     *  We also reset the backoff so the NEXT drop starts the 2s→60s climb over rather than resuming the
     *  old cap; without this, a long outage leaves recovery up to 60s away even after the network is back.
     *  Called from LaunchActivity.onResume and on ConnectionStateConnected (per-account, but iterates all). */
    public static void reconnectAllNow() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < N; a++) {
                try {
                    if (!ready(a)) continue;
                    backoffMs[a] = 0;
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
        WebSocket ws = sockets[account];
        sockets[account] = null;
        if (ws != null) {
            try { ws.close(1000, "bye"); } catch (Throwable ignore) {}
        }
    }

    private static final class Listener extends WebSocketListener {
        private final int account;
        private final String syncId;
        private int ref;

        Listener(int account, String syncId) { this.account = account; this.syncId = syncId; }

        @Override public void onOpen(final WebSocket ws, Response response) {
            try { ws.send(phx("realtime:sync:" + syncId, "phx_join", joinPayload(), ++ref).toString()); } catch (Throwable ignore) {}
            if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: realtime connected account " + account);
            AndroidUtilities.runOnUIThread(() -> {
                backoffMs[account] = 0;
                if (sockets[account] == ws) scheduleHeartbeat(account, ws);
            });
        }

        @Override public void onMessage(WebSocket ws, String text) {
            try {
                JsonObject m = JsonParser.parseString(text).getAsJsonObject();
                if ("broadcast".equals(optStr(m, "event"))) {
                    JsonObject payload = m.has("payload") && m.get("payload").isJsonObject() ? m.getAsJsonObject("payload") : null;
                    if (payload != null && "blob_changed".equals(optStr(payload, "event"))) {
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
            handleDrop(ws);
        }

        private void handleDrop(final WebSocket ws) {
            AndroidUtilities.runOnUIThread(() -> {
                if (sockets[account] == ws) {
                    sockets[account] = null;
                    cancel(heartbeats, account);
                    scheduleReconnect(account);
                }
            });
        }
    }

    private static void scheduleHeartbeat(final int account, final WebSocket ws) {
        cancel(heartbeats, account);
        heartbeats[account] = new Runnable() {
            @Override public void run() {
                if (sockets[account] != ws) return; // stale socket
                try { ws.send(phx("phoenix", "heartbeat", new JsonObject(), 0).toString()); } catch (Throwable ignore) {}
                AndroidUtilities.runOnUIThread(this, 25000);
            }
        };
        AndroidUtilities.runOnUIThread(heartbeats[account], 25000);
    }

    private static void scheduleReconnect(final int account) {
        cancel(reconnects, account);
        int delay = backoffMs[account] <= 0 ? 2000 : Math.min(backoffMs[account] * 2, 60000);
        backoffMs[account] = delay;
        reconnects[account] = () -> { reconnects[account] = null; connect(account); };
        AndroidUtilities.runOnUIThread(reconnects[account], delay);
    }

    private static JsonObject phx(String topic, String event, JsonObject payload, int ref) {
        JsonObject o = new JsonObject();
        o.addProperty("topic", topic);
        o.addProperty("event", event);
        o.add("payload", payload);
        o.addProperty("ref", String.valueOf(ref));
        return o;
    }

    private static JsonObject joinPayload() {
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("self", false);
        JsonObject presence = new JsonObject();
        presence.addProperty("key", "");
        JsonObject config = new JsonObject();
        config.add("broadcast", broadcast);
        config.add("presence", presence);
        config.add("postgres_changes", new JsonArray());
        JsonObject payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("access_token", LeemenConfig.SUPABASE_ANON_KEY);
        return payload;
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

    private static void cancel(Runnable[] arr, int account) {
        if (arr[account] != null) {
            AndroidUtilities.cancelRunOnUIThread(arr[account]);
            arr[account] = null;
        }
    }
}
