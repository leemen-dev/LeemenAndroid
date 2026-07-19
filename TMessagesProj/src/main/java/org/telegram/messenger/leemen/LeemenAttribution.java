package org.telegram.messenger.leemen;

import android.content.Context;
import android.content.SharedPreferences;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;
import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Phase 7 — install attribution (Play Install Referrer). First-touch, once per install: read the Play
 * referrer, POST /v1/attribution { install_id, install_referrer }, then never touch it again. The raw
 * referrer is NEVER persisted on-device (only a "sent" flag); Play itself holds the value until we send,
 * so a failed POST simply retries on the next launch — no on-device referrer artifact (§6.5 deniability).
 * Gated on both the analytics/attribution opt-in and the backend kill-switch: the referrer is captured only
 * after the user explicitly enables telemetry and a current remote policy allows it.
 */
public final class LeemenAttribution {

    private LeemenAttribution() {}

    private static final String PREFS = "leemen_attribution";
    private static volatile boolean inFlight;

    public static void captureIfNeeded() {
        try {
            if (!LeemenAnalytics.isTelemetryEnabled()) return;
            SharedPreferences p = prefs();
            if (p.getBoolean("sent", false) || p.getBoolean("skip", false)) return; // first-touch, once ever
            if (inFlight) return;
            inFlight = true;

            final InstallReferrerClient client = InstallReferrerClient.newBuilder(ApplicationLoader.applicationContext).build();
            client.startConnection(new InstallReferrerStateListener() {
                @Override
                public void onInstallReferrerSetupFinished(int responseCode) {
                    boolean postStarted = false;
                    try {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            ReferrerDetails d = client.getInstallReferrer();
                            String referrer = d != null ? d.getInstallReferrer() : null;
                            if (referrer != null && !referrer.isEmpty()) {
                                post(referrer);            // referrer already extracted; client can close now
                                postStarted = true;        // post()'s callback owns clearing inFlight
                            } else {
                                prefs().edit().putBoolean("skip", true).apply(); // nothing to attribute → don't retry
                            }
                        }
                        // non-OK (FEATURE_NOT_SUPPORTED / SERVICE_UNAVAILABLE): leave flags unset → retry next launch
                    } catch (Throwable e) {
                        FileLog.e(e);
                    } finally {
                        try { client.endConnection(); } catch (Throwable ignore) {}
                        if (!postStarted) inFlight = false;
                    }
                }

                @Override
                public void onInstallReferrerServiceDisconnected() {
                    inFlight = false;
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
            inFlight = false;
        }
    }

    private static void post(String referrer) {
        if (!LeemenAnalytics.isTelemetryEnabled()) { inFlight = false; return; }
        JsonObject body = new JsonObject();
        body.addProperty("install_id", LeemenAnalytics.installId());
        body.addProperty("install_referrer", referrer);
        LeemenRestClient.post(LeemenConfig.EP_ATTRIBUTION, null, body, (resp, code, ec, em) -> {
            if (code >= 200 && code < 300) {
                prefs().edit().putBoolean("sent", true).apply(); // first-touch done; referrer discarded (never stored)
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: attribution sent");
            } else if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: /attribution failed code=" + code + " err=" + ec + " (retry next launch)");
            }
            inFlight = false;
        });
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
