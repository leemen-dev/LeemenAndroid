package org.telegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/** PIN-based authentication for Private Space entry / password management. */
public final class PrivateSpacePinDialog {

    private static final int MIN_LEN = 4;
    private static final int MAX_LEN = 6;

    private PrivateSpacePinDialog() {}

    /** Prompt for current PIN to enter Private Space. Calls onSuccess if verified. */
    public static void showEnter(Context context, int currentAccount, Runnable onSuccess) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (!ssc.hasPassword()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        if (ssc.isPinPromptSkippable()) {
            // Recent successful PIN entry is still within the user-configured timeout window.
            if (onSuccess != null) onSuccess.run();
            return;
        }
        AlertDialog.Builder builder = baseBuilder(context, R.string.PrivateSpacePinEnterTitle);
        EditText field = newPinField(context);
        TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        error.setVisibility(TextView.GONE);
        LinearLayout container = wrap(context, field, error);
        builder.setView(container);

        AlertDialog[] alertRef = new AlertDialog[1];
        Runnable submit = () -> {
            String pin = field.getText().toString();
            if (ssc.verifyPassword(pin)) {
                ssc.recordPinVerified();
                if (alertRef[0] != null) alertRef[0].dismiss();
                if (onSuccess != null) onSuccess.run();
            } else {
                error.setText(LocaleController.getString(R.string.PrivateSpacePinWrong));
                error.setVisibility(TextView.VISIBLE);
                field.setText("");
                AndroidUtilities.shakeView(field);
            }
        };
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString(R.string.PrivateSpacePinEnter), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = builder.create();
        alertRef[0] = alert;
        alert.show();
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
        focus(field);
    }

    /** Prompt to set a new PIN (two-field confirmation). */
    public static void showSet(Context context, int currentAccount, Runnable onSuccess) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        AlertDialog.Builder builder = baseBuilder(context, R.string.PrivateSpacePinSetTitle);
        EditText field1 = newPinField(context);
        EditText field2 = newPinField(context);
        field2.setImeOptions(EditorInfo.IME_ACTION_DONE);
        TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        error.setVisibility(TextView.GONE);
        LinearLayout container = wrap(context, field1, field2, error);
        builder.setView(container);

        AlertDialog[] alertRef = new AlertDialog[1];
        Runnable submit = () -> {
            String pin1 = field1.getText().toString();
            String pin2 = field2.getText().toString();
            if (pin1.length() < MIN_LEN) {
                error.setText(LocaleController.formatString("PrivateSpacePinTooShort", R.string.PrivateSpacePinTooShort, MIN_LEN));
                error.setVisibility(TextView.VISIBLE);
                AndroidUtilities.shakeView(field1);
                return;
            }
            if (!pin1.equals(pin2)) {
                error.setText(LocaleController.getString(R.string.PrivateSpacePinMismatch));
                error.setVisibility(TextView.VISIBLE);
                AndroidUtilities.shakeView(field2);
                return;
            }
            ssc.setPassword(pin1);
            if (alertRef[0] != null) alertRef[0].dismiss();
            if (onSuccess != null) onSuccess.run();
        };
        field2.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = builder.create();
        alertRef[0] = alert;
        alert.show();
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
        focus(field1);
    }

    /**
     * Prompt for the account-entry password (per-account gate guarding switch INTO this account).
     * If the target account has no entry password, runs onSuccess immediately. If user cancels,
     * onCancel runs (may be null).
     */
    public static void showEnterAccountPassword(Context context, int targetAccount, Runnable onSuccess, Runnable onCancel) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(targetAccount);
        if (!ssc.hasEntryPassword()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        AlertDialog.Builder builder = baseBuilder(context, R.string.HiddenAccountEnterPasswordTitle);
        EditText field = newPinField(context);
        TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        error.setVisibility(TextView.GONE);
        LinearLayout container = wrap(context, field, error);
        builder.setView(container);

        AlertDialog[] alertRef = new AlertDialog[1];
        boolean[] succeeded = {false};
        Runnable submit = () -> {
            String pin = field.getText().toString();
            if (ssc.verifyEntryPassword(pin)) {
                succeeded[0] = true;
                if (alertRef[0] != null) alertRef[0].dismiss();
                if (onSuccess != null) onSuccess.run();
            } else {
                error.setText(LocaleController.getString(R.string.HiddenAccountWrongPassword));
                error.setVisibility(TextView.VISIBLE);
                field.setText("");
                AndroidUtilities.shakeView(field);
            }
        };
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString(R.string.PrivateSpacePinEnter), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setOnDismissListener(d -> {
            if (!succeeded[0] && onCancel != null) onCancel.run();
        });
        AlertDialog alert = builder.create();
        alertRef[0] = alert;
        alert.show();
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
        focus(field);
    }

    /** Prompt to set/change the account entry password (two-field confirmation). */
    public static void showSetAccountPassword(Context context, int targetAccount, Runnable onSuccess) {
        SecondSpaceController ssc = SecondSpaceController.getInstance(targetAccount);
        AlertDialog.Builder builder = baseBuilder(context, R.string.HiddenAccountEntryPasswordTitle);
        EditText field1 = newPinField(context);
        EditText field2 = newPinField(context);
        field2.setImeOptions(EditorInfo.IME_ACTION_DONE);
        TextView error = new TextView(context);
        error.setTextSize(13);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        error.setVisibility(TextView.GONE);
        LinearLayout container = wrap(context, field1, field2, error);
        builder.setView(container);

        AlertDialog[] alertRef = new AlertDialog[1];
        Runnable submit = () -> {
            String pin1 = field1.getText().toString();
            String pin2 = field2.getText().toString();
            if (pin1.length() < MIN_LEN) {
                error.setText(LocaleController.formatString("PrivateSpacePinTooShort", R.string.PrivateSpacePinTooShort, MIN_LEN));
                error.setVisibility(TextView.VISIBLE);
                AndroidUtilities.shakeView(field1);
                return;
            }
            if (!pin1.equals(pin2)) {
                error.setText(LocaleController.getString(R.string.PrivateSpacePinMismatch));
                error.setVisibility(TextView.VISIBLE);
                AndroidUtilities.shakeView(field2);
                return;
            }
            ssc.setEntryPassword(pin1);
            if (alertRef[0] != null) alertRef[0].dismiss();
            if (onSuccess != null) onSuccess.run();
        };
        field2.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString(R.string.Save), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alert = builder.create();
        alertRef[0] = alert;
        alert.show();
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
        focus(field1);
    }

    /** Prompt to remove the current PIN — requires entering the current PIN. */
    public static void showRemove(Context context, int currentAccount, Runnable onSuccess) {
        showEnter(context, currentAccount, () -> {
            SecondSpaceController.getInstance(currentAccount).setPassword(null);
            if (onSuccess != null) onSuccess.run();
        });
    }

    // --- helpers ---

    private static AlertDialog.Builder baseBuilder(Context context, int titleRes) {
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(LocaleController.getString(titleRes));
        return b;
    }

    private static EditText newPinField(Context context) {
        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.PrivateSpacePinHint));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_LEN)});
        field.setGravity(Gravity.CENTER);
        field.setTextSize(20);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        return field;
    }

    private static LinearLayout wrap(Context context, android.view.View... children) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = AndroidUtilities.dp(20);
        layout.setPadding(p, AndroidUtilities.dp(8), p, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AndroidUtilities.dp(8);
        for (android.view.View v : children) {
            if (v.getParent() != null) ((LinearLayout) v.getParent()).removeView(v);
            layout.addView(v, lp);
        }
        return layout;
    }

    private static void focus(EditText field) {
        field.postDelayed(() -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }, 100);
    }
}
