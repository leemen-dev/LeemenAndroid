package org.telegram.messenger.leemen;

import org.telegram.messenger.BuildVars;

/**
 * Static configuration for the Leemen backend integration (identity, blob sync, analytics).
 *
 * Environment split: debug / beta builds (BuildVars.DEBUG_VERSION == true) talk to the dev backend;
 * release / standalone (public) builds talk to prod. This mirrors how the rest of the app already
 * keys behaviour off DEBUG_VERSION, so betas never touch prod data.
 *
 * Backend contracts: see Notion «Бэкенд» → "Client → Server Handoff" → "Full client integration".
 */
public final class LeemenConfig {

    private LeemenConfig() {}

    /** REST gateway base (no trailing slash). All authed endpoints take Authorization: Bearer <session-jwt>. */
    public static final String BASE_URL = BuildVars.DEBUG_VERSION
            ? "https://api-dev.leemen.app/v1"
            : "https://api.leemen.app/v1";

    // --- REST endpoint paths (appended to BASE_URL) ---
    public static final String EP_AUTH_TELEGRAM   = "/auth/telegram";
    public static final String EP_ME              = "/me";
    public static final String EP_SESSION_STATUS  = "/session/status";
    public static final String EP_ACCOUNT_KEY     = "/account/key";
    public static final String EP_WRAP_DEFAULT    = "/account/wrap-default";
    // max-privacy (Phase 2): client wraps K_master with a dedicated passphrase + BIP-39 recovery phrase
    public static final String EP_UPGRADE_PRIVACY   = "/account/upgrade-privacy";   // default → max
    public static final String EP_DOWNGRADE_PRIVACY = "/account/downgrade-privacy"; // max → default {k_master}
    public static final String EP_WRAP_PW           = "/account/wrap-pw";           // change code (CAS wrap_version)
    public static final String EP_RESET_PRIVATE_SPACE = "/security/reset-private-space"; // both secrets lost {confirm:"RESET"}
    public static final String EP_ACCOUNT_DELETE  = "/account/delete"; // GDPR erasure; body {confirm:"DELETE"}
    public static final String EP_FILTER          = "/filter";
    public static final String EP_CONTENT         = "/content";
    public static final String EP_DEVICES_REGISTER= "/devices/register";
    public static final String EP_PROMO_REDEEM    = "/promo/redeem";
    public static final String EP_ENTITLEMENTS_PLAY = "/entitlements/play"; // verify Play purchase_token (backend Phase 2)
    public static final String EP_EVENTS          = "/events";
    public static final String EP_ATTRIBUTION     = "/attribution";
    public static final String EP_HEARTBEAT       = "/heartbeat";
    public static final String EP_CONSENT         = "/consent"; // consent ledger: {type,granted,version,locale}
    public static final String EP_CLIENT_CONFIG   = "/client-config"; // public telemetry kill-switch policy
    /** Public, rate-limited Google Play reviewer handoff for Telegram's one-time QR login token. */
    public static final String EP_REVIEWER_TELEGRAM_LOGIN = "/reviewer/telegram-login";

    // --- Google Play Billing (Leemen Premium subscription) ---
    // TODO(release): these MUST match the subscription product + base plan IDs created in Play Console.
    /** Single subscription product holding both base plans. */
    public static final String PLAY_PRODUCT_PREMIUM = "leemen_premium";
    /** Base-plan ids within {@link #PLAY_PRODUCT_PREMIUM}. */
    public static final String PLAY_BASE_PLAN_MONTHLY = "monthly";
    public static final String PLAY_BASE_PLAN_YEARLY  = "yearly";

    // --- Identity binding (MTProto WebApp / initData) ---
    /** Bot whose Mini App signs the Telegram user id; we call messages.requestWebView headless. */
    public static final String AUTH_BOT_USERNAME = "leemen_auth_bot";
    public static final String AUTH_WEBAPP_URL   = "https://auth.leemen.app";

    // --- Supabase Realtime (notify-only broadcast; connected DIRECTLY, not via the gateway) ---
    /** Project ref → wss://<ref>.supabase.co/realtime/v1 . Public anon key supplied at Phase 4. */
    public static final String SUPABASE_REF = BuildVars.DEBUG_VERSION
            ? "xprbsvntzmqgkkxysdqb"   // dev
            : "voyoecrgmtdtplazsvih";  // prod
    /** Public anon key — safe to embed (RLS-protected); used only to open the Realtime websocket. */
    public static final String SUPABASE_ANON_KEY = BuildVars.DEBUG_VERSION
            ? "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhwcmJzdm50em1xZ2treHlzZHFiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODA1MTI4NjUsImV4cCI6MjA5NjA4ODg2NX0.cVRRCXwI7N3RrEhld57FdMnvsky9oKOLtMS9bnkgFu0"
            : "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZveW9lY3JnbXRkdHBsYXpzdmloIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzkwMDM1NzQsImV4cCI6MjA5NDU3OTU3NH0.Y_L1FO1EaLUo9T6UNkY_C-2EkqnSg37yLDQ0TplkAHA";
    public static String supabaseRealtimeUrl() {
        return "wss://" + SUPABASE_REF + ".supabase.co/realtime/v1";
    }
    /** Realtime channel for a Telegram account = "sync:" + sync_account_id (treat id as secret). */
    public static String syncChannel(String syncAccountId) {
        return "sync:" + syncAccountId;
    }

    // --- Hosted legal documents (live; linked by app UI locale) ---
    private static final String LEGAL_BASE = "https://leemen.app";

    /** True iff the app UI language is Russian (selects the /ru/ document + ledger locale). */
    public static boolean isRu() {
        try {
            String lang = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().getLangCode();
            return lang != null && lang.startsWith("ru");
        } catch (Throwable e) {
            return false;
        }
    }

    public static String termsUrl()   { return LEGAL_BASE + (isRu() ? "/ru/terms"   : "/terms"); }
    public static String privacyUrl() { return LEGAL_BASE + (isRu() ? "/ru/privacy" : "/privacy"); }
    /** Authenticated self-service account deletion page (Play Data Safety web resource + store in-app link). */
    public static String deleteAccountUrl() { return LEGAL_BASE + (isRu() ? "/ru/delete-account" : "/delete-account"); }
}
