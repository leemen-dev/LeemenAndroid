package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Shown to a NEW user (phone number not yet registered) instead of in-fork registration: Leemen logs in
 * with an existing Telegram account, so we send them to the official Telegram app to register their
 * number first (cleaner anti-spam posture + deniability — a normal Telegram account, not one created via
 * a fork). They return and log in here. Reached from LoginActivity.setPage(VIEW_REGISTER); analytics
 * new_account_redirect_view fires there.
 */
public class NewAccountRedirectActivity extends BaseFragment {

    private static final String OFFICIAL_TELEGRAM_PKG = "org.telegram.messenger";

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NewAccountRedirectTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(24), dp(28), dp(24));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.large_hidden);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton), PorterDuff.Mode.SRC_IN));
        content.addView(icon, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NewAccountRedirectTitle));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        TextView text = new TextView(context);
        text.setText(LocaleController.getString(R.string.NewAccountRedirectText));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        text.setGravity(Gravity.CENTER);
        content.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 24));

        TextView button = new TextView(context);
        button.setText(LocaleController.getString(R.string.NewAccountRedirectButton));
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setTypeface(AndroidUtilities.bold());
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        button.setOnClickListener(v -> openOfficialTelegram(getParentActivity()));
        content.addView(button, LayoutHelper.createLinear(220, 48, Gravity.CENTER_HORIZONTAL));

        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        fragmentView = root;
        return root;
    }

    static void openOfficialTelegram(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(OFFICIAL_TELEGRAM_PKG);
            if (launch != null) {
                ctx.startActivity(launch);
                return;
            }
        } catch (Throwable ignore) {}
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + OFFICIAL_TELEGRAM_PKG)));
        } catch (Throwable e) {
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + OFFICIAL_TELEGRAM_PKG)));
            } catch (Throwable e2) {
                try {
                    android.widget.Toast.makeText(ctx, LocaleController.getString(R.string.NoHandleAppInstalled), android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {}
            }
        }
    }
}
