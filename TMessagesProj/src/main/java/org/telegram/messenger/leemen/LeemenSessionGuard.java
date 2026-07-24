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

    /** An open stale session self-terminates within this bound even if it otherwise makes no REST calls. */
    private static final long FOREGROUND_CHECK_INTERVAL_MS = 30_000L;

    private static final Set<Integer> checksInFlight = new HashSet<>();
    /** Token-scoped rather than account-scoped: a reused slot with a new generation must remain handleable. */
    private static final Set<String> terminatingTokens = new HashSet<>();

    private static boolean foreground;

    private static final Runnable foregroundPoll = new Runnable() {
        @Override
        public void run() {
            if (!foreground) return;
            checkAllBoundAccounts();
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

    private static void checkAllBoundAccounts() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                if (UserConfig.getInstance(account).isClientActivated()
                        && !LeemenAccount.isDisabled(account)
                        && LeemenAccount.hasBinding(account)) {
                    check(account);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private static void check(final int account) {
        final String token = LeemenAccount.getToken(account);
        if (TextUtils.isEmpty(token)) return;
        synchronized (checksInFlight) {
            if (!checksInFlight.add(account)) return;
        }
        LeemenRestClient.get(LeemenConfig.EP_SESSION_STATUS, token, (resp, code, errorCode, errorMsg) -> {
            synchronized (checksInFlight) {
                checksInFlight.remove(account);
            }
            // LeemenRestClient routes the authoritative deletion response through onBackendResult before
            // this callback. Everything else is retryable on the next poll/foreground entry.
        });
    }

    /**
     * Observe every authenticated REST response, including the dedicated status poll. Kept package-visible
     * for {@link LeemenRestClient}; callbacks are already delivered on the UI thread.
     */
    static void onBackendResult(@Nullable String bearer, int httpCode, @Nullable String errorCode) {
        if (TextUtils.isEmpty(bearer) || TextUtils.isEmpty(errorCode)) return;
        final boolean deletedGeneration =
                httpCode == 401 && ("auth_account_deleted".equals(errorCode) || "account_deleted".equals(errorCode));
        // Compatibility with a backend rolling deployment: /me and account/key historically exposed the
        // same authoritative missing-master condition as 404 account_not_found.
        final boolean legacyMissingMaster = httpCode == 404 && "account_not_found".equals(errorCode);
        if (!deletedGeneration && !legacyMissingMaster) return;

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!bearer.equals(LeemenAccount.getToken(account))) continue;
            if (!UserConfig.getInstance(account).isClientActivated()) return;
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
