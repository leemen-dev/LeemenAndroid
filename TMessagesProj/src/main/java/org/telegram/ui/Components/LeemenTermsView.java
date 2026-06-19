package org.telegram.ui.Components;

import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.leemen.LeemenConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Leemen onboarding gate: accept the Terms of Service + Privacy Policy before using the app.
 *
 * Full-screen blocking overlay (mirrors {@link TermsOfServiceView}), added by LaunchActivity over the
 * drawer container so it does not touch the fragment stack. The KZ→EU cross-border DISCLOSURE paragraph is
 * shown (not opt-in'd) when the backend flags the account as a Kazakhstan signup (me.kz_consent_required).
 * Acceptance is recorded backend + local by the caller (LeemenConsent.grant); decline logs the user out.
 */
public class LeemenTermsView extends FrameLayout {

    public interface Delegate {
        void onAccept(int account, boolean kzShown);
        void onDecline(int account);
    }

    private Delegate delegate;
    private int currentAccount;
    private boolean kzShown;

    private final TextView kzTextView;

    public LeemenTermsView(final Context context) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        // Eat touches so nothing behind the gate is interactable.
        setClickable(true);
        setFocusable(true);

        final int top = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? AndroidUtilities.statusBarHeight : 0;
        if (top > 0) {
            View statusBar = new View(context);
            statusBar.setBackgroundColor(0xff000000);
            addView(statusBar, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, top));
        }

        final LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        ImageView imageView = new ImageView(context);
        imageView.setImageResource(R.drawable.logo_middle);
        linearLayout.addView(imageView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 28, 0, 0));

        TextView titleTextView = new TextView(context);
        titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setText(LocaleController.getString(R.string.LeemenTermsTitle));
        linearLayout.addView(titleTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 20, 0, 0));

        TextView bodyTextView = new TextView(context);
        bodyTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bodyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bodyTextView.setGravity(Gravity.LEFT | Gravity.TOP);
        bodyTextView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        bodyTextView.setText(LocaleController.getString(R.string.LeemenTermsBody));
        linearLayout.addView(bodyTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 15, 0, 0));

        // KZ→EU cross-border disclosure paragraph (shown only for KZ-signup accounts; not an opt-in).
        kzTextView = new TextView(context);
        kzTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        kzTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        kzTextView.setGravity(Gravity.LEFT | Gravity.TOP);
        kzTextView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        kzTextView.setText(LocaleController.getString(R.string.LeemenTermsKzDisclosure));
        kzTextView.setVisibility(GONE);
        linearLayout.addView(kzTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 15, 0, 0));

        TextView termsLink = makeLink(context, LocaleController.getString(R.string.LeemenTermsLinkTerms));
        termsLink.setOnClickListener(v -> Browser.openUrl(getContext(), LeemenConfig.termsUrl()));
        linearLayout.addView(termsLink, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 18, 0, 0));

        TextView privacyLink = makeLink(context, LocaleController.getString(R.string.LeemenTermsLinkPrivacy));
        privacyLink.setOnClickListener(v -> Browser.openUrl(getContext(), LeemenConfig.privacyUrl()));
        linearLayout.addView(privacyLink, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 10, 0, 0));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        scrollView.setPadding(AndroidUtilities.dp(24f), top, AndroidUtilities.dp(24f), AndroidUtilities.dp(75f));
        scrollView.addView(linearLayout, new LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView declineTextView = new TextView(context);
        declineTextView.setText(LocaleController.getString(R.string.Decline).toUpperCase());
        declineTextView.setGravity(Gravity.CENTER);
        declineTextView.setTypeface(AndroidUtilities.bold());
        declineTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        declineTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        declineTextView.setBackground(Theme.getRoundRectSelectorDrawable(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)));
        declineTextView.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        addView(declineTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 16, 16));
        declineTextView.setOnClickListener(view -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle(LocaleController.getString(R.string.LeemenTermsTitle));
            builder.setMessage(LocaleController.getString(R.string.LeemenTermsDeclineConfirm));
            builder.setPositiveButton(LocaleController.getString(R.string.LogOut), (dialog, which) -> {
                if (delegate != null) delegate.onDecline(currentAccount);
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.show();
        });

        TextView acceptTextView = new TextView(context);
        acceptTextView.setText(LocaleController.getString(R.string.Accept));
        acceptTextView.setGravity(Gravity.CENTER);
        acceptTextView.setTypeface(AndroidUtilities.bold());
        acceptTextView.setTextColor(0xffffffff);
        acceptTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        acceptTextView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(4),
                Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        acceptTextView.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
        addView(acceptTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 42, Gravity.RIGHT | Gravity.BOTTOM, 16, 0, 16, 16));
        acceptTextView.setOnClickListener(view -> {
            if (delegate != null) delegate.onAccept(currentAccount, kzShown);
        });

        final View lineView = new View(context);
        lineView.setBackgroundColor(Theme.getColor(Theme.key_divider));
        final LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.bottomMargin = AndroidUtilities.dp(75f);
        params.gravity = Gravity.BOTTOM;
        addView(lineView, params);
    }

    private TextView makeLink(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setGravity(Gravity.LEFT);
        return tv;
    }

    public void show(int account, boolean kz) {
        currentAccount = account;
        kzShown = kz;
        kzTextView.setVisibility(kz ? VISIBLE : GONE);
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }
}
