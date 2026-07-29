package org.telegram.messenger.leemen;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Reconciles server-authoritative account state that does not belong in the encrypted PS blob:
 * Premium entitlements and the default/max privacy mode. Realtime is notify-only, so every event is
 * confirmed with GET /me; the foreground cadence is the missed-event correctness backstop.
 */
public final class LeemenAccountState {

    private LeemenAccountState() {}

    /** Token-scoped so a reused local account slot cannot receive an older generation's response. */
    private static final Set<String> inFlight = new HashSet<>();
    /** A Realtime event racing an older GET must force one fresh snapshot after that GET settles. */
    private static final Set<String> pending = new HashSet<>();
    /** Every GET /me, including Consent/Billing callers, is ordered per local account slot. */
    private static final Object requestOrderLock = new Object();
    private static final String[] latestIssuedBearer = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] latestIssuedRequest = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static long nextRequestId;

    static long beginMeRequest(@Nullable String bearer) {
        if (TextUtils.isEmpty(bearer)) return 0L;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!bearer.equals(LeemenAccount.getToken(account))
                    || !UserConfig.getInstance(account).isClientActivated()
                    || LeemenAccount.isDisabled(account)) {
                continue;
            }
            synchronized (requestOrderLock) {
                long requestId = ++nextRequestId;
                latestIssuedBearer[account] = bearer;
                latestIssuedRequest[account] = requestId;
                return requestId;
            }
        }
        return 0L;
    }

    public static void refresh(final int account) {
        refresh(account, false);
    }

    public static void onRemoteChanged(final int account) {
        refresh(account, true);
    }

    private static void refresh(final int account, boolean ensureFreshAfterInFlight) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || !UserConfig.getInstance(account).isClientActivated()
                || LeemenAccount.isDisabled(account)) {
            return;
        }
        final String token = LeemenAccount.getToken(account);
        if (TextUtils.isEmpty(token)) return;
        synchronized (inFlight) {
            if (!inFlight.add(token)) {
                if (ensureFreshAfterInFlight) pending.add(token);
                return;
            }
        }
        LeemenRestClient.get(LeemenConfig.EP_ME, token, (resp, code, errorCode, errorMessage) -> {
            boolean repeat;
            synchronized (inFlight) {
                inFlight.remove(token);
                repeat = pending.remove(token);
            }
            if (token.equals(LeemenAccount.getToken(account))
                    && UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)
                    && (resp == null || code < 200 || code >= 300)
                    && BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: account state refresh failed account " + account
                        + " code=" + code + " err=" + errorCode);
            }
            if (repeat
                    && token.equals(LeemenAccount.getToken(account))
                    && UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)) {
                refresh(account, false);
            }
        });
    }

    /**
     * Apply every successful authenticated GET /me snapshot before its request-specific callback.
     * Keeping this at the REST boundary makes Premium/privacy global account state: a paywall check,
     * foreground poll, startup reconcile and Realtime wake-up all update the same controller cache.
     */
    static void applyMeSnapshot(@Nullable String bearer, long requestId, JsonObject response) {
        if (TextUtils.isEmpty(bearer) || requestId <= 0L || response == null) return;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!bearer.equals(LeemenAccount.getToken(account))
                    || !UserConfig.getInstance(account).isClientActivated()
                    || LeemenAccount.isDisabled(account)) {
                continue;
            }
            long serverNowMs = parseServerNow(response);
            if (serverNowMs <= 0L) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: ignored invalid /me snapshot account " + account);
                }
                return;
            }
            synchronized (requestOrderLock) {
                // A response from an older request must never overwrite a snapshot requested later.
                // server_now cannot order these safely: an older request can finish last after reading
                // part of its data before the newer request.
                if (!bearer.equals(latestIssuedBearer[account])
                        || requestId != latestIssuedRequest[account]) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: ignored stale /me snapshot account " + account);
                    }
                    return;
                }
            }
            if (!LeemenBilling.applyEntitlementsFromMe(account, response, serverNowMs)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: ignored invalid /me snapshot account " + account);
                }
                return;
            }
            applyPrivacyMode(account, response);
            return;
        }
    }

    private static long parseServerNow(JsonObject response) {
        try {
            if (!response.has("server_now") || response.get("server_now").isJsonNull()) return 0L;
            return LeemenBilling.parseExpiryMs(response.get("server_now").getAsString());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static void applyPrivacyMode(int account, JsonObject response) {
        try {
            if (!response.has("account") || !response.get("account").isJsonObject()) return;
            JsonObject accountObject = response.getAsJsonObject("account");
            if (!accountObject.has("privacy_mode") || accountObject.get("privacy_mode").isJsonNull()) return;
            String mode = accountObject.get("privacy_mode").getAsString();
            if (!"default".equals(mode) && !"max".equals(mode)) return;
            LeemenAccount.setPrivacyMode(account, mode);
            if (!LeemenAccount.hasKMaster(account)) {
                // In max mode this raises the unwrap prompt; in default it restores/bootstrap-wraps K_master.
                LeemenKey.ensureKey(account);
            }
        } catch (Throwable ignored) {
        }
    }
}
