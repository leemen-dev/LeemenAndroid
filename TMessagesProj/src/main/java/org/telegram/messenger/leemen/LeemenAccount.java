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

    /** K_master (the blob E2E key) stored Keystore-wrapped (see LeemenKeyStore). Never the raw key here. */
    public static String getWrappedKMaster(int account) {
        return prefs().getString("kmaster_" + account, null);
    }

    public static void setWrappedKMaster(int account, String wrapped) {
        prefs().edit().putString("kmaster_" + account, wrapped).apply();
    }

    public static boolean hasKMaster(int account) {
        return !TextUtils.isEmpty(getWrappedKMaster(account));
    }

    /** True if the user deleted their Leemen account — suppresses auto-rebind until they re-login to
     *  Telegram (performLogout → clear() resets this). */
    public static boolean isDisabled(int account) {
        return prefs().getBoolean("disabled_" + account, false);
    }

    public static void setDisabled(int account, boolean disabled) {
        prefs().edit().putBoolean("disabled_" + account, disabled).apply();
    }

    /** Delete the Leemen account + all its data (GDPR): server DSR (best-effort) → local wipe → disable
     *  auto-rebind. Does NOT touch the Telegram account. onDone runs on the UI thread when finished. */
    public static void deleteAccountAndData(int account, Runnable onDone) {
        // Disabled FIRST: blocks sync/realtime/device/heartbeat from racing the async server round-trip
        // with the still-present token. The token itself stays until clear() so the DSR call can auth.
        setDisabled(account, true);
        requestServerDelete(account, () -> {
            try {
                LeemenRealtime.disconnect(account);
                // Full sync teardown (in-memory CRDT cache + debounce/watchdog + persisted state) — a bare
                // LeemenSyncState.clear would leave the cached working copy alive and a later re-bind
                // could push the OLD hidden set into the fresh account.
                LeemenSync.clearAccount(account);
                org.telegram.messenger.SecondSpaceController.getInstance(account).wipeAllLocalData();
                clear(account);            // token / sync_account_id / K_master
                setDisabled(account, true); // don't silently re-create on next launch
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
            }
            if (onDone != null) onDone.run();
        });
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

    /** Server-side account deletion (GDPR right-to-erasure). POST /v1/account/delete with the contract's
     *  {confirm:"DELETE"} guard; cascades the whole core graph + analytics trail server-side. Local wipe
     *  proceeds regardless of the outcome (the user asked for their data gone; a transient server failure
     *  must not keep local PS data alive) — failures are logged. The session JWT stays cryptographically
     *  valid until TTL, so the caller MUST drop the token right after (deleteAccountAndData does). */
    public static void requestServerDelete(int account, Runnable onDone) {
        String token = getToken(account);
        if (token == null) {
            if (onDone != null) onDone.run();
            return;
        }
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("confirm", "DELETE");
        LeemenRestClient.post(LeemenConfig.EP_ACCOUNT_DELETE, token, body, (resp, code, ec, em) -> {
            if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                boolean ok = resp != null && code == 200 && resp.has("ok") && resp.get("ok").getAsBoolean();
                org.telegram.messenger.FileLog.d("Leemen: /account/delete code=" + code + " ok=" + ok + (ec != null ? " err=" + ec : ""));
            }
            if (onDone != null) onDone.run();
        });
    }

    /** Drop this account's Leemen identity (call on Telegram logout of the account). */
    public static void clear(int account) {
        prefs().edit()
                .remove("token_" + account)
                .remove("sync_" + account)
                .remove("privacy_" + account)
                .remove("kmaster_" + account)
                .remove("disabled_" + account)
                .apply();
    }
}
