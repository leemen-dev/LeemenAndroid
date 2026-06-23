package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.messenger.leemen.LeemenAnalytics;
import org.telegram.messenger.leemen.LeemenBilling;
import org.telegram.messenger.leemen.LeemenConfig;
import org.telegram.messenger.leemen.LeemenPromo;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;

import java.util.Date;
import java.util.HashMap;

/**
 * Leemen Premium subscription screen — Telegram-Premium-style: premium-gradient hero, feature list,
 * plan cards, a gradient Subscribe button, and a "Redeem promo code" row.
 *
 * Billing is local-only for now: Subscribe grants the entitlement via
 * {@link SecondSpaceController#activateLeemenPremiumLocally(int)}; a redeemed promo grants it via the
 * backend ({@link LeemenPromo}) and reflects the returned expiry. Analytics fires paywall_view /
 * paywall_cta_tap / subscribe_flow_started (allowlisted, non-PS).
 * TODO(billing): replace local activation with a Google Play / backend-verified purchase.
 */
public class LeemenPremiumActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private int selectedMonths = 12; // yearly by default (better value)
    private PlanRow monthlyRow;
    private PlanRow yearlyRow;
    private TextView subscribeButton;
    private TextView statusView;
    private final String placement;

    public LeemenPremiumActivity() {
        this("direct");
    }

    /** @param placement analytics placement tag (e.g. "settings", "limit", "second_space"). */
    public LeemenPremiumActivity(String placement) {
        this.placement = placement == null ? "direct" : placement;
    }

    private static int[] premiumColors() {
        return new int[]{
                Theme.getColor(Theme.key_premiumGradient1),
                Theme.getColor(Theme.key_premiumGradient2),
                Theme.getColor(Theme.key_premiumGradient3),
                Theme.getColor(Theme.key_premiumGradient4),
        };
    }

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

        // --- gradient hero ---
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, premiumColors());
        hero.setBackground(heroBg);
        hero.setPadding(dp(22), dp(28), dp(22), dp(28));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.large_hidden);
        icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        hero.addView(icon, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 8));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.LeemenPremium));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.LeemenPremiumScreenSubtitle));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(0xCCFFFFFF);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 12, 6, 12, 0));

        content.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- active-until status ---
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTypeface(AndroidUtilities.bold());
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText));
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setVisibility(View.GONE);
        content.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 10, 22, 2));

        // --- feature list (placeholder copy — refine per product) ---
        LinearLayout features = new LinearLayout(context);
        features.setOrientation(LinearLayout.VERTICAL);
        features.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        features.setPadding(0, dp(6), 0, dp(6));
        addFeature(features, R.drawable.large_hidden,
                LocaleController.getString(R.string.LeemenPremiumFeatureUnlimited),
                LocaleController.getString(R.string.LeemenPremiumFeat1Desc));
        addFeature(features, R.drawable.menu_devices,
                LocaleController.getString(R.string.LeemenPremiumFeat2Title),
                LocaleController.getString(R.string.LeemenPremiumFeat2Desc));
        addFeature(features, R.drawable.msg_premium_liststar,
                LocaleController.getString(R.string.LeemenPremiumFeat3Title),
                LocaleController.getString(R.string.LeemenPremiumFeat3Desc));
        content.addView(features, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        // --- plan cards ---
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

        // --- redeem promo code ---
        TextView promoRow = linkRow(context, LocaleController.getString(R.string.LeemenPromoRow));
        promoRow.setOnClickListener(v -> showPromoDialog());
        content.addView(promoRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 12, 0, 0));

        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 72));

        // --- bottom gradient Subscribe button ---
        FrameLayout buttonContainer = new FrameLayout(context);
        buttonContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        subscribeButton = new TextView(context);
        subscribeButton.setGravity(Gravity.CENTER);
        subscribeButton.setTextColor(Color.WHITE);
        subscribeButton.setTypeface(AndroidUtilities.bold());
        subscribeButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        GradientDrawable btnBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, premiumColors());
        btnBg.setCornerRadius(dp(8));
        subscribeButton.setBackground(btnBg);
        subscribeButton.setOnClickListener(v -> onSubscribeClick());
        buttonContainer.addView(subscribeButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_VERTICAL, 16, 0, 16, 0));
        root.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 72, Gravity.BOTTOM));

        fragmentView = root;
        selectPlan(selectedMonths);
        updateState();
        loadPrices();
        // Sync entitlement from Play (cross-device restore + acknowledgement retry); server is truth.
        LeemenBilling.getInstance().restore(currentAccount);

        HashMap<String, String> props = new HashMap<>();
        props.put("placement", placement);
        LeemenAnalytics.track("paywall_view", props);
        return root;
    }

    /** Replace the static fallback prices with Google Play's localized (region-aware) prices. */
    private void loadPrices() {
        LeemenBilling.getInstance().queryProduct(details -> {
            if (details == null || monthlyRow == null || yearlyRow == null) {
                return; // keep string fallbacks (product not configured / offline)
            }
            String monthly = LeemenBilling.formattedPrice(details, LeemenConfig.PLAY_BASE_PLAN_MONTHLY);
            String yearly = LeemenBilling.formattedPrice(details, LeemenConfig.PLAY_BASE_PLAN_YEARLY);
            if (monthly != null) {
                monthlyRow.setPrice(monthly);
            }
            if (yearly != null) {
                yearlyRow.setPrice(yearly);
            }
        });
    }

    private LeemenBilling.FlowListener billingFlowListener;

    @Override
    public boolean onFragmentCreate() {
        boolean created = super.onFragmentCreate();
        getNotificationCenter().addObserver(this, NotificationCenter.secondSpaceModeChanged);
        billingFlowListener = (ok, reason) -> {
            if (fragmentView == null || getParentActivity() == null) {
                return;
            }
            if (ok) {
                updateState();
                BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.LeemenPremiumActivated))
                        .show();
            } else if (!"canceled".equals(reason)) {
                BulletinFactory.of(this)
                        .createErrorBulletin(LocaleController.getString(R.string.LeemenPremiumPurchaseError))
                        .show();
            }
        };
        LeemenBilling.getInstance().setFlowListener(billingFlowListener);
        return created;
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.secondSpaceModeChanged);
        LeemenBilling.getInstance().removeFlowListener(billingFlowListener);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.secondSpaceModeChanged) {
            updateState();
        }
    }

    private void addFeature(LinearLayout container, int iconRes, CharSequence title, CharSequence desc) {
        Context context = container.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(21), dp(9), dp(21), dp(9));

        ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setImageResource(iconRes);
        iv.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        GradientDrawable sq = new GradientDrawable(GradientDrawable.Orientation.TL_BR, premiumColors());
        sq.setCornerRadius(dp(10));
        iv.setBackground(sq);
        iv.setPadding(dp(7), dp(7), dp(7), dp(7));
        row.addView(iv, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        texts.addView(t);
        TextView d = new TextView(context);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        d.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        texts.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private TextView linkRow(Context context, CharSequence text) {
        TextView row = new TextView(context);
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.setBackground(Theme.getSelectorDrawable(true));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(21), 0, dp(21), 0);
        Drawable arrow = context.getResources().getDrawable(R.drawable.msg_arrowright).mutate();
        arrow.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.MULTIPLY));
        row.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null);
        return row;
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
        HashMap<String, String> props = new HashMap<>();
        props.put("plan", selectedMonths == 12 ? "yearly" : "monthly");
        LeemenAnalytics.track("paywall_cta_tap", props);
        LeemenAnalytics.track("subscribe_flow_started");

        if (getParentActivity() == null) {
            return;
        }
        String basePlan = selectedMonths == 12
                ? org.telegram.messenger.leemen.LeemenConfig.PLAY_BASE_PLAN_YEARLY
                : org.telegram.messenger.leemen.LeemenConfig.PLAY_BASE_PLAN_MONTHLY;
        // Real Google Play purchase → backend verifies the token → entitlement reflected via the
        // FlowListener / secondSpaceModeChanged. No local grant on the happy path (server is truth).
        org.telegram.messenger.leemen.LeemenBilling.getInstance()
                .launchPurchase(getParentActivity(), currentAccount, basePlan);
    }

    private void showPromoDialog() {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        EditText input = new EditText(context);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setText("");
        input.setHint(LocaleController.getString(R.string.LeemenPromoHint));
        input.setSingleLine(true);
        FrameLayout container = new FrameLayout(context);
        container.setPadding(dp(22), dp(4), dp(22), 0);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(LocaleController.getString(R.string.LeemenPromoTitle));
        b.setView(container);
        b.setPositiveButton(LocaleController.getString(R.string.LeemenPromoButton), (d, w) -> {
            String code = input.getText().toString().trim();
            if (!code.isEmpty()) {
                redeemPromo(code);
            }
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void redeemPromo(String code) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);
        LeemenPromo.redeem(currentAccount, code, (ok, entId, kind, expiresAt, reason) -> {
            try { progress.dismiss(); } catch (Exception ignore) {}
            if (getParentActivity() == null || fragmentView == null) {
                return; // fragment left while the request was in flight
            }
            if (ok) {
                long until = LeemenBilling.parseExpiryMs(expiresAt);
                SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
                if (until > 0) {
                    ssc.setLeemenPremiumUntil(until);
                } else {
                    ssc.activateLeemenPremiumLocally(1);
                }
                updateState();
                BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.LeemenPromoSuccess))
                        .show();
            } else {
                BulletinFactory.of(this)
                        .createErrorBulletin(LocaleController.getString(R.string.LeemenPromoError))
                        .show();
            }
        });
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
        private final TextView priceView;

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

            priceView = new TextView(context);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            priceView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            priceView.setText(price);
            addView(priceView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT), 21, 0, 21, 0));
        }

        void setChecked(boolean checked) {
            radio.setChecked(checked, true);
        }

        void setPrice(CharSequence price) {
            priceView.setText(price);
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
        PrivateSpacePaywallBottomSheet.show(fragment, () -> fragment.presentFragment(new LeemenPremiumActivity("limit")));
    }

    /** Renew-or-trim prompt shown on entering the space while over the free limit without a sub. */
    public static void showOverLimitDialog(BaseFragment fragment, int account) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivateSpaceOverLimitTitle));
        b.setMessage(LocaleController.getString(R.string.PrivateSpaceOverLimitMessage));
        b.setPositiveButton(LocaleController.getString(R.string.LeemenPremiumRenew), (d, w) -> fragment.presentFragment(new LeemenPremiumActivity("limit")));
        b.setNegativeButton(LocaleController.getString(R.string.PrivateSpaceOverLimitRemove), (d, w) -> fragment.presentFragment(new SecondSpaceSettingsActivity()));
        fragment.showDialog(b.create());
    }
}
