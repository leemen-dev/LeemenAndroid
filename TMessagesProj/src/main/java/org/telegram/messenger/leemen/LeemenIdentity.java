package org.telegram.messenger.leemen;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 1 — invisible per-Telegram-account Leemen identity binding.
 *
 * After a Telegram login (and for already-logged-in accounts on app start), we obtain a Telegram-signed
 * statement of the user id WITHOUT opening a WebView: resolve @leemen_auth_bot, call MTProto
 * messages.requestWebView headless, pull the `tgWebAppData` (= initData) out of the returned URL fragment,
 * and POST it to /v1/auth/telegram. The backend verifies the HMAC, finds/creates the master_account +
 * sync_account, and returns {token, sync_account_id, privacy_mode}. We persist that per account.
 *
 * Best-effort + idempotent: skips bound accounts, dedupes concurrent attempts, never throws into callers.
 */
public final class LeemenIdentity {

    private LeemenIdentity() {}

    private static final Set<Integer> inFlight = new HashSet<>();
    /** Invalidates async bind callbacks when a local Telegram account slot is logged out and reused. */
    private static final long[] lifecycleGeneration = new long[UserConfig.MAX_ACCOUNT_COUNT];
    /** Generation owning the current inFlight entry; prevents an old callback from clearing a newer bind. */
    private static final long[] inFlightGeneration = new long[UserConfig.MAX_ACCOUNT_COUNT];

    /** Exact generation that a rejected session is allowed to renew. */
    private static final class Renewal {
        final String rejectedToken;
        final String masterAccountId;
        final String syncAccountId;

        Renewal(String rejectedToken, String masterAccountId, String syncAccountId) {
            this.rejectedToken = rejectedToken;
            this.masterAccountId = masterAccountId;
            this.syncAccountId = syncAccountId;
        }
    }

    // Post-login self-healing retry: the bind+key+sync chain can fail transiently right after a login (server
    // still settling a just-deleted identity, username resolver not warm yet, key fetch hiccup). Without it the
    // OFF-mode list stays fail-closed ("only the system chat") until the next app launch re-runs
    // bindAllActivated — the "delete → relogin → only system chat until restart" symptom.
    //
    // A delete → IMMEDIATE relogin races the backend's still-settling hard-delete cascade, which can take
    // longer than a short fixed window to re-accept /auth. The retry therefore uses capped-exponential backoff
    // and KEEPS retrying (up to a generous wall-clock ceiling) instead of giving up after a fixed attempt
    // count — otherwise a >60s backend settle stranded the gate closed until a cold restart. It self-terminates
    // the instant the sync lands (or the account is logged out / disabled). The gate stays fail-closed
    // throughout: nothing is revealed, we only drive the legitimate bind to completion so no restart is needed.
    private static final long RETRY_BASE_MS = 2000;
    private static final long RETRY_MAX_MS = 60_000;            // backoff caps at 60s between attempts
    private static final long RETRY_CEILING_MS = 30 * 60_000L;  // ...and stops after ~30 min of failures
    private static final Runnable[] retryRunnable = new Runnable[UserConfig.MAX_ACCOUNT_COUNT];

    /** Bind now and, if the initial sync doesn't complete, keep retrying with backoff (call at onAuthSuccess). */
    public static void bindWithRetry(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        cancelRetry(account);
        final long generation = getLifecycleGeneration(account);
        bindIfNeeded(account);
        scheduleRetry(account, 0, SystemClock.elapsedRealtime(), generation);
    }

    private static void scheduleRetry(final int account, final int attempt, final long startedAt,
                                      final long generation) {
        if (!isLifecycleCurrent(account, generation)) return;
        if (SystemClock.elapsedRealtime() - startedAt > RETRY_CEILING_MS) {
            return; // gave up after the ceiling; a cold start / foreground rearmPendingSync() retries
        }
        final Runnable r = () -> {
            retryRunnable[account] = null;
            if (!isLifecycleCurrent(account, generation)
                    || !UserConfig.getInstance(account).isClientActivated()
                    || LeemenAccount.isDisabled(account)
                    || LeemenSync.hasInitialSyncCompleted(account)) {
                return; // slot reused / logged out / deleted / sync already landed — nothing more to do
            }
            if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: bind retry #" + (attempt + 1) + " account " + account);
            bindIfNeeded(account);   // idempotent: binds, or (if bound) re-fetches the key
            LeemenSync.syncAll();    // and (if bound+keyed) drives the sync that opens the gate
            scheduleRetry(account, attempt + 1, startedAt, generation);
        };
        retryRunnable[account] = r;
        long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(attempt, 5));
        AndroidUtilities.runOnUIThread(r, delay);
    }

    /**
     * End every bind/retry owned by the previous occupant of this local Telegram account slot.
     * Call both on logout and on explicit login: the latter is the authoritative point at which a stale
     * persisted deletion flag/binding is allowed to be replaced by a newly authenticated generation.
     */
    public static void resetAccountLifecycle(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        cancelRetry(account);
        synchronized (inFlight) {
            lifecycleGeneration[account]++;
            inFlight.remove(account);
        }
    }

    /** Re-arm the post-login self-heal for any activated account whose initial sync hasn't landed yet.
     *  Call when the app returns to the foreground: {@code onResume} only reconnects realtime, which itself
     *  needs an existing bind, so an account left pending after the retry's ceiling (e.g. backgrounded while
     *  a just-deleted backend was still settling) would otherwise stay fail-closed until a cold restart. */
    public static void rearmPendingSync() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()
                        && !LeemenAccount.isDisabled(a)
                        && !LeemenSync.hasInitialSyncCompleted(a)) {
                    bindWithRetry(a);
                }
            } catch (Throwable ignore) {}
        }
    }

    private static void cancelRetry(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        if (retryRunnable[account] != null) {
            AndroidUtilities.cancelRunOnUIThread(retryRunnable[account]);
            retryRunnable[account] = null;
        }
    }

    /** App-start safety net: bind every activated account that isn't bound yet. */
    public static void bindAllActivated() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    bindIfNeeded(a);
                }
            } catch (Throwable ignore) {}
        }
    }

    /** Idempotent, best-effort bind for one account. Safe to call repeatedly. */
    public static void bindIfNeeded(int account) {
        bind(account, false);
    }

    /**
     * Replace a rejected backend session JWT without touching the account's local protected-space state.
     *
     * The backend session has a finite TTL and can also become unverifiable after a signing-key rotation.
     * Keeping the old token makes every protected request (including GET /me) fail forever because a normal
     * {@link #bindIfNeeded(int)} deliberately skips an account that still has a persisted binding. The
     * rejected-token comparison makes a late 401 harmless after another request has already refreshed the
     * session, while the regular in-flight guard deduplicates concurrent 401 responses.
     */
    static void renewRejectedSession(int account, String rejectedToken) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || TextUtils.isEmpty(rejectedToken)
                || !rejectedToken.equals(LeemenAccount.getToken(account))) {
            return;
        }
        bind(account, true);
    }

    private static void bind(int account, boolean forceSessionRenewal) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        long generation = -1;
        try {
            if (!UserConfig.getInstance(account).isClientActivated()) return;
            if (LeemenAccount.isDisabled(account)) return; // user deleted their Leemen account
            if (!forceSessionRenewal && LeemenAccount.hasBinding(account)) {
                LeemenKey.ensureKey(account); // bound already; make sure the key was fetched too
                return;
            }
            final Renewal renewal;
            if (forceSessionRenewal) {
                String masterAccountId = LeemenAccount.getMasterAccountId(account);
                String syncAccountId = LeemenAccount.getSyncAccountId(account);
                String rejectedToken = LeemenAccount.getToken(account);
                if (TextUtils.isEmpty(rejectedToken)
                        || TextUtils.isEmpty(masterAccountId)
                        || TextUtils.isEmpty(syncAccountId)) {
                    return; // cannot prove which existing generation is safe to renew
                }
                renewal = new Renewal(rejectedToken, masterAccountId, syncAccountId);
            } else {
                renewal = null;
            }
            synchronized (inFlight) {
                if (inFlight.contains(account)) return;
                inFlight.add(account);
                generation = lifecycleGeneration[account];
                inFlightGeneration[account] = generation;
            }
            resolveBotThenBind(account, generation, renewal);
        } catch (Throwable e) {
            FileLog.e(e);
            if (generation >= 0) clearInFlight(account, generation);
        }
    }

    private static void clearInFlight(int account, long generation) {
        synchronized (inFlight) {
            if (inFlight.contains(account) && inFlightGeneration[account] == generation) {
                inFlight.remove(account);
            }
        }
    }

    private static long getLifecycleGeneration(int account) {
        synchronized (inFlight) {
            return lifecycleGeneration[account];
        }
    }

    private static boolean isLifecycleCurrent(int account, long generation) {
        synchronized (inFlight) {
            return lifecycleGeneration[account] == generation;
        }
    }

    private static void resolveBotThenBind(int account, long generation, Renewal renewal) {
        MessagesController.getInstance(account).getUserNameResolver().resolve(LeemenConfig.AUTH_BOT_USERNAME, peerId -> {
            try {
                if (!isLifecycleCurrent(account, generation)) {
                    clearInFlight(account, generation);
                    return;
                }
                if (peerId == null || peerId <= 0) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: auth-bot resolve failed (peerId=" + peerId + ")");
                    clearInFlight(account, generation);
                    return;
                }
                requestInitData(account, peerId, generation, renewal);
            } catch (Throwable e) {
                FileLog.e(e);
                clearInFlight(account, generation);
            }
        });
    }

    private static void requestInitData(int account, long botId, long generation, Renewal renewal) {
        requestInitData(account, botId, generation, false, renewal);
    }

    /** Fetch a FRESH, single-use initData via headless requestWebView, then POST it. CONTRACT §2: the backend
     *  accepts each initData exactly once and only within 1h of its auth_date — so every call obtains a
     *  brand-new signed string; a string is never cached or reused. {@code retried} caps the replay/expired
     *  self-retry at exactly one. */
    private static void requestInitData(int account, long botId, long generation, boolean retried, Renewal renewal) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_requestWebView req = new TLRPC.TL_messages_requestWebView();
        req.peer = mc.getInputPeer(botId);
        req.bot = mc.getInputUser(botId);
        req.platform = "android";
        req.url = LeemenConfig.AUTH_WEBAPP_URL;
        req.flags |= 2; // url is flags.1

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!isLifecycleCurrent(account, generation)) {
                clearInFlight(account, generation);
                return;
            }
            if (!(response instanceof TLRPC.TL_webViewResultUrl)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: requestWebView failed: " + (error != null ? error.text : "null response"));
                }
                clearInFlight(account, generation);
                return;
            }
            String initData = extractInitData(((TLRPC.TL_webViewResultUrl) response).url);
            if (TextUtils.isEmpty(initData)) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: no tgWebAppData in requestWebView result url");
                clearInFlight(account, generation);
                return;
            }
            postAuth(account, botId, initData, generation, retried, renewal);
        }));
    }

    /** Extract the tgWebAppData value (= raw initData querystring) from the result URL fragment. */
    private static String extractInitData(String resultUrl) {
        if (TextUtils.isEmpty(resultUrl)) return null;
        try {
            String fragment = Uri.parse(resultUrl).getEncodedFragment();
            if (TextUtils.isEmpty(fragment)) {
                int h = resultUrl.indexOf('#');
                fragment = h >= 0 ? resultUrl.substring(h + 1) : null;
            }
            if (TextUtils.isEmpty(fragment)) return null;
            for (String part : fragment.split("&")) {
                if (part.startsWith("tgWebAppData=")) {
                    String v = part.substring("tgWebAppData=".length());
                    return URLDecoder.decode(v, StandardCharsets.UTF_8.name());
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private static void postAuth(int account, long botId, String initData, long generation,
                                 boolean retried, Renewal renewal) {
        final long expectedTelegramUserId = UserConfig.getInstance(account).getClientUserId();
        JsonObject body = new JsonObject();
        body.addProperty("initData", initData);
        // Opt in explicitly so rolling/older clients do not receive an unused ciphertext snapshot. An older
        // backend ignores this field and returns the legacy response, which is handled below.
        body.addProperty("bootstrap_version", LeemenAuthBootstrap.SCHEMA_VERSION);
        if (renewal != null) {
            // Lookup-only mode: the backend may mint a replacement JWT only for this exact still-existing
            // generation. It must never create a new master account while healing a rejected session.
            body.addProperty("mode", "renew");
            body.addProperty("expected_master_account_id", renewal.masterAccountId);
            body.addProperty("expected_sync_account_id", renewal.syncAccountId);
        }
        LeemenRestClient.post(LeemenConfig.EP_AUTH_TELEGRAM, null, body, (resp, code, errCode, errMsg) -> {
            try {
                if (!isLifecycleCurrent(account, generation)
                        || !UserConfig.getInstance(account).isClientActivated()
                        || UserConfig.getInstance(account).getClientUserId() != expectedTelegramUserId
                        || LeemenAccount.isDisabled(account)) {
                    clearInFlight(account, generation);
                    return; // the slot was logged out, reused, or entered deletion while auth was in flight
                }
                if (resp != null && code >= 200 && code < 300 && resp.has("token") && resp.has("sync_account_id")) {
                    String token = resp.get("token").getAsString();
                    String syncId = resp.get("sync_account_id").getAsString();
                    String masterId = resp.has("master_account_id") && !resp.get("master_account_id").isJsonNull()
                            ? resp.get("master_account_id").getAsString() : null;
                    String privacy = resp.has("privacy_mode") && !resp.get("privacy_mode").isJsonNull()
                            ? resp.get("privacy_mode").getAsString() : null;
                    boolean created = resp.has("created") && !resp.get("created").isJsonNull()
                            && resp.get("created").getAsBoolean();
                    final LeemenAuthBootstrap bootstrap = LeemenAuthBootstrap.parse(resp);
                    if (renewal != null) {
                        // Defense in depth for a rolling backend deploy (or a compromised/misbehaving
                        // endpoint): never let session recovery cross generations or invoke a local wipe.
                        if (!renewal.rejectedToken.equals(LeemenAccount.getToken(account))
                                || created
                                || !renewal.masterAccountId.equals(masterId)
                                || !renewal.syncAccountId.equals(syncId)) {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("Leemen: rejected unsafe session renewal account " + account);
                            }
                            clearInFlight(account, generation);
                            return;
                        }
                    } else if (created) {
                        LeemenAccount.prepareForNewGeneration(account);
                    }
                    LeemenAccount.save(account, token, syncId, masterId, privacy);
                    final Runnable startProtectedSync = () -> {
                        boolean accepted = false;
                        if (bootstrap != null && LeemenSync.stageAuthBootstrap(account, token, bootstrap)) {
                            accepted = LeemenKey.acceptAuthBootstrap(account, token, bootstrap.key);
                            if (!accepted) {
                                LeemenSync.discardAuthBootstrap(account, token);
                            }
                        }
                        if (!accepted) {
                            // Missing/malformed/unknown bootstrap (rolling deploy), or a local key-store failure:
                            // retain the established fail-closed key + concurrent blob GET path.
                            boolean keyAlreadyReady = LeemenAccount.hasKMaster(account);
                            LeemenKey.ensureKey(account);
                            if (keyAlreadyReady) {
                                LeemenSync.onRemoteChanged(account);
                            }
                        }
                    };
                    if (renewal != null) {
                        LeemenSync.onSessionRenewed(account, startProtectedSync);
                    } else {
                        startProtectedSync.run();
                    }
                    // A fresh/replacement token must immediately consume the authoritative snapshot. Waiting
                    // for the next startup/foreground poll leaves Premium and privacy stale after first bind
                    // and makes a recovered expired/rotated session appear broken until another app cycle.
                    LeemenAccountState.refresh(account);
                    // Binding is asynchronous, so the startup-wide Play restore may have run before this
                    // identity existed. Re-enter the shared/coalesced restore path now that routing by the
                    // exact master_account_id is possible.
                    LeemenBilling.getInstance().restore(account);
                    // A session token now exists → let the Terms/Privacy acceptance gate run (LaunchActivity).
                    try {
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.leemenBindCompleted, account);
                    } catch (Throwable ignore) {}
                    if (BuildVars.LOGS_ENABLED) {
                        // NB: never log sync_account_id — it's the Realtime channel secret (LeemenConfig).
                        FileLog.d("Leemen: bound account " + account
                                + " mode=" + privacy + " created=" + (resp.has("created") ? resp.get("created") : "?"));
                    }
                    clearInFlight(account, generation);
                    return;
                }
                // CONTRACT §2: initData is FRESH + SINGLE-USE (accepted once, within 1h of auth_date). On a
                // replayed/expired string, obtain a BRAND-NEW initData and retry EXACTLY once — never reuse or
                // loop on the same string.
                if (!retried
                        && ((code == 401 && "init_data_replayed".equals(errCode))
                            || (code == 400 && "init_data_expired".equals(errCode)))) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: /auth/telegram " + errCode + " — refetch fresh initData, retry once");
                    requestInitData(account, botId, generation, true, renewal); // keeps the in-flight guard; fresh initData
                    return;
                }
                if (renewal != null
                        && code == 401
                        && "auth_account_deleted".equals(errCode)
                        && renewal.rejectedToken.equals(LeemenAccount.getToken(account))
                        && !LeemenAccount.isDisabled(account)) {
                    // Fresh Telegram proof plus the backend's lookup-only renewal confirms that the exact
                    // stored generation no longer exists. Preserve the established destructive ordering.
                    clearInFlight(account, generation);
                    LeemenAccount.logoutDeletedGeneration(account);
                    return;
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: /auth/telegram failed code=" + code + " err=" + errCode + " " + errMsg);
                }
                clearInFlight(account, generation);
            } catch (Throwable e) {
                FileLog.e(e);
                clearInFlight(account, generation);
            }
        });
    }
}
