package org.telegram.messenger.leemen;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;

/**
 * Per-Telegram-account Leemen identity: the session JWT and the sync_account_id (Realtime channel key +
 * blob scope), keyed by the local account slot (currentAccount, 0..MAX_ACCOUNT_COUNT-1).
 *
 * Stored in a dedicated SharedPreferences file with account-suffixed keys — NOT in the per-account
 * "mainconfig" file, so a future Leemen logout/clear is a single, contained operation. The session token
 * is a bearer credential; do not log it. (K_master, the blob encryption key, lives separately in the
 * Android Keystore — see the crypto phase — never here.)
 */
public final class LeemenAccount {

    private LeemenAccount() {}

    private static final String PREFS = "leemen_account";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    /** Session JWT for this account, or null if not yet bound. */
    public static String getToken(int account) {
        return prefs().getString("token_" + account, null);
    }

    /** sync_account_id (surrogate per Telegram account) — Realtime channel key + blob scope. */
    public static String getSyncAccountId(int account) {
        return prefs().getString("sync_" + account, null);
    }

    /** Privacy mode reported by the backend ("default" | "max"); null until first bind. */
    public static String getPrivacyMode(int account) {
        return prefs().getString("privacy_" + account, null);
    }

    public static boolean hasBinding(int account) {
        return !TextUtils.isEmpty(getToken(account)) && !TextUtils.isEmpty(getSyncAccountId(account));
    }

    /** Persist the result of a successful /v1/auth/telegram bind. */
    public static void save(int account, String token, String syncAccountId, String privacyMode) {
        SharedPreferences.Editor e = prefs().edit()
                .putString("token_" + account, token)
                .putString("sync_" + account, syncAccountId);
        if (privacyMode != null) {
            e.putString("privacy_" + account, privacyMode);
        }
        e.apply();
    }

    /** Drop this account's Leemen identity (call on Telegram logout of the account). */
    public static void clear(int account) {
        prefs().edit()
                .remove("token_" + account)
                .remove("sync_" + account)
                .remove("privacy_" + account)
                .apply();
    }
}
