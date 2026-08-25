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

    /** Update the cached server-authoritative privacy mode and refresh any already-open settings screen. */
    public static boolean setPrivacyMode(int account, String mode) {
        String previous = getPrivacyMode(account);
        if (TextUtils.equals(previous, mode)) {
            return false;
        }
        prefs().edit().putString("privacy_" + account, mode).apply();
        try {
            org.telegram.messenger.NotificationCenter.getInstance(account)
                    .postNotificationName(org.telegram.messenger.NotificationCenter.secondSpaceModeChanged);
        } catch (Throwable ignored) {
        }
        return true;
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
        return prefs().contains(pendingConsentKey(account, type));
    }

    public static boolean getPendingConsent(int account, String type) {
        return prefs().getBoolean(pendingConsentKey(account, type), false);
    }

    public static String getPendingConsentVersion(int account, String type) {
        return prefs().getString(pendingConsentVersionKey(account, type), null);
    }

    public static void setPendingConsent(int account, String type, Boolean granted) {
        String key = pendingConsentKey(account, type);
        String versionKey = pendingConsentVersionKey(account, type);
        SharedPreferences.Editor e = prefs().edit();
        if (granted == null) {
            e.remove(key).remove(versionKey);
        } else {
            e.putBoolean(key, granted)
                    .putString(versionKey, LeemenConsent.CURRENT_TERMS_VERSION);
        }
        e.apply();
    }

    /**
     * Persist both rows represented by the single telemetry switch in one synchronous transaction.
     * This is the privacy boundary: when this method returns, a process crash cannot forget the user's
     * latest revoke/grant before either network request starts.
     */
    static boolean setPendingTelemetryConsent(int account, boolean granted) {
        String analyticsKey = pendingConsentKey(account, LeemenConsent.TYPE_ANALYTICS);
        String attributionKey = pendingConsentKey(account, LeemenConsent.TYPE_ATTRIBUTION);
        return prefs().edit()
                .putBoolean(analyticsKey, granted)
                .putString(analyticsKey + "_version", LeemenConsent.CURRENT_TERMS_VERSION)
                .putBoolean(attributionKey, granted)
                .putString(attributionKey + "_version", LeemenConsent.CURRENT_TERMS_VERSION)
                .commit();
    }

    /**
     * Analytics/attribution choices belong to the stable backend generation, not a reusable local slot.
     * Keeping their pending writes under master_account_id lets an offline choice survive ordinary
     * logout/rebind while a newly-created master UUID can never inherit it.
     */
    private static String pendingConsentKey(int account, String type) {
        String masterAccountId = getMasterAccountId(account);
        boolean telemetryType = LeemenConsent.TYPE_ANALYTICS.equals(type)
                || LeemenConsent.TYPE_ATTRIBUTION.equals(type);
        if (!TextUtils.isEmpty(masterAccountId) && telemetryType) {
            String masterKey = "consent_pending_master_" + masterAccountId + "_" + type;
            // One-time migration from released builds that stored telemetry mutations by reusable slot.
            // Only migrate after this slot is positively bound to the master generation that will own it.
            if (hasBinding(account)) {
                String legacyKey = "consent_pending_" + account + "_" + type;
                SharedPreferences p = prefs();
                if (p.contains(legacyKey)) {
                    SharedPreferences.Editor e = p.edit();
                    if (!p.contains(masterKey)) {
                        String legacyVersion = p.getString(legacyKey + "_version", null);
                        e.putBoolean(masterKey, p.getBoolean(legacyKey, false))
                                .putString(masterKey + "_version",
                                        TextUtils.isEmpty(legacyVersion)
                                                ? LeemenConsent.CURRENT_TERMS_VERSION
                                                : legacyVersion);
                    }
                    e.remove(legacyKey).remove(legacyKey + "_version").apply();
                }
            }
            return masterKey;
        }
        return "consent_pending_" + account + "_" + type;
    }

    private static String pendingConsentVersionKey(int account, String type) {
        return pendingConsentKey(account, type) + "_version";
    }

    static void clearTelemetryConsentPendingForGeneration(int account) {
        String masterAccountId = getMasterAccountId(account);
        if (TextUtils.isEmpty(masterAccountId)) return;
        prefs().edit()
                .remove("consent_pending_master_" + masterAccountId + "_" + LeemenConsent.TYPE_ANALYTICS)
                .remove("consent_pending_master_" + masterAccountId + "_" + LeemenConsent.TYPE_ANALYTICS + "_version")
                .remove("consent_pending_master_" + masterAccountId + "_" + LeemenConsent.TYPE_ATTRIBUTION)
                .remove("consent_pending_master_" + masterAccountId + "_" + LeemenConsent.TYPE_ATTRIBUTION + "_version")
                .apply();
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

    public interface AccountDeletionCallback {
        /** Runs on the UI thread. A successful callback is delivered immediately before local logout. */
        void onResult(boolean deleted);
    }

    /**
     * Full in-app account deletion (Variant B). Order is load-bearing: confirmed server erasure runs while
     * the Leemen token + Telegram session are still alive, then we log out every OTHER Leemen Telegram client,
     * then this device's Telegram (→ clean first-run). Does NOT delete the Telegram account. A retryable server
     * failure leaves the account and local data intact so the UI can report it instead of claiming deletion.
     */
    public static void deleteAndLogoutEverywhere(int account, AccountDeletionCallback onComplete) {
        // Keep the local hide-list intact until every remote-session revoke has completed (or timed out).
        // Clearing it earlier can briefly expose former private-space chats in the still logged-in UI.
        setDisabled(account, true);
        requestServerDelete(account, deleted -> {
            if (!deleted) {
                setDisabled(account, false);
                if (onComplete != null) onComplete.onResult(false);
                return;
            }
            terminateOtherAppSessions(account, () -> {
                // Preserve the owner's hide-list until performLogout snapshots it for the hidden-account cascade.
                // The remaining generation cleanup and current-session logout still run in the same UI turn.
                wipeLocalAccountDataBeforeLogout(account);
                try {
                    if (onComplete != null) onComplete.onResult(true);
                } finally {
                    org.telegram.messenger.MessagesController.getInstance(account).performLogout(1);
                }
            });
        });
    }

    private static void wipeLocalAccountDataBeforeLogout(int account) {
        // Keep every cleanup step independent. A failure in Realtime/sync teardown must never skip the
        // credential wipe that follows. Private-space state is intentionally left intact here: both callers
        // immediately invoke performLogout(), which must first snapshot the owner's hidden-account edges and
        // cascade the logout before it performs the authoritative PIN/private-space slot wipe.
        try {
            LeemenRealtime.disconnect(account);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            // Full sync teardown (in-memory CRDT cache + debounce/watchdog + persisted state). It also
            // invalidates already-running callbacks so they cannot restore the old PIN after this wipe.
            LeemenSync.clearAccount(account);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            LeemenAnalytics.clearAccountGeneration(account);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            clear(account);            // token / sync_account_id / wrapped K_master (prefs)
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            // The AndroidKeyStore wrap-key alias is install-global (shared across accounts). Delete the raw
            // TEE key only when no other account still has a wrapped K_master, else theirs breaks.
            boolean anyKMasterLeft = false;
            for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (hasKMaster(a)) { anyKMasterLeft = true; break; }
            }
            if (!anyKMasterLeft) {
                LeemenKeyStore.deleteWrapKey();
            }
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            setDisabled(account, true); // don't silently re-create on next launch
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /**
     * The backend confirmed that this JWT's master-account generation was deleted elsewhere. Erase its
     * generation-bound data and immediately enter Telegram logout. The logout snapshots and terminates hidden
     * accounts before wiping the private-space working copy, so a later explicit registration cannot inherit or
     * push stale local state. This does not attempt to revoke sibling sessions: each surviving Leemen client
     * observes the same server signal and logs itself out.
     */
    public static void logoutDeletedGeneration(int account) {
        if (account < 0 || account >= org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT) return;
        if (!org.telegram.messenger.UserConfig.getInstance(account).isClientActivated()) return;
        // Suppress bind/sync work during the atomic wipe → logout transition. performLogout clears this
        // slot-scoped flag together with the rest of the binding once the Telegram account is deactivated.
        setDisabled(account, true);
        wipeLocalAccountDataBeforeLogout(account);
        org.telegram.messenger.MessagesController.getInstance(account).performLogout(1);
    }

    /**
     * The auth endpoint created a brand-new backend generation in an already-used local slot. Clear every
     * generation-bound local artifact before saving its token so an old PIN/blob/key can never seed the new
     * account. Unlike deleted-generation logout, this keeps the Telegram session active.
     */
    public static void prepareForNewGeneration(int account) {
        try {
            LeemenRealtime.disconnect(account);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            LeemenSync.clearAccount(account);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            org.telegram.messenger.SecondSpaceController.getInstance(account).wipeAllLocalData();
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        try {
            clear(account);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /**
     * An explicit Telegram login is the only operation allowed to re-arm an account after deletion.
     * Clear every persisted remnant of the previous local generation before /auth/telegram starts. This
     * deliberately also handles an interrupted remote-logout cleanup: a stale token or disabled flag can
     * otherwise make bindIfNeeded() return forever and leave the chat list fail-closed.
     */
    public static void prepareForTelegramLogin(int account) {
        prepareForNewGeneration(account);
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
     *  {confirm:"DELETE"} guard; cascades the core account graph + linked analytics trail server-side. */
    private static void requestServerDelete(int account, AccountDeletionCallback onDone) {
        String token = getToken(account);
        if (token == null) {
            // Without a bearer we cannot prove that the same Telegram identity has no Leemen data from
            // another device. Keep the local session intact and let the user retry after binding recovers.
            if (onDone != null) onDone.onResult(false);
            return;
        }
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("confirm", "DELETE");
        LeemenRestClient.post(LeemenConfig.EP_ACCOUNT_DELETE, token, body, (resp, code, ec, em) -> {
            boolean ok = false;
            try {
                ok = resp != null && code == 200 && resp.has("ok") && resp.get("ok").getAsBoolean();
            } catch (Throwable ignored) {
            }
            // A response can be lost after the server commits the deletion. Treat the authoritative
            // deleted-generation responses as idempotent success when the user retries.
            if (!ok) {
                ok = code == 401 && ("auth_account_deleted".equals(ec) || "account_deleted".equals(ec));
            }
            if (!ok) {
                ok = code == 404 && "account_not_found".equals(ec);
            }
            if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                org.telegram.messenger.FileLog.d("Leemen: /account/delete code=" + code + " ok=" + ok + (ec != null ? " err=" + ec : ""));
            }
            // Wake every currently connected sibling immediately. The broadcast is only a hint: each receiver
            // confirms the deleted generation against /session/status before wiping or logging out.
            if (ok) {
                LeemenRealtime.broadcastAccountGenerationInvalidated(account);
            }
            if (onDone != null) onDone.onResult(ok);
        });
    }

    /** Drop this account's Leemen identity (call on Telegram logout of the account). */
    public static void clear(int account) {
        boolean committed = prefs().edit()
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
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_TERMS + "_version")
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_KZ_CROSS_BORDER)
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_KZ_CROSS_BORDER + "_version")
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_ANALYTICS)
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_ANALYTICS + "_version")
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_ATTRIBUTION)
                .remove("consent_pending_" + account + "_" + LeemenConsent.TYPE_ATTRIBUTION + "_version")
                .commit();
        if (!committed && org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            org.telegram.messenger.FileLog.d("Leemen: failed to synchronously clear account slot " + account);
        }
        LeemenKey.clearAccount(account);
        LeemenMaxPrivacy.clearAuthBootstrapKey(account);
        LeemenConsent.clearAccount(account);
        LeemenAnalytics.clearAccountSession(account);
        // Run after the token removal: an old in-flight register callback now fails its token check, while
        // this final removal also wins if that callback completed in the narrow window above.
        LeemenDevice.clearAccount(account);
    }
}
