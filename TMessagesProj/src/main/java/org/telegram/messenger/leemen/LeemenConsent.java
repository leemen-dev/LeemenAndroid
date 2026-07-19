package org.telegram.messenger.leemen;

import com.google.gson.JsonObject;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Onboarding Terms/Privacy acceptance gate + consent ledger client.
 *
 * This is a Terms/Policy ACCEPTANCE gate (not a "data transfer opt-in"): every user accepts the Terms +
 * Privacy Policy once; declining logs them out (the core product needs the EU backend). The KZ→EU
 * cross-border transfer is DISCLOSED inside that screen — shown as an extra paragraph when the backend
 * flags the account as a Kazakhstan signup ({@code me.account.kz_consent_required}, decided server-side by
 * signup IP) — and recorded as a separate ledger entry on accept, but it is never a blocking opt-in.
 *
 * Acceptance is recorded BACKEND + LOCAL:
 *  - backend: POST /v1/consent {type, granted, version, locale} (server timestamps it; we never send a
 *    client clock). type ∈ terms | analytics | attribution | kz_cross_border.
 *  - local: a per-account mirror in {@link LeemenAccount} prefs, so repeat launches need no /me round-trip.
 *
 * The reprompt decision is SERVER-AUTHORITATIVE (survives reinstall / shows once across devices): GET /v1/me
 * returns account.consents.terms {granted, version}; we re-prompt iff it is not granted at the CURRENT
 * version. The local mirror is only a fast-path cache for "already accepted on THIS install".
 */
public final class LeemenConsent {

    private LeemenConsent() {}

    /** Bump on any substantive change to the Terms OR the Privacy Policy → users re-confirm. Date form is
     *  human-readable, sortable, and 1:1 with the "updated" date published on the hosted documents.
     *  Current value = the later of Terms (2026-06-18) and Privacy (2026-06-19); one acceptance covers both. */
    public static final String CURRENT_TERMS_VERSION = "2026-06-19";

    public static final String TYPE_TERMS = "terms";
    public static final String TYPE_KZ_CROSS_BORDER = "kz_cross_border";
    public static final String TYPE_ANALYTICS = "analytics";
    public static final String TYPE_ATTRIBUTION = "attribution";
    private static final String[] LEDGER_TYPES = {
            TYPE_TERMS, TYPE_KZ_CROSS_BORDER, TYPE_ANALYTICS, TYPE_ATTRIBUTION
    };
    /** request key → token that owns the request; token identity prevents account-slot reuse races. */
    private static final Map<String, String> inFlight = new HashMap<>();

    public interface Eval {
        /** @param needsPrompt show the Terms gate; @param kzDisclosure include the KZ→EU paragraph. */
        void on(boolean needsPrompt, boolean kzDisclosure);
    }

    /** app locale for the ledger + which document URL to open ("ru" | "en"). */
    public static String currentLocale() {
        return LeemenConfig.isRu() ? "ru" : "en";
    }

    /**
     * Decide whether the Terms gate must be shown for this account, and whether to include the KZ paragraph.
     * Fast path: local mirror already at CURRENT version → no prompt, no network. Otherwise GET /v1/me and
     * honor the server's consent ledger (authoritative). Callback is delivered on the UI thread.
     * Precondition: the account is bound (has a session token) — callers gate on LeemenAccount.hasBinding.
     */
    public static void evaluate(final int account, final Eval cb) {
        if (CURRENT_TERMS_VERSION.equals(LeemenAccount.getAcceptedTermsVersion(account))) {
            cb.on(false, false);
            return;
        }
        final String token = LeemenAccount.getToken(account);
        if (token == null) {
            cb.on(true, false); // not bound yet (shouldn't happen — caller guards) → prompt terms, no KZ paragraph
            return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ME, token, (resp, code, ec, em) -> {
            try {
                if (resp != null && code >= 200 && code < 300 && resp.has("account") && resp.get("account").isJsonObject()) {
                    JsonObject acc = resp.getAsJsonObject("account");
                    boolean kz = boolField(acc, "kz_consent_required");

                    boolean serverGranted = false;
                    String serverVersion = null;
                    if (acc.has("consents") && acc.get("consents").isJsonObject()) {
                        JsonObject consents = acc.getAsJsonObject("consents");
                        if (consents.has(TYPE_TERMS) && consents.get(TYPE_TERMS).isJsonObject()) {
                            JsonObject terms = consents.getAsJsonObject(TYPE_TERMS);
                            serverGranted = boolField(terms, "granted");
                            serverVersion = strField(terms, "version");
                        }
                    }

                    if (serverGranted && CURRENT_TERMS_VERSION.equals(serverVersion)) {
                        // already accepted current version (e.g. on another device / before reinstall) → mirror, no prompt
                        LeemenAccount.setAcceptedTermsVersion(account, CURRENT_TERMS_VERSION);
                        cb.on(false, false);
                    } else {
                        cb.on(true, kz);
                    }
                } else {
                    // can't confirm (offline / error) → prompt for Terms, omit KZ paragraph (best-effort)
                    cb.on(true, false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                cb.on(true, false);
            }
        });
    }

    /** User accepted: set the local mirror and record to the backend ledger (terms, plus kz_cross_border if
     *  the KZ paragraph was shown). Best-effort POST; a failure sets a dirty flag re-flushed at next startup. */
    public static void grant(int account, boolean kzShown) {
        LeemenAccount.setAcceptedTermsVersion(account, CURRENT_TERMS_VERSION);
        postConsent(account, TYPE_TERMS, true);
        if (kzShown) {
            postConsent(account, TYPE_KZ_CROSS_BORDER, true);
        }
    }

    /** Record a consent grant/revoke of any type (analytics/attribution) in the backend ledger. Best-effort:
     *  the local gate is the source of truth; this is the compliance audit record. Versioned by the current
     *  policy version (consent is given under the policy text in effect). */
    public static void recordConsent(int account, String type, boolean granted) {
        postConsent(account, type, granted);
    }

    /** Re-send every pending ledger mutation. The stored value is last-write-wins, including revocations. */
    public static void flushDirty(int account) {
        if (!LeemenAccount.hasBinding(account)) return;
        // Migrate the original terms-only dirty bit to the generalized durable queue.
        if (LeemenAccount.isConsentDirty(account)
                && CURRENT_TERMS_VERSION.equals(LeemenAccount.getAcceptedTermsVersion(account))) {
            LeemenAccount.setPendingConsent(account, TYPE_TERMS, true);
            LeemenAccount.setConsentDirty(account, false);
        }
        for (String type : LEDGER_TYPES) {
            if (LeemenAccount.hasPendingConsent(account, type)) {
                sendPending(account, type);
            }
        }
    }

    private static void postConsent(final int account, final String type, final boolean granted) {
        // Persist before IO so process death, offline starts and rapid grant→revoke changes are recoverable.
        LeemenAccount.setPendingConsent(account, type, granted);
        sendPending(account, type);
    }

    private static void sendPending(final int account, final String type) {
        if (!LeemenAccount.hasPendingConsent(account, type)) return;
        final String token = LeemenAccount.getToken(account);
        if (token == null) return;
        final String requestKey = account + ":" + type;
        synchronized (inFlight) {
            if (inFlight.containsKey(requestKey)) return;
            inFlight.put(requestKey, token);
        }
        final boolean granted = LeemenAccount.getPendingConsent(account, type);
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("granted", granted);
        body.addProperty("version", CURRENT_TERMS_VERSION);
        body.addProperty("locale", currentLocale());
        // NB: no accepted_at — the server timestamps the legal record; the client clock is not trusted.
        LeemenRestClient.post(LeemenConfig.EP_CONSENT, token, body, (resp, code, ec, em) -> {
            boolean ok = resp != null && code >= 200 && code < 300 && boolField(resp, "ok");
            boolean tokenStillCurrent = token.equals(LeemenAccount.getToken(account));
            boolean hasNewerValue = false;
            if (tokenStillCurrent && LeemenAccount.hasPendingConsent(account, type)) {
                hasNewerValue = LeemenAccount.getPendingConsent(account, type) != granted;
                if (ok && !hasNewerValue) {
                    LeemenAccount.setPendingConsent(account, type, null);
                }
            }
            synchronized (inFlight) {
                if (token.equals(inFlight.get(requestKey))) inFlight.remove(requestKey);
            }
            // Serialize opposite mutations: a revoke queued behind a grant is sent only after the grant
            // completes, so request reordering cannot resurrect the older server state.
            if (tokenStillCurrent && hasNewerValue) sendPending(account, type);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: POST /consent type=" + type + " granted=" + granted + " code=" + code + " ok=" + ok + (ec != null ? " err=" + ec : ""));
            }
        });
    }

    /** Forget requests belonging to an account slot before that slot is reused by another Telegram account. */
    static void clearAccount(int account) {
        synchronized (inFlight) {
            for (String type : LEDGER_TYPES) inFlight.remove(account + ":" + type);
        }
    }

    private static boolean boolField(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean(); } catch (Throwable e) { return false; }
    }

    private static String strField(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null; } catch (Throwable e) { return null; }
    }
}
