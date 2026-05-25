package org.telegram.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.IntDef;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Full-screen PIN entry / setup for Private Space — mirrors the standard system
 * passcode UX (numeric keypad via software IME, large centered input, submit on Done).
 * Replaces the {@code PrivateSpacePinDialog.show*} AlertDialog flows when a more
 * prominent surface is desired.
 */
public class PrivateSpacePasscodeActivity extends BaseFragment {

    public static final int MODE_ENTER = 0;
    public static final int MODE_SET = 1;
    public static final int MODE_REMOVE = 2;

    public static final int TARGET_CURRENT = 0;
    public static final int TARGET_REAL = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MODE_ENTER, MODE_SET, MODE_REMOVE})
    public @interface Mode {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TARGET_CURRENT, TARGET_REAL})
    public @interface Target {}

    private static final int MIN_LEN = 4;
    private static final int MAX_LEN = 6;

    private final int mode;
    private final int target;
    private Runnable onSuccess;

    private TextView titleView;
    private TextView subtitleView;
    private TextView errorView;
    private EditText pinInput;
    private TextView submitButton;

    private String firstStageEntry; // used in MODE_SET — captured after stage 1 to verify stage 2

    public PrivateSpacePasscodeActivity(@Mode int mode) {
        this(mode, TARGET_CURRENT);
    }

    public PrivateSpacePasscodeActivity(@Mode int mode, @Target int target) {
        this.mode = mode;
        this.target = target;
    }

    public PrivateSpacePasscodeActivity setOnSuccess(Runnable r) {
        this.onSuccess = r;
        return this;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarWhiteSelector), false);
        actionBar.setCastShadows(false);
        actionBar.setTitle("");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 72, 0, 0));

        titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setGravity(Gravity.CENTER);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 24, 0, 24, 8));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitleView.setGravity(Gravity.CENTER);
        column.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 32, 0, 32, 40));

        pinInput = new EditText(context);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        pinInput.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        pinInput.setHint(LocaleController.getString(R.string.PrivateSpacePinHint));
        pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_LEN)});
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 32);
        pinInput.setLetterSpacing(0.4f);
        pinInput.setBackgroundDrawable(null);
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pinInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onSubmit();
                return true;
            }
            return false;
        });
        pinInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (errorView.getVisibility() == View.VISIBLE) {
                    errorView.setVisibility(View.INVISIBLE);
                }
                updateSubmitEnabled();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        column.addView(pinInput, LayoutHelper.createLinear(220, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        errorView = new TextView(context);
        errorView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.INVISIBLE);
        column.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 32, 0, 32, 24));

        submitButton = new TextView(context);
        submitButton.setTypeface(AndroidUtilities.bold());
        submitButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        submitButton.setGravity(Gravity.CENTER);
        submitButton.setBackground(Theme.AdaptiveRipple.filledRect(Theme.key_featuredStickers_addButton, 8));
        submitButton.setOnClickListener(v -> onSubmit());
        column.addView(submitButton, LayoutHelper.createLinear(220, 48,
                Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

        applyModeTexts(false);
        updateSubmitEnabled();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pinInput != null) {
            pinInput.postDelayed(() -> {
                pinInput.requestFocus();
                AndroidUtilities.showKeyboard(pinInput);
            }, 100);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (pinInput != null) {
            AndroidUtilities.hideKeyboard(pinInput);
        }
    }

    private void applyModeTexts(boolean secondStage) {
        switch (mode) {
            case MODE_ENTER:
                titleView.setText(LocaleController.getString(R.string.PrivateSpacePinEnterTitle));
                subtitleView.setText(LocaleController.getString(R.string.PrivateSpacePinEnterSubtitle));
                submitButton.setText(LocaleController.getString(R.string.PrivateSpacePinEnter));
                break;
            case MODE_SET:
                if (secondStage) {
                    titleView.setText(LocaleController.getString(R.string.PrivateSpacePinConfirmTitle));
                    subtitleView.setText(LocaleController.getString(R.string.PrivateSpacePinConfirmSubtitle));
                    submitButton.setText(LocaleController.getString(R.string.Save));
                } else {
                    titleView.setText(LocaleController.getString(R.string.PrivateSpacePinSetTitle));
                    subtitleView.setText(LocaleController.getString(R.string.PrivateSpacePinSetSubtitle));
                    submitButton.setText(LocaleController.getString(R.string.Next));
                }
                break;
            case MODE_REMOVE:
                titleView.setText(LocaleController.getString(R.string.PrivateSpacePinRemoveTitle));
                subtitleView.setText(LocaleController.getString(R.string.PrivateSpacePinRemoveSubtitle));
                submitButton.setText(LocaleController.getString(R.string.Remove));
                break;
        }
    }

    private void updateSubmitEnabled() {
        boolean enabled = pinInput != null && pinInput.getText().length() >= MIN_LEN;
        submitButton.setAlpha(enabled ? 1f : 0.5f);
        submitButton.setEnabled(enabled);
    }

    private void onSubmit() {
        String pin = pinInput.getText().toString();
        if (pin.length() < MIN_LEN) {
            showError(LocaleController.formatString("PrivateSpacePinTooShort", R.string.PrivateSpacePinTooShort, MIN_LEN));
            return;
        }
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        switch (mode) {
            case MODE_ENTER: {
                if (!ssc.verifyPassword(pin)) {
                    showError(LocaleController.getString(R.string.PrivateSpacePinWrong));
                    pinInput.setText("");
                    AndroidUtilities.shakeView(pinInput);
                } else {
                    ssc.recordPinVerified();
                    ssc.setActiveMode(SecondSpaceController.MODE_REAL);
                    finishFragmentAndRun();
                }
                break;
            }
            case MODE_SET:
                if (firstStageEntry == null) {
                    firstStageEntry = pin;
                    pinInput.setText("");
                    applyModeTexts(true);
                } else if (firstStageEntry.equals(pin)) {
                    if (target == TARGET_REAL) {
                        ssc.setRealPassword(pin);
                    } else {
                        ssc.setPassword(pin);
                    }
                    finishFragmentAndRun();
                } else {
                    showError(LocaleController.getString(R.string.PrivateSpacePinMismatch));
                    pinInput.setText("");
                    AndroidUtilities.shakeView(pinInput);
                    firstStageEntry = null;
                    applyModeTexts(false);
                }
                break;
            case MODE_REMOVE: {
                boolean ok = ssc.verifyPassword(pin);
                if (ok) {
                    if (target == TARGET_REAL) {
                        ssc.setRealPassword(null);
                    } else {
                        ssc.setPassword(null);
                    }
                    finishFragmentAndRun();
                } else {
                    showError(LocaleController.getString(R.string.PrivateSpacePinWrong));
                    pinInput.setText("");
                    AndroidUtilities.shakeView(pinInput);
                }
                break;
            }
        }
    }

    private void showError(CharSequence message) {
        if (errorView == null) return;
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void finishFragmentAndRun() {
        if (pinInput != null) AndroidUtilities.hideKeyboard(pinInput);
        Runnable r = onSuccess;
        onSuccess = null;
        finishFragment();
        if (r != null) r.run();
    }
}
