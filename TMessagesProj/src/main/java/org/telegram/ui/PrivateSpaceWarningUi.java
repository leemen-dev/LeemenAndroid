package org.telegram.ui;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;

/**
 * Private-space privacy-warning dialogs, raised from the in-private-space warning button. These are
 * shown only in MODE_REAL (behind the PIN), so they may name the hidden chats explicitly — unlike the
 * cover-reachable sessions / privacy screens, whose warning copy stays generic.
 *
 * Two cases, cloud password first (higher priority):
 *   (1) no Telegram cloud password set  -> offer to set 2FA;
 *   (2) a non-leemen session exists      -> offer to open the sessions screen.
 */
public final class PrivateSpaceWarningUi {

    private PrivateSpaceWarningUi() {}

    /** (1) Higher priority: no Telegram cloud password is set. */
    public static void showNoCloudPasswordWarning(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivacyWarningNoCloudPasswordTitle));
        b.setMessage(LocaleController.getString(R.string.PrivacyWarningNoCloudPasswordText));
        b.setPositiveButton(LocaleController.getString(R.string.PrivacyWarningSetCloudPassword),
                (d, w) -> openSetCloudPassword(fragment));
        b.setNegativeButton(LocaleController.getString(R.string.PrivacyWarningNotNow), null);
        fragment.showDialog(b.create());
    }

    private static void openSetCloudPassword(BaseFragment fragment) {
        final int account = fragment.getCurrentAccount();
        // Fetch a FRESH password object each time: initPasswordNewAlgo mutates it in place (it prepends
        // random salt), so we must never reuse a shared/cached instance; this also reflects any change
        // since the warning was raised.
        TL_account.getPassword req = new TL_account.getPassword();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (!(response instanceof TL_account.Password)) {
                // Couldn't fetch — fall back to the setup screen, which self-loads for TYPE_INTRO.
                fragment.presentFragment(new TwoStepVerificationSetupActivity(TwoStepVerificationSetupActivity.TYPE_INTRO, null));
                return;
            }
            TL_account.Password pw = (TL_account.Password) response;
            if (!TwoStepVerificationActivity.canHandleCurrentPassword(pw, false)) {
                AlertsCreator.showUpdateAppAlert(fragment.getParentActivity(),
                        LocaleController.getString(R.string.UpdateAppAlert), true);
                return;
            }
            if (pw.has_password) {
                // Already set since the warning was raised — open management instead of setup.
                TwoStepVerificationActivity tsv = new TwoStepVerificationActivity();
                tsv.setPassword(pw);
                fragment.presentFragment(tsv);
                return;
            }
            TwoStepVerificationActivity.initPasswordNewAlgo(pw);
            int type = TextUtils.isEmpty(pw.email_unconfirmed_pattern)
                    ? TwoStepVerificationSetupActivity.TYPE_INTRO
                    : TwoStepVerificationSetupActivity.TYPE_EMAIL_CONFIRM;
            fragment.presentFragment(new TwoStepVerificationSetupActivity(type, pw));
        }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
    }

    /** (2) A non-leemen session is active — the hidden chats are visible there. */
    public static void showNonLeemenSessionWarning(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivacyWarningOtherSessionTitle));
        b.setMessage(LocaleController.getString(R.string.PrivacyWarningOtherSessionText));
        b.setPositiveButton(LocaleController.getString(R.string.PrivacyWarningOpenSessions),
                (d, w) -> fragment.presentFragment(new SessionsActivity(0)));
        b.setNegativeButton(LocaleController.getString(R.string.PrivacyWarningNotNow), null);
        fragment.showDialog(b.create());
    }
}
