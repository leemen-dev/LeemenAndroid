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
import com.android.billingclient.api.PendingPurchasesParams;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Google Play Billing for Leemen Premium (a single SUBS product with monthly/yearly base plans).
 *
 * Flow: {@link #queryProduct} → show localized price → {@link #launchPurchase} → Play →
 * {@link #onPurchasesUpdated} → verify the purchase token on OUR backend
 * (POST /v1/entitlements/play) → backend grants the entitlement → acknowledge the purchase →
 * refresh the trusted {@code GET /me} snapshot.
 *
 * The SERVER is the source of truth: a real purchase never grants premium client-side, only after
 * the backend verifies the token with the Google Play Developer API.
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
    private boolean connecting;
    private final List<Pending> whenReady = new ArrayList<>();
    private ProductDetails cachedProduct;
    private boolean restoreQueryInFlight;
    private final Set<Integer> pendingRestoreAccounts = new HashSet<>();
    private final Set<String> processingPurchaseTokens = new HashSet<>();

    // Identity of the in-flight LIVE purchase, set on launch; onPurchasesUpdated fires asynchronously.
    private int pendingAccount = -1;
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
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .build();
        }
        if (!connected && !connecting) {
            connecting = true;
            try {
                client.startConnection(this);
            } catch (Throwable e) {
                connecting = false;
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
        connecting = false;
        connected = result.getResponseCode() == BillingClient.BillingResponseCode.OK;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Leemen: billing setup code=" + result.getResponseCode());
        }
        drainReady(connected);
    }

    @Override
    public void onBillingServiceDisconnected() {
        connected = false;
        connecting = false;
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
            client.queryProductDetailsAsync(params, (billingResult, queryResult) -> {
                List<ProductDetails> list = queryResult.getProductDetailsList();
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

    /** Launch the Play purchase sheet for the given base plan. Returns false when the account is not
     *  currently bound; asynchronous Play/connect failures are delivered through the flow listener. */
    public boolean launchPurchase(final Activity activity, final int account, final String basePlanId) {
        final String expectedToken = LeemenAccount.getToken(account);
        final String expectedMasterId = obfuscatedAccountId(account);
        if (activity == null || TextUtils.isEmpty(expectedToken) || TextUtils.isEmpty(expectedMasterId)
                || !isCurrentIdentity(account, expectedToken, expectedMasterId)) {
            return false;
        }
        ensureConnected(() -> queryProduct(details -> {
            ProductDetails.SubscriptionOfferDetails offer = offerFor(details, basePlanId);
            if (details == null || offer == null) {
                notifyFlow(false, "product_unavailable");
                return;
            }
            if (!isCurrentIdentity(account, expectedToken, expectedMasterId)) {
                notifyFlow(false, "account_changed");
                return;
            }
            BillingFlowParams.ProductDetailsParams pdp = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .setOfferToken(offer.getOfferToken())
                    .build();
            BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(pdp))
                    .setObfuscatedAccountId(expectedMasterId)
                    .build();
            pendingAccount = account;
            final BillingResult r;
            try {
                r = client.launchBillingFlow(activity, flow);
            } catch (Throwable e) {
                pendingAccount = -1;
                FileLog.e(e);
                notifyFlow(false, "launch_failed");
                return;
            }
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                pendingAccount = -1;
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
        final int account = pendingAccount;
        pendingAccount = -1;
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            notifyFlow(false, "canceled");
            return;
        }
        if (code != BillingClient.BillingResponseCode.OK || purchases == null) {
            notifyFlow(false, "billing_error");
            return;
        }
        boolean matched = false;
        for (Purchase purchase : purchases) {
            if (isLeemenPremiumPurchase(purchase) && purchaseBelongsToAccount(purchase, account)) {
                matched = true;
                handlePurchase(account, purchase, true);
            }
        }
        if (!matched) {
            notifyFlow(false, "purchase_account_mismatch");
        }
    }

    /** Re-verify any active subscription purchases for {@code account} (cross-device restore +
     *  acknowledgement retry). Safe to call on app start / paywall open; backend verify is idempotent. */
    public void restore(final int account) {
        if (!isRestorableAccount(account)) {
            return;
        }
        pendingRestoreAccounts.add(account);
        ensureConnected(this::runRestoreQuery);
    }

    /** Restore purchases once for all local Leemen identities. Play returns a user-wide list, so every
     *  purchase is routed only by its exact obfuscatedAccountId instead of being replayed into each slot. */
    public void restoreAllBoundAccounts() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (isRestorableAccount(account)) {
                pendingRestoreAccounts.add(account);
            }
        }
        if (!pendingRestoreAccounts.isEmpty()) {
            ensureConnected(this::runRestoreQuery);
        }
    }

    private void runRestoreQuery() {
        if (restoreQueryInFlight || pendingRestoreAccounts.isEmpty() || client == null || !client.isReady()) {
            return;
        }
        final Set<Integer> requestedAccounts = new HashSet<>(pendingRestoreAccounts);
        pendingRestoreAccounts.clear();
        restoreQueryInFlight = true;
        try {
            client.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                    (billingResult, list) -> {
                        restoreQueryInFlight = false;
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && list != null) {
                            for (Purchase purchase : list) {
                                int account = restoredPurchaseAccount(purchase, requestedAccounts);
                                if (account >= 0) {
                                    handlePurchase(account, purchase, false);
                                }
                            }
                        } else if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("Leemen: restore query code=" + billingResult.getResponseCode());
                        }
                        if (!pendingRestoreAccounts.isEmpty()) {
                            runRestoreQuery();
                        }
                    });
        } catch (Throwable e) {
            restoreQueryInFlight = false;
            FileLog.e(e);
        }
    }

    private void handlePurchase(final int account, final Purchase purchase, final boolean live) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            // PENDING (e.g. cash/voucher) — wait for a later update; never grant here.
            if (live) notifyFlow(false, "pending");
            return;
        }
        if (!isLeemenPremiumPurchase(purchase) || !purchaseBelongsToAccount(purchase, account)) {
            if (live) notifyFlow(false, "purchase_account_mismatch");
            return;
        }
        final String purchaseToken = purchase.getPurchaseToken();
        final String expectedToken = LeemenAccount.getToken(account);
        final String expectedMasterId = LeemenAccount.getMasterAccountId(account);
        if (TextUtils.isEmpty(purchaseToken) || TextUtils.isEmpty(expectedToken)
                || TextUtils.isEmpty(expectedMasterId)
                || !isCurrentIdentity(account, expectedToken, expectedMasterId)) {
            if (live) notifyFlow(false, "account_changed");
            return;
        }
        if (!processingPurchaseTokens.add(purchaseToken)) {
            return;
        }
        verifyOnBackend(expectedToken, purchase, (granted, reason) -> {
            if (!isCurrentIdentity(account, expectedToken, expectedMasterId)) {
                finishPurchaseProcessing(purchaseToken);
                if (live) notifyFlow(false, "account_changed");
                return;
            }
            if (!granted) {
                finishPurchaseProcessing(purchaseToken);
                if (live) notifyFlow(false, reason);
                return;
            }
            final Runnable complete = () -> {
                if (!isCurrentIdentity(account, expectedToken, expectedMasterId)) {
                    finishPurchaseProcessing(purchaseToken);
                    if (live) notifyFlow(false, "account_changed");
                    return;
                }
                finishPurchaseProcessing(purchaseToken);
                // Pull server_now + the full entitlement set. Never derive access from the device clock
                // or from a single store response. If another /me is already running, force a follow-up
                // because it may have read the database before this grant.
                LeemenAccountState.onRemoteChanged(account);
                if (live) notifyFlow(true, null);
            };
            if (purchase.isAcknowledged()) {
                complete.run();
            } else {
                acknowledge(purchase, ackOk -> {
                    if (ackOk) {
                        complete.run();
                    } else {
                        finishPurchaseProcessing(purchaseToken);
                        if (live) notifyFlow(false, "ack_failed");
                    }
                });
            }
        });
    }

    /** Result of {@link #checkOtherPlatformSubscription}: the {@code source} of an active paid
     *  subscription on a platform OTHER than Google Play, or {@code null} if there is none. */
    public interface OtherPlatformCallback {
        void onResult(@Nullable String otherPlatformSource, boolean authoritative);
    }

    /** One-subscription-across-platforms gate (CONTRACT §7/§10): inspect {@code /me.entitlements} for an
     *  active paid subscription whose {@code source} is a DIFFERENT billing platform — anything other than
     *  Google Play (this platform) or a {@code promo}/{@code manual} grant (not a store sub). The caller must NOT start a
     *  Play purchase while one exists. Callback on the UI thread. Errors are reported as
     *  {@code authoritative=false}, and the caller must fail closed until /me is confirmed. */
    public static void checkOtherPlatformSubscription(final int account, final OtherPlatformCallback cb) {
        final String token = LeemenAccount.getToken(account);
        final String masterId = LeemenAccount.getMasterAccountId(account);
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(masterId)
                || !isCurrentIdentity(account, token, masterId)) {
            cb.onResult(null, false);
            return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ME, token, (resp, httpCode, ec, em) -> {
            if (!isCurrentIdentity(account, token, masterId)) {
                cb.onResult(null, false);
                return;
            }
            String src = null;
            boolean authoritative = false;
            try {
                if (resp != null && httpCode >= 200 && httpCode < 300
                        && resp.has("server_now") && !resp.get("server_now").isJsonNull()
                        && resp.has("entitlements") && resp.get("entitlements").isJsonArray()) {
                    long serverNowMs = parseExpiryMs(resp.get("server_now").getAsString());
                    if (serverNowMs <= 0L) {
                        cb.onResult(null, false);
                        return;
                    }
                    com.google.gson.JsonArray arr = resp.getAsJsonArray("entitlements");
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
                            long expiresMs = parseExpiryMs(e.get("expires_at").getAsString());
                            if (expiresMs <= 0L) {
                                cb.onResult(null, false);
                                return;
                            }
                            active = expiresMs > serverNowMs;
                        }
                        if (active) {
                            src = source;
                            break;
                        }
                    }
                    authoritative = true;
                }
            } catch (Throwable ignore) {
                src = null;
                authoritative = false;
            }
            cb.onResult(src, authoritative);
        });
    }

    /** Sentinel "premium until" for a perpetual entitlement (server returns expires_at = null, e.g. a
     *  lifetime promo). A FIXED far-future epoch (2100-01-01) keeps {@link SecondSpaceController#
     *  setLeemenPremiumSnapshot} stable across reconciles (no notification churn) and still formats as a
     *  sane date in the paywall. */
    private static final long PERPETUAL_PREMIUM_UNTIL = 4102444800000L;

    /** Reconcile every bound, activated account's local premium with the server. Realtime now wakes the same
     *  GET /me path; this startup call remains the missed-event/offline recovery backstop. */
    public static void reconcileAllEntitlements() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated() && LeemenAccount.hasBinding(a)) {
                    reconcileEntitlements(a);
                }
            } catch (Throwable ignore) {}
        }
    }

    /** Reconcile local premium and privacy mode from the server's authoritative GET /me snapshot. */
    public static void reconcileEntitlements(final int account) {
        LeemenAccountState.refresh(account);
    }

    /**
     * Apply only a fully-formed entitlement array from GET /me. Package-visible so
     * {@link LeemenAccountState} can reconcile Premium and privacy mode in one network round-trip.
     */
    static boolean applyEntitlementsFromMe(final int account, JsonObject response, long serverNowMs) {
        if (response == null || !response.has("entitlements") || !response.get("entitlements").isJsonArray()) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: entitlement snapshot ignored account " + account + " missing array");
            }
            return false; // untrustworthy/partial answer → keep the offline cache
        }
        long until = 0L;
        int count = 0;
        try {
            com.google.gson.JsonArray arr = response.getAsJsonArray("entitlements");
            count = arr.size();
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) continue;
                JsonObject e = arr.get(i).getAsJsonObject();
                String kind = e.has("kind") && !e.get("kind").isJsonNull()
                        ? e.get("kind").getAsString() : null;
                if (!"premium".equals(kind)) continue;
                long exp = !e.has("expires_at") || e.get("expires_at").isJsonNull()
                        ? PERPETUAL_PREMIUM_UNTIL
                        : parseExpiryMs(e.get("expires_at").getAsString());
                if (exp <= 0L) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: entitlement snapshot rejected invalid expiry account " + account);
                    }
                    return false;
                }
                if (exp > serverNowMs && exp > until) until = exp;
            }
        } catch (Throwable ignore) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: entitlement snapshot parse failed account " + account);
            }
            return false;
        }
        SecondSpaceController.getInstance(account).setLeemenPremiumSnapshot(until, serverNowMs);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Leemen: entitlement snapshot applied account " + account
                    + " count=" + count + " premium=" + (until > serverNowMs));
        }
        return true;
    }

    /** POST the purchase token to the backend for server-side verification + entitlement grant.
     *  Access is never applied from this response; after acknowledgement we refresh {@code GET /me},
     *  whose {@code server_now} anchors the trusted monotonic Premium deadline. */
    private void verifyOnBackend(final String expectedToken, final Purchase purchase, final VerifyResult result) {
        JsonObject body = new JsonObject();
        body.addProperty("purchase_token", purchase.getPurchaseToken());
        body.addProperty("product_id", LeemenConfig.PLAY_PRODUCT_PREMIUM);
        LeemenRestClient.post(LeemenConfig.EP_ENTITLEMENTS_PLAY, expectedToken, body,
                (respBody, httpCode, errCode, errMsg) -> {
            boolean ok = respBody != null && httpCode == 200
                    && respBody.has("ok") && respBody.get("ok").getAsBoolean()
                    && respBody.has("granted") && respBody.get("granted").getAsBoolean();
            if (ok) {
                result.onResult(true, null);
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: /entitlements/play verify failed code=" + httpCode + " err=" + errCode);
            }
            result.onResult(false, "paid_platform_conflict".equals(errCode)
                    ? "paid_platform_conflict" : "verify_failed");
        });
    }

    private interface VerifyResult {
        void onResult(boolean granted, @Nullable String reason);
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
        try {
            client.acknowledgePurchase(params, billingResult -> {
                boolean ok = billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: acknowledge code=" + billingResult.getResponseCode());
                }
                cb.onAck(ok);
            });
        } catch (Throwable e) {
            FileLog.e(e);
            cb.onAck(false);
        }
    }

    private void notifyFlow(boolean ok, @Nullable String reason) {
        final FlowListener l = flowListener;
        if (l == null) {
            return;
        }
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> l.onPurchaseResult(ok, reason));
    }

    private void finishPurchaseProcessing(String purchaseToken) {
        processingPurchaseTokens.remove(purchaseToken);
    }

    private static boolean isLeemenPremiumPurchase(@Nullable Purchase purchase) {
        return purchase != null && purchase.getProducts() != null
                && purchase.getProducts().contains(LeemenConfig.PLAY_PRODUCT_PREMIUM);
    }

    @Nullable
    private static String purchaseObfuscatedAccountId(@Nullable Purchase purchase) {
        try {
            return purchase == null || purchase.getAccountIdentifiers() == null
                    ? null : purchase.getAccountIdentifiers().getObfuscatedAccountId();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean purchaseBelongsToAccount(@Nullable Purchase purchase, int account) {
        String purchaseAccountId = purchaseObfuscatedAccountId(purchase);
        String masterAccountId = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                ? LeemenAccount.getMasterAccountId(account) : null;
        return !TextUtils.isEmpty(purchaseAccountId) && purchaseAccountId.equals(masterAccountId)
                && isRestorableAccount(account);
    }

    /** Route a restored Play purchase to exactly one requested local account. Duplicate local bindings
     *  are rejected too: silently choosing a slot could apply a callback after that slot was reused. */
    private static int restoredPurchaseAccount(@Nullable Purchase purchase, Set<Integer> requestedAccounts) {
        if (!isLeemenPremiumPurchase(purchase)) {
            return -1;
        }
        String purchaseAccountId = purchaseObfuscatedAccountId(purchase);
        if (TextUtils.isEmpty(purchaseAccountId)) {
            return -1;
        }
        int match = -1;
        for (Integer candidate : requestedAccounts) {
            if (candidate == null || !isRestorableAccount(candidate)) {
                continue;
            }
            if (purchaseAccountId.equals(LeemenAccount.getMasterAccountId(candidate))) {
                if (match >= 0) {
                    return -1;
                }
                match = candidate;
            }
        }
        return match;
    }

    private static boolean isRestorableAccount(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        try {
            return UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)
                    && LeemenAccount.hasBinding(account)
                    && !TextUtils.isEmpty(LeemenAccount.getMasterAccountId(account));
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean isCurrentIdentity(int account, @Nullable String expectedToken,
                                             @Nullable String expectedMasterId) {
        return isRestorableAccount(account)
                && !TextUtils.isEmpty(expectedToken)
                && !TextUtils.isEmpty(expectedMasterId)
                && expectedToken.equals(LeemenAccount.getToken(account))
                && expectedMasterId.equals(LeemenAccount.getMasterAccountId(account));
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

    /** Parse the backend's canonical UTC ISO-8601 expires_at → epoch ms, or 0 if invalid. */
    public static long parseExpiryMs(@Nullable String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (RuntimeException ignore) {
            return 0;
        }
    }
}
