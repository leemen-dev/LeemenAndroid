package org.telegram.messenger.leemen;

import android.app.Activity;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Google Play Billing for Leemen Premium (a single SUBS product with monthly/yearly base plans).
 *
 * Flow: {@link #queryProduct} → show localized price → {@link #launchPurchase} → Play →
 * {@link #onPurchasesUpdated} → verify the purchase token on OUR backend
 * (POST /v1/entitlements/play) → backend grants the entitlement → we mirror expires_at into
 * {@link SecondSpaceController#setLeemenPremiumUntil(long)} and acknowledge the purchase.
 *
 * The SERVER is the source of truth: a real purchase never grants premium client-side, only after
 * the backend verifies the token with the Google Play Developer API. (DEBUG builds keep a local
 * fallback for when the endpoint isn't deployed yet, so the dev UI can be exercised.)
 *
 * TWO EXTERNAL DEPENDENCIES before this works in production:
 *   1. Play Console — subscription {@link LeemenConfig#PLAY_PRODUCT_PREMIUM} with base plans
 *      {@link LeemenConfig#PLAY_BASE_PLAN_MONTHLY}/{@code _YEARLY}, and an internal-testing build.
 *   2. Backend — POST /v1/entitlements/play {purchase_token, product_id} (Бэкенд Phase 2).
 *
 * BillingClient is app-global; entitlements are granted to whichever Leemen account launched the
 * purchase (its session JWT carries identity to the backend), defaulting to the selected account.
 */
public final class LeemenBilling implements PurchasesUpdatedListener, BillingClientStateListener {

    private static volatile LeemenBilling instance;

    public static LeemenBilling getInstance() {
        if (instance == null) {
            synchronized (LeemenBilling.class) {
                if (instance == null) {
                    instance = new LeemenBilling();
                }
            }
        }
        return instance;
    }

    private LeemenBilling() {}

    /** Notified about the live purchase flow so the paywall can show a bulletin. */
    public interface FlowListener {
        void onPurchaseResult(boolean ok, @Nullable String reason);
    }

    /** Receives the resolved subscription product details (null on failure). */
    public interface ProductCallback {
        void onResult(@Nullable ProductDetails details);
    }

    // All mutable state below is confined to the MAIN thread: BillingClient delivers every callback on
    // the main thread, LeemenRestClient delivers on the UI thread, and launchPurchase is called from UI.
    // No locks are needed as long as that confinement holds.
    private BillingClient client;
    private boolean connected;
    private final List<Pending> whenReady = new ArrayList<>();
    private ProductDetails cachedProduct;

    // Identity of the in-flight LIVE purchase, set on launch; onPurchasesUpdated fires asynchronously.
    private int pendingAccount = -1;
    private int pendingMonths = 1;
    private FlowListener flowListener;

    private static final class Pending {
        final Runnable onReady;
        final Runnable onFail;
        Pending(Runnable r, Runnable f) { onReady = r; onFail = f; }
    }

    public void setFlowListener(@Nullable FlowListener l) {
        this.flowListener = l;
    }

    /** Clear the listener only if it is still the one {@code l} installed (avoids a destroyed fragment
     *  clearing a newer paywall's listener). */
    public void removeFlowListener(@Nullable FlowListener l) {
        if (this.flowListener == l) {
            this.flowListener = null;
        }
    }

    // ---- connection ----------------------------------------------------------------------------

    private void ensureConnected(Runnable onReady) {
        ensureConnected(onReady, null);
    }

    private void ensureConnected(Runnable onReady, @Nullable Runnable onFail) {
        if (connected && client != null && client.isReady()) {
            onReady.run();
            return;
        }
        whenReady.add(new Pending(onReady, onFail));
        if (client == null) {
            client = BillingClient.newBuilder(ApplicationLoader.applicationContext)
                    .setListener(this)
                    .enablePendingPurchases()
                    .build();
        }
        if (!connected) {
            try {
                client.startConnection(this);
            } catch (Throwable e) {
                FileLog.e(e);
                drainReady(false);
            }
        }
    }

    /** Run every queued callback's onReady (ok) or onFail (so a tap that can't connect still gets feedback
     *  instead of silently disappearing). */
    private void drainReady(boolean ok) {
        List<Pending> copy = new ArrayList<>(whenReady);
        whenReady.clear();
        for (Pending p : copy) {
            Runnable r = ok ? p.onReady : p.onFail;
            if (r != null) {
                try { r.run(); } catch (Throwable e) { FileLog.e(e); }
            }
        }
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult result) {
        connected = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Leemen: billing setup code=" + result.getResponseCode());
        }
        drainReady(connected);
    }

    @Override
    public void onBillingServiceDisconnected() {
        connected = false;
    }

    // ---- products / price ----------------------------------------------------------------------

    /** Fetch the subscription product details (for localized prices). Runs cb on the main thread;
     *  delivers null on connect failure / when the product isn't configured yet. */
    public void queryProduct(final ProductCallback cb) {
        if (cachedProduct != null) {
            cb.onResult(cachedProduct);
            return;
        }
        ensureConnected(() -> {
            QueryProductDetailsParams.Product product = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(LeemenConfig.PLAY_PRODUCT_PREMIUM)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build();
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                    .setProductList(Collections.singletonList(product))
                    .build();
            client.queryProductDetailsAsync(params, (billingResult, list) -> {
                ProductDetails found = null;
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
                    for (ProductDetails pd : list) {
                        if (LeemenConfig.PLAY_PRODUCT_PREMIUM.equals(pd.getProductId())) {
                            found = pd;
                            break;
                        }
                    }
                }
                if (found != null) {
                    cachedProduct = found;
                } else if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: queryProduct code=" + billingResult.getResponseCode()
                            + " (product not configured in Play Console yet?)");
                }
                final ProductDetails result = found;
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> cb.onResult(result));
            });
        }, () -> org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> cb.onResult(null)));
    }

    /** Localized formatted price for a base plan ("monthly"/"yearly"), or null if unavailable. */
    @Nullable
    public static String formattedPrice(@Nullable ProductDetails details, String basePlanId) {
        ProductDetails.PricingPhase phase = recurringPhase(details, basePlanId);
        return phase == null ? null : phase.getFormattedPrice();
    }

    /** Recurring (non-intro) price in micros for a base plan, or 0 if unavailable.
     *  Lets the paywall compute the yearly discount + struck-through "12× monthly" price from real
     *  Play data (region-aware) instead of hardcoding any amount. */
    public static long priceMicros(@Nullable ProductDetails details, String basePlanId) {
        ProductDetails.PricingPhase phase = recurringPhase(details, basePlanId);
        return phase == null ? 0 : phase.getPriceAmountMicros();
    }

    /** ISO currency code for a base plan's recurring price, or null if unavailable. */
    @Nullable
    public static String priceCurrency(@Nullable ProductDetails details, String basePlanId) {
        ProductDetails.PricingPhase phase = recurringPhase(details, basePlanId);
        return phase == null ? null : phase.getPriceCurrencyCode();
    }

    /** The recurring (last, non-intro) pricing phase of a base plan, or null. */
    @Nullable
    private static ProductDetails.PricingPhase recurringPhase(@Nullable ProductDetails details, String basePlanId) {
        ProductDetails.SubscriptionOfferDetails offer = offerFor(details, basePlanId);
        if (offer == null) {
            return null;
        }
        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
        for (int i = phases.size() - 1; i >= 0; i--) {
            if (!TextUtils.isEmpty(phases.get(i).getFormattedPrice())) {
                return phases.get(i);
            }
        }
        return null;
    }

    @Nullable
    private static ProductDetails.SubscriptionOfferDetails offerFor(@Nullable ProductDetails details, String basePlanId) {
        if (details == null || details.getSubscriptionOfferDetails() == null) {
            return null;
        }
        for (ProductDetails.SubscriptionOfferDetails o : details.getSubscriptionOfferDetails()) {
            if (basePlanId.equals(o.getBasePlanId())) {
                return o;
            }
        }
        return null;
    }

    // ---- purchase ------------------------------------------------------------------------------

    /** Launch the Play purchase sheet for the given base plan. Returns false if it can't start
     *  (product not yet available) — caller should keep the local fallback / show an error. */
    public boolean launchPurchase(final Activity activity, final int account, final String basePlanId) {
        if (activity == null) {
            return false;
        }
        pendingAccount = account;
        pendingMonths = LeemenConfig.PLAY_BASE_PLAN_YEARLY.equals(basePlanId) ? 12 : 1;
        ensureConnected(() -> queryProduct(details -> {
            ProductDetails.SubscriptionOfferDetails offer = offerFor(details, basePlanId);
            if (details == null || offer == null) {
                notifyFlow(false, "product_unavailable");
                return;
            }
            BillingFlowParams.ProductDetailsParams pdp = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .setOfferToken(offer.getOfferToken())
                    .build();
            BillingFlowParams.Builder flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(pdp));
            String obf = obfuscatedAccountId(account);
            if (obf != null) {
                flow.setObfuscatedAccountId(obf);
            }
            BillingResult r = client.launchBillingFlow(activity, flow.build());
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: launchBillingFlow code=" + r.getResponseCode());
                }
                notifyFlow(false, "launch_failed");
            }
        }), () -> notifyFlow(false, "billing_unavailable"));
        return true;
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result, @Nullable List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            notifyFlow(false, "canceled");
            return;
        }
        if (code != BillingClient.BillingResponseCode.OK || purchases == null) {
            notifyFlow(false, "billing_error");
            return;
        }
        int account = pendingAccount >= 0 ? pendingAccount : UserConfig.selectedAccount;
        for (Purchase purchase : purchases) {
            handlePurchase(account, purchase, pendingMonths, true);
        }
    }

    /** Re-verify any active subscription purchases for {@code account} (cross-device restore +
     *  acknowledgement retry). Safe to call on app start / paywall open; backend verify is idempotent. */
    public void restore(final int account) {
        ensureConnected(() -> client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (billingResult, list) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK || list == null) {
                        return;
                    }
                    for (Purchase purchase : list) {
                        handlePurchase(account, purchase, 12, false);
                    }
                }));
    }

    private void handlePurchase(final int account, final Purchase purchase, final int fallbackMonths, final boolean live) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            // PENDING (e.g. cash/voucher) — wait for a later update; never grant here.
            if (live) notifyFlow(false, "pending");
            return;
        }
        verifyOnBackend(account, purchase, fallbackMonths, live, (granted, expiresMs) -> {
            // Server is the source of truth: grant ONLY for a confirmed, still-valid future expiry.
            if (!granted || expiresMs <= System.currentTimeMillis()) {
                if (live) notifyFlow(false, "verify_failed");
                return;
            }
            final Runnable grant = () -> {
                SecondSpaceController.getInstance(account).setLeemenPremiumUntil(expiresMs);
                if (live) notifyFlow(true, null);
            };
            if (purchase.isAcknowledged()) {
                grant.run(); // already past Google's refund window — safe to grant
            } else {
                // New purchase: acknowledge FIRST. Only mirror the entitlement once the ack lands, so a
                // failed ack (→ Google auto-refunds in 3 days) never leaves the user with free premium.
                // If ack fails, restore() re-attempts ack+grant on the next app start.
                acknowledge(purchase, ackOk -> {
                    if (ackOk) {
                        grant.run();
                    } else if (live) {
                        notifyFlow(false, "ack_failed");
                    }
                });
            }
        });
    }

    /** Result of {@link #checkOtherPlatformSubscription}: the {@code source} of an active paid
     *  subscription on a platform OTHER than Google Play, or {@code null} if there is none. */
    public interface OtherPlatformCallback {
        void onResult(@Nullable String otherPlatformSource);
    }

    /** One-subscription-across-platforms gate (CONTRACT §7/§10): inspect {@code /me.entitlements} for an
     *  active paid subscription whose {@code source} is a DIFFERENT billing platform — anything other than
     *  Google Play (this platform) or a {@code promo}/{@code manual} grant (not a store sub). The caller must NOT start a
     *  Play purchase while one exists. Callback on the UI thread; {@code null} = clear to purchase. Fails
     *  OPEN (null) on any error — the server additionally rejects a redundant Play purchase. */
    public static void checkOtherPlatformSubscription(final int account, final OtherPlatformCallback cb) {
        final String token = LeemenAccount.getToken(account);
        if (token == null) {
            cb.onResult(null);
            return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ME, token, (resp, httpCode, ec, em) -> {
            String src = null;
            try {
                if (resp != null && httpCode >= 200 && httpCode < 300
                        && resp.has("entitlements") && resp.get("entitlements").isJsonArray()) {
                    com.google.gson.JsonArray arr = resp.getAsJsonArray("entitlements");
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isJsonObject()) {
                            continue;
                        }
                        JsonObject e = arr.get(i).getAsJsonObject();
                        String source = e.has("source") && !e.get("source").isJsonNull()
                                ? e.get("source").getAsString() : null;
                        if (source == null || "play".equals(source) || "promo".equals(source) || "manual".equals(source)) {
                            continue; // this platform, or a non-store grant (promo / operator "manual" comp, CONTRACT §7) — not another billing platform
                        }
                        boolean active;
                        if (!e.has("expires_at") || e.get("expires_at").isJsonNull()) {
                            active = true; // perpetual entitlement
                        } else {
                            active = parseExpiryMs(e.get("expires_at").getAsString()) > now;
                        }
                        if (active) {
                            src = source;
                            break;
                        }
                    }
                }
            } catch (Throwable ignore) {
                src = null;
            }
            cb.onResult(src);
        });
    }

    /** Sentinel "premium until" for a perpetual entitlement (server returns expires_at = null, e.g. a
     *  lifetime promo). A FIXED far-future epoch (2100-01-01) keeps {@link SecondSpaceController#
     *  setLeemenPremiumUntil} stable across reconciles (no notification churn) and still formats as a
     *  sane date in the paywall. */
    private static final long PERPETUAL_PREMIUM_UNTIL = 4102444800000L;

    /** Reconcile EVERY bound, activated account's local premium with the server (see {@link
     *  #reconcileEntitlements}). Call on app start — it is the only path that observes a server-side
     *  revocation (ON_HOLD/EXPIRED/REVOKED delete the entitlement row; nothing is pushed via Realtime). */
    public static void reconcileAllEntitlements() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated() && LeemenAccount.hasBinding(a)) {
                    reconcileEntitlements(a);
                }
            } catch (Throwable ignore) {}
        }
    }

    /** Reconcile local premium with the server's authoritative entitlement set (CONTRACT §7): GET /me and
     *  set {@code leemenPremiumUntil} to the latest active expiry across ALL sources (play/tribute/promo/…),
     *  or {@code 0} when no active entitlement remains. This is the ONLY path that LOWERS local premium — it
     *  is how the client learns of a server-side revocation (a deleted row), which is never pushed over
     *  Realtime. Applies ONLY on a well-formed 200 carrying an {@code entitlements} array; any error or
     *  malformed response leaves the cached value untouched, so a transient/offline failure never locks out
     *  a paid user. The REST callback already runs on the UI thread (LeemenRestClient). */
    public static void reconcileEntitlements(final int account) {
        final String token = LeemenAccount.getToken(account);
        if (token == null || LeemenAccount.isDisabled(account)) {
            return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ME, token, (resp, httpCode, ec, em) -> {
            if (resp == null || httpCode < 200 || httpCode >= 300
                    || !resp.has("entitlements") || !resp.get("entitlements").isJsonArray()) {
                return; // not a trustworthy answer — keep the cache (offline-safe)
            }
            long until = 0L;
            try {
                com.google.gson.JsonArray arr = resp.getAsJsonArray("entitlements");
                long now = System.currentTimeMillis();
                for (int i = 0; i < arr.size(); i++) {
                    if (!arr.get(i).isJsonObject()) {
                        continue;
                    }
                    JsonObject e = arr.get(i).getAsJsonObject();
                    long exp = !e.has("expires_at") || e.get("expires_at").isJsonNull()
                            ? PERPETUAL_PREMIUM_UNTIL                          // null = perpetual entitlement
                            : parseExpiryMs(e.get("expires_at").getAsString());
                    if (exp > now && exp > until) {
                        until = exp;
                    }
                }
            } catch (Throwable ignore) {
                return; // blew up mid-read — don't act on a half-parsed set
            }
            SecondSpaceController.getInstance(account).setLeemenPremiumUntil(until);
        });
    }

    /** POST the purchase token to the backend for server-side verification + entitlement grant.
     *  result(granted, expiresMs) — expiresMs is the backend-confirmed expiry (epoch ms). A DEBUG build
     *  may, for a LIVE purchase only, fall back to a local grant when the endpoint isn't deployed yet
     *  (404 / network) so dev QA can exercise the UI; this NEVER happens in release nor during restore. */
    private void verifyOnBackend(final int account, final Purchase purchase, final int fallbackMonths,
                                 final boolean live, final VerifyResult result) {
        final String token = LeemenAccount.getToken(account);
        if (token == null) {
            result.onResult(false, 0);
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("purchase_token", purchase.getPurchaseToken());
        body.addProperty("product_id", LeemenConfig.PLAY_PRODUCT_PREMIUM);
        LeemenRestClient.post(LeemenConfig.EP_ENTITLEMENTS_PLAY, token, body, (respBody, httpCode, errCode, errMsg) -> {
            boolean ok = respBody != null && httpCode == 200
                    && respBody.has("ok") && respBody.get("ok").getAsBoolean();
            if (ok) {
                long expires = respBody.has("expires_at") && !respBody.get("expires_at").isJsonNull()
                        ? parseExpiryMs(respBody.get("expires_at").getAsString()) : 0;
                // grant iff the server returned a valid future expiry (handlePurchase re-checks too)
                result.onResult(expires > System.currentTimeMillis(), expires);
                return;
            }
            boolean notDeployed = httpCode == 404 || httpCode == -1;
            if (BuildVars.DEBUG_VERSION && live && notDeployed) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: /entitlements/play not deployed (code=" + httpCode + ") — DEBUG local grant");
                }
                long exp = System.currentTimeMillis() + (long) fallbackMonths * 31L * 24L * 60L * 60L * 1000L;
                result.onResult(true, exp);
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: /entitlements/play verify failed code=" + httpCode + " err=" + errCode);
                }
                result.onResult(false, 0);
            }
        });
    }

    private interface VerifyResult {
        void onResult(boolean granted, long expiresMs);
    }

    private interface AckResult {
        void onAck(boolean ok);
    }

    private void acknowledge(final Purchase purchase, final AckResult cb) {
        if (purchase.isAcknowledged()) {
            cb.onAck(true);
            return;
        }
        if (client == null) {
            cb.onAck(false);
            return;
        }
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();
        client.acknowledgePurchase(params, billingResult -> {
            boolean ok = billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: acknowledge code=" + billingResult.getResponseCode());
            }
            cb.onAck(ok);
        });
    }

    private void notifyFlow(boolean ok, @Nullable String reason) {
        final FlowListener l = flowListener;
        if (l == null) {
            return;
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> l.onPurchaseResult(ok, reason));
    }

    /** Play's obfuscatedAccountId — MUST be the raw master_account_id UUID exactly as /v1/auth/telegram
     *  returned it (== JWT `sub`). The backend reads externalAccountIdentifiers.obfuscatedExternalAccountId
     *  back from subscriptionsv2.get and compares it to `sub` by exact string equality (no case-fold/trim/
     *  dash-strip); any other value → 409 purchase_account_mismatch and the grant never lands (contract §4).
     *  So forward the stored UUID verbatim — do NOT hash it, do NOT derive it from the Telegram uid. Null
     *  when the account isn't bound yet (no purchase should start in that state). */
    @Nullable
    private static String obfuscatedAccountId(int account) {
        String masterId = LeemenAccount.getMasterAccountId(account);
        return TextUtils.isEmpty(masterId) ? null : masterId;
    }

    /** Parse a backend expires_at (epoch ms / epoch s / ISO-8601) → epoch ms, or 0 if unknown. */
    public static long parseExpiryMs(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            if (s.matches("\\d+")) {
                long v = Long.parseLong(s);
                return v > 1_000_000_000_000L ? v : v * 1000L;
            }
            return java.time.Instant.parse(s).toEpochMilli();
        } catch (Throwable e) {
            return 0;
        }
    }
}
