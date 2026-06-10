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
    public static final String EP_ACCOUNT_KEY     = "/account/key";
    public static final String EP_WRAP_DEFAULT    = "/account/wrap-default";
    public static final String EP_FILTER          = "/filter";
    public static final String EP_CONTENT         = "/content";
    public static final String EP_DEVICES_REGISTER= "/devices/register";
    public static final String EP_PROMO_REDEEM    = "/promo/redeem";
    public static final String EP_EVENTS          = "/events";
    public static final String EP_ATTRIBUTION     = "/attribution";
    public static final String EP_HEARTBEAT       = "/heartbeat";

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
}
