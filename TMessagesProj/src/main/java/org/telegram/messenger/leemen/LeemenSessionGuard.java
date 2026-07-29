package org.telegram.messenger.leemen;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Server-authoritative account-generation guard.
 *
 * Telegram can temporarily forbid a freshly-created session from revoking other authorizations. Account
 * deletion still removes the Leemen master row immediately, so every surviving client periodically asks the
 * backend whether its JWT generation remains active. A valid signature whose {@code sub} is gone returns
 * {@code auth_account_deleted}; only that explicit signal triggers destructive local cleanup + Telegram logout.
 *
 * Transport failures, 5xx responses and unknown errors are deliberately ignored and retried later.
 */
public final class LeemenSessionGuard {

    private LeemenSessionGuard() {}

    /** Foreground backstop for deletion plus Premium/privacy-mode events missed while Realtime was offline. */
    private static final long FOREGROUND_CHECK_INTERVAL_MS = 30_000L;

    /** Status requests are token-scoped so a newly registered generation reusing the same slot is independent. */
    private static final Set<String> checksInFlight = new HashSet<>();
    /** A Realtime deletion hint received during an older status request must force one fresh check afterward. */
    private static final Set<String> checksPending = new HashSet<>();
    /** Token-scoped rather than account-scoped: a reused slot with a new generation must remain handleable. */
    private static final Set<String> terminatingTokens = new HashSet<>();

    private static boolean foreground;

    private static final Runnable foregroundPoll = new Runnable() {
        @Override
        public void run() {
            if (!foreground) return;
            refreshAllBoundAccounts();
            AndroidUtilities.runOnUIThread(this, FOREGROUND_CHECK_INTERVAL_MS);
        }
    };

    /** Start (or restart) the immediate + 30-second foreground validation loop. UI thread. */
    public static void onAppForeground() {
        foreground = true;
        AndroidUtilities.cancelRunOnUIThread(foregroundPoll);
        foregroundPoll.run();
    }

    /** Stop periodic traffic while the app is not visible. */
    public static void onAppBackground() {
        foreground = false;
        AndroidUtilities.cancelRunOnUIThread(foregroundPoll);
    }

    private static void refreshAllBoundAccounts() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                if (UserConfig.getInstance(account).isClientActivated()
                        && !LeemenAccount.isDisabled(account)
                        && LeemenAccount.hasBinding(account)) {
                    // GET /me is authoritative for account existence, privacy mode and entitlements. Its
                    // deleted-generation errors are still handled centrally by LeemenRestClient below.
                    LeemenAccountState.refresh(account);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /**
     * A Realtime event is only a low-trust wake-up hint; this method immediately asks the authenticated REST
     * endpoint for the authoritative generation state before any destructive action.
     */
    public static void checkNow(final int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
            if (!UserConfig.getInstance(account).isClientActivated()
                    || LeemenAccount.isDisabled(account)
                    || !LeemenAccount.hasBinding(account)) {
                return;
            }
            check(account, true);
        });
    }

    private static void check(final int account, boolean ensureFreshAfterInFlight) {
        final String token = LeemenAccount.getToken(account);
        if (TextUtils.isEmpty(token)) return;
        synchronized (checksInFlight) {
            if (!checksInFlight.add(token)) {
                if (ensureFreshAfterInFlight) {
                    checksPending.add(token);
                }
                return;
            }
        }
        LeemenRestClient.get(LeemenConfig.EP_SESSION_STATUS, token, (resp, code, errorCode, errorMsg) -> {
            boolean repeat;
            synchronized (checksInFlight) {
                checksInFlight.remove(token);
                repeat = checksPending.remove(token);
            }
            // LeemenRestClient routes the authoritative deletion response through onBackendResult before
            // this callback. Everything else is retryable on the next poll/foreground entry.
            if (repeat
                    && token.equals(LeemenAccount.getToken(account))
                    && UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)) {
                check(account, false);
            }
        });
    }

    /**
     * Observe every authenticated REST response, including the dedicated status poll. Kept package-visible
     * for {@link LeemenRestClient}; callbacks are already delivered on the UI thread.
     */
    static void onBackendResult(@Nullable String bearer, int httpCode, @Nullable String errorCode) {
        if (TextUtils.isEmpty(bearer) || TextUtils.isEmpty(errorCode)) return;
        final boolean rejectedSession =
                httpCode == 401 && ("auth_invalid".equals(errorCode) || "auth_malformed".equals(errorCode));
        final boolean deletedGeneration =
                httpCode == 401 && ("auth_account_deleted".equals(errorCode) || "account_deleted".equals(errorCode));
        // Compatibility with a backend rolling deployment: /me and account/key historically exposed the
        // same authoritative missing-master condition as 404 account_not_found.
        final boolean legacyMissingMaster = httpCode == 404 && "account_not_found".equals(errorCode);
        if (!rejectedSession && !deletedGeneration && !legacyMissingMaster) return;

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!bearer.equals(LeemenAccount.getToken(account))) continue;
            if (!UserConfig.getInstance(account).isClientActivated()) return;
            if (rejectedSession) {
                // Expiry/key rotation is recoverable and must never wipe local protected-space data. Obtain a
                // fresh Telegram-backed Leemen JWT in place; the successful bind immediately retries GET /me,
                // which restores Premium/privacy. Concurrent 401s are deduplicated inside LeemenIdentity.
                if (!LeemenAccount.isDisabled(account)) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: backend session rejected; renewing account " + account);
                    }
                    LeemenIdentity.renewRejectedSession(account, bearer);
                }
                return;
            }
            // The initiating delete flow sets disabled before calling /account/delete and owns its
            // revoke → wipe → logout ordering. Do not let a concurrent request pre-empt that sequence.
            if (LeemenAccount.isDisabled(account)) return;
            synchronized (terminatingTokens) {
                if (!terminatingTokens.add(bearer)) return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: deleted account generation detected; logging out account " + account);
            }
            LeemenAccount.logoutDeletedGeneration(account);
            return;
        }
    }
}
