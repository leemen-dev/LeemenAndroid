package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/**
 * "Unlimited hidden chats" upsell, shown once after the user has lived the value loop
 * (hidden their first chat, seen it vanish, and re-entered the space).
 *
 * The primary action closes this dialog window first and only then runs the supplied navigation
 * callback. Presenting a fragment while the dialog is still attached races the tablet/layers
 * navigation layouts and can make a valid tap appear to do nothing.
 */
public class PrivateSpacePaywallBottomSheet extends BottomSheetWithRecyclerListView {

    private static final int ITEM_HORIZONTAL_PADDING = 27;
    private static final int ICON_SIZE = 24;
    private static final int ITEM_TEXT_PADDING = 68;
    private static final int BOTTOM_ACTION_SPACING = 16;
    private static final int ACTION_LEFT_MARGIN = 20;
    private static final int ACTION_RIGHT_MARGIN = 22;

    private final Theme.ResourcesProvider rp;
    private final LinearLayout customView;
    private final Runnable onSubscribe;
    private UniversalAdapter adapter;
    private boolean subscribeRequested;
    private boolean subscribeDispatched;

    @SuppressLint("UseCompatLoadingForDrawables")
    private PrivateSpacePaywallBottomSheet(Context context, BaseFragment fragment, Runnable onSubscribe) {
        super(context, fragment, false, false, false, fragment != null ? fragment.getResourceProvider() : null);
        this.rp = fragment != null ? fragment.getResourceProvider() : null;
        this.onSubscribe = onSubscribe;
        fixNavigationBar();
        // Keep the secondary action comfortably above the navigation safe-area in the initial
        // (not-yet-scrolled) sheet position. Two percent is about 16dp on a typical phone.
        topPadding = .18f;

        LinearLayout linearLayout = customView = new LinearLayout(context);
        // Keep an additional visual gap above the system inset when the sheet is scrolled to the end.
        applyBottomInset(AndroidUtilities.navigationBarHeight);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        // RecyclerListView checks only the holder's direct child before deciding that a gesture belongs to
        // the row. Mark the custom root clickable so nested CTA TextViews keep their click sequence.
        linearLayout.setClickable(true);

        FrameLayout topView = new FrameLayout(context);
        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setImageResource(R.drawable.large_hidden);
        imageView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        imageView.setBackground(Theme.createCircleDrawable(dp(80), Theme.getColor(Theme.key_featuredStickers_addButton, rp)));
        topView.addView(imageView, LayoutHelper.createFrame(80, 80, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 0));
        linearLayout.addView(topView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 100, 0, 0, 0, 0));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.PrivateSpacePaywallTitle));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        linearLayout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 14, 22, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.PrivateSpacePaywallSubtitle));
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setLineSpacing(dp(2), 1f);
        linearLayout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 22, 8, 22, 0));

        FrameLayout f1 = new FeatureCell(context, R.drawable.msg_stories_stealth,
                LocaleController.getString(R.string.PrivateSpacePaywallFeature1Title),
                LocaleController.getString(R.string.PrivateSpacePaywallFeature1Text));
        linearLayout.addView(f1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20, 0, 0));

        FrameLayout fAccount = new FeatureCell(context, R.drawable.msg_limit_accounts,
                LocaleController.getString(R.string.PrivateSpacePaywallFeature2Title),
                LocaleController.getString(R.string.PrivateSpacePaywallFeature2Text));
        linearLayout.addView(fAccount, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16, 0, 0));

        TextView button = new TextView(context);
        button.setLines(1);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, rp));
        button.setTypeface(AndroidUtilities.bold());
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setText(LocaleController.getString(R.string.PrivateSpacePaywallButton));
        button.setBackground(Theme.AdaptiveRipple.filledRect(Theme.getColor(Theme.key_featuredStickers_addButton, rp), 8));
        button.setOnClickListener(e -> {
            if (subscribeRequested || isDismissed()) return;
            subscribeRequested = true;
            dismiss();
        });
        keepClickInsideRecycler(button);
        linearLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0,
                ACTION_LEFT_MARGIN, 22, ACTION_RIGHT_MARGIN, 0));

        TextView later = new TextView(context);
        later.setGravity(Gravity.CENTER);
        later.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton, rp));
        later.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        later.setText(LocaleController.getString(R.string.PrivateSpacePaywallLater));
        later.setBackground(Theme.AdaptiveRipple.filledRect(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, rp), 0f), 8));
        later.setOnClickListener(e -> dismiss());
        keepClickInsideRecycler(later);
        linearLayout.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0,
                ACTION_LEFT_MARGIN, 16, ACTION_RIGHT_MARGIN, 8));

        adapter.update(false);
    }

    @Override
    protected void onInsetsChanged() {
        super.onInsetsChanged();
        applyBottomInset(getSystemBottomInset());
    }

    private void applyBottomInset(int bottomInset) {
        if (customView == null) {
            return;
        }
        customView.setPadding(backgroundPaddingLeft + dp(6), 0, backgroundPaddingLeft + dp(6),
                Math.max(0, bottomInset) + dp(BOTTOM_ACTION_SPACING));
    }

    /**
     * These CTAs live two levels below a RecyclerView holder. Once a press begins on a button, keep the
     * RecyclerView/bottom-sheet drag recognizers from stealing ACTION_UP and cancelling performClick().
     */
    private void keepClickInsideRecycler(View button) {
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                recyclerListView.requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                recyclerListView.requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
    }

    public static PrivateSpacePaywallBottomSheet show(BaseFragment fragment, Runnable onSubscribe) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        PrivateSpacePaywallBottomSheet sheet = new PrivateSpacePaywallBottomSheet(fragment.getParentActivity(), fragment, onSubscribe);
        if (fragment.showDialog(sheet, ignored -> sheet.dispatchSubscribeAfterDismiss()) == null) {
            return null;
        }
        return sheet;
    }

    private void dispatchSubscribeAfterDismiss() {
        if (!subscribeRequested || subscribeDispatched || onSubscribe == null) {
            return;
        }
        subscribeDispatched = true;
        // BaseFragment clears visibleDialog immediately after invoking this listener. Post one UI turn so
        // navigation sees the fully-detached dialog and the final active tablet layer.
        AndroidUtilities.runOnUIThread(onSubscribe);
    }

    private class FeatureCell extends FrameLayout {
        public FeatureCell(Context context, int icon, CharSequence header, CharSequence text) {
            super(context);
            boolean isRtl = LocaleController.isRTL;
            ImageView ivIcon = new ImageView(context);
            Drawable iconDrawable = context.getResources().getDrawable(icon).mutate();
            iconDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton, rp), PorterDuff.Mode.MULTIPLY));
            ivIcon.setImageDrawable(iconDrawable);
            addView(ivIcon, LayoutHelper.createFrame(ICON_SIZE, ICON_SIZE, isRtl ? Gravity.RIGHT : Gravity.LEFT, isRtl ? 0 : ITEM_HORIZONTAL_PADDING, 4, isRtl ? ITEM_HORIZONTAL_PADDING : 0, 0));

            TextView tvTitle = new TextView(context);
            tvTitle.setText(header);
            tvTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tvTitle.setTypeface(AndroidUtilities.bold());
            addView(tvTitle, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, isRtl ? Gravity.RIGHT : Gravity.LEFT, isRtl ? ITEM_HORIZONTAL_PADDING : ITEM_TEXT_PADDING, 0, isRtl ? ITEM_TEXT_PADDING : ITEM_HORIZONTAL_PADDING, 0));

            TextView tvSubtitle = new TextView(context);
            tvSubtitle.setText(text);
            tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tvSubtitle.setTextColor(Theme.getColor(Theme.key_player_actionBarSubtitle, rp));
            tvSubtitle.setLineSpacing(dp(2), 1f);
            addView(tvSubtitle, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, isRtl ? Gravity.RIGHT : Gravity.LEFT, isRtl ? ITEM_HORIZONTAL_PADDING : ITEM_TEXT_PADDING, 20, isRtl ? ITEM_TEXT_PADDING : ITEM_HORIZONTAL_PADDING, 0));
        }
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PrivateSpacePaywallTitle);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return adapter = new UniversalAdapter(listView, getContext(), UserConfig.selectedAccount, 0, true, this::fillItems, rp);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // The default custom item is MATCH_PARENT high. On wide/short devices the secondary CTA can then
        // be drawn past that measured touch boundary: it is visible, but taps reach the sheet background.
        // Keep full sheet width, but measure height from real content so the whole footer is scrollable and
        // hit-testable. asFullyCustom would make the child WRAP_CONTENT in both dimensions.
        items.add(UItem.asCustom(customView, LayoutHelper.WRAP_CONTENT));
    }
}
