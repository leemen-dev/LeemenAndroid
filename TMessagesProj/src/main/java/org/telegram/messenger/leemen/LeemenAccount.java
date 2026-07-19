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

    /** master_account_id (UUID, == JWT `sub`) returned by /v1/auth/telegram; null until first bind.
     *  This is the principal identity; it is the value Google Play's obfuscatedAccountId MUST equal so
     *  /v1/entitlements/play binds the purchase to this account (contract §4/§7). Stored verbatim.
     *
     *  Backfill: installs bound before this field was persisted have no stored value. Since it is
     *  identical to the JWT `sub` we already hold, decode it from the token once and cache it — this
     *  keeps purchases binding correctly across the upgrade without forcing a re-auth. */
    public static String getMasterAccountId(int account) {
        String stored = prefs().getString("master_" + account, null);
        if (!TextUtils.isEmpty(stored)) {
            return stored;
        }
        String sub = subFromToken(getToken(account));
        if (!TextUtils.isEmpty(sub)) {
            prefs().edit().putString("master_" + account, sub).apply();
        }
        return sub;
    }

    /** Read the `sub` claim (== master_account_id) from our own session JWT WITHOUT verifying the
     *  signature — verification is the server's job; we only parse a token we minted-for ourselves to
     *  recover a value the server already vouched for. Returns null on any malformed input. */
    private static String subFromToken(String token) {
        if (TextUtils.isEmpty(token)) {
            return null;
        }
        try {
            int dot1 = token.indexOf('.');
            int dot2 = dot1 < 0 ? -1 : token.indexOf('.', dot1 + 1);
            if (dot1 <= 0 || dot2 <= dot1) {
                return null;
            }
            byte[] json = android.util.Base64.decode(token.substring(dot1 + 1, dot2),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            com.google.gson.JsonObject claims = com.google.gson.JsonParser
                    .parseString(new String(json, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (claims.has("sub") && !claims.get("sub").isJsonNull()) {
                String sub = claims.get("sub").getAsString();
                return TextUtils.isEmpty(sub) ? null : sub;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** Privacy mode reported by the backend ("default" | "max"); null until first bind. */
    public static String getPrivacyMode(int account) {
        return prefs().getString("privacy_" + account, null);
    }

    public static boolean isMaxPrivacy(int account) {
        return "max".equals(getPrivacyMode(account));
    }

    /** Locally update the cached privacy mode after a successful upgrade/downgrade. */
    public static void setPrivacyMode(int account, String mode) {
        prefs().edit().putString("privacy_" + account, mode).apply();
    }

    /** CAS counter for the max-mode wraps (returned by upgrade/wrap-pw/account-key); 0 until known. */
    public static int getWrapVersion(int account) {
        return prefs().getInt("wrapver_" + account, 0);
    }

    public static void setWrapVersion(int account, int version) {
        prefs().edit().putInt("wrapver_" + account, version).apply();
    }

    // --- Terms/Privacy acceptance (local fast-path mirror of the server consent ledger; see LeemenConsent) ---
    /** Locally-cached accepted Terms version, or null if never accepted on this install. */
    public static String getAcceptedTermsVersion(int account) {
        return prefs().getString("terms_ver_" + account, null);
    }

    public static void setAcceptedTermsVersion(int account, String version) {
        prefs().edit().putString("terms_ver_" + account, version).apply();
    }

    /** True if a consent grant has not yet been confirmed to the backend (re-flushed at startup). */
    public static boolean isConsentDirty(int account) {
        return prefs().getBoolean("consent_dirty_" + account, false);
    }

    public static void setConsentDirty(int account, boolean dirty) {
        prefs().edit().putBoolean("consent_dirty_" + account, dirty).apply();
    }

    /** Durable last-write-wins queue for the backend consent ledger. A stored false is a pending revoke. */
    public static boolean hasPendingConsent(int account, String type) {
        return prefs().contains("consent_pending_" + account + "_" + type);
    }

    public static boolean getPendingConsent(int account, String type) {
        return prefs().getBoolean("consent_pending_" + account + "_" + type, false);
    }

    public static void setPendingConsent(int account, String type, Boolean granted) {
        String key = "consent_pending_" + account + "_" + type;
        SharedPreferences.Editor e = prefs().edit();
        if (granted == null) e.remove(key); else e.putBoolean(key, granted);
        e.apply();
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

    /** Drop only the wrapped K_master (e.g. max-mode reset re-keys from bootstrap); leaves token/binding. */
    public static void dropKMaster(int account) {
        prefs().edit().remove("kmaster_" + account).apply();
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

    /**
     * Full in-app account deletion (Variant B). Order is load-bearing: server erasure runs while the Leemen
     * token + Telegram session are still alive, then we log out every OTHER Leemen Telegram client, then this
     * device's Telegram (→ clean first-run). Does NOT delete the Telegram account. onComplete runs on the UI
     * thread just before this device logs out.
     */
    public static void deleteAndLogoutEverywhere(int account, Runnable onComplete) {
        // Keep the local hide-list intact until every remote-session revoke has completed (or timed out).
        // Clearing it earlier can briefly expose former private-space chats in the still logged-in UI.
        setDisabled(account, true);
        requestServerDelete(account, () -> terminateOtherAppSessions(account, () -> {
            // The local wipe and current-session logout run back-to-back in the same UI turn, so the
            // dialogsNeedReload emitted by wipeAllLocalData cannot render an unhidden chat list in between.
            wipeLocalAccountData(account);
            if (onComplete != null) onComplete.run();
            org.telegram.messenger.MessagesController.getInstance(account).performLogout(1);
        }));
    }

    private static void wipeLocalAccountData(int account) {
        try {
            LeemenRealtime.disconnect(account);
            // Full sync teardown (in-memory CRDT cache + debounce/watchdog + persisted state) — a bare
            // LeemenSyncState.clear would leave the cached working copy alive and a later re-bind
            // could push the OLD hidden set into the fresh account.
            LeemenSync.clearAccount(account);
            org.telegram.messenger.SecondSpaceController.getInstance(account).wipeAllLocalData();
            clear(account);            // token / sync_account_id / wrapped K_master (prefs)
            // The AndroidKeyStore wrap-key alias is install-global (shared across accounts). Delete the
            // raw TEE key only when no other account still has a wrapped K_master, else theirs breaks.
            boolean anyKMasterLeft = false;
            for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (hasKMaster(a)) { anyKMasterLeft = true; break; }
            }
            if (!anyKMasterLeft) {
                LeemenKeyStore.deleteWrapKey();
            }
            setDisabled(account, true); // don't silently re-create on next launch
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /** Terminate every OTHER active Telegram session created by THIS app (api_id == APP_ID) — i.e. the user's
     *  other Leemen installs — leaving official Telegram clients (different api_id) and the Telegram account
     *  intact. The current session is excluded by its `current` flag (its api_id is also APP_ID) and is logged
     *  out separately by performLogout. Best-effort + async; onComplete runs on the UI thread regardless. */
    private static void terminateOtherAppSessions(int account, Runnable onComplete) {
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable finish = () -> {
            if (completed.compareAndSet(false, true) && onComplete != null) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(onComplete);
            }
        };
        // Session revocation is best-effort, but account deletion must never leave the user trapped on a
        // spinner if Telegram does not answer. Continue to the atomic local-wipe/logout step after timeout.
        org.telegram.messenger.AndroidUtilities.runOnUIThread(finish, 5000);
        org.telegram.tgnet.tl.TL_account.getAuthorizations req = new org.telegram.tgnet.tl.TL_account.getAuthorizations();
        org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (response instanceof org.telegram.tgnet.tl.TL_account.authorizations) {
                            java.util.ArrayList<org.telegram.tgnet.TLRPC.TL_authorization> list =
                                    ((org.telegram.tgnet.tl.TL_account.authorizations) response).authorizations;
                            java.util.ArrayList<Long> hashes = new java.util.ArrayList<>();
                            for (int i = 0; i < list.size(); i++) {
                                org.telegram.tgnet.TLRPC.TL_authorization auth = list.get(i);
                                if ((auth.flags & 1) != 0) continue;                                  // current session
                                if (auth.api_id != org.telegram.messenger.BuildVars.APP_ID) continue;  // only Leemen clients
                                hashes.add(auth.hash);
                            }
                            if (hashes.isEmpty()) {
                                finish.run();
                                return;
                            }
                            java.util.concurrent.atomic.AtomicInteger remaining =
                                    new java.util.concurrent.atomic.AtomicInteger(hashes.size());
                            for (int i = 0; i < hashes.size(); i++) {
                                org.telegram.tgnet.tl.TL_account.resetAuthorization reset = new org.telegram.tgnet.tl.TL_account.resetAuthorization();
                                reset.hash = hashes.get(i);
                                org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(reset, (r2, e2) -> {
                                    if (remaining.decrementAndGet() == 0) {
                                        finish.run();
                                    }
                                });
                            }
                            return;
                        }
                    } catch (Throwable e) {
                        org.telegram.messenger.FileLog.e(e);
                    }
                    finish.run();
                }));
    }

    /** Persist the result of a successful /v1/auth/telegram bind. */
    public static void save(int account, String token, String syncAccountId, String masterAccountId, String privacyMode) {
        SharedPreferences.Editor e = prefs().edit()
                .putString("token_" + account, token)
                .putString("sync_" + account, syncAccountId);
        if (!TextUtils.isEmpty(masterAccountId)) {
            // Store the UUID verbatim (canonical lowercase, dashed) — Play's obfuscatedAccountId must
            // match it byte-for-byte under exact string equality (contract §4). Never hash/normalize.
            e.putString("master_" + account, masterAccountId);
        }
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
                .remove("master_" + account)
                .remove("privacy_" + account)
                .remove("kmaster_" + account)
                .remove("disabled_" + account)
                .remove("terms_ver_" + account)
                .remove("consent_dirty_" + account)
                .remove("wrapver_" + account)
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_TERMS)
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_KZ_CROSS_BORDER)
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_ANALYTICS)
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_ATTRIBUTION)
                .apply();
        LeemenConsent.clearAccount(account);
        // Run after the token removal: an old in-flight register callback now fails its token check, while
        // this final removal also wins if that callback completed in the narrow window above.
        LeemenDevice.clearAccount(account);
    }
}
