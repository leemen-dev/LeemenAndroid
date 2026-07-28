package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
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
import org.telegram.messenger.BillingController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.messenger.leemen.LeemenAnalytics;
import org.telegram.messenger.leemen.LeemenBilling;
import org.telegram.messenger.leemen.LeemenConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.GLIcon.GLIconRenderer;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.PremiumButtonView;
import org.telegram.ui.Components.Premium.StarParticlesView;
import org.telegram.ui.Components.RadioButton;

import java.util.Date;
import java.util.HashMap;

/**
 * Leemen Premium subscription screen — styled to read like Telegram Premium: a dark starry hero with the
 * Telegram-Premium 3D GLIcon star, an annual/monthly selector with a Play-derived discount badge +
 * struck-through "12× monthly" price, premium feature rows, and a gradient Subscribe button.
 *
 * The screen is always dark (a premium "space" surface) regardless of the app theme. Prices, the discount
 * percentage, and the strike-through amount are pulled from Google Play ({@link LeemenBilling#queryProduct})
 * — nothing is hardcoded; the string resources are only fallbacks until Play responds. Purchase goes through
 * {@link LeemenBilling#launchPurchase}, server-verified and reflected via the FlowListener. Per the
 * one-subscription-across-platforms rule (CONTRACT §7/§10), the Subscribe button + plans are hidden when /me
 * reports an active paid subscription from another platform. Analytics fires paywall_view / paywall_cta_tap /
 * subscribe_flow_started (allowlisted, non-PS).
 */
public class LeemenPremiumActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    // Always-dark premium palette (independent of the app theme, like Telegram's premium hero).
    private static final int BG = 0xFF0E1621;
    private static final int CARD = 0xFF18222E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT2 = 0xFF8A9AAA;
    private static final int DIVIDER = 0x14FFFFFF;

    private int selectedMonths = 12; // yearly by default (better value)
    private PlanRow monthlyRow;
    private PlanRow yearlyRow;
    private PremiumButtonView subscribeButton;
    private GLIconTextureView starIcon;
    private String yearlyPriceText;
    private String monthlyPriceText;
    private TextView statusView;
    /** Non-null when /me shows an active paid subscription on a DIFFERENT platform — the buy button +
     *  plan cards are then hidden (one subscription across platforms, CONTRACT §7/§10). */
    private String otherPlatformSource;
    private LinearLayout plansContainer;
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
        actionBar.setBackgroundColor(BG);
        actionBar.setTitleColor(TEXT);
        actionBar.setItemsColor(TEXT, false);
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(BG);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(16));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // --- dark starry hero ---
        FrameLayout hero = new FrameLayout(context);

        StarParticlesView stars = new StarParticlesView(context);
        stars.setClipWithGradient();
        hero.addView(stars, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout heroContent = new LinearLayout(context);
        heroContent.setOrientation(LinearLayout.VERTICAL);
        heroContent.setGravity(Gravity.CENTER_HORIZONTAL);
        heroContent.setPadding(dp(24), dp(18), dp(24), dp(22));

        // The Telegram-Premium 3D star: same GLIcon renderer + premium gradient, gently idles and tilts to touch.
        hero.setClipChildren(false);
        heroContent.setClipChildren(false);
        starIcon = new GLIconTextureView(context, GLIconRenderer.FRAGMENT_STYLE, Icon3D.TYPE_STAR);
        starIcon.setStarParticlesView(stars);
        heroContent.addView(starIcon, LayoutHelper.createLinear(160, 160, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 6));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.LeemenPremium));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 23);
        title.setTextColor(TEXT);
        title.setGravity(Gravity.CENTER);
        heroContent.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.LeemenPremiumScreenSubtitle));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(TEXT2);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2), 1.0f);
        heroContent.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 8, 8, 8, 0));

        hero.addView(heroContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        content.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- active-until status ---
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTypeface(AndroidUtilities.bold());
        statusView.setTextColor(0xFF4FCB78);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setVisibility(View.GONE);
        content.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 10, 4, 2));

        // --- plan selector (rounded card) ---
        yearlyPriceText = LocaleController.getString(R.string.LeemenPremiumPriceYearly);
        monthlyPriceText = LocaleController.getString(R.string.LeemenPremiumPriceMonthly);
        LinearLayout plans = new LinearLayout(context);
        plans.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable plansBg = new GradientDrawable();
        plansBg.setColor(CARD);
        plansBg.setCornerRadius(dp(14));
        plans.setBackground(plansBg);
        plansContainer = plans;
        content.addView(plans, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));

        yearlyRow = new PlanRow(context,
                LocaleController.getString(R.string.LeemenPremiumPlanYearly),
                LocaleController.getString(R.string.LeemenPremiumPriceYearly),
                LocaleController.getString(R.string.LeemenPremiumYearlyBadge),
                false);
        yearlyRow.setOnClickListener(v -> selectPlan(12));
        plans.addView(yearlyRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER);
        plans.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 0, 0, 0));

        monthlyRow = new PlanRow(context,
                LocaleController.getString(R.string.LeemenPremiumPlanMonthly),
                LocaleController.getString(R.string.LeemenPremiumPriceMonthly),
                null,
                false);
        monthlyRow.setOnClickListener(v -> selectPlan(1));
        plans.addView(monthlyRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        // --- feature list ---
        LinearLayout features = new LinearLayout(context);
        features.setOrientation(LinearLayout.VERTICAL);
        features.setPadding(0, dp(10), 0, dp(6));
        addFeature(features, R.drawable.large_hidden,
                LocaleController.getString(R.string.LeemenPremiumFeatureUnlimited),
                LocaleController.getString(R.string.LeemenPremiumFeat1Desc));
        addFeature(features, R.drawable.msg_limit_accounts,
                LocaleController.getString(R.string.LeemenPremiumFeatAccountTitle),
                LocaleController.getString(R.string.LeemenPremiumFeatAccountDesc));
        content.addView(features, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 76));

        // --- bottom gradient Subscribe button ---
        FrameLayout buttonContainer = new FrameLayout(context);
        buttonContainer.setBackgroundColor(BG);
        subscribeButton = new PremiumButtonView(context, true, getResourceProvider());
        subscribeButton.setButton(LocaleController.getString(R.string.LeemenPremiumSubscribe), v -> onSubscribeClick());
        buttonContainer.addView(subscribeButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_VERTICAL, 16, 0, 16, 0));
        root.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 76, Gravity.BOTTOM));

        fragmentView = root;
        selectPlan(selectedMonths);
        updateState();
        loadPrices();
        // Sync entitlement from Play (cross-device restore + acknowledgement retry); server is truth.
        LeemenBilling.getInstance().restore(currentAccount);
        // onResume performs one authoritative /me reconciliation for both Premium state and the
        // one-subscription-across-platforms purchase gate. Keeping that in one request avoids racing
        // duplicate snapshots when the screen is first presented.

        HashMap<String, String> props = new HashMap<>();
        props.put("placement", placement);
        LeemenAnalytics.track("paywall_view", props);
        return root;
    }

    /** Replace the static fallback prices with Google Play's localized (region-aware) prices, and derive
     *  the yearly discount badge + struck-through "12× monthly" amount from the real Play prices. */
    private void loadPrices() {
        LeemenBilling.getInstance().queryProduct(details -> {
            if (details == null || monthlyRow == null || yearlyRow == null) {
                return; // keep string fallbacks (product not configured / offline)
            }
            String monthly = LeemenBilling.formattedPrice(details, LeemenConfig.PLAY_BASE_PLAN_MONTHLY);
            String yearly = LeemenBilling.formattedPrice(details, LeemenConfig.PLAY_BASE_PLAN_YEARLY);
            if (monthly != null) {
                monthlyRow.setPrice(monthly);
                monthlyPriceText = monthly;
            }
            if (yearly != null) {
                yearlyRow.setPrice(yearly);
                yearlyPriceText = yearly;
            }
            applySubscribeButton();
            // Discount + strike-through from real Play amounts: yearly vs 12× monthly, same currency.
            long monthlyMicros = LeemenBilling.priceMicros(details, LeemenConfig.PLAY_BASE_PLAN_MONTHLY);
            long yearlyMicros = LeemenBilling.priceMicros(details, LeemenConfig.PLAY_BASE_PLAN_YEARLY);
            String currency = LeemenBilling.priceCurrency(details, LeemenConfig.PLAY_BASE_PLAN_YEARLY);
            if (monthlyMicros > 0 && yearlyMicros > 0 && currency != null) {
                long fullYearMicros = monthlyMicros * 12L;
                if (yearlyMicros < fullYearMicros) {
                    int percent = Math.round((1f - (float) yearlyMicros / fullYearMicros) * 100f);
                    yearlyRow.setBadge(LocaleController.formatString(R.string.LeemenPremiumDiscountBadge, percent));
                    yearlyRow.setStrikethrough(
                            BillingController.getInstance().formatCurrency(fullYearMicros, currency, 6));
                }
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
    public void onResume() {
        super.onResume();
        if (starIcon != null) {
            starIcon.setPaused(false);
            starIcon.setDialogVisible(false);
        }
        // Re-read the authoritative server snapshot whenever this existing screen becomes visible
        // again (for example after an operator grant while the app was backgrounded). The helper also
        // applies every active entitlement source to the local Premium cache before invoking us.
        LeemenBilling.checkOtherPlatformSubscription(currentAccount, source -> {
            otherPlatformSource = source;
            updateState();
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        if (starIcon != null) {
            starIcon.setPaused(true);
            starIcon.setDialogVisible(true);
        }
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
        t.setTextColor(TEXT);
        texts.addView(t);
        TextView d = new TextView(context);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        d.setTextColor(TEXT2);
        d.setLineSpacing(dp(1), 1.0f);
        texts.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.msg_arrowright);
        chevron.setScaleType(ImageView.ScaleType.CENTER);
        chevron.setColorFilter(new PorterDuffColorFilter(TEXT2, PorterDuff.Mode.SRC_IN));
        row.addView(chevron, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        container.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void selectPlan(int months) {
        selectedMonths = months;
        if (monthlyRow != null) {
            monthlyRow.setChecked(months == 1);
        }
        if (yearlyRow != null) {
            yearlyRow.setChecked(months == 12);
        }
        applySubscribeButton();
    }

    /** Subscribe button label carries the selected plan's price (Telegram-style). Only shown when NOT
     *  subscribed — an active subscription hides the button entirely (see updateState). */
    private void applySubscribeButton() {
        if (subscribeButton == null) {
            return;
        }
        String price = selectedMonths == 12 ? yearlyPriceText : monthlyPriceText;
        String label = price != null
                ? LocaleController.formatString(R.string.LeemenPremiumSubscribeFor, price)
                : LocaleController.getString(R.string.LeemenPremiumSubscribe);
        subscribeButton.setButton(label, v -> onSubscribeClick());
    }

    private void onSubscribeClick() {
        HashMap<String, String> props = new HashMap<>();
        props.put("plan", selectedMonths == 12 ? "yearly" : "monthly");
        LeemenAnalytics.track("paywall_cta_tap", props);
        LeemenAnalytics.track("subscribe_flow_started");

        if (getParentActivity() == null) {
            return;
        }
        if (otherPlatformSource != null) {
            return; // one active subscription across platforms — don't start a Play purchase here
        }
        String basePlan = selectedMonths == 12
                ? org.telegram.messenger.leemen.LeemenConfig.PLAY_BASE_PLAN_YEARLY
                : org.telegram.messenger.leemen.LeemenConfig.PLAY_BASE_PLAN_MONTHLY;
        // Real Google Play purchase → backend verifies the token → entitlement reflected via the
        // FlowListener / secondSpaceModeChanged. No local grant on the happy path (server is truth).
        org.telegram.messenger.leemen.LeemenBilling.getInstance()
                .launchPurchase(getParentActivity(), currentAccount, basePlan);
    }

    private void updateState() {
        if (subscribeButton == null) {
            return;
        }
        SecondSpaceController ssc = SecondSpaceController.getInstance(currentAccount);
        boolean activeOtherPlatform = otherPlatformSource != null; // one subscription across platforms (CONTRACT §7/§10)
        boolean activeHere = ssc.hasLeemenPremium();
        if (activeOtherPlatform || activeHere) {
            // Already subscribed — show the status, hide the buy button + plan selector (nothing to buy).
            statusView.setVisibility(View.VISIBLE);
            if (activeOtherPlatform) {
                statusView.setText(LocaleController.getString(R.string.LeemenPremiumSubscriptionActive));
            } else {
                String date = android.text.format.DateFormat
                        .getDateFormat(ApplicationLoader.applicationContext)
                        .format(new Date(ssc.getLeemenPremiumUntil()));
                statusView.setText(LocaleController.formatString(R.string.LeemenPremiumActiveUntil, date));
            }
            subscribeButton.setVisibility(View.GONE);
            if (plansContainer != null) {
                plansContainer.setVisibility(View.GONE);
            }
            return;
        }
        // Not subscribed — show the plan selector + Subscribe button.
        statusView.setVisibility(View.GONE);
        subscribeButton.setVisibility(View.VISIBLE);
        if (plansContainer != null) {
            plansContainer.setVisibility(View.VISIBLE);
        }
        applySubscribeButton();
    }

    /** One selectable plan: radio + name (+ optional discount badge) on the left, struck-through full price
     *  over the actual price on the right. */
    private class PlanRow extends LinearLayout {
        private final RadioButton radio;
        private final TextView badgeView;
        private final TextView strikeView;
        private final TextView priceView;

        PlanRow(Context context, CharSequence name, CharSequence price, CharSequence badge, boolean unusedDivider) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(Theme.getSelectorDrawable(false));
            setPadding(dp(16), 0, dp(16), 0);

            radio = new RadioButton(context);
            radio.setSize(dp(20));
            radio.setColor(0xFF55657A, premiumColors()[0]);
            addView(radio, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            TextView nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(TEXT);
            nameView.setText(name);
            addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            badgeView = new TextView(context);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            badgeView.setTypeface(AndroidUtilities.bold());
            badgeView.setTextColor(Color.WHITE);
            badgeView.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(0xFF3AA856);
            badgeBg.setCornerRadius(dp(6));
            badgeView.setBackground(badgeBg);
            badgeView.setVisibility(View.GONE);
            if (badge != null) {
                badgeView.setText(badge);
                badgeView.setVisibility(View.VISIBLE);
            }
            addView(badgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            // spacer pushes the price cluster to the right
            View spacer = new View(context);
            addView(spacer, LayoutHelper.createLinear(0, 1, 1f));

            strikeView = new TextView(context);
            strikeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            strikeView.setTextColor(TEXT2);
            strikeView.getPaint().setStrikeThruText(true);
            strikeView.setVisibility(View.GONE);
            addView(strikeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

            priceView = new TextView(context);
            priceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            priceView.setTextColor(TEXT);
            priceView.setText(price);
            addView(priceView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }

        void setChecked(boolean checked) {
            radio.setChecked(checked, true);
        }

        void setPrice(CharSequence price) {
            priceView.setText(price);
        }

        void setBadge(CharSequence badge) {
            badgeView.setText(badge);
            badgeView.setVisibility(View.VISIBLE);
        }

        void setStrikethrough(CharSequence full) {
            strikeView.setText(full);
            strikeView.setVisibility(View.VISIBLE);
        }
    }

    /** Limit-reached upsell shown when a free user tries to hide more than the allowed chats.
     *  Uses the shared paywall sheet; its Subscribe action opens this screen. */
    public static void showUpgradeDialog(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        PrivateSpacePaywallBottomSheet.show(fragment, () -> fragment.presentFragment(new LeemenPremiumActivity("limit")));
    }

    /** Blocking renew-or-reveal gate shown on entry into the space when the subscription has lapsed while
     *  over the free allowance (extra hidden chats, or any hidden account). The space itself is NOT
     *  revealed — this only gates the real-mode UI. Non-cancelable: the user must either renew or go to
     *  settings to reveal/manage; there is no dismiss-into-an-over-limit space. Revealing is non-destructive
     *  (chats/accounts simply become visible again) and never happens automatically. */
    public static AlertDialog showOverLimitDialog(BaseFragment fragment, int account) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle(LocaleController.getString(R.string.PrivateSpaceOverLimitTitle));
        b.setMessage(LocaleController.getString(R.string.PrivateSpaceOverLimitMessage));
        b.setPositiveButton(LocaleController.getString(R.string.LeemenPremiumRenew), (d, w) -> fragment.presentFragment(new LeemenPremiumActivity("limit")));
        b.setNegativeButton(LocaleController.getString(R.string.PrivateSpaceOverLimitRemove), (d, w) -> fragment.presentFragment(new SecondSpaceSettingsActivity()));
        AlertDialog dialog = b.create();
        if (fragment.showDialog(dialog) == null) {
            return null;
        }
        // BaseFragment.showDialog() enables outside-touch cancellation before showing every dialog.
        // Apply the subscription gate's stricter policy afterwards so Back, an outside tap and a swipe
        // cannot dismiss it. The only exits are the explicit renewal and settings actions above.
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }
}
