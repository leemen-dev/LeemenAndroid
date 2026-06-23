package org.telegram.messenger.leemen;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * Privacy-warning state for the in-private-space warning indicator. Two conditions make the hidden
 * chats potentially exposed, and light up the warning button while the user is inside the private space:
 *   (1) a NON-LEEMEN active session exists — another Telegram client where the hidden chats are NOT
 *       hidden (the hiding is client-side, only in this app);
 *   (2) no Telegram cloud password (2FA) is set — someone could break into a non-leemen session and the
 *       hidden chats would be visible there.
 * The server pushes no update when either changes, so both are fetched on demand (getAuthorizations /
 * getPassword), cached per account, and refreshed when the user enters the private space or returns to
 * the chat list. A per-request in-flight guard dedups concurrent fetches (instead of a time throttle),
 * so a just-fixed condition is always re-fetched on the next refresh rather than latched.
 *
 * All state lives on the UI thread: refresh() is called from UI, and every response callback marshals
 * its writes through AndroidUtilities.runOnUIThread.
 */
public final class LeemenPrivacyWarning {

    private LeemenPrivacyWarning() {}

    private static final int N = UserConfig.MAX_ACCOUNT_COUNT;
    private static final boolean[] sessionsLoaded = new boolean[N];
    private static final boolean[] nonLeemenSessionFound = new boolean[N];
    private static final boolean[] passwordLoaded = new boolean[N];
    private static final boolean[] cloudPasswordSet = new boolean[N];
    private static final boolean[] sessionsInFlight = new boolean[N];
    private static final boolean[] passwordInFlight = new boolean[N];

    private static boolean inRange(int account) {
        return account >= 0 && account < N;
    }

    /** A session where the hidden chats would be visible: NOT this app's api_id, and NOT the current
     *  session (flags bit 0). Mirrors {@link LeemenAccount#terminateOtherAppSessions}. */
    public static boolean isNonLeemenActiveSession(TLRPC.TL_authorization a) {
        return a != null && (a.flags & 1) == 0 && a.api_id != BuildVars.APP_ID;
    }

    /** True when something is privacy-wrong and the warning indicator should light up. Only fires once
     *  the relevant data has actually loaded, so it never flashes before we know. */
    public static boolean hasWarning(int account) {
        return isCloudPasswordMissing(account) || hasNonLeemenSession(account);
    }

    /** Higher-priority condition: no Telegram cloud password is set. */
    public static boolean isCloudPasswordMissing(int account) {
        return inRange(account) && passwordLoaded[account] && !cloudPasswordSet[account];
    }

    public static boolean hasNonLeemenSession(int account) {
        return inRange(account) && sessionsLoaded[account] && nonLeemenSessionFound[account];
    }

    /** Fetch both conditions from the server and cache them. {@code onUpdated} runs on the UI thread after
     *  EACH successful response, so the warning indicator refreshes as soon as either resolves. */
    public static void refresh(int account, Runnable onUpdated) {
        if (!inRange(account) || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        refreshSessions(account, onUpdated);
        refreshCloudPassword(account, onUpdated);
    }

    public static void refreshSessions(int account, Runnable onUpdated) {
        if (!inRange(account) || sessionsInFlight[account]) {
            return;
        }
        sessionsInFlight[account] = true;
        TL_account.getAuthorizations req = new TL_account.getAuthorizations();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            sessionsInFlight[account] = false;
            if (response instanceof TL_account.authorizations) {
                TL_account.authorizations res = (TL_account.authorizations) response;
                boolean found = false;
                if (res.authorizations != null) {
                    for (int i = 0, sz = res.authorizations.size(); i < sz; i++) {
                        if (isNonLeemenActiveSession(res.authorizations.get(i))) {
                            found = true;
                            break;
                        }
                    }
                }
                nonLeemenSessionFound[account] = found;
                sessionsLoaded[account] = true;
                if (onUpdated != null) onUpdated.run();
            }
        }));
    }

    public static void refreshCloudPassword(int account, Runnable onUpdated) {
        if (!inRange(account) || passwordInFlight[account]) {
            return;
        }
        passwordInFlight[account] = true;
        TL_account.getPassword req = new TL_account.getPassword();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            passwordInFlight[account] = false;
            if (response instanceof TL_account.Password) {
                TL_account.Password pw = (TL_account.Password) response;
                cloudPasswordSet[account] = pw.has_password;
                passwordLoaded[account] = true;
                if (onUpdated != null) onUpdated.run();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
    }
}
