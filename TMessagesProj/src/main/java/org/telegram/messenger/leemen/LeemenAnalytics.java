package org.telegram.messenger.leemen;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6 — neutral product analytics. Server-primary, no third-party SDK. NEVER emits anything about
 * private space (§0 of the Analytics spec). Events are validated against a name+prop allowlist mirrored
 * byte-for-byte from the backend (unknown names/props are dropped client-side too) and sent batched to
 * POST /v1/events with the exact consenting account's Bearer token (the server can then associate install_id
 * with the Leemen account, so this data is pseudonymous rather than anonymous).
 *
 * install_id is a stable per-install UUID (NOT cleared on logout). Consent is account-wide: its fail-closed
 * local cache is keyed by master_account_id and reconciled from the backend ledger returned by GET /me.
 * A backend-owned remote kill-switch is an additional mandatory gate: every process starts fail-closed and
 * telemetry stays off until LeemenTelemetryPolicy accepts a current /client-config response.
 */
public final class LeemenAnalytics {

    private LeemenAnalytics() {}

    private static final String PREFS = "leemen_analytics";
    private static final int QUEUE_CAP = 200;
    private static final int BATCH_MAX = 100;
    private static final long FLUSH_DELAY_MS = 2000;
    private static final String CONSENT_VALUE_PREFIX = "consent_value_";
    private static final String CONSENT_VERSION_PREFIX = "consent_version_";

    // Allowlist: event name -> permitted prop keys (must match the backend's analytics_allowlist).
    private static final Map<String, Set<String>> ALLOW = new HashMap<>();
    static {
        ALLOW.put("app_first_open", set());
        ALLOW.put("onboarding_step_view", set("step"));
        ALLOW.put("onboarding_completed", set("last_step"));
        ALLOW.put("onboarding_abandoned", set("last_step"));
        ALLOW.put("signup_started", set());
        ALLOW.put("signup_otp_requested", set());
        ALLOW.put("signup_completed", set());
        ALLOW.put("paywall_view", set("placement"));
        ALLOW.put("paywall_cta_tap", set("plan"));
        ALLOW.put("subscribe_flow_started", set());
        ALLOW.put("new_account_redirect_view", set());
    }

    /** An event never changes principal when the selected Telegram account changes before the batch flush. */
    private static final class QueuedEvent {
        final String masterAccountId;
        final JsonObject payload;

        QueuedEvent(String masterAccountId, JsonObject payload) {
            this.masterAccountId = masterAccountId;
            this.payload = payload;
        }
    }

    private static final List<QueuedEvent> queue = new ArrayList<>();
    /** In-process ordering fence: an older /me request cannot undo a choice made while it was in flight. */
    private static final long[] localDecisionRevision = new long[UserConfig.MAX_ACCOUNT_COUNT];
    /** A persisted grant cannot emit until this exact bound token confirms current server state. */
    private static final String[] consentConfirmedToken = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile String sessionId;
    /** True from first-open enqueue until the server accepts it (or consent is revoked). */
    private static boolean firstOpenPending;
    private static Runnable flushRunnable;

    // ===== public API =====

    public static void track(String name) { track(name, null); }

    public static void track(String name, Map<String, String> props) {
        int account = telemetryAccount();
        if (account < 0) return;
        track(account, name, props);
    }

    private static void track(int account, String name, Map<String, String> props) {
        try {
            if (!isTelemetryEnabled(account)) return;
            String masterAccountId = LeemenAccount.getMasterAccountId(account);
            if (masterAccountId == null) return;
            Set<String> allowedProps = ALLOW.get(name);
            if (allowedProps == null) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: analytics drop unknown event '" + name + "'");
                return;
            }
            JsonObject ev = new JsonObject();
            ev.addProperty("name", name);
            ev.addProperty("ts", System.currentTimeMillis());
            JsonObject p = new JsonObject();
            if (props != null) {
                for (Map.Entry<String, String> e : props.entrySet()) {
                    if (allowedProps.contains(e.getKey()) && e.getValue() != null) {
                        p.addProperty(e.getKey(), e.getValue());
                    }
                }
            }
            ev.add("props", p);
            synchronized (queue) {
                queue.add(new QueuedEvent(masterAccountId, ev));
                while (queue.size() > QUEUE_CAP) {
                    QueuedEvent removed = queue.remove(0); // drop oldest
                    if (isEventNamed(removed.payload, "app_first_open")) firstOpenPending = false;
                }
            }
            scheduleFlush();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Fire app_first_open exactly once per install. */
    public static void onAppStart() {
        int account = telemetryAccount();
        if (account >= 0) onAppStart(account);
    }

    private static void onAppStart(int account) {
        try {
            if (!isTelemetryEnabled(account)) return;
            SharedPreferences pr = prefs();
            if (!pr.getBoolean("first_open_sent", false)) {
                boolean enqueue;
                synchronized (queue) {
                    enqueue = !firstOpenPending;
                    if (enqueue) firstOpenPending = true;
                }
                if (enqueue) {
                    track(account, "app_first_open", null);
                    synchronized (queue) {
                        // Defensive: track() catches its own construction errors.
                        if (!containsEvent(queue, "app_first_open")) firstOpenPending = false;
                    }
                }
                flush();
            } else {
                flush(); // drain anything queued from a previous session
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Account-wide opt-in restored from the server ledger. Unknown/old-policy state is always OFF. */
    public static boolean hasConsent(int account) {
        String masterAccountId = LeemenAccount.getMasterAccountId(account);
        return masterAccountId != null
                && LeemenConsent.CURRENT_TERMS_VERSION.equals(
                        prefs().getString(CONSENT_VERSION_PREFIX + masterAccountId, null))
                && prefs().getBoolean(CONSENT_VALUE_PREFIX + masterAccountId, false);
    }

    /** True for either an explicit grant or an explicit refusal at the current policy version. */
    public static boolean hasConsentDecision(int account) {
        String masterAccountId = LeemenAccount.getMasterAccountId(account);
        return masterAccountId != null
                && LeemenConsent.CURRENT_TERMS_VERSION.equals(
                        prefs().getString(CONSENT_VERSION_PREFIX + masterAccountId, null))
                && prefs().contains(CONSENT_VALUE_PREFIX + masterAccountId);
    }

    /** Local explicit consent AND the current backend kill-switch decision are both required. */
    public static boolean isTelemetryEnabled(int account) {
        return account >= 0
                && account < consentConfirmedToken.length
                && isConsentConfirmedForCurrentToken(account)
                && hasConsent(account)
                && LeemenTelemetryPolicy.isEnabled();
    }

    /** Store an explicit grant/refusal and append both halves of the combined switch to the server ledger. */
    public static boolean setConsent(int account, boolean granted) {
        if (!validBoundAccount(account) || LeemenAccount.getMasterAccountId(account) == null) return false;
        // Pending-first is load-bearing: a /me beginning before this write captures the old revision and is
        // rejected below; one beginning after it sees pending=true. There is no snapshot that can observe
        // the new revision while still believing no local mutation exists.
        if (!LeemenConsent.persistTelemetryConsent(account, granted)) return false;
        localDecisionRevision[account]++;
        // A refusal closes the gate immediately. A grant is remembered in the UI, but remains fail-closed
        // until both ledger writes succeed and a fresh authenticated /me confirms the combined state.
        applyLocalDecision(account, granted, false);
        LeemenConsent.sendTelemetryConsent(account);
        return true;
    }

    /**
     * Upgrade the old install-global choice to the currently selected stable master account. Released builds
     * already recorded grants in the ledger, but "Not now" was represented only by consent_prompt_shown.
     * Preserve either choice once, then let the normal durable/server-confirmed path own it.
     */
    public static void migrateLegacyConsent(int account) {
        if (!validBoundAccount(account) || LeemenAccount.getMasterAccountId(account) == null) return;
        SharedPreferences pr = prefs();
        boolean hasLegacyChoice = pr.contains("consent") || pr.getBoolean("consent_prompt_shown", false);
        if (hasConsentDecision(account)) {
            if (hasLegacyChoice) {
                pr.edit().remove("consent").remove("consent_prompt_shown").apply();
            }
            return;
        }
        Boolean pendingChoice = pendingTelemetryDecision(account);
        if (pendingChoice != null) {
            // Released builds may already have a durable offline write. Seed only the local UI mirror;
            // flushDirty will deliver the existing rows and /me will confirm them before telemetry starts.
            applyLocalDecision(account, pendingChoice, false);
        } else if (hasLegacyChoice) {
            setConsent(account, pr.getBoolean("consent", false));
        }
    }

    /** Drop unsent payloads immediately when the backend disables telemetry or policy resolution fails. */
    static void onRemotePolicyDisabled() {
        synchronized (queue) {
            queue.clear();
            firstOpenPending = false;
        }
        if (flushRunnable != null) {
            org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushRunnable = null;
        }
    }

    /**
     * Apply the latest combined analytics+attribution ledger state from an ordered GET /me snapshot.
     * Missing, malformed, mismatched or old-policy rows are UNKNOWN/OFF. A pending local mutation wins over
     * the snapshot, as does a choice made after that GET started.
     */
    static void applyConsentFromMe(int account, JsonObject response, long revisionAtRequestStart,
                                   boolean pendingAtRequestStart, String masterAtRequestStart) {
        String currentMasterAccountId = LeemenAccount.getMasterAccountId(account);
        if (!validBoundAccount(account)
                || currentMasterAccountId == null
                || !currentMasterAccountId.equals(masterAtRequestStart)
                || pendingAtRequestStart
                || revisionAtRequestStart != localDecisionRevision[account]
                || LeemenAccount.hasPendingConsent(account, LeemenConsent.TYPE_ANALYTICS)
                || LeemenAccount.hasPendingConsent(account, LeemenConsent.TYPE_ATTRIBUTION)) {
            return;
        }
        Boolean analytics = consentValue(response, LeemenConsent.TYPE_ANALYTICS);
        Boolean attribution = consentValue(response, LeemenConsent.TYPE_ATTRIBUTION);
        if (analytics != null && attribution != null && analytics.equals(attribution)) {
            applyLocalDecision(account, analytics, true);
        } else {
            clearLocalDecision(account);
        }
    }

    static long localDecisionRevision(int account) {
        return account >= 0 && account < localDecisionRevision.length ? localDecisionRevision[account] : -1L;
    }

    /** Erase consent artifacts owned by a backend generation that was deleted or replaced. */
    static void clearAccountGeneration(int account) {
        String masterAccountId = LeemenAccount.getMasterAccountId(account);
        if (masterAccountId == null) return;
        prefs().edit()
                .remove(CONSENT_VALUE_PREFIX + masterAccountId)
                .remove(CONSENT_VERSION_PREFIX + masterAccountId)
                .apply();
        LeemenAccount.clearTelemetryConsentPendingForGeneration(account);
        synchronized (queue) {
            removeQueuedEventsLocked(masterAccountId);
            firstOpenPending = containsEvent(queue, "app_first_open");
        }
        if (account >= 0 && account < consentConfirmedToken.length) {
            consentConfirmedToken[account] = null;
            localDecisionRevision[account]++;
        }
    }

    /** Invalidate process-only confirmation when a local Telegram slot logs out; keep account-wide choice. */
    static void clearAccountSession(int account) {
        if (account < 0 || account >= consentConfirmedToken.length) return;
        consentConfirmedToken[account] = null;
        localDecisionRevision[account]++;
    }

    /** Stable per-install id (UUIDv4). Created once; never reset on logout — only on uninstall/wipe. */
    public static String installId() {
        SharedPreferences pr = prefs();
        String id = pr.getString("install_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            pr.edit().putString("install_id", id).apply();
        }
        return id;
    }

    // ===== flush =====

    private static void scheduleFlush() {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            if (flushRunnable != null) org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushRunnable = () -> { flushRunnable = null; flush(); };
            org.telegram.messenger.AndroidUtilities.runOnUIThread(flushRunnable, FLUSH_DELAY_MS);
        });
    }

    private static void flush() {
        final List<QueuedEvent> batch = new ArrayList<>();
        final String masterAccountId;
        final int account;
        final long consentRevisionAtSend;
        synchronized (queue) {
            if (queue.isEmpty()) return;
            String owner = queue.get(0).masterAccountId;
            int ownerAccount = accountForMaster(owner);
            if (ownerAccount < 0 || !hasConsent(ownerAccount)) {
                removeQueuedEventsLocked(owner);
                firstOpenPending = containsEvent(queue, "app_first_open");
                if (!queue.isEmpty()) scheduleFlush();
                return;
            }
            // A policy refresh or token renewal temporarily closes the gate. Keep already-consented
            // in-memory events until the exact token and policy are confirmed again.
            if (!isTelemetryEnabled(ownerAccount)) return;
            masterAccountId = owner;
            account = ownerAccount;
            consentRevisionAtSend = localDecisionRevision[account];
            for (int i = 0; i < queue.size() && batch.size() < BATCH_MAX;) {
                QueuedEvent event = queue.get(i);
                if (masterAccountId.equals(event.masterAccountId)) {
                    batch.add(event);
                    queue.remove(i);
                } else {
                    i++;
                }
            }
        }
        JsonObject client = new JsonObject();
        client.addProperty("platform", "android");
        client.addProperty("app_version", BuildVars.BUILD_VERSION_STRING);
        client.addProperty("build", appBuildCode());

        JsonObject body = new JsonObject();
        body.add("client", client);
        body.addProperty("install_id", installId());
        body.addProperty("session_id", sessionId());
        JsonArray arr = new JsonArray();
        for (QueuedEvent e : batch) arr.add(e.payload);
        body.add("events", arr);

        String token = LeemenAccount.getToken(account);
        LeemenRestClient.post(LeemenConfig.EP_EVENTS, token, body, (resp, code, ec, em) -> {
            if (code >= 200 && code < 300) {
                if (containsEvent(batch, "app_first_open")) {
                    prefs().edit().putBoolean("first_open_sent", true).apply();
                    synchronized (queue) { firstOpenPending = false; }
                }
                synchronized (queue) {
                    if (!queue.isEmpty()) scheduleFlush(); // more pending
                }
            } else if (consentRevisionAtSend == localDecisionRevision[account]
                    && canRetainQueuedEventsForMaster(masterAccountId)) {
                // transient failure: requeue (bounded); retried on the next event / app start
                synchronized (queue) {
                    queue.addAll(0, batch);
                    while (queue.size() > QUEUE_CAP) queue.remove(queue.size() - 1);
                    if (containsEvent(queue, "app_first_open")) firstOpenPending = true;
                }
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: /events flush failed code=" + code + " err=" + ec);
            } else if (containsEvent(batch, "app_first_open")) {
                synchronized (queue) {
                    firstOpenPending = containsEvent(queue, "app_first_open");
                }
            }
        });
    }

    // ===== helpers =====

    private static void applyLocalDecision(int account, boolean granted, boolean confirmed) {
        String masterAccountId = LeemenAccount.getMasterAccountId(account);
        if (masterAccountId == null) return;
        boolean hadDecision = hasConsentDecision(account);
        boolean previous = hadDecision && hasConsent(account);
        String currentToken = LeemenAccount.getToken(account);
        boolean wasConfirmed = currentToken != null && currentToken.equals(consentConfirmedToken[account]);
        prefs().edit()
                .putBoolean(CONSENT_VALUE_PREFIX + masterAccountId, granted)
                .putString(CONSENT_VERSION_PREFIX + masterAccountId, LeemenConsent.CURRENT_TERMS_VERSION)
                // Remove the obsolete install-global keys so they can never be mistaken for current state.
                .remove("consent")
                .remove("consent_prompt_shown")
                .apply();
        if (confirmed && (!hadDecision || previous != granted)) {
            // Remote changes invalidate in-flight event batches just like an explicit local switch.
            localDecisionRevision[account]++;
        }
        consentConfirmedToken[account] = confirmed ? currentToken : null;
        if (!granted) {
            synchronized (queue) {
                removeQueuedEventsLocked(masterAccountId);
                firstOpenPending = containsEvent(queue, "app_first_open");
            }
        } else if (confirmed && (!wasConfirmed || !hadDecision || !previous)) {
            onAppStart(account);
            LeemenHeartbeat.maybeSendAll();
            LeemenAttribution.captureIfNeeded(account);
        }
        if (!hadDecision || previous != granted) {
            notifyConsentChanged(account);
        }
    }

    private static void clearLocalDecision(int account) {
        String masterAccountId = LeemenAccount.getMasterAccountId(account);
        if (masterAccountId == null) return;
        boolean hadDecision = hasConsentDecision(account);
        prefs().edit()
                .remove(CONSENT_VALUE_PREFIX + masterAccountId)
                .remove(CONSENT_VERSION_PREFIX + masterAccountId)
                .remove("consent")
                .remove("consent_prompt_shown")
                .apply();
        if (hadDecision) localDecisionRevision[account]++;
        consentConfirmedToken[account] = LeemenAccount.getToken(account);
        synchronized (queue) {
            removeQueuedEventsLocked(masterAccountId);
            firstOpenPending = containsEvent(queue, "app_first_open");
        }
        if (hadDecision) notifyConsentChanged(account);
    }

    private static Boolean consentValue(JsonObject response, String type) {
        try {
            if (response == null
                    || !response.has("consents")
                    || !response.get("consents").isJsonObject()) {
                return null;
            }
            JsonObject consents = response.getAsJsonObject("consents");
            if (!consents.has(type) || !consents.get(type).isJsonObject()) return null;
            JsonObject record = consents.getAsJsonObject(type);
            if (!record.has("version")
                    || record.get("version").isJsonNull()
                    || !LeemenConsent.CURRENT_TERMS_VERSION.equals(record.get("version").getAsString())
                    || !record.has("granted")
                    || !record.get("granted").isJsonPrimitive()
                    || !record.getAsJsonPrimitive("granted").isBoolean()) {
                return null;
            }
            return record.get("granted").getAsBoolean();
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** A matched durable pair is itself an explicit legacy choice, but never a server confirmation. */
    private static Boolean pendingTelemetryDecision(int account) {
        if (!LeemenAccount.hasPendingConsent(account, LeemenConsent.TYPE_ANALYTICS)
                || !LeemenAccount.hasPendingConsent(account, LeemenConsent.TYPE_ATTRIBUTION)
                || !LeemenConsent.CURRENT_TERMS_VERSION.equals(
                        LeemenAccount.getPendingConsentVersion(account, LeemenConsent.TYPE_ANALYTICS))
                || !LeemenConsent.CURRENT_TERMS_VERSION.equals(
                        LeemenAccount.getPendingConsentVersion(account, LeemenConsent.TYPE_ATTRIBUTION))) {
            return null;
        }
        boolean analytics = LeemenAccount.getPendingConsent(account, LeemenConsent.TYPE_ANALYTICS);
        boolean attribution = LeemenAccount.getPendingConsent(account, LeemenConsent.TYPE_ATTRIBUTION);
        return analytics == attribution ? analytics : null;
    }

    private static int telemetryAccount() {
        try {
            int sel = UserConfig.selectedAccount;
            return validBoundAccount(sel) ? sel : -1;
        } catch (Throwable ignore) {}
        return -1;
    }

    private static int accountForMaster(String masterAccountId) {
        if (masterAccountId == null) return -1;
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (validBoundAccount(account)
                    && masterAccountId.equals(LeemenAccount.getMasterAccountId(account))) {
                return account;
            }
        }
        return -1;
    }

    private static boolean validBoundAccount(int account) {
        return account >= 0
                && account < UserConfig.MAX_ACCOUNT_COUNT
                && UserConfig.getInstance(account).isClientActivated()
                && !LeemenAccount.isDisabled(account)
                && LeemenAccount.hasBinding(account);
    }

    private static boolean canRetainQueuedEventsForMaster(String masterAccountId) {
        int account = accountForMaster(masterAccountId);
        return account >= 0 && hasConsent(account);
    }

    private static boolean isConsentConfirmedForCurrentToken(int account) {
        String token = LeemenAccount.getToken(account);
        return token != null && token.equals(consentConfirmedToken[account]);
    }

    private static void removeQueuedEventsLocked(String masterAccountId) {
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (masterAccountId.equals(queue.get(i).masterAccountId)) queue.remove(i);
        }
    }

    private static void notifyConsentChanged(int account) {
        try {
            org.telegram.messenger.NotificationCenter.getInstance(account).postNotificationName(
                    org.telegram.messenger.NotificationCenter.leemenAnalyticsConsentChanged);
        } catch (Throwable ignore) {
        }
    }

    private static String sessionId() {
        if (sessionId == null) sessionId = UUID.randomUUID().toString();
        return sessionId;
    }

    private static int appBuildCode() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean containsEvent(List<QueuedEvent> events, String name) {
        for (int i = 0; i < events.size(); i++) {
            if (isEventNamed(events.get(i).payload, name)) return true;
        }
        return false;
    }

    private static boolean isEventNamed(JsonObject event, String name) {
        try {
            return event != null && event.has("name") && name.equals(event.get("name").getAsString());
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static Set<String> set(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }
}
