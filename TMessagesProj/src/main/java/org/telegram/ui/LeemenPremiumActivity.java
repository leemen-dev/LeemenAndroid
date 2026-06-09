package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;

import java.util.Date;

/**
 * Leemen Premium subscription screen. Leemen's own subscription (unlimited hidden chats) on top,
 * with a link down to the bundled Telegram Premium screen.
 *
 * Billing is local-only for now: tapping Subscribe grants the entitlement via
 * {@link SecondSpaceController#activateLeemenPremiumLocally(int)}.
 * TODO(billing): replace local activation with a Google Play / backend-verified purchase.
 */
public class LeemenPremiumActivity extends BaseFragment {

    private int selectedMonths = 12; // yearly by default (better value)
    private PlanRow monthlyRow;
    private PlanRow yearlyRow;
    private TextView subscribeButton;
    private TextView statusView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.LeemenPremium));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(16));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.large_hidden);
        icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        icon.setBackground(Theme.createCircleDrawable(dp(80), Theme.getColor(Theme.key_featuredStickers_addButton)));
        content.addView(icon, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.LeemenPremium));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 16, 22, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.LeemenPremiumScreenSubtitle));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 6, 22, 8));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTypeface(AndroidUtilities.bold());
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText));
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setVisibility(View.GONE);
        content.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 2, 22, 6));

        LinearLayout plans = new LinearLayout(context);
        plans.setOrientation(LinearLayout.VERTICAL);
        plans.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(plans, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        yearlyRow = new PlanRow(context,
                LocaleController.getString(R.string.LeemenPremiumPlanYearly),
                LocaleController.getString(R.string.LeemenPremiumPriceYearly),
                LocaleController.getString(R.string.LeemenPremiumYearlyBadge),
                true);
        yearlyRow.setOnClickListener(v -> selectPlan(12));
        plans.addView(yearlyRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));

        monthlyRow = new PlanRow(context,
                LocaleController.getString(R.string.LeemenPremiumPlanMonthly),
                LocaleController.getString(R.string.LeemenPremiumPriceMonthly),
                null,
                false);
        monthlyRow.setOnClickListener(v -> selectPlan(1));
        plans.addView(monthlyRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));

        TextView feature = new TextView(context);
        feature.setText(LocaleController.getString(R.string.LeemenPremiumFeatureUnlimited));
        feature.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        feature.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(feature, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 21, 10, 21, 0));

        TextView tgRow = new TextView(context);
        tgRow.setText(LocaleController.getString(R.string.LeemenPremiumTelegramRow));
        tgRow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tgRow.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tgRow.setBackground(Theme.getSelectorDrawable(true));
        tgRow.setGravity(Gravity.CENTER_VERTICAL);
        tgRow.setPadding(dp(21), 0, dp(21), 0);
        Drawable arrow = context.getResources().getDrawable(R.drawable.msg_arrowright).mutate();
        arrow.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.MULTIPLY));
        tgRow.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null);
        tgRow.setOnClickListener(v -> presentFragment(new PremiumPreviewFragment("settings")));
        content.addView(tgRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 0, 24, 0, 0));

        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 72));

        FrameLayout buttonContainer = new FrameLayout(context);
        buttonContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        subscribeButton = new TextView(context);
        subscribeButton.setGravity(Gravity.CENTER);
        subscribeButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        subscribeButton.setTypeface(AndroidUtilities.bold());
        subscribeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subscribeButton.setBackground(Theme.AdaptiveRipple.filledRect(Theme.getColor(Theme.key_featuredStickers_addButton), 8));
        subscribeButton.setOnClickListener(v -> onSubscribeClick());
        buttonContainer.addView(subscribeButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_VERTICAL, 16, 0, 16, 0));
        root.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 72, Gravity.BOTTOM));

        fragmentView = root;
        selectPlan(selectedMonths);
        updateState();
        return root;
    }

    private void selectPlan(int months) {
        selectedMonths = months;
        if (monthlyRow != null) {
            monthlyRow.setChecked(months == 1);
        }
        if (yearlyRow != null) {
            yearlyRow.setChecked(months == 12);
        }
    }

    private void onSubscribeClick() {
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        // TODO(billing): replace with Google Play / backend-verified purchase.
        ssc.activateLeemenPremiumLocally(selectedMonths);
        updateState();
        BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.LeemenPremiumActivated))
                .show();
    }

    private void updateState() {
        if (subscribeButton == null) {
            return;
        }
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        if (ssc.hasLeemenPremium()) {
            String date = android.text.format.DateFormat
                    .getDateFormat(ApplicationLoader.applicationContext)
                    .format(new Date(ssc.getLeemenPremiumUntil()));
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(LocaleController.formatString(R.string.LeemenPremiumActiveUntil, date));
            subscribeButton.setText(LocaleController.getString(R.string.LeemenPremiumRenew));
        } else {
            statusView.setVisibility(View.GONE);
            subscribeButton.setText(LocaleController.getString(R.string.LeemenPremiumSubscribe));
        }
    }

    private class PlanRow extends FrameLayout {
        private final RadioButton radio;

        PlanRow(Context context, CharSequence name, CharSequence price, CharSequence badge, boolean topDivider) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));
            boolean rtl = LocaleController.isRTL;

            radio = new RadioButton(context);
            radio.setSize(dp(20));
            radio.setColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_radioBackgroundChecked));
            addView(radio, LayoutHelper.createFrame(22, 22, Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT), 21, 0, 21, 0));

            TextView nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            CharSequence n = name;
            if (badge != null) {
                n = TextUtils_concat(name, "   ", badge);
            }
            nameView.setText(n);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT), 60, 0, 60, 0));

            TextView priceView = new TextView(context);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            priceView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            priceView.setText(price);
            addView(priceView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT), 21, 0, 21, 0));
        }

        void setChecked(boolean checked) {
            radio.setChecked(checked, true);
        }
    }

    private static CharSequence TextUtils_concat(CharSequence a, CharSequence sep, CharSequence b) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(a).append(sep);
        int start = sb.length();
        sb.append(b);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText)), start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Limit-reached upsell shown when a free user tries to hide more than the allowed chats.
     *  Uses the shared paywall sheet; its Subscribe action opens this screen. */
    public static void showUpgradeDialog(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        PrivateSpacePaywallBottomSheet.show(fragment, () -> fragment.presentFragment(new LeemenPremiumActivity()));
    }

    /** Renew-or-trim prompt shown on entering the space while over the free limit without a sub. */
    public static void showOverLimitDialog(BaseFragment fragment, int account) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivateSpaceOverLimitTitle));
        b.setMessage(LocaleController.getString(R.string.PrivateSpaceOverLimitMessage));
        b.setPositiveButton(LocaleController.getString(R.string.LeemenPremiumRenew), (d, w) -> fragment.presentFragment(new LeemenPremiumActivity()));
        b.setNegativeButton(LocaleController.getString(R.string.PrivateSpaceOverLimitRemove), (d, w) -> fragment.presentFragment(new SecondSpaceSettingsActivity()));
        b.show();
    }
}
