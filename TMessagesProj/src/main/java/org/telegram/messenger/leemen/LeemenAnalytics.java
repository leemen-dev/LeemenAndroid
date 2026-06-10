package org.telegram.messenger.leemen;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6 — neutral product analytics. Server-primary, no third-party SDK. NEVER emits anything about
 * private space (§0 of the Analytics spec). Events are validated against a name+prop allowlist mirrored
 * byte-for-byte from the backend (unknown names/props are dropped client-side too) and sent batched to
 * POST /v1/events: anonymous before login, Bearer after (the server stitches install_id ↔ master_account).
 *
 * install_id is a stable per-install UUID (NOT cleared on logout); opt-out is a separate local control.
 * NOTE: a remote kill-switch (fail-closed cold-start) is specified but has no endpoint yet — tracked as a
 * TODO; until then only the funnel/neutral events below are ever sent and opt-out is honored.
 */
public final class LeemenAnalytics {

    private LeemenAnalytics() {}

    private static final String PREFS = "leemen_analytics";
    private static final int QUEUE_CAP = 200;
    private static final int BATCH_MAX = 100;
    private static final long FLUSH_DELAY_MS = 2000;

    // Allowlist: event name -> permitted prop keys (must match the backend's analytics_allowlist).
    private static final Map<String, Set<String>> ALLOW = new HashMap<>();
    static {
        ALLOW.put("app_first_open", set());
        ALLOW.put("onboarding_step_view", set("step"));
        ALLOW.put("onboarding_completed", set("last_step"));
        ALLOW.put("onboarding_abandoned", set("last_step"));
        ALLOW.put("signup_started", set());
        ALLOW.put("signup_otp_requested", set());
        ALLOW.put("signup_completed", set());
        ALLOW.put("paywall_view", set("placement"));
        ALLOW.put("paywall_cta_tap", set("plan"));
        ALLOW.put("subscribe_flow_started", set());
        ALLOW.put("new_account_redirect_view", set());
    }

    private static final List<JsonObject> queue = new ArrayList<>();
    private static volatile String sessionId;
    private static Runnable flushRunnable;

    // ===== public API =====

    public static void track(String name) { track(name, null); }

    public static void track(String name, Map<String, String> props) {
        try {
            if (isOptedOut()) return;
            Set<String> allowedProps = ALLOW.get(name);
            if (allowedProps == null) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: analytics drop unknown event '" + name + "'");
                return;
            }
            JsonObject ev = new JsonObject();
            ev.addProperty("name", name);
            ev.addProperty("ts", System.currentTimeMillis());
            JsonObject p = new JsonObject();
            if (props != null) {
                for (Map.Entry<String, String> e : props.entrySet()) {
                    if (allowedProps.contains(e.getKey()) && e.getValue() != null) {
                        p.addProperty(e.getKey(), e.getValue());
                    }
                }
            }
            ev.add("props", p);
            synchronized (queue) {
                queue.add(ev);
                while (queue.size() > QUEUE_CAP) queue.remove(0); // drop oldest
            }
            scheduleFlush();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Fire app_first_open exactly once per install. */
    public static void onAppStart() {
        try {
            if (isOptedOut()) return;
            SharedPreferences pr = prefs();
            if (!pr.getBoolean("first_open_sent", false)) {
                track("app_first_open");
                pr.edit().putBoolean("first_open_sent", true).apply();
            } else {
                flush(); // drain anything queued from a previous session
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean isOptedOut() {
        return prefs().getBoolean("opt_out", false);
    }

    public static void setOptedOut(boolean optedOut) {
        prefs().edit().putBoolean("opt_out", optedOut).apply();
        if (optedOut) synchronized (queue) { queue.clear(); }
    }

    /** Stable per-install id (UUIDv4). Created once; never reset on logout — only on uninstall/wipe. */
    public static String installId() {
        SharedPreferences pr = prefs();
        String id = pr.getString("install_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            pr.edit().putString("install_id", id).apply();
        }
        return id;
    }

    // ===== flush =====

    private static void scheduleFlush() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            if (flushRunnable != null) org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushRunnable = () -> { flushRunnable = null; flush(); };
            org.telegram.messenger.AndroidUtilities.runOnUIThread(flushRunnable, FLUSH_DELAY_MS);
        });
    }

    private static void flush() {
        if (isOptedOut()) return;
        final List<JsonObject> batch = new ArrayList<>();
        synchronized (queue) {
            if (queue.isEmpty()) return;
            int n = Math.min(BATCH_MAX, queue.size());
            for (int i = 0; i < n; i++) batch.add(queue.remove(0));
        }
        JsonObject client = new JsonObject();
        client.addProperty("platform", "android");
        client.addProperty("app_version", BuildVars.BUILD_VERSION_STRING);
        client.addProperty("build", appBuildCode());

        JsonObject body = new JsonObject();
        body.add("client", client);
        body.addProperty("install_id", installId());
        body.addProperty("session_id", sessionId());
        JsonArray arr = new JsonArray();
        for (JsonObject e : batch) arr.add(e);
        body.add("events", arr);

        String token = currentToken(); // Bearer if a bound account exists, else anonymous
        LeemenRestClient.post(LeemenConfig.EP_EVENTS, token, body, (resp, code, ec, em) -> {
            if (code >= 200 && code < 300) {
                synchronized (queue) {
                    if (!queue.isEmpty()) scheduleFlush(); // more pending
                }
            } else if (!isOptedOut()) {
                // transient failure: requeue (bounded); retried on the next event / app start
                synchronized (queue) {
                    queue.addAll(0, batch);
                    while (queue.size() > QUEUE_CAP) queue.remove(queue.size() - 1);
                }
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: /events flush failed code=" + code + " err=" + ec);
            }
        });
    }

    // ===== helpers =====

    private static String currentToken() {
        try {
            int sel = UserConfig.selectedAccount;
            if (sel >= 0 && sel < UserConfig.MAX_ACCOUNT_COUNT && LeemenAccount.hasBinding(sel)) {
                return LeemenAccount.getToken(sel);
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (LeemenAccount.hasBinding(a)) return LeemenAccount.getToken(a);
            }
        } catch (Throwable ignore) {}
        return null; // anonymous
    }

    private static String sessionId() {
        if (sessionId == null) sessionId = UUID.randomUUID().toString();
        return sessionId;
    }

    private static int appBuildCode() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<String> set(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }
}
