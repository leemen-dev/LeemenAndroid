package org.telegram.messenger.leemen;

import com.google.gson.JsonObject;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

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
 * returns consents.terms {granted, version}; we re-prompt iff it is not granted at the CURRENT
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
        evaluate(account, cb, 0);
    }

    private static void evaluate(final int account, final Eval cb, final int staleRetries) {
        final boolean termsAcceptedLocally =
                CURRENT_TERMS_VERSION.equals(LeemenAccount.getAcceptedTermsVersion(account));
        if (termsAcceptedLocally && LeemenAnalytics.hasConsentDecision(account)) {
            cb.on(false, false);
            return;
        }
        final String token = LeemenAccount.getToken(account);
        if (token == null) {
            cb.on(true, false); // not bound yet (shouldn't happen — caller guards) → prompt terms, no KZ paragraph
            return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ME, token, (resp, code, ec, em) -> {
            if (!token.equals(LeemenAccount.getToken(account))
                    || !UserConfig.getInstance(account).isClientActivated()
                    || LeemenAccount.isDisabled(account)) {
                return;
            }
            try {
                if (resp != null && code >= 200 && code < 300 && resp.has("account") && resp.get("account").isJsonObject()) {
                    if (!LeemenAccountState.wasMeSnapshotApplied(account, resp)) {
                        // Never bypass the global request ordering with an older response. Retry as the
                        // newest request with a small bound; central account-state reconciliation also
                        // coalesces a fresh snapshot whenever any successful response is superseded.
                        if (staleRetries < 3) {
                            org.telegram.messenger.AndroidUtilities.runOnUIThread(
                                    () -> evaluate(account, cb, staleRetries + 1),
                                    100L * (staleRetries + 1));
                        } else {
                            // Keep the authoritative background reconcile, but never strand the caller's
                            // onboarding flow. Local Terms remain usable; analytics is still fail-closed and
                            // its prompt can safely represent UNKNOWN as an explicit choice.
                            LeemenAccountState.onRemoteChanged(account);
                            cb.on(!termsAcceptedLocally, false);
                        }
                        return;
                    }
                    JsonObject acc = resp.getAsJsonObject("account");
                    boolean kz = boolField(acc, "kz_consent_required");

                    boolean serverGranted = false;
                    String serverVersion = null;
                    if (resp.has("consents") && resp.get("consents").isJsonObject()) {
                        JsonObject consents = resp.getAsJsonObject("consents");
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
                    } else if (termsAcceptedLocally) {
                        // We only came to /me because analytics consent was not cached. Preserve the existing
                        // local Terms fast path while the ordered /me application restores analytics state.
                        cb.on(false, false);
                    } else {
                        cb.on(true, kz);
                    }
                } else {
                    // Keep a previously accepted local Terms version usable offline. Analytics remains
                    // fail-closed and can still be chosen explicitly in the prompt/settings.
                    cb.on(!termsAcceptedLocally, false);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                cb.on(!termsAcceptedLocally, false);
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

    /** Record a consent grant/revoke of any type in the backend ledger. The durable local mutation is
     *  last-write-wins; telemetry grants remain fail-closed until a fresh /me confirms the server record.
     *  Versioned by the current policy version (consent is given under the policy text in effect). */
    public static void recordConsent(int account, String type, boolean granted) {
        postConsent(account, type, granted);
    }

    /** Persist both halves before advancing the local decision revision or starting network IO. */
    static boolean persistTelemetryConsent(int account, boolean granted) {
        return LeemenAccount.setPendingTelemetryConsent(account, granted);
    }

    /** Start both already-persisted requests after the local fail-closed decision mirror is updated. */
    static void sendTelemetryConsent(int account) {
        sendPending(account, TYPE_ANALYTICS);
        sendPending(account, TYPE_ATTRIBUTION);
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

    /** Re-send durable mutations for every active bound account, not only the currently visible one. */
    public static void flushDirtyAll() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)
                    && LeemenAccount.hasBinding(account)) {
                flushDirty(account);
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
        final boolean telemetryType = isTelemetryType(type);
        final String masterAccountId = telemetryType ? LeemenAccount.getMasterAccountId(account) : null;
        if (telemetryType && masterAccountId == null) return;
        // Telemetry choices belong to the master generation. Keeping this guard across a local logout/rebind
        // prevents an old grant request from arriving after the new token's revoke request.
        final String requestKey = telemetryType
                ? "master:" + masterAccountId + ":" + type
                : account + ":" + type;
        synchronized (inFlight) {
            if (inFlight.containsKey(requestKey)) return;
            inFlight.put(requestKey, token);
        }
        final boolean granted = LeemenAccount.getPendingConsent(account, type);
        final String storedPendingVersion = LeemenAccount.getPendingConsentVersion(account, type);
        final String pendingVersion = storedPendingVersion != null ? storedPendingVersion : CURRENT_TERMS_VERSION;
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("granted", granted);
        body.addProperty("version", pendingVersion);
        body.addProperty("locale", currentLocale());
        // NB: no accepted_at — the server timestamps the legal record; the client clock is not trusted.
        LeemenRestClient.post(LeemenConfig.EP_CONSENT, token, body, (resp, code, ec, em) -> {
            boolean ok = resp != null && code >= 200 && code < 300 && boolField(resp, "ok");
            int ownerAccount = telemetryType ? boundAccountForMaster(masterAccountId) : account;
            boolean ownerStillCurrent = ownerAccount >= 0
                    && (telemetryType || token.equals(LeemenAccount.getToken(ownerAccount)));
            boolean tokenWasReplaced = ownerStillCurrent
                    && !token.equals(LeemenAccount.getToken(ownerAccount));
            boolean hasNewerValue = false;
            if (ownerStillCurrent && LeemenAccount.hasPendingConsent(ownerAccount, type)) {
                String currentPendingVersion = LeemenAccount.getPendingConsentVersion(ownerAccount, type);
                hasNewerValue = LeemenAccount.getPendingConsent(ownerAccount, type) != granted
                        || (currentPendingVersion != null && !pendingVersion.equals(currentPendingVersion));
                if (ok && !hasNewerValue) {
                    LeemenAccount.setPendingConsent(ownerAccount, type, null);
                }
            }
            synchronized (inFlight) {
                if (token.equals(inFlight.get(requestKey))) inFlight.remove(requestKey);
            }
            // Serialize opposite mutations: a revoke queued behind a grant is sent only after the grant
            // completes, so request reordering cannot resurrect the older server state.
            if (ownerStillCurrent && (hasNewerValue || (!ok && tokenWasReplaced))) {
                sendPending(ownerAccount, type);
            }
            if (ok && ownerStillCurrent && telemetryType
                    && !LeemenAccount.hasPendingConsent(ownerAccount, TYPE_ANALYTICS)
                    && !LeemenAccount.hasPendingConsent(ownerAccount, TYPE_ATTRIBUTION)) {
                // A /me that started while these writes were pending may have read the old ledger. Force a
                // post-ack snapshot; request ordering then makes only this fresh combined state applicable.
                LeemenAccountState.onRemoteChanged(ownerAccount);
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: POST /consent type=" + type + " granted=" + granted + " code=" + code + " ok=" + ok + (ec != null ? " err=" + ec : ""));
            }
        });
    }

    /**
     * Forget slot-owned Terms/KZ requests before slot reuse. Master-owned telemetry requests deliberately
     * stay serialized until their callback/timeout, so a same-master rebind cannot reorder grant and revoke.
     */
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

    private static boolean isTelemetryType(String type) {
        return TYPE_ANALYTICS.equals(type) || TYPE_ATTRIBUTION.equals(type);
    }

    private static int boundAccountForMaster(String masterAccountId) {
        if (masterAccountId == null) return -1;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)
                    && LeemenAccount.hasBinding(account)
                    && masterAccountId.equals(LeemenAccount.getMasterAccountId(account))) {
                return account;
            }
        }
        return -1;
    }
}
