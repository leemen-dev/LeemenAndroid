package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Build;
import android.os.PersistableBundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.leemen.LeemenAccount;
import org.telegram.messenger.leemen.LeemenMaxPrivacy;
import org.telegram.messenger.leemen.LeemenMnemonic;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Max-privacy (zero-knowledge) management screen: enable / change passphrase / disable. The destructive crypto
 * lives in {@link LeemenMaxPrivacy} (off-UI Argon2id). {@link #promptForUnwrap} is the new-device flow the app
 * triggers on {@code leemenMaxKeyNeeded} — enter the passphrase (or recovery phrase, or reset) to recover K_master.
 */
public class LeemenPrivacyModeActivity extends BaseFragment {

    private static final int MIN_PASSPHRASE = 8;
    private static final long RECOVERY_CLIPBOARD_TTL_MS = 60_000L;
    private static boolean promptShowing; // guards the new-device unwrap dialog against stacking
    private LinearLayout container;

    private interface OnText { void run(String text); }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.LeemenPrivacyModeTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(container, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        rebuild();
        return root;
    }

    private void rebuild() {
        if (container == null) return;
        Context ctx = container.getContext();
        container.removeAllViews();
        boolean max = LeemenAccount.isMaxPrivacy(currentAccount);

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(ctx);
        info.setText(LocaleController.getString(max ? R.string.LeemenMaxEnabledInfo : R.string.LeemenPrivacyModeInfo));
        container.addView(info);

        if (!max) {
            container.addView(row(ctx, LocaleController.getString(R.string.LeemenEnableMax), false, false, v -> startEnable()));
        } else {
            container.addView(row(ctx, LocaleController.getString(R.string.LeemenChangeCode), true, false, v -> startChange()));
            container.addView(row(ctx, LocaleController.getString(R.string.LeemenDisableMax), false, true, v -> startDisable()));
        }
    }

    private TextSettingsCell row(Context ctx, String title, boolean divider, boolean red, View.OnClickListener click) {
        TextSettingsCell c = new TextSettingsCell(ctx);
        c.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        c.setText(title, divider);
        if (red) c.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        c.setOnClickListener(click);
        return c;
    }

    // --- enable / change / disable ---

    private void startEnable() {
        askNewPassphrase(R.string.LeemenSetPassphraseTitle, pass -> {
            AlertDialog progress = spinner(getParentActivity());
            LeemenMaxPrivacy.upgrade(currentAccount, pass, (ok, mnemonic, err) -> {
                dismiss(progress);
                if (ok && mnemonic != null) {
                    showRecovery(mnemonic, this::rebuild);
                } else {
                    toast(getParentActivity(), LocaleController.getString(R.string.LeemenMaxError));
                }
            });
        });
    }

    private void startChange() {
        askNewPassphrase(R.string.LeemenChangeCode, pass -> {
            AlertDialog progress = spinner(getParentActivity());
            LeemenMaxPrivacy.changeCode(currentAccount, pass, (ok, err) -> {
                dismiss(progress);
                toast(getParentActivity(), LocaleController.getString(ok ? R.string.LeemenCodeChanged : R.string.LeemenMaxError));
            });
        });
    }

    private void startDisable() {
        Activity act = getParentActivity();
        if (act == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(act);
        b.setTitle(LocaleController.getString(R.string.LeemenDisableMax));
        b.setMessage(LocaleController.getString(R.string.LeemenDisableMaxConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
            AlertDialog progress = spinner(act);
            LeemenMaxPrivacy.downgrade(currentAccount, (ok, err) -> {
                dismiss(progress);
                if (ok) rebuild();
                toast(act, LocaleController.getString(ok ? R.string.LeemenMaxDisabled : R.string.LeemenMaxError));
            });
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void askNewPassphrase(int titleRes, OnText onOk) {
        Activity act = getParentActivity();
        if (act == null) return;
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(24), dp(8), dp(24), dp(4));
        final EditTextBoldCursor p1 = passField(act, LocaleController.getString(R.string.LeemenPassphraseHint));
        final EditTextBoldCursor p2 = passField(act, LocaleController.getString(R.string.LeemenPassphraseConfirmHint));
        ll.addView(p1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        ll.addView(p2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(act);
        b.setTitle(LocaleController.getString(titleRes));
        b.setMessage(LocaleController.getString(R.string.LeemenSetPassphraseInfo));
        b.setView(ll);
        b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = b.create();
        showDialog(dialog);
        View pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setOnClickListener(v -> {
                String a = text(p1), c = text(p2);
                if (a.length() < MIN_PASSPHRASE) { toast(act, LocaleController.getString(R.string.LeemenPassphraseTooShort)); return; }
                if (!a.equals(c)) { toast(act, LocaleController.getString(R.string.LeemenPassphraseMismatch)); return; }
                dialog.dismiss();
                onOk.run(a);
            });
        }
    }

    private void showRecovery(String mnemonic, Runnable onDone) {
        Activity act = getParentActivity();
        if (act == null) { if (onDone != null) onDone.run(); return; }
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(24), dp(8), dp(24), dp(4));
        TextView words = new TextView(act);
        words.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        words.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        words.setTypeface(Typeface.MONOSPACE);
        words.setText(mnemonic);
        words.setTextIsSelectable(true);
        ll.addView(words, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView copy = new TextView(act);
        copy.setText(LocaleController.getString(R.string.Copy));
        copy.setTextColor(Theme.getColor(Theme.key_dialogTextLink));
        copy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        copy.setPadding(0, dp(14), 0, 0);
        copy.setOnClickListener(v -> {
            copyRecoveryPhrase(act, mnemonic);
            toast(act, LocaleController.getString(R.string.LeemenRecoveryCopied));
        });
        ll.addView(copy, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(act);
        b.setTitle(LocaleController.getString(R.string.LeemenRecoveryTitle));
        b.setMessage(LocaleController.getString(R.string.LeemenRecoveryInfo));
        b.setView(ll);
        b.setPositiveButton(LocaleController.getString(R.string.LeemenRecoverySaved), (d, w) -> { if (onDone != null) onDone.run(); });
        AlertDialog dialog = b.create();
        // The upgrade is already committed. The user must explicitly confirm saving the one-time phrase;
        // Back/outside dismissal would make the max-mode data unrecoverable.
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        showDialog(dialog);
        if (dialog.getWindow() != null) {
            // Recovery material remains protected even when the user intentionally allows screenshots in PS.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private static void copyRecoveryPhrase(Activity activity, String mnemonic) {
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            ClipData clip = ClipData.newPlainText("Leemen recovery phrase", mnemonic);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    ClipData current = clipboard.getPrimaryClip();
                    if (current == null || current.getItemCount() == 0
                            || !TextUtils.equals(current.getItemAt(0).coerceToText(activity), mnemonic)) {
                        return; // the user copied something else; never erase it
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip();
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                } catch (Throwable ignore) {
                }
            }, RECOVERY_CLIPBOARD_TTL_MS);
        } catch (Throwable ignore) {
        }
    }

    // --- new-device unwrap prompt (triggered by leemenMaxKeyNeeded) ---

    public static void promptForUnwrap(Activity activity, int account) {
        if (activity == null || promptShowing) return;
        if (LeemenAccount.hasKMaster(account)) return; // key already recovered — nothing to prompt for
        LinearLayout ll = new LinearLayout(activity);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(24), dp(8), dp(24), dp(4));
        final EditTextBoldCursor field = passField(activity, LocaleController.getString(R.string.LeemenPassphraseHint));
        ll.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.LeemenUnlockTitle));
        b.setMessage(LocaleController.getString(R.string.LeemenUnlockInfo));
        b.setView(ll);
        b.setPositiveButton(LocaleController.getString(R.string.LeemenUnlockAction), null);
        b.setNeutralButton(LocaleController.getString(R.string.LeemenUseRecovery), null);
        AlertDialog dialog = b.create();
        // Non-skippable: decryption is mandatory on a new device — passphrase, recovery, or reset. No cancel.
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> promptShowing = false);
        promptShowing = true;
        dialog.show();
        View pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setOnClickListener(v -> {
                String pass = text(field);
                if (pass.isEmpty()) return;
                AlertDialog progress = spinner(activity);
                LeemenMaxPrivacy.unwrapWithPassphrase(account, pass, (ok, err) -> {
                    dismiss(progress);
                    if (ok) { dialog.dismiss(); toast(activity, LocaleController.getString(R.string.LeemenUnlocked)); }
                    else toast(activity, LocaleController.getString(R.string.LeemenWrongSecret));
                });
            });
        }
        View neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (neutral != null) {
            neutral.setOnClickListener(v -> { dialog.dismiss(); promptForRecovery(activity, account); });
        }
    }

    private static void promptForRecovery(Activity activity, int account) {
        if (activity == null) return;
        LinearLayout ll = new LinearLayout(activity);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(24), dp(8), dp(24), dp(4));
        final EditTextBoldCursor field = new EditTextBoldCursor(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHint(LocaleController.getString(R.string.LeemenRecoveryHint));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        ll.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView reset = new TextView(activity);
        reset.setText(LocaleController.getString(R.string.LeemenDeleteLostSecrets));
        reset.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        reset.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        reset.setPadding(0, dp(16), 0, 0);
        ll.addView(reset, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(LocaleController.getString(R.string.LeemenEnterRecoveryTitle));
        b.setMessage(LocaleController.getString(R.string.LeemenEnterRecoveryInfo));
        b.setView(ll);
        b.setPositiveButton(LocaleController.getString(R.string.LeemenUnlockAction), null);
        b.setNegativeButton(LocaleController.getString(R.string.Back), null);
        AlertDialog dialog = b.create();
        // Non-skippable: "Back" returns to the passphrase prompt, not out of the decryption gate.
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        reset.setOnClickListener(v -> {
            dialog.dismiss();
            // Losing both secrets must never clear only the PS metadata: that would make its Telegram chats
            // visible. Use the full PIN-gated account deletion + all-Leemen-session logout flow instead.
            PrivacySettingsActivity.showLeemenDeleteDialog(activity, account,
                    () -> promptForRecovery(activity, account));
        });
        View back = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (back != null) {
            back.setOnClickListener(v -> { dialog.dismiss(); promptForUnwrap(activity, account); });
        }
        View pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setOnClickListener(v -> {
                String phrase = text(field);
                if (!LeemenMnemonic.isValid(phrase)) { toast(activity, LocaleController.getString(R.string.LeemenRecoveryInvalid)); return; }
                AlertDialog progress = spinner(activity);
                LeemenMaxPrivacy.unwrapWithRecovery(account, phrase, (ok, err) -> {
                    dismiss(progress);
                    if (ok) { dialog.dismiss(); toast(activity, LocaleController.getString(R.string.LeemenUnlocked)); }
                    else toast(activity, LocaleController.getString(R.string.LeemenWrongSecret));
                });
            });
        }
    }

    // --- shared helpers ---

    private static EditTextBoldCursor passField(Context ctx, String hint) {
        EditTextBoldCursor e = new EditTextBoldCursor(ctx);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        e.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        e.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        e.setHint(hint);
        e.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        e.setSingleLine(true);
        return e;
    }

    private static AlertDialog spinner(Activity activity) {
        AlertDialog d = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        d.setCanCancel(false);
        d.show();
        return d;
    }

    private static void dismiss(AlertDialog d) {
        try { if (d != null) d.dismiss(); } catch (Exception ignore) {}
    }

    private static void toast(Activity activity, String s) {
        if (activity != null) Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }

    private static String text(EditTextBoldCursor e) {
        CharSequence c = e.getText();
        return c == null ? "" : c.toString();
    }
}
