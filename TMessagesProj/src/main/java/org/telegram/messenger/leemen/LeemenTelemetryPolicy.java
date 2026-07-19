package org.telegram.messenger.leemen;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Remote, backend-owned telemetry kill-switch. The decision is deliberately process-memory-only: every
 * cold start must obtain a fresh valid policy before events, heartbeat or attribution can run. Any unknown,
 * malformed, unsupported or unreachable policy fails closed. A periodic refresh bounds propagation of an
 * emergency disable for a long-running app without coupling requests to Private Space state.
 *
 * Contract: unauthenticated GET /v1/client-config
 * {"schema_version":1,"telemetry":{"enabled":true}}
 */
public final class LeemenTelemetryPolicy {

    private LeemenTelemetryPolicy() {}

    private static final int SCHEMA_VERSION = 1;
    private static final long REFRESH_INTERVAL_MS = 5 * 60_000L;

    private enum State { UNKNOWN, ENABLED, DISABLED }

    private static final Object lock = new Object();
    private static volatile State state = State.UNKNOWN;
    private static boolean inFlight;
    private static Runnable refreshRunnable;

    /** True only after this process has received a valid, supported, explicitly enabled policy. */
    public static boolean isEnabled() {
        return state == State.ENABLED;
    }

    /** Fetch now. Coalesces concurrent callers and suspends telemetry while the new decision is unknown. */
    public static void refresh() {
        synchronized (lock) {
            if (inFlight) return;
            inFlight = true;
            state = State.UNKNOWN;
            if (refreshRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
                refreshRunnable = null;
            }
        }
        LeemenRestClient.get(LeemenConfig.EP_CLIENT_CONFIG, null, (body, code, ec, em) -> {
            boolean enabled = code >= 200 && code < 300 && parseEnabled(body);
            synchronized (lock) {
                inFlight = false;
                state = enabled ? State.ENABLED : State.DISABLED;
            }
            if (enabled) {
                // These methods retain their own consent / once-per-install / once-per-day gates.
                LeemenAnalytics.onAppStart();
                LeemenHeartbeat.maybeSendAll();
                LeemenAttribution.captureIfNeeded();
            } else {
                LeemenAnalytics.onRemotePolicyDisabled();
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: telemetry policy disabled/fail-closed code=" + code + " err=" + ec);
                }
            }
            scheduleRefresh();
        });
    }

    /** Strict parser: unknown versions and string/number stand-ins for booleans are disabled. */
    static boolean parseEnabled(JsonObject body) {
        try {
            if (body == null || !body.has("schema_version")
                    || !body.has("telemetry") || !body.get("telemetry").isJsonObject()) {
                return false;
            }
            JsonPrimitive version = body.getAsJsonPrimitive("schema_version");
            if (!version.isNumber() || !Integer.toString(SCHEMA_VERSION).equals(version.getAsString())) return false;
            JsonObject telemetry = body.getAsJsonObject("telemetry");
            if (!telemetry.has("enabled")) return false;
            JsonPrimitive enabled = telemetry.getAsJsonPrimitive("enabled");
            return enabled.isBoolean() && enabled.getAsBoolean();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static void scheduleRefresh() {
        synchronized (lock) {
            if (refreshRunnable != null) AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
            refreshRunnable = () -> {
                synchronized (lock) { refreshRunnable = null; }
                refresh();
            };
            AndroidUtilities.runOnUIThread(refreshRunnable, REFRESH_INTERVAL_MS);
        }
    }
}
