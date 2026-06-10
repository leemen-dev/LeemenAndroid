package org.telegram.messenger.leemen;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

/**
 * Phase 8 — neutral retention heartbeat. POST /v1/heartbeat (authed) at most once per UTC day per bound
 * account, on app foreground. Deliberately NOT keyed to install_id and NOT gated on private-space state:
 * its cadence is identical whether or not PS is used, so its presence/absence leaks nothing (§ deniability).
 */
public final class LeemenHeartbeat {

    private LeemenHeartbeat() {}

    private static final String PREFS = "leemen_heartbeat";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Send today's heartbeat for every bound, activated account that hasn't sent one yet today. */
    public static void maybeSendAll() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated() && LeemenAccount.hasBinding(a)) {
                    maybeSend(a);
                }
            } catch (Throwable ignore) {}
        }
    }

    private static void maybeSend(final int account) {
        if (LeemenAccount.isDisabled(account)) return;
        final String token = LeemenAccount.getToken(account);
        if (token == null) return;
        final long today = System.currentTimeMillis() / 86_400_000L; // UTC-day bucket (date granularity)
        if (prefs().getLong("last_" + account, 0) == today) return;
        // empty JSON body; the server reads identity from the JWT only
        LeemenRestClient.post(LeemenConfig.EP_HEARTBEAT, token, new com.google.gson.JsonObject(), (body, code, ec, em) -> {
            if (code >= 200 && code < 300) {
                prefs().edit().putLong("last_" + account, today).apply();
            } else if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: heartbeat acct=" + account + " code=" + code + " err=" + ec);
            }
        });
    }
}
