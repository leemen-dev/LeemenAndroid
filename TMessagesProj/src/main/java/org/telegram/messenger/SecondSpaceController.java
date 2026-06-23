package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class SecondSpaceController extends BaseController implements NotificationCenter.NotificationCenterDelegate {

    private static final String PREF_DIALOG_IDS = "second_space_dialogs";
    private static final String PREF_EXPOSED = "second_space_exposed";
    private static final String PREF_LAST_DECIDED = "second_space_last_decided";
    private static final String PREF_SHOW_ENTRY_BUTTON = "second_space_show_entry_button";
    private static final String PREF_PENDING_OFF_MODE = "second_space_pending_off_mode";
    private static final String PREF_PENDING_MESSAGES = "second_space_pending_messages";
    /** Hidden chats whose CURRENT draft was authored inside the private space (MODE_REAL). These are wiped
     *  (local + server) on leaving the private space so they can never surface in the OFF-mode list. OFF-mode
     *  drafts are never listed here and are kept. Persisted so a kill mid-PS is recovered on next launch. */
    private static final String PREF_PS_DRAFTS = "second_space_ps_drafts";
    private static final String PREF_PRIVATE_SEARCHES = "second_space_private_searches";
    private static final String PREF_PASSWORD_HASH = "second_space_password_hash"; // legacy (pre-§7.2); ignored
    // Private-space entry PIN, account-level + synced (§7.2): Argon2id(hash, salt) stored base64.
    private static final String PREF_PS_PIN_STATE = "second_space_ps_pin_state";
    private static final String PREF_PS_PIN_HASH = "second_space_ps_pin_hash";
    private static final String PREF_PS_PIN_SALT = "second_space_ps_pin_salt";
    /** {@link #psPinState} values; the strings match {@code LeemenBlob.SET}/{@code NONE} on purpose. */
    private static final String PIN_SET = "set";
    private static final String PIN_NONE = "none";
    private static final String PREF_TAB_SEQUENCE = "second_space_tab_sequence";
    private static final String PREF_PIN_IN_SEARCH = "second_space_pin_in_search";
    private static final String PREF_SHORTCUT_TESTED = "second_space_shortcut_tested";
    private static final String PREF_PIN_TIMEOUT_MIN = "second_space_pin_timeout_min";
    private static final String PREF_PIN_LAST_OK_MS = "second_space_pin_last_ok_ms";
    // Storage key kept as "entry_password" for backward compatibility; semantics are now
    // the main account's single switch password (see the switch-password section below).
    private static final String PREF_SWITCH_PASSWORD_HASH = "second_space_entry_password_hash";
    private static final String PREF_HIDDEN_ACCOUNTS = "second_space_hidden_accounts";
    private static final String PREF_EYE_HINT_SHOWN = "second_space_eye_hint_shown";
    private static final String PREF_TOOLBAR_HINT_SHOWN = "second_space_toolbar_hint_shown";
    private static final String PREF_SELF_PINNED = "second_space_self_pinned";
    private static final String PREF_ALLOW_SCREENSHOTS = "second_space_allow_screenshots";
    private static final String PREF_ONBOARDING_DONE = "second_space_onboarding_done";
    private static final String PREF_PAYWALL_SHOWN = "second_space_paywall_shown";
    /** Leemen Premium expiry, epoch ms. {@code 0} = never subscribed. Local-only for now:
     *  set by {@link #activateLeemenPremiumLocally(int)} until real billing is wired. */
    private static final String PREF_PREMIUM_UNTIL = "second_space_premium_until";

    public static final int MODE_OFF = 0;
    public static final int MODE_REAL = 1;

    /** Free tier: how many chats a user may hide without a Leemen Premium subscription.
     *  Hard-enforced at every add-chat site via {@link #canAddChats(int)} (the picker, the
     *  long-press preview menu, and multi-select), which blocks the add and offers the paywall.
     *  Billing itself is still a local stub ({@link #activateLeemenPremiumLocally(int)}) until
     *  real Leemen billing is wired. */
    public static final int MAX_HIDDEN_CHATS_FREE = 1;

    /** A single step in the tab-tap gesture sequence. */
    public static final class TabStep {
        public final int tabIndex;
        public final boolean longPress;

        public TabStep(int tabIndex, boolean longPress) {
            this.tabIndex = tabIndex;
            this.longPress = longPress;
        }
    }

    private static final SecondSpaceController[] Instance = new SecondSpaceController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static SecondSpaceController getInstance(int num) {
        SecondSpaceController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new SecondSpaceController(num);
                }
            }
        }
        return localInstance;
    }

    /** Private-space chat membership. */
    private final Set<Long> dialogIds = new HashSet<>();
    private final Map<Long, Set<Integer>> exposedMessages = new HashMap<>();
    private final Map<Long, Set<Integer>> selfPinnedMessages = new HashMap<>();
    private final Map<Long, Integer> lastDecidedMessageId = new HashMap<>();
    /** Per-chat set of message ids the user sent while in OFF mode, still awaiting a decision.
     *  Off-mode-only sends go here directly so they can never be confused with normal active-
     *  mode sends — those simply don't get tracked. */
    private final Map<Long, Set<Integer>> pendingMessages = new HashMap<>();
    /** Hidden chats whose current draft was authored inside the private space (MODE_REAL). Wiped (local +
     *  server) when the user leaves the private space, so a secret draft can never appear in the OFF-mode
     *  list. OFF-mode drafts are never listed here and survive. Persisted so a kill mid-PS is recovered on
     *  the next (always-OFF) launch. */
    private final Set<Long> psDraftDialogs = new HashSet<>();
    /** In-memory bodies of the latest exposed/pending messages, keyed dialogId → (msgId → object). Telegram's
     *  {@code dialogMessagesByIds} holds ONLY each dialog's CURRENT top message and evicts older ones, so once a
     *  hidden chat receives a newer (hidden) message the exposed message vanishes from that cache and the OFF-mode
     *  preview goes blank. We keep the exposed/pending bodies here so the preview resolves regardless of top-message
     *  status or load timing. EXPOSED/PENDING messages are the ones shown in OFF mode anyway, so caching their bodies
     *  leaks nothing the user hasn't chosen to surface. NOT persisted — repopulated each session from the local DB
     *  warmup; main-thread access only (DialogCell.update + messagesDidLoad + applySyncedState all run on UI). */
    private final Map<Long, Map<Integer, MessageObject>> safePreviewCache = new HashMap<>();
    /** Stable class-guid for the dialog-list preview warmup loads (results route through messagesDidLoad). */
    private int previewWarmGuid = 0;
    /** Count of in-flight warmup tasks (DB loads + server fetches). The launch gate holds while this is > 0 and
     *  lifts REACTIVELY (no timer) the moment the last task settles — every task settles (DB always returns,
     *  every network request completes or errors), so it can't hang. UI-thread only. */
    private int warmupInFlight = 0;
    /** While true, the OFF-mode list is held fail-closed (only the system chat shows) until the INITIAL preview
     *  warmup settles — so chats never appear with empty previews. Armed once per session (at startup if hidden
     *  chats are persisted, else on the first sync that populates them) and never re-armed, so mid-session syncs
     *  don't blank the list. Read by {@link org.telegram.messenger.leemen.LeemenSync#isInitialSyncPending}. */
    private volatile boolean warmupGateActive = false; // read cross-thread from LeemenSync.isInitialSyncPending
    private boolean initialWarmupDone = false;
    private final Set<Long> privateSearchDialogs = new HashSet<>();
    /** Private-space entry PIN — ACCOUNT-level, synced via the content blob's ps_pin register (§7.2).
     *  Stored as Argon2id(pin, salt) (base64). state ∈ {"set","none",""}; "" = never enrolled (not synced). */
    private String psPinState = "";
    private String psPinHash = "";
    private String psPinSalt = "";
    private final java.util.List<TabStep> tabSequence = new java.util.ArrayList<>();
    private boolean pinInSearchEnabled;
    private boolean shortcutTested;
    private int pinTimeoutMinutes;
    private long pinLastVerifiedAt;
    /** Active mode: {@link #MODE_OFF} or {@link #MODE_REAL}.
     *  Non-persistent — always {@code MODE_OFF} on next launch (deniability). */
    private int activeMode = MODE_OFF;
    /** Single switch password owned by THIS account when it acts as the main account:
     *  gates switching into any account this account hides ({@link #hiddenAccounts}).
     *  Empty = no password. Independent of the Private Space PIN ({@link #psPinState}). */
    private String switchPasswordHash;
    /** Hidden-other-accounts list — accounts whose tray notifications / switcher
     *  presence are suppressed while private space is not active (i.e. in OFF mode). */
    private final Set<Integer> hiddenAccounts = new HashSet<>();
    /** When {@code true}, screenshots / screen-recording are permitted while this account's
     *  Private Space is open. Default {@code false}: FLAG_SECURE blocks capture in-space. */
    private boolean allowScreenshots;

    /** True while applying a merged sync blob (LeemenSync projection) — suppresses the
     *  local-change → sync-push hook so remote application doesn't echo back as a push. */
    private boolean applyingRemoteSync;

    /** Leemen Premium expiry (epoch ms); {@code 0} = no subscription. */
    private long leemenPremiumUntil;

    private boolean entryButtonVisible;

    private SecondSpaceController(int num) {
        super(num);
        SharedPreferences prefs = getMessagesController().getMainSettings();
        // activeMode is intentionally non-persistent: always starts MODE_OFF on app launch (deniability)
        entryButtonVisible = prefs.getBoolean(PREF_SHOW_ENTRY_BUTTON, true);
        loadDialogIds(dialogIds, prefs.getString(PREF_DIALOG_IDS, ""));
        // Existing users who already hid chats before onboarding shipped: treat them as having
        // finished onboarding so they skip the first-hide coach-marks and go straight to the
        // one-time paywall offer on their next entry into the space.
        if (!prefs.contains(PREF_ONBOARDING_DONE) && !dialogIds.isEmpty()) {
            prefs.edit().putBoolean(PREF_ONBOARDING_DONE, true).apply();
        }
        loadExposed(prefs.getString(PREF_EXPOSED, ""));
        loadLastDecided(prefs.getString(PREF_LAST_DECIDED, ""));
        loadPendingMessages(prefs.getString(PREF_PENDING_MESSAGES, ""));
        loadDialogIds(psDraftDialogs, prefs.getString(PREF_PS_DRAFTS, ""));
        loadSelfPinned(prefs.getString(PREF_SELF_PINNED, ""));
        String searchCsv = prefs.getString(PREF_PRIVATE_SEARCHES, "");
        if (!TextUtils.isEmpty(searchCsv)) {
            for (String s : searchCsv.split(",")) {
                try {
                    privateSearchDialogs.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        psPinState = prefs.getString(PREF_PS_PIN_STATE, "");
        psPinHash = prefs.getString(PREF_PS_PIN_HASH, "");
        psPinSalt = prefs.getString(PREF_PS_PIN_SALT, "");
        // Recovery: a 'set' PIN whose stored material is corrupt/undecodable can never verify — a hard
        // lockout, since both entry AND removal require a successful verify. Drop it to "never enrolled"
        // (NOT "none", which the next reconcile would turn into a tombstone that wipes the PIN on every
        // device). State "" doesn't sync, so the good PIN re-projects from the blob on the next sync.
        if (PIN_SET.equals(psPinState) && !isWellFormedPinMaterial(psPinHash, psPinSalt)) {
            psPinState = "";
            psPinHash = "";
            psPinSalt = "";
            prefs.edit().putString(PREF_PS_PIN_STATE, "").remove(PREF_PS_PIN_HASH).remove(PREF_PS_PIN_SALT).apply();
        }
        loadTabSequence(prefs.getString(PREF_TAB_SEQUENCE, ""));
        pinInSearchEnabled = prefs.getBoolean(PREF_PIN_IN_SEARCH, false);
        shortcutTested = prefs.getBoolean(PREF_SHORTCUT_TESTED, false);
        pinTimeoutMinutes = prefs.getInt(PREF_PIN_TIMEOUT_MIN, 0);
        pinLastVerifiedAt = prefs.getLong(PREF_PIN_LAST_OK_MS, 0L);
        switchPasswordHash = prefs.getString(PREF_SWITCH_PASSWORD_HASH, "");
        allowScreenshots = prefs.getBoolean(PREF_ALLOW_SCREENSHOTS, false);
        leemenPremiumUntil = prefs.getLong(PREF_PREMIUM_UNTIL, 0L);
        loadHiddenAccounts(hiddenAccounts, prefs.getString(PREF_HIDDEN_ACCOUNTS, ""), num);
        // Hold the OFF-mode list until the INITIAL preview warmup settles — set SYNCHRONOUSLY here, before the
        // first dialog-list render, so a hidden chat with a not-yet-loaded exposed preview never flashes empty.
        // Lifted reactively when the warmup (kicked off in the deferred block below) settles. Only the system chat
        // shows in the meantime (isHiddenFromCurrentView exempts it). No hidden-exposed chats → nothing to hold.
        warmupGateActive = hasAnySafePreviewToWarm();
        // We need to track placeholder-id → server-id renames globally, not just while a
        // ChatActivity for that dialog happens to be open. Otherwise: user sends in off
        // mode, exits chat before the round-trip completes, server confirms → ChatActivity
        // is gone → no rename happens, pending set still holds the stale negative id,
        // re-entering the chat in active mode shows the message without the eye badge.
        //
        // addObserver is main-thread-only in DEBUG builds, and getInstance() can be triggered
        // from any thread (push paths, MessagesStorage callbacks, NotificationsController
        // background work). Defer registration to the main thread.
        AndroidUtilities.runOnUIThread(() -> {
            getNotificationCenter().addObserver(this, NotificationCenter.messageReceivedByServer);
            // Drop tracking (exposed, pending, last-exposed cache) for messages that get
            // deleted out from under us. Without this, deleted-from-off-mode messages
            // linger in the dialog cell preview (stale cached MessageObject) until the
            // chat is opened again to repopulate the cache.
            getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
            // Warm the OFF-mode preview cache: whenever ANY chat's messages load (chat open, pagination, or our
            // own dialog-list warmup), capture the ones that are exposed/pending so the dialog cell can render
            // them even after they stop being the dialog's top message. Cheap for non-hidden chats (a couple of
            // map misses), so a blanket observer is fine.
            getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
            // Server-side warmup results: reloadMessages (used by warmSafePreviews on a fresh reinstall, where the
            // local DB is still empty) fetches exposed/pending bodies over the network and broadcasts them here.
            getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
            // Draft deniability on launch (always OFF mode): recover any private-space draft left marked after a
            // kill mid-private-space (no clean PS exit to wipe it). Only wipes drafts genuinely authored in the
            // private space — OFF-mode drafts are never in this set, so they always survive a restart.
            wipePsDrafts();
            // STARTUP warmup: the persisted exposed/pending sets are already loaded (above), so warm their preview
            // bodies NOW — straight from the local DB — instead of waiting for this session's network sync to run
            // applySyncedState (~1s later). On an upgrade/"install over" the bodies are still in the DB from a
            // previous fetch, so the preview fills in immediately on launch rather than flashing empty for seconds.
            if (!dialogIds.isEmpty()) {
                if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                    org.telegram.messenger.FileLog.d("Leemen: startup preview warmup, hiddenChats=" + dialogIds.size()
                            + " gateActive=" + warmupGateActive);
                }
                warmSafePreviews();
                // Edge: the gate was armed (above) but warmup issued no tasks (everything already resolvable) →
                // lift now so the list can't stay stuck hidden.
                if (warmupGateActive && warmupInFlight == 0) liftWarmupGate();
            }
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messageReceivedByServer) {
            Integer oldId = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : null;
            Integer newId = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : null;
            if (oldId == null || newId == null || oldId.equals(newId)) return;
            // Placeholder ids are negative and unique per account, so a single iteration to find which chat
            // (if any) holds the renamed id is fine — there's at most one. Search ALL id-keyed tag sets
            // (exposed + pending + self-pinned), not just pending, or an exposed message would strand.
            Long owningDialog = findDialogHoldingMessage(oldId);
            if (owningDialog != null) {
                replaceMessageId(owningDialog, oldId, newId);
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            // args: (ArrayList<Integer> mids, long channelId, ...). channelId > 0 → channel
            // deletion, dialog_id = -channelId. channelId == 0 → non-channel deletion;
            // non-channel mids are globally unique within a user account, so a single
            // dialog among our tracked set will own each deleted mid.
            @SuppressWarnings("unchecked")
            ArrayList<Integer> mids = args.length > 0 && args[0] instanceof ArrayList ? (ArrayList<Integer>) args[0] : null;
            if (mids == null || mids.isEmpty()) return;
            long channelId = args.length > 1 && args[1] instanceof Long ? (Long) args[1] : 0L;
            if (channelId > 0) {
                purgeDeletedFromTracking(-channelId, mids);
            } else {
                // Walk the union of tracked dialog ids (exposed + pending). Snapshot first
                // to avoid ConcurrentModificationException — purge writes back into the
                // maps via persist*.
                java.util.HashSet<Long> tracked = new java.util.HashSet<>();
                tracked.addAll(exposedMessages.keySet());
                tracked.addAll(pendingMessages.keySet());
                for (Long did : tracked) {
                    purgeDeletedFromTracking(did, mids);
                }
            }
        } else if (id == NotificationCenter.messagesDidLoad) {
            // args: (dialogId, count, ArrayList<MessageObject> objects, isCache, ..., classGuid[10], ...)
            long dialogId = args.length > 0 && args[0] instanceof Long ? (Long) args[0] : 0L;
            int guid = args.length > 10 && args[10] instanceof Integer ? (Integer) args[10] : 0;
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> objects = args.length > 2 && args[2] instanceof ArrayList ? (ArrayList<MessageObject>) args[2] : null;
            boolean any = false;
            if (objects != null) {
                for (int i = 0, n = objects.size(); i < n; i++) {
                    MessageObject mo = objects.get(i);
                    if (mo != null && isSafeMessage(dialogId, mo.getId())) {
                        cacheSafePreview(dialogId, mo);
                        any = true;
                    }
                }
            }
            // Repaint the dialog cell now that its exposed/pending body is resolvable.
            if (any) {
                if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                    org.telegram.messenger.FileLog.d("Leemen: warmup cached body via messagesDidLoad dialog " + dialogId + " (local DB / chat load)");
                }
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            }
            // If this was one of OUR warmup DB loads, settle it: anything still unresolved wasn't in the local DB,
            // so fetch it over the network. Must run even when objects is empty (a DB miss) — that's the case that
            // needs the network. The guid is dedicated to warmup loads, so no other messagesDidLoad matches.
            if (guid != 0 && guid == previewWarmGuid) {
                onWarmupDbLoadSettled(dialogId);
            }
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            // args: (long dialogId, ArrayList<MessageObject> objects, [boolean]). This is how a network refetch
            // (reloadMessages) delivers bodies that weren't in the local DB — the fresh-reinstall path.
            long dialogId = args.length > 0 && args[0] instanceof Long ? (Long) args[0] : 0L;
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> objects = args.length > 1 && args[1] instanceof ArrayList ? (ArrayList<MessageObject>) args[1] : null;
            if (objects == null || objects.isEmpty()) return;
            boolean any = false, newlyAvailable = false;
            for (int i = 0, n = objects.size(); i < n; i++) {
                MessageObject mo = objects.get(i);
                if (mo != null && isSafeMessage(dialogId, mo.getId())) {
                    if (cacheSafePreview(dialogId, mo)) newlyAvailable = true;
                    any = true;
                }
            }
            if (any) getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            // A previously-missing exposed/pending body just arrived from the server — tell an open OFF-mode chat
            // to reload so it shows the message it couldn't read from the (empty) local DB on a fresh reinstall.
            if (newlyAvailable) {
                getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceSyncApplied);
                if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                    org.telegram.messenger.FileLog.d("Leemen: preview warmup cached body dialog " + dialogId
                            + " (server fetch arrived)");
                }
            }
        }
    }

    /** True when {@code messageId} is currently tracked as exposed or pending for {@code dialogId}. */
    private boolean isSafeMessage(long dialogId, int messageId) {
        Set<Integer> ex = exposedMessages.get(dialogId);
        if (ex != null && ex.contains(messageId)) return true;
        Set<Integer> pe = pendingMessages.get(dialogId);
        return pe != null && pe.contains(messageId);
    }

    /** Stash a safe (exposed/pending) message body for the OFF-mode preview. No-op if the message isn't
     *  actually safe for this dialog (so we never cache a hidden message). Returns true when this id was NOT
     *  cached before (a newly-available body), so callers can distinguish a first arrival from a refresh. */
    private boolean cacheSafePreview(long dialogId, MessageObject mo) {
        if (mo == null || mo.getDialogId() != dialogId) return false;
        if (!isSafeMessage(dialogId, mo.getId())) return false;
        Map<Integer, MessageObject> m = safePreviewCache.get(dialogId);
        if (m == null) { m = new HashMap<>(); safePreviewCache.put(dialogId, m); }
        boolean isNew = !m.containsKey(mo.getId());
        m.put(mo.getId(), mo);
        return isNew;
    }

    /** Drop a message id from the preview cache once it is no longer exposed OR pending. Guarded so un-exposing a
     *  message that is still pending (or vice-versa) keeps it cached. Call AFTER mutating the exposed/pending set. */
    private void evictSafePreview(long dialogId, int messageId) {
        if (isSafeMessage(dialogId, messageId)) return; // still surfaced via the other set — keep the body
        Map<Integer, MessageObject> m = safePreviewCache.get(dialogId);
        if (m == null) return;
        m.remove(messageId);
        if (m.isEmpty()) safePreviewCache.remove(dialogId);
    }

    /** While true the OFF-mode list is held fail-closed (only the system chat shows) until the INITIAL preview
     *  warmup settles — so chats never appear with empty previews. Read by
     *  {@link org.telegram.messenger.leemen.LeemenSync#isInitialSyncPending}. OFF-mode only. */
    public boolean isWarmupGateActive() {
        return activeMode == MODE_OFF && warmupGateActive;
    }

    /** Any hidden chat with a positive exposed/pending id — i.e. a preview that must be warmed before it can
     *  render. (At launch nothing is in the in-memory cache yet, so such a preview is by definition not ready.) */
    private boolean hasAnySafePreviewToWarm() {
        for (Long did : dialogIds) {
            if (did != null && getLatestSafeMessageId(did) > 0) return true;
        }
        // Also arm for exposed/pending chats that aren't (yet) in dialogIds — else the gate wouldn't hold for
        // them and they'd render empty (BUG 1) / leak first (BUG 2).
        for (Long did : exposedMessages.keySet()) {
            if (did != null && getLatestSafeMessageId(did) > 0) return true;
        }
        for (Long did : pendingMessages.keySet()) {
            if (did != null && getLatestSafeMessageId(did) > 0) return true;
        }
        return false;
    }

    /** A warmup DB load for {@code dialogId} just finished. Whatever is STILL unresolved wasn't in the local DB →
     *  fetch it over the network (the body is stored to the DB for next launch). Then settle the DB task. */
    private void onWarmupDbLoadSettled(long dialogId) {
        Set<Integer> missing = collectSafeIds(dialogId);
        if (!missing.isEmpty()) {
            // Increment the server task BEFORE settling the DB task so the counter never dips to 0 between them
            // (which would prematurely lift the gate). Body arrives via replaceMessagesObjects → cacheSafePreview.
            warmupInFlight++;
            getMessagesController().reloadMessages(new ArrayList<>(missing), dialogId, this::settleWarmupTask);
            if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                org.telegram.messenger.FileLog.d("Leemen: preview warmup dialog " + dialogId + " DB miss → server fetch " + missing);
            }
        }
        settleWarmupTask();
    }

    /** One warmup task (a DB load or a server fetch) settled. When the last one settles, lift the launch gate. */
    private void settleWarmupTask() {
        if (warmupInFlight > 0) warmupInFlight--;
        if (warmupInFlight == 0) liftWarmupGate();
    }

    /** The initial warmup has settled: reveal the list (lift the gate) and never re-arm for this session, so
     *  mid-session syncs that re-warm a preview don't blank the whole list. */
    private void liftWarmupGate() {
        initialWarmupDone = true;
        if (warmupGateActive) {
            warmupGateActive = false;
            if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                org.telegram.messenger.FileLog.d("Leemen: preview warmup settled — lifting launch gate");
            }
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceSyncApplied);
        }
    }

    /** Drop {@code mids} from {@code dialogId}'s exposed / pending sets. Idempotent —
     *  sets may not contain the ids at all (cheap no-op). Each remove persists + fires
     *  {@code dialogsNeedReload}, so the dialog cell repaints without the now-deleted
     *  message in any tracking surface. */
    private void purgeDeletedFromTracking(long dialogId, ArrayList<Integer> mids) {
        boolean ownsAnyDeleted = false;
        for (int i = 0, n = mids.size(); i < n; i++) {
            int mid = mids.get(i);
            boolean wasTracked = false;
            if (isMessageExposed(dialogId, mid)) {
                unexposeMessage(dialogId, mid);
                wasTracked = true;
            }
            if (isMessagePending(dialogId, mid)) {
                unmarkMessagePending(dialogId, mid);
                wasTracked = true;
            }
            // Remove from Telegram's global cache so the deleted message can't linger as a dialog
            // preview (e.g. in archive after entering PS). ONLY for a mid that was actually tracked
            // (exposed/pending) for THIS hidden dialog. messagesDeleted fans this method out across
            // every tracked dialog, so an unconditional remove here would strip an UNRELATED (non-
            // hidden) chat's just-deleted message out of dialogMessagesByIds before Telegram's own
            // handler reads it to set deleted=true — defeating its delete→preview recompute and
            // leaving the deleted message stuck as that chat's preview until the next message.
            if (wasTracked) {
                ownsAnyDeleted = true;
                getMessagesController().dialogMessagesByIds.remove(mid);
            }
        }
        // Fix up the dialog's top-message reference only for a dialog the private space actually
        // owns (a hidden chat, or one that held a tracked message just deleted). For a non-hidden
        // dialog this is a hard no-op, so Telegram's native deletion flow is left fully intact.
        if (!ownsAnyDeleted && !isInSecondSpace(dialogId)) {
            return;
        }
        // If the deleted message was the dialog's top message, clear stale reference
        // so the cell picks up the correct (previous) message on next layout pass.
        ArrayList<MessageObject> topMessages = getMessagesController().dialogMessage.get(dialogId);
        if (topMessages != null) {
            boolean removed = false;
            for (int i = topMessages.size() - 1; i >= 0; i--) {
                MessageObject mo = topMessages.get(i);
                if (mo != null && mids.contains(mo.getId())) {
                    topMessages.remove(i);
                    removed = true;
                }
            }
            if (removed) {
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            }
        }
    }

    private static void loadHiddenAccounts(Set<Integer> into, String csv, int selfNum) {
        if (TextUtils.isEmpty(csv)) return;
        for (String s : csv.split(",")) {
            try {
                int id = Integer.parseInt(s);
                // Defense in depth: an account can never hide itself.
                if (id != selfNum) {
                    into.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // --- PIN remember-timeout ---
    //
    // After a successful PIN entry, skip prompt for `pinTimeoutMinutes` minutes.
    // 0 = always ask (default). Updated whenever PIN is verified.

    public int getPinTimeoutMinutes() {
        return pinTimeoutMinutes;
    }

    public void setPinTimeoutMinutes(int minutes) {
        pinTimeoutMinutes = Math.max(0, minutes);
        getMessagesController().getMainSettings().edit().putInt(PREF_PIN_TIMEOUT_MIN, pinTimeoutMinutes).apply();
        notifyLeemenSync();
    }

    public boolean isPinPromptSkippable() {
        if (pinTimeoutMinutes <= 0 || pinLastVerifiedAt <= 0) return false;
        long deadlineMs = pinLastVerifiedAt + pinTimeoutMinutes * 60_000L;
        return System.currentTimeMillis() < deadlineMs;
    }

    public void recordPinVerified() {
        pinLastVerifiedAt = System.currentTimeMillis();
        getMessagesController().getMainSettings().edit().putLong(PREF_PIN_LAST_OK_MS, pinLastVerifiedAt).apply();
    }

    public void clearPinVerified() {
        if (pinLastVerifiedAt == 0L) return;
        pinLastVerifiedAt = 0L;
        getMessagesController().getMainSettings().edit().remove(PREF_PIN_LAST_OK_MS).apply();
    }

    /** Whether screenshots / screen-recording are permitted while this account's space is open. */
    public boolean isScreenshotsAllowed() {
        return allowScreenshots;
    }

    public void setScreenshotsAllowed(boolean allowed) {
        if (allowScreenshots == allowed) {
            return;
        }
        allowScreenshots = allowed;
        getMessagesController().getMainSettings().edit().putBoolean(PREF_ALLOW_SCREENSHOTS, allowed).apply();
        notifyLeemenSync();
        // Re-evaluate FLAG_SECURE immediately: the in-space secure flag now depends on this toggle.
        try {
            org.telegram.ui.LaunchActivity la = org.telegram.ui.LaunchActivity.instance;
            if (la != null) la.invalidateFlagSecure();
        } catch (Throwable ignored) {
        }
    }

    private void loadTabSequence(String json) {
        tabSequence.clear();
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                tabSequence.add(new TabStep(obj.getInt("t"), obj.getBoolean("l")));
            }
        } catch (Exception ignored) {
        }
    }

    public java.util.List<TabStep> getTabSequence() {
        return Collections.unmodifiableList(tabSequence);
    }

    public void setTabSequence(java.util.List<TabStep> steps) {
        boolean changed = !sameSequence(tabSequence, steps);
        tabSequence.clear();
        if (steps != null) tabSequence.addAll(steps);
        try {
            JSONArray arr = new JSONArray();
            for (TabStep s : tabSequence) {
                JSONObject o = new JSONObject();
                o.put("t", s.tabIndex);
                o.put("l", s.longPress);
                arr.put(o);
            }
            getMessagesController().getMainSettings().edit().putString(PREF_TAB_SEQUENCE, arr.toString()).apply();
        } catch (Exception ignored) {
        }
        // Entry method changed → user must re-verify before the entry button can be hidden.
        if (changed) {
            clearShortcutTested();
            // Tier-P: propagate the gesture to same-platform devices. Suppressed while applying a remote sync.
            notifyLeemenSync();
        }
    }

    public static boolean sameSequence(java.util.List<TabStep> a, java.util.List<TabStep> b) {
        int an = a == null ? 0 : a.size();
        int bn = b == null ? 0 : b.size();
        if (an != bn) return false;
        for (int i = 0; i < an; i++) {
            TabStep sa = a.get(i);
            TabStep sb = b.get(i);
            if (sa.tabIndex != sb.tabIndex || sa.longPress != sb.longPress) return false;
        }
        return true;
    }

    public boolean isPinInSearchEnabled() {
        return pinInSearchEnabled;
    }

    public void setPinInSearchEnabled(boolean value) {
        boolean changed = pinInSearchEnabled != value;
        pinInSearchEnabled = value;
        getMessagesController().getMainSettings().edit().putBoolean(PREF_PIN_IN_SEARCH, value).apply();
        if (changed) {
            clearShortcutTested();
        }
    }

    /**
     * True once the user has successfully entered Private Space via a configured shortcut
     * (tap-sequence or PIN-in-search) at least once. Used to keep the explicit Settings
     * entry button visible as a fallback until the shortcut is verified working.
     */
    public boolean isShortcutTested() {
        return shortcutTested;
    }

    public void markShortcutTested() {
        if (!shortcutTested) {
            shortcutTested = true;
            getMessagesController().getMainSettings().edit().putBoolean(PREF_SHORTCUT_TESTED, true).apply();
        }
    }

    /**
     * Reset the "verified" flag — called whenever entry methods change (PIN set/removed,
     * sequence updated, PIN-in-search toggled). Also forces the raw entry-button-visible
     * flag back to ON so the settings toggle stays in sync with what the user actually sees
     * (the button must remain reachable until the new method is verified). Fires
     * {@link NotificationCenter#secondSpaceModeChanged} so dependent UI rebinds.
     * Returns true if anything actually changed.
     */
    public boolean clearShortcutTested() {
        boolean changed = false;
        SharedPreferences.Editor editor = getMessagesController().getMainSettings().edit();
        if (shortcutTested) {
            shortcutTested = false;
            editor.putBoolean(PREF_SHORTCUT_TESTED, false);
            changed = true;
        }
        if (!entryButtonVisible) {
            entryButtonVisible = true;
            editor.putBoolean(PREF_SHOW_ENTRY_BUTTON, true);
            changed = true;
        }
        if (changed) {
            editor.apply();
            getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceModeChanged);
        }
        return changed;
    }

    public boolean hasConfiguredShortcut() {
        return !tabSequence.isEmpty() || pinInSearchEnabled;
    }

    // --- Password (PIN) ---

    public boolean hasPassword() {
        return PIN_SET.equals(psPinState);
    }

    public boolean verifyPassword(String pin) {
        if (!PIN_SET.equals(psPinState)) return true; // no PIN set → nothing to verify against
        if (pin == null) return false;
        byte[] salt = b64decode(psPinSalt);
        byte[] expected = b64decode(psPinHash);
        if (salt == null || expected == null) return false;
        byte[] pinBytes = pin.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] got = org.telegram.messenger.leemen.LeemenCrypto.argon2id(pinBytes, salt);
        java.util.Arrays.fill(pinBytes, (byte) 0);
        boolean ok = got != null && java.security.MessageDigest.isEqual(got, expected);
        if (got != null) java.util.Arrays.fill(got, (byte) 0);
        return ok;
    }

    public void setPassword(String pin) {
        setRealPassword(pin);
    }

    public boolean hasRealPassword() {
        return PIN_SET.equals(psPinState);
    }

    public void setRealPassword(String pin) {
        boolean had = PIN_SET.equals(psPinState);
        if (TextUtils.isEmpty(pin)) {
            psPinState = PIN_NONE;
            psPinHash = "";
            psPinSalt = "";
            persistPsPin();
            clearPinVerified();
            if (had) clearShortcutTested();
            notifyLeemenSync();
            return;
        }
        byte[] pinBytes = pin.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] salt = org.telegram.messenger.leemen.LeemenCrypto.randomSalt();
        byte[] hash = org.telegram.messenger.leemen.LeemenCrypto.argon2id(pinBytes, salt);
        java.util.Arrays.fill(pinBytes, (byte) 0);
        if (hash == null) {
            return; // crypto unavailable — keep the existing PIN rather than silently dropping it
        }
        String newHash = b64(hash);
        String newSalt = b64(salt);
        java.util.Arrays.fill(hash, (byte) 0);
        java.util.Arrays.fill(salt, (byte) 0);
        boolean changed = !had || !newHash.equals(psPinHash);
        psPinState = PIN_SET;
        psPinHash = newHash;
        psPinSalt = newSalt;
        persistPsPin();
        clearPinVerified();
        if (changed) clearShortcutTested();
        notifyLeemenSync();
    }

    /** Apply a PS-PIN register projected from a synced blob (§7.2). Guarded so it doesn't echo back as a
     *  push. A WELL-FORMED {@code "set"} (32-byte hash, 16-byte salt) stores the synced material; an
     *  explicit {@code "none"} clears the PIN. A MALFORMED {@code "set"} (torn/partial blob) is IGNORED —
     *  we must NOT collapse it to "none", or the next reconcile would tombstone the PIN on every device. */
    public void applySyncedPin(String state, String hashB64, String saltB64) {
        boolean wellFormedSet = PIN_SET.equals(state) && isWellFormedPinMaterial(hashB64, saltB64);
        if (!wellFormedSet && !PIN_NONE.equals(state)) {
            return; // malformed 'set' or unknown state → leave the local PIN untouched
        }
        applyingRemoteSync = true;
        try {
            if (wellFormedSet) {
                psPinState = PIN_SET;
                psPinHash = hashB64;
                psPinSalt = saltB64;
            } else {
                psPinState = PIN_NONE;
                psPinHash = "";
                psPinSalt = "";
            }
            persistPsPin();
            clearPinVerified();
        } finally {
            applyingRemoteSync = false;
        }
    }

    /** True iff the base64 hash/salt decode to the exact Argon2id lengths (32 / 16). Keeps malformed
     *  material out of the persisted 'set' state, which would otherwise be unverifiable (a hard lockout). */
    private static boolean isWellFormedPinMaterial(String hashB64, String saltB64) {
        byte[] h = b64decode(hashB64);
        byte[] s = b64decode(saltB64);
        return h != null && h.length == org.telegram.messenger.leemen.LeemenCrypto.PIN_HASH_BYTES
                && s != null && s.length == org.telegram.messenger.leemen.LeemenCrypto.SALT_BYTES;
    }

    /** PS-PIN register for sync reconcile. State {@code ""} means never enrolled — callers must NOT push
     *  it, so a device that never set a PIN can't clobber one synced from elsewhere. */
    public String getPsPinState() { return psPinState; }
    public String getPsPinHashB64() { return psPinHash; }
    public String getPsPinSaltB64() { return psPinSalt; }

    private void persistPsPin() {
        getMessagesController().getMainSettings().edit()
                .putString(PREF_PS_PIN_STATE, psPinState)
                .putString(PREF_PS_PIN_HASH, psPinHash)
                .putString(PREF_PS_PIN_SALT, psPinSalt)
                .apply();
    }

    private static String b64(byte[] b) {
        return b == null ? "" : android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    private static byte[] b64decode(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            return android.util.Base64.decode(s, android.util.Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hashPassword(String pin) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(pin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return pin;
        }
    }

    private static void loadDialogIds(Set<Long> into, String csv) {
        if (TextUtils.isEmpty(csv)) return;
        for (String s : csv.split(",")) {
            try {
                into.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void loadExposed(String json) {
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long dialogId = Long.parseLong(key);
                JSONArray arr = obj.getJSONArray(key);
                Set<Integer> set = new HashSet<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    set.add(arr.getInt(i));
                }
                if (!set.isEmpty()) {
                    exposedMessages.put(dialogId, set);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void loadLastDecided(String json) {
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                lastDecidedMessageId.put(Long.parseLong(key), obj.getInt(key));
            }
        } catch (Exception ignored) {
        }
    }

    // --- Chat membership ---

    public boolean isInSecondSpace(long dialogId) {
        return dialogIds.contains(dialogId);
    }

    public boolean isInRealSpace(long dialogId) {
        return dialogIds.contains(dialogId);
    }

    /**
     * True when a read action for {@code dialogId} must be suppressed: a private-space chat viewed
     * from the off-mode (deniable) view. Peeking at an exposed message in off mode must not consume
     * the chat's unread state — the unread badge has to survive into the private space — and must not
     * send a read receipt that would leak "I read this" to the sender. In the privileged private-space
     * view (MODE_REAL) this returns false, so reads there behave normally.
     *
     * Deliberately narrower than {@link #isHiddenFromCurrentView}: it does NOT fire on the
     * initial-sync fail-closed window, so reads of ordinary (non-private-space) chats during startup
     * are never swallowed.
     */
    public boolean isReadSuppressed(long dialogId) {
        return activeMode == MODE_OFF && dialogIds.contains(dialogId);
    }

    /** True iff {@code dialogId} is hidden from whichever view the user currently sees.
     *  MODE_REAL sees everything; MODE_OFF hides private-space chats. */
    public boolean isHiddenFromCurrentView(long dialogId) {
        // The Telegram service chat (login codes; 777000 et al.) is ALWAYS visible and can never be hidden —
        // a Leemen-backend failure must never break the login-code delivery flow.
        if (UserObject.isService(dialogId)) {
            return false;
        }
        switch (activeMode) {
            case MODE_REAL:
                return false;
            case MODE_OFF:
            default:
                // FAIL-CLOSED: hide every non-system chat until the Leemen server has conclusively confirmed
                // the hidden set this session. A server-hidden chat must NEVER appear — not even briefly, and
                // not if the backend is unreachable (then only the service chat shows, by design).
                if (org.telegram.messenger.leemen.LeemenSync.isInitialSyncPending(currentAccount)) {
                    return true;
                }
                return dialogIds.contains(dialogId);
        }
    }

    /** Visible in the CURRENT OFF-mode list? Use this for list/preview filtering instead of open-coding
     *  {@code !isHiddenFromCurrentView || hasExposedMessages || hasPendingOffModeWork} — those OR clauses must
     *  obey the fail-closed gate too, or the most sensitive row (an exposed chat) would be the ONE left visible
     *  during the pre-sync/warmup window while innocent chats are correctly hidden (deniability inversion).
     *  FAIL-CLOSED: while the OFF-mode initial-sync/warmup gate is up, ONLY the service chat shows — exposed and
     *  pending chats are hidden too. Once it lifts, exposed/pending chats stay reachable exactly as before. */
    public boolean isVisibleInCurrentView(long dialogId) {
        if (activeMode == MODE_OFF && org.telegram.messenger.leemen.LeemenSync.isInitialSyncPending(currentAccount)) {
            return UserObject.isService(dialogId);
        }
        return !isHiddenFromCurrentView(dialogId)
                || hasExposedMessages(dialogId)
                || hasPendingOffModeWork(dialogId);
    }

    /** Record the authoring context of a freshly-saved dialog-level draft. A draft typed inside the private
     *  space (MODE_REAL) on a hidden chat is marked for wipe-on-exit; any other save (OFF mode, emptied, or a
     *  non-hidden chat) clears the mark so the draft is kept. Called from {@link MediaDataController#saveDraft}
     *  on the user-typed path only. */
    public void onDraftAuthored(long dialogId, boolean nonEmpty) {
        boolean changed;
        if (activeMode == MODE_REAL && nonEmpty && dialogIds.contains(dialogId)) {
            // Secret draft written inside the private space → wipe it when leaving the space.
            changed = psDraftDialogs.add(dialogId);
        } else {
            // OFF-mode draft (keep), an emptied draft, or a non-hidden chat → nothing to wipe.
            changed = psDraftDialogs.remove(dialogId);
        }
        if (changed) {
            persistLongCsv(psDraftDialogs, PREF_PS_DRAFTS);
        }
    }

    /** True while a draft save for {@code dialogId} is happening inside the private space (MODE_REAL) on a
     *  hidden chat. Such drafts are kept ON-DEVICE ONLY — never pushed to the Telegram server — so they don't
     *  sync to other devices and can't outlive the private-space session. OFF-mode drafts sync normally. */
    public boolean isPrivateSpaceLocalDraft(long dialogId) {
        return activeMode == MODE_REAL && dialogIds.contains(dialogId);
    }

    /** Wipe (locally) every draft authored inside the private space so it can't surface in the OFF-mode list.
     *  Private-space drafts are never pushed to the server (see {@link #isPrivateSpaceLocalDraft}), so a LOCAL
     *  clear is enough — and it must NOT push an empty draft, which would clobber a legitimate OFF-mode draft
     *  the server still holds for the same chat. Safe to call when the set is empty. */
    private void wipePsDrafts() {
        if (psDraftDialogs.isEmpty()) {
            return;
        }
        java.util.List<Long> toWipe = new java.util.ArrayList<>(psDraftDialogs);
        psDraftDialogs.clear();
        persistLongCsv(psDraftDialogs, PREF_PS_DRAFTS);
        for (Long did : toWipe) {
            if (did == null) continue;
            getMediaDataController().cleanDraft(did, 0, false); // local-only; never on the server
        }
    }

    public void addToSecondSpace(long dialogId) {
        addToCurrentSpace(dialogId);
    }

    public void removeFromSecondSpace(long dialogId) {
        if (dialogIds.remove(dialogId)) {
            exposedMessages.remove(dialogId);
            lastDecidedMessageId.remove(dialogId);
            pendingMessages.remove(dialogId);
            selfPinnedMessages.remove(dialogId);
            if (psDraftDialogs.remove(dialogId)) {
                persistLongCsv(psDraftDialogs, PREF_PS_DRAFTS);
            }
            persistDialogIds();
            persistExposed();
            persistLastDecided();
            persistPendingMessages();
            persistSelfPinned();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    /** Add {@code dialogId} to the private space chat list. */
    public void addToCurrentSpace(long dialogId) {
        if (UserObject.isService(dialogId)) {
            return; // the Telegram service chat (login codes) can never be hidden
        }
        if (dialogIds.add(dialogId)) {
            persistDialogIds();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void removeFromCurrentSpace(long dialogId) {
        if (dialogIds.remove(dialogId)) {
            exposedMessages.remove(dialogId);
            lastDecidedMessageId.remove(dialogId);
            pendingMessages.remove(dialogId);
            selfPinnedMessages.remove(dialogId);
            if (psDraftDialogs.remove(dialogId)) {
                persistLongCsv(psDraftDialogs, PREF_PS_DRAFTS);
            }
            persistDialogIds();
            persistExposed();
            persistLastDecided();
            persistPendingMessages();
            persistSelfPinned();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    /** All private-space chat ids. */
    public Set<Long> getDialogIds() {
        return Collections.unmodifiableSet(dialogIds);
    }

    // --- Leemen sync (Phase 3): bulk accessors + merged-state projection ---

    public Set<Integer> getSelfPinnedMessageIds(long dialogId) {
        Set<Integer> s = selfPinnedMessages.get(dialogId);
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    public Set<Long> getPrivateSearchDialogIds() {
        return new HashSet<>(privateSearchDialogs);
    }

    /** Replace Core PS state from a merged sync blob (LeemenSync projection): persist + one reload,
     *  guarded so these writes don't re-trigger a push. PIN is NOT synced yet (§7.2 migration). */
    public void applySyncedState(Set<Long> members,
                                 Map<Long, Set<Integer>> exposed,
                                 Map<Long, Set<Integer>> pending,
                                 Map<Long, Set<Integer>> selfPinned,
                                 Set<Long> privateSearch,
                                 Integer pinTimeout,
                                 Boolean allowScreenshotsVal,
                                 java.util.List<TabStep> tabSeq) {
        applyingRemoteSync = true;
        try {
            // Tier-P: apply our platform's synced tab gesture under the SAME guard (no spurious back-push).
            if (tabSeq != null && !sameSequence(tabSequence, tabSeq)) {
                setTabSequence(tabSeq);
            }
            dialogIds.clear();
            if (members != null) {
                for (Long id : members) {
                    if (!UserObject.isService(id)) dialogIds.add(id); // service chat is never hidden
                }
            }
            replaceMsgMap(exposedMessages, exposed);
            replaceMsgMap(pendingMessages, pending);
            replaceMsgMap(selfPinnedMessages, selfPinned);
            privateSearchDialogs.clear();
            if (privateSearch != null) privateSearchDialogs.addAll(privateSearch);
            lastDecidedMessageId.keySet().retainAll(dialogIds);
            // PS-draft wipe marks are local (never synced); drop marks for chats no longer hidden.
            if (psDraftDialogs.retainAll(dialogIds)) {
                persistLongCsv(psDraftDialogs, PREF_PS_DRAFTS);
            }
            if (pinTimeout != null) pinTimeoutMinutes = Math.max(0, pinTimeout);
            if (allowScreenshotsVal != null) allowScreenshots = allowScreenshotsVal;
            persistDialogIds();
            persistExposed();
            persistPendingMessages();
            persistSelfPinned();
            persistLastDecided();
            persistPrivateSearches();
            getMessagesController().getMainSettings().edit()
                    .putInt(PREF_PIN_TIMEOUT_MIN, pinTimeoutMinutes)
                    .putBoolean(PREF_ALLOW_SCREENSHOTS, allowScreenshots)
                    .apply();
        } finally {
            applyingRemoteSync = false;
        }
        // Drop preview-cache entries that are no longer exposed/pending after this apply.
        pruneSafePreviewCache();
        // Arm the launch gate on the FIRST sync that brings hidden chats (covers fresh install, where the
        // constructor saw no persisted set yet). Set BEFORE the reload below so the list is already held — not
        // re-armed after the initial warmup, so mid-session syncs that re-warm a preview don't blank the list.
        if (!initialWarmupDone && hasAnySafePreviewToWarm()) warmupGateActive = true;
        // Reactive warmup: load the exposed/pending bodies that aren't resolvable yet — DB-first, then network for
        // whatever the DB is missing. Each task lands via messagesDidLoad / replaceMessagesObjects → cacheSafePreview
        // → a fresh dialogsNeedReload, so the preview fills in exactly when the data is ready. Run BEFORE the reload
        // below so it already sees the gate as held. Works even when the exposed message isn't the dialog's top.
        warmSafePreviews();
        // Edge: armed but nothing to warm (all already resolvable) → lift now so the list isn't stuck hidden.
        if (warmupGateActive && warmupInFlight == 0) liftWarmupGate();
        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            int ex = 0, pe = 0;
            for (Set<Integer> s : exposedMessages.values()) ex += s.size();
            for (Set<Integer> s : pendingMessages.values()) pe += s.size();
            org.telegram.messenger.FileLog.d("Leemen: applySyncedState members=" + dialogIds.size()
                    + " exposedMsgs=" + ex + " pendingMsgs=" + pe + " warmupInFlight=" + warmupInFlight);
            for (Long did : dialogIds) {
                int lid = getLatestExposedMessageId(did);
                if (lid != 0) {
                    org.telegram.messenger.FileLog.d("Leemen: PS dialog " + did + " latestExposedId=" + lid
                            + " cached=" + (resolveMessageFromCache(did, lid) != null));
                }
            }
        }
        // Tell any OPEN OFF-mode chat that the synced exposed/pending set changed so it re-filters its messages.
        // Narrow event (only ChatActivity listens) — deliberately NOT secondSpaceModeChanged, which would churn
        // the whole mode-transition UI (tabs, settings, shared-media) on every sync.
        getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceSyncApplied);
    }

    /** Load the exposed/pending message bodies for every hidden dialog from the local DB, so the OFF-mode dialog
     *  preview can render them without waiting for (or depending on) Telegram's top-message cache. Skips ids that
     *  already resolve (cheap idempotent re-sync). Results arrive via messagesDidLoad → {@link #cacheSafePreview}. */
    /** Warm the OFF-mode preview bodies for every hidden dialog, DB-FIRST. Each dialog's unresolved exposed/pending
     *  ids are loaded from the local DB (instant, offline); whatever the DB doesn't have is then fetched over the
     *  network (see {@link #onWarmupDbLoadSettled}) and stored for next launch. Every task is counted so the launch
     *  gate lifts reactively when the last one settles. Idempotent — already-resolvable ids are skipped. */
    private void warmSafePreviews() {
        if (previewWarmGuid == 0) previewWarmGuid = getConnectionsManager().generateClassGuid();
        // Warm dialogIds ∪ exposed ∪ pending — so a warm task is actually scheduled for exposed/pending chats
        // and the gate (armed by hasAnySafePreviewToWarm) has something to settle on for them.
        Set<Long> dialogsToWarm = new HashSet<>(dialogIds);
        dialogsToWarm.addAll(exposedMessages.keySet());
        dialogsToWarm.addAll(pendingMessages.keySet());
        for (Long did : dialogsToWarm) {
            if (did == null) continue;
            Set<Integer> ids = collectSafeIds(did);
            if (ids.isEmpty()) continue;
            int[] arr = new int[ids.size()];
            int i = 0;
            for (Integer id : ids) arr[i++] = id;
            warmupInFlight++; // DB load task — its messagesDidLoad settles it (and triggers the server fallback)
            getMessagesController().loadExposedMessages(did, arr, previewWarmGuid, 0);
            if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                org.telegram.messenger.FileLog.d("Leemen: preview warmup dialog " + did + " ids=" + ids
                        + " (DB first), inFlight=" + warmupInFlight);
            }
        }
    }

    /** Exposed ∪ pending ids for {@code dialogId} that aren't resolvable yet (positive ids only — negative ids are
     *  local in-flight placeholders, not in the DB). Empty when everything already resolves. */
    private Set<Integer> collectSafeIds(long dialogId) {
        Set<Integer> out = new HashSet<>();
        Set<Integer> ex = exposedMessages.get(dialogId);
        if (ex != null) out.addAll(ex);
        Set<Integer> pe = pendingMessages.get(dialogId);
        if (pe != null) out.addAll(pe);
        out.removeIf(id -> id == null || id <= 0 || resolveMessageFromCache(dialogId, id) != null);
        return out;
    }

    /** Forget cached bodies for ids that are no longer exposed/pending (or whole dialogs no longer tracked). */
    private void pruneSafePreviewCache() {
        java.util.Iterator<Map.Entry<Long, Map<Integer, MessageObject>>> it = safePreviewCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Map<Integer, MessageObject>> e = it.next();
            long did = e.getKey();
            e.getValue().keySet().removeIf(id -> id == null || !isSafeMessage(did, id));
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    private static void replaceMsgMap(Map<Long, Set<Integer>> target, Map<Long, Set<Integer>> src) {
        // Preserve LOCAL in-flight placeholders (negative message ids). They are deliberately NOT in the
        // synced blob (§4b never syncs negative ids), so a wholesale replace from the blob would wipe a
        // just-sent, not-yet-server-confirmed message out of the OFF-mode view — making the first pending
        // message vanish whenever any sync projects (e.g. when leaving the chat triggers an id migration).
        Map<Long, Set<Integer>> keepNegatives = new HashMap<>();
        for (Map.Entry<Long, Set<Integer>> e : target.entrySet()) {
            Set<Integer> negs = null;
            for (Integer mid : e.getValue()) {
                if (mid != null && mid < 0) {
                    if (negs == null) negs = new HashSet<>();
                    negs.add(mid);
                }
            }
            if (negs != null) keepNegatives.put(e.getKey(), negs);
        }
        target.clear();
        if (src != null) {
            for (Map.Entry<Long, Set<Integer>> e : src.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    target.put(e.getKey(), new HashSet<>(e.getValue()));
                }
            }
        }
        for (Map.Entry<Long, Set<Integer>> e : keepNegatives.entrySet()) {
            Set<Integer> dst = target.get(e.getKey());
            if (dst == null) {
                dst = new HashSet<>();
                target.put(e.getKey(), dst);
            }
            dst.addAll(e.getValue());
        }
    }

    /** Local PS-state change → schedule a debounced sync push (no-op while applying remote state). */
    private void notifyLeemenSync() {
        if (applyingRemoteSync) return;
        try {
            org.telegram.messenger.leemen.LeemenSync.onLocalMutation(currentAccount);
        } catch (Throwable ignored) {
        }
    }

    /** Wipe ALL local private-space data for this account (every second_space_* pref + in-memory state).
     *  Used by the "delete account &amp; data" flow. Guarded so the cascade of clears doesn't echo back as
     *  a sync push. Does NOT touch the Telegram account. */
    public void wipeAllLocalData() {
        applyingRemoteSync = true;
        try {
            android.content.SharedPreferences prefs = getMessagesController().getMainSettings();
            android.content.SharedPreferences.Editor e = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                if (key != null && key.startsWith("second_space_")) {
                    e.remove(key);
                }
            }
            e.apply();
            dialogIds.clear();
            exposedMessages.clear();
            pendingMessages.clear();
            selfPinnedMessages.clear();
            psDraftDialogs.clear();
            safePreviewCache.clear();
            warmupGateActive = false;
            initialWarmupDone = false;
            warmupInFlight = 0;
            lastDecidedMessageId.clear();
            privateSearchDialogs.clear();
            hiddenAccounts.clear();
            tabSequence.clear();
            psPinState = "";
            psPinHash = "";
            psPinSalt = "";
            switchPasswordHash = "";
            pinInSearchEnabled = false;
            shortcutTested = false;
            pinTimeoutMinutes = 0;
            pinLastVerifiedAt = 0;
            allowScreenshots = false;
            leemenPremiumUntil = 0;
            activeMode = MODE_OFF;
        } finally {
            applyingRemoteSync = false;
        }
        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    /** Alias for {@link #getDialogIds()}. */
    public Set<Long> getRealSpaceDialogIds() {
        return Collections.unmodifiableSet(dialogIds);
    }

    /** Chats in the currently-active space (for settings UI). */
    public Set<Long> getCurrentSpaceDialogIds() {
        return Collections.unmodifiableSet(dialogIds);
    }

    // --- Mode ---

    public boolean isActive() {
        return activeMode != MODE_OFF;
    }

    public boolean isRealActive() {
        return activeMode == MODE_REAL;
    }

    public int getActiveMode() {
        return activeMode;
    }

    /** Toggle: {@code true} → {@link #MODE_REAL}, {@code false} → {@link #MODE_OFF}. */
    public void setActive(boolean value) {
        setActiveMode(value ? MODE_REAL : MODE_OFF);
    }

    public void setActiveMode(int mode) {
        if (mode != MODE_OFF && mode != MODE_REAL) {
            return;
        }
        int oldMode = activeMode;
        if (oldMode == mode) {
            return;
        }
        activeMode = mode;
        // Intentionally NOT persisted: activeMode is per-session, always MODE_OFF on next launch.
        // Leaving the private space: wipe any draft authored inside it so a secret draft can't show in OFF mode.
        if (oldMode == MODE_REAL && mode == MODE_OFF) {
            wipePsDrafts();
        }
        // Re-evaluate FLAG_SECURE: snapshot capture / screenshots must be blocked while PS is on.
        try {
            org.telegram.ui.LaunchActivity la = org.telegram.ui.LaunchActivity.instance;
            if (la != null) la.invalidateFlagSecure();
        } catch (Throwable ignored) {
        }
        NotificationCenter nc = getNotificationCenter();
        nc.postNotificationName(NotificationCenter.secondSpaceModeChanged);
        // Saved Messages surfaces filter by each saved copy's SOURCE peer (see isSavedSourceSuppressed), but
        // SavedMessagesController.allDialogs is a mode-dependent cache built at load time. Rebuild it on the
        // mode flip so hidden saved sub-dialogs are re-hidden (OFF) / restored (REAL) without an app restart;
        // this re-broadcasts savedMessagesDialogsUpdate so the saved-dialogs list + Saved tabs refresh.
        try {
            getMessagesController().getSavedMessagesController().updateAllDialogs(true);
        } catch (Throwable ignored) {
        }
        nc.postNotificationName(NotificationCenter.dialogsNeedReload);
        // When this account is the selected one, the set of hidden other-accounts depends
        // on which mode we're in. Compute the delta and adjust tray notifications + badge.
        if (currentAccount == UserConfig.selectedAccount) {
            Set<Integer> oldHidden = activeHiddenAccountsForMode(oldMode);
            Set<Integer> newHidden = activeHiddenAccountsForMode(activeMode);
            if (!oldHidden.equals(newHidden)) {
                for (Integer acc : oldHidden) {
                    if (!newHidden.contains(acc)) {
                        NotificationsController.getInstance(acc).showNotifications();
                    }
                }
                for (Integer acc : newHidden) {
                    if (!oldHidden.contains(acc)) {
                        NotificationsController.getInstance(acc).hideNotifications();
                    }
                }
                NotificationsController.getInstance(currentAccount).updateBadge();
            }
        }
    }

    /** Set of OTHER accounts to hide from the device's UI when this account is in {@code mode}.
     *  MODE_REAL is privileged (sees everything); MODE_OFF hides the accounts in the hide list. */
    private Set<Integer> activeHiddenAccountsForMode(int mode) {
        if (mode == MODE_REAL) {
            return Collections.emptySet();
        }
        return new HashSet<>(hiddenAccounts);
    }

    // --- Entry button visibility ---

    public boolean isEntryButtonVisible() {
        return entryButtonVisible;
    }

    /**
     * Toggle visibility of the explicit «Enter Private Space» button in main Settings.
     * Invariants:
     *   - Hide allowed only when a shortcut (tap-sequence or PIN-in-search) is configured
     *     AND the user has successfully entered Private Space via that shortcut at least once.
     *   - Until tested, the button stays visible as a fallback even if the user toggles it off.
     * Returns true on successful state change, false if invariant blocks.
     */
    public boolean setEntryButtonVisible(boolean value) {
        if (entryButtonVisible == value) {
            return true;
        }
        if (!value && !canHideEntryButton()) {
            return false;
        }
        entryButtonVisible = value;
        getMessagesController().getMainSettings().edit().putBoolean(PREF_SHOW_ENTRY_BUTTON, value).apply();
        getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceModeChanged);
        return true;
    }

    public boolean canHideEntryButton() {
        return hasConfiguredShortcut() && shortcutTested;
    }

    /** Effective visibility, taking the «testing required» override into account. */
    public boolean isEntryButtonEffectivelyVisible() {
        // Force visible whenever the user can't legitimately hide it.
        return entryButtonVisible || !canHideEntryButton();
    }

    /** Deprecated alias — kept for the older Settings UI path that still calls it. */
    public boolean hasEnterShortcutConfigured() {
        return hasConfiguredShortcut();
    }

    // --- Per-message exposure ---

    public boolean isMessageExposed(long dialogId, int messageId) {
        Set<Integer> set = exposedMessages.get(dialogId);
        return set != null && set.contains(messageId);
    }

    public boolean hasExposedMessages(long dialogId) {
        Set<Integer> set = exposedMessages.get(dialogId);
        return set != null && !set.isEmpty();
    }

    public Set<Integer> getExposedMessageIds(long dialogId) {
        Set<Integer> set = exposedMessages.get(dialogId);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    /** Highest message id currently marked exposed for {@code dialogId}, or 0 when none.
     *  Returned as a bare integer — callers wanting the actual {@link MessageObject}
     *  should resolve it through {@link #resolveLatestExposedPreview(long)} or directly
     *  via {@code MessagesController.dialogMessagesByIds}, not through any PS-specific
     *  cache (we intentionally don't hold message bodies). */
    public int getLatestExposedMessageId(long dialogId) {
        Set<Integer> set = exposedMessages.get(dialogId);
        if (set == null || set.isEmpty()) return 0;
        int max = Integer.MIN_VALUE;
        for (Integer id : set) {
            if (id != null && id > max) max = id;
        }
        return max == Integer.MIN_VALUE ? 0 : max;
    }

    /** Highest message id from either exposed or pending sets, or 0 when none. */
    public int getLatestSafeMessageId(long dialogId) {
        int exposed = getLatestExposedMessageId(dialogId);
        Set<Integer> pset = pendingMessages.get(dialogId);
        int pending = 0;
        if (pset != null) {
            for (Integer id : pset) {
                if (id != null && id > pending) pending = id;
            }
        }
        return Math.max(exposed, pending);
    }

    /** Resolve the latest-exposed {@link MessageObject} for {@code dialogId} by looking
     *  it up in Telegram's per-account {@code dialogMessagesByIds} cache. PS code never
     *  stores message bodies itself — this is a pure read of state that exists for
     *  reasons unrelated to private space.
     *
     *  Returns {@code null} when (a) no exposed ids tracked, (b) the cache has been
     *  evicted (e.g. app restart, never-opened-in-this-session chat), or (c) channel
     *  message-id collision lands on a message in a different dialog. Callers must
     *  tolerate {@code null} as a "no preview right now" signal. */
    public MessageObject resolveLatestExposedPreview(long dialogId) {
        int id = getLatestExposedMessageId(dialogId);
        if (id == 0) return null;
        return resolveMessageFromCache(dialogId, id);
    }

    public MessageObject resolveLatestPendingPreview(long dialogId) {
        Set<Integer> set = pendingMessages.get(dialogId);
        if (set == null || set.isEmpty()) return null;
        int max = Integer.MIN_VALUE;
        for (Integer id : set) {
            if (id != null && id > max) max = id;
        }
        if (max == Integer.MIN_VALUE) return null;
        return resolveMessageFromCache(dialogId, max);
    }

    /** Resolve the latest safe (exposed or pending) preview for a hidden dialog. */
    public MessageObject resolveLatestSafePreview(long dialogId) {
        MessageObject exposed = resolveLatestExposedPreview(dialogId);
        MessageObject pending = resolveLatestPendingPreview(dialogId);
        if (exposed == null) return pending;
        if (pending == null) return exposed;
        return pending.getId() > exposed.getId() ? pending : exposed;
    }

    private MessageObject resolveMessageFromCache(long dialogId, int id) {
        // Prefer Telegram's top-message cache (freshest), and opportunistically mirror the hit into our own
        // cache so the preview keeps resolving after this message stops being the dialog's top message.
        MessageObject mo = getMessagesController().dialogMessagesByIds.get(id);
        if (mo != null && mo.getDialogId() == dialogId) {
            cacheSafePreview(dialogId, mo);
            return mo;
        }
        // Fallback: the exposed/pending message is no longer (or not yet) the dialog's top message, so it was
        // evicted from / never present in dialogMessagesByIds. Resolve from our warmed safe-preview cache.
        Map<Integer, MessageObject> m = safePreviewCache.get(dialogId);
        MessageObject cached = m != null ? m.get(id) : null;
        if (cached != null && cached.getDialogId() == dialogId) return cached;
        return null;
    }

    // --- One-shot UX hints ---

    public boolean isEyeHintShown() {
        return getMessagesController().getMainSettings().getBoolean(PREF_EYE_HINT_SHOWN, false);
    }

    public void markEyeHintShown() {
        getMessagesController().getMainSettings().edit().putBoolean(PREF_EYE_HINT_SHOWN, true).apply();
    }

    public boolean isExposureToolbarHintShown() {
        return getMessagesController().getMainSettings().getBoolean(PREF_TOOLBAR_HINT_SHOWN, false);
    }

    public void markExposureToolbarHintShown() {
        getMessagesController().getMainSettings().edit().putBoolean(PREF_TOOLBAR_HINT_SHOWN, true).apply();
    }

    // --- First-run onboarding & paywall (one-shot) ---

    /** True once the user has hidden their first chat (or is a pre-onboarding existing user).
     *  Gates the first-hide coach-marks (shown only while this is false). */
    public boolean isOnboardingDone() {
        return getMessagesController().getMainSettings().getBoolean(PREF_ONBOARDING_DONE, false);
    }

    public void markOnboardingDone() {
        markOnboardingDone("first_hide");
    }

    /** @param lastStep how onboarding completed (e.g. "settings_tour" or "first_hide"), for analytics. */
    public void markOnboardingDone(String lastStep) {
        boolean was = isOnboardingDone();
        getMessagesController().getMainSettings().edit().putBoolean(PREF_ONBOARDING_DONE, true).apply();
        if (!was) {
            org.telegram.messenger.leemen.LeemenAnalytics.track("onboarding_completed", java.util.Collections.singletonMap("last_step", lastStep));
        }
    }

    /** True once the "unlimited hidden chats" offer has been shown. Shown at most once,
     *  on a re-entry into the space after the first chat was hidden. */
    public boolean isPaywallShown() {
        return getMessagesController().getMainSettings().getBoolean(PREF_PAYWALL_SHOWN, false);
    }

    public void markPaywallShown() {
        getMessagesController().getMainSettings().edit().putBoolean(PREF_PAYWALL_SHOWN, true).apply();
    }

    /** Free-tier hidden-chat allowance. */
    public int getFreeHiddenLimit() {
        return MAX_HIDDEN_CHATS_FREE;
    }

    /** Whether the user holds more hidden chats than the free tier allows, ignoring subscription.
     *  Kept for callers that need the raw count test; prefer {@link #isOverChatLimit()} for
     *  premium-aware enforcement. */
    public boolean isOverFreeLimit() {
        return dialogIds.size() > MAX_HIDDEN_CHATS_FREE;
    }

    // --- Leemen Premium subscription (local stub until real billing) ---

    /** True while a Leemen Premium subscription is active (unlimited hidden chats). */
    public boolean hasLeemenPremium() {
        return leemenPremiumUntil > System.currentTimeMillis();
    }

    /** Subscription expiry as epoch ms; {@code 0} when never subscribed. */
    public long getLeemenPremiumUntil() {
        return leemenPremiumUntil;
    }

    /** Set the subscription expiry directly (epoch ms). Fires UI refresh so paywall / limit
     *  state and the dialog list re-evaluate. */
    public void setLeemenPremiumUntil(long whenMs) {
        if (leemenPremiumUntil == whenMs) {
            return;
        }
        leemenPremiumUntil = whenMs;
        getMessagesController().getMainSettings().edit().putLong(PREF_PREMIUM_UNTIL, whenMs).apply();
        NotificationCenter nc = getNotificationCenter();
        nc.postNotificationName(NotificationCenter.secondSpaceModeChanged);
        nc.postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    /** Grant {@code months} of Leemen Premium locally, extending from now (or current expiry if
     *  still active). TODO(billing): replace with Google Play / backend-verified entitlement. */
    public void activateLeemenPremiumLocally(int months) {
        long base = Math.max(leemenPremiumUntil, System.currentTimeMillis());
        setLeemenPremiumUntil(base + (long) months * 31L * 24L * 60L * 60L * 1000L);
    }

    /** Whether {@code n} more chats can be hidden under the current entitlement. Premium = always. */
    public boolean canAddChats(int n) {
        return hasLeemenPremium() || dialogIds.size() + n <= MAX_HIDDEN_CHATS_FREE;
    }

    /** Premium-aware: holds more hidden chats than allowed and has no active subscription.
     *  Triggers the renew-or-trim prompt on entry into the space. */
    public boolean isOverChatLimit() {
        return !hasLeemenPremium() && dialogIds.size() > MAX_HIDDEN_CHATS_FREE;
    }

    /** Put {@code message} into Telegram's global {@code dialogMessagesByIds} so that
     *  resolve methods can find it for dialog-list preview. Works for both exposed and
     *  pending messages — the name is historical. PS code holds no MessageObject
     *  references itself — this writes into an existing Telegram-managed cache. */
    public void ensureInGlobalCache(MessageObject message) {
        if (message == null || message.getId() == 0) return;
        getMessagesController().dialogMessagesByIds.put(message.getId(), message);
    }

    public void exposeMessage(long dialogId, int messageId) {
        Set<Integer> set = exposedMessages.get(dialogId);
        if (set == null) {
            set = new HashSet<>();
            exposedMessages.put(dialogId, set);
        }
        if (set.add(messageId)) {
            persistExposed();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void unexposeMessage(long dialogId, int messageId) {
        Set<Integer> set = exposedMessages.get(dialogId);
        if (set != null && set.remove(messageId)) {
            if (set.isEmpty()) {
                exposedMessages.remove(dialogId);
            }
            evictSafePreview(dialogId, messageId);
            persistExposed();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    /** Drop every exposed marker for a chat in one go. Used by the "Hide all" decision flow
     *  which nukes off-mode visibility for the whole chat regardless of how each message got
     *  there (previous explicit decision or current off-mode work). */
    public void clearAllExposedMessages(long dialogId) {
        Set<Integer> removed = exposedMessages.remove(dialogId);
        if (removed != null && !removed.isEmpty()) {
            pruneSafePreviewCache();
            persistExposed();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    // --- Search history scoping (per chat) ---
    //
    // Recent-search entries are mode-scoped: a dialog searched in ACTIVE mode is marked
    // "private" and only surfaces in active-mode recents. Searching the same dialog in
    // OFF mode demotes the mark — once exposed outside, no further hiding.

    public boolean isPrivateSearchOnly(long dialogId) {
        return privateSearchDialogs.contains(dialogId);
    }

    public void markPrivateSearch(long dialogId) {
        if (privateSearchDialogs.add(dialogId)) {
            persistPrivateSearches();
        }
    }

    public void unmarkPrivateSearch(long dialogId) {
        if (privateSearchDialogs.remove(dialogId)) {
            persistPrivateSearches();
        }
    }

    private void persistPrivateSearches() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Long id : privateSearchDialogs) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        getMessagesController().getMainSettings().edit().putString(PREF_PRIVATE_SEARCHES, sb.toString()).apply();
        notifyLeemenSync();
    }

    // --- Pending off-mode messages (per chat, per message id) ---
    //
    // A message lands here ONLY when the user explicitly sends it while in OFF mode in a
    // hidden chat. Active-mode sends are NEVER tracked here, so they can't leak into the
    // off-mode view via a fuzzy "is this newer than my last decision?" check.
    //
    // Lifecycle of an id in this set:
    //   1. User sends in off-mode → ChatActivity.onMessageSend grabs the local placeholder
    //      and calls markMessagePending(dialogId, placeholderId)  (placeholderId < 0).
    //   2. Server confirms the message → messageReceivedByServer fires with (oldId, newId).
    //      ChatActivity hook calls replacePendingMessageId so the set now holds newId.
    //   3. User enters PS-on, decision dialog fires showing exactly these messages.
    //      - "Hide all" / dismiss → clearAllPendingMessages(dialogId)
    //      - "Show outside"      → for each: exposeMessage + unmarkMessagePending
    //      - "Manage" + selection→ for selected: exposeMessage + unmarkMessagePending,
    //                              for rest: unmarkMessagePending (they become hidden)

    /** True if the chat currently has any off-mode sends waiting for a decision. */
    public boolean hasPendingOffModeWork(long dialogId) {
        Set<Integer> set = pendingMessages.get(dialogId);
        return set != null && !set.isEmpty();
    }

    public boolean isMessagePending(long dialogId, int messageId) {
        Set<Integer> set = pendingMessages.get(dialogId);
        return set != null && set.contains(messageId);
    }

    // --- Shared media-surface visibility ---
    //
    // Media surfaces (shared-media gallery, photo viewer, media calendar, media search) must
    // agree with the chat message list: in OFF mode a hidden chat exposes only its exposed/
    // pending media. These two helpers are the single shared predicate so every surface
    // mirrors ChatActivity.filterToExposedSecondSpace without re-deriving the rule.

    /** Chat-level gate for media surfaces; alias of {@link #isHiddenFromCurrentView(long)}
     *  named for intent. When true, only exposed/pending media of {@code dialogId} may show. */
    public boolean isMediaSuppressed(long dialogId) {
        return isHiddenFromCurrentView(dialogId);
    }

    /** Suppression predicate for Saved-Messages surfaces, keyed on a saved message's SOURCE peer (the chat the
     *  copy was saved FROM — {@link MessageObject#getSavedDialogId()} / {@code SavedDialog.dialogId}), NOT the
     *  Saved-Messages container (which is always selfId and never hidden). True only when that source peer is a
     *  hidden chat in OFF mode. The user's own notes (selfId / {@link UserObject#ANONYMOUS}) and the unknown
     *  fallback (0) are NEVER suppressed — including during the OFF fail-closed initial-sync window — so normal
     *  Saved Messages content (your own notes, saves from non-hidden chats) is unaffected. No-op in MODE_REAL. */
    public boolean isSavedSourceSuppressed(long savedSourcePeer) {
        if (savedSourcePeer == 0
                || savedSourcePeer == getUserConfig().getClientUserId()
                || savedSourcePeer == UserObject.ANONYMOUS) {
            return false;
        }
        return isHiddenFromCurrentView(savedSourcePeer);
    }

    /** Per-message visibility predicate. MODE_REAL / non-hidden chats are privileged (always
     *  visible). For a hidden chat in OFF mode a message shows only if it is exposed, pending,
     *  or the user's own in-flight outgoing placeholder (negative id / sending / send-error)
     *  not yet tagged — mirrors ChatActivity.filterToExposedSecondSpace lines 20238-20263. */
    public boolean isMessageVisibleInCurrentView(long dialogId, MessageObject mo) {
        if (!isHiddenFromCurrentView(dialogId)) return true;
        if (mo == null) return false;
        int id = mo.getId();
        if (isMessageExposed(dialogId, id)) return true;
        if (isMessagePending(dialogId, id)) return true;
        return mo.isOut() && (id < 0 || mo.isSending() || mo.isSendError());
    }

    /** Convenience overload keying on the message's own dialog id — use when iterating a media
     *  list that may mix a chat and its merged (migrated-from) dialog. */
    public boolean isMessageVisibleInCurrentView(MessageObject mo) {
        return mo != null && isMessageVisibleInCurrentView(mo.getDialogId(), mo);
    }

    public Set<Integer> getPendingMessages(long dialogId) {
        Set<Integer> set = pendingMessages.get(dialogId);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    public void markMessagePending(long dialogId, int messageId) {
        Set<Integer> set = pendingMessages.get(dialogId);
        if (set == null) {
            set = new HashSet<>();
            pendingMessages.put(dialogId, set);
        }
        if (set.add(messageId)) {
            persistPendingMessages();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void unmarkMessagePending(long dialogId, int messageId) {
        Set<Integer> set = pendingMessages.get(dialogId);
        if (set != null && set.remove(messageId)) {
            if (set.isEmpty()) {
                pendingMessages.remove(dialogId);
            }
            evictSafePreview(dialogId, messageId);
            persistPendingMessages();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void clearAllPendingMessages(long dialogId) {
        if (pendingMessages.remove(dialogId) != null) {
            pruneSafePreviewCache();
            persistPendingMessages();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    /** Telegram replaces a placeholder's negative local id with the server-assigned positive
     *  id at confirmation time (mutates the same MessageObject). Anything tracking that id
     *  must follow the rename — otherwise the set holds a phantom negative id and the now-
     *  positive-id message looks "not pending" to the filter. */
    /** Migrate a message's id (negative placeholder → positive server id, on messageReceivedByServer) across
     *  ALL id-keyed PS tag sets — exposed, pending AND self-pinned — so a confirmed message keeps its tag and
     *  doesn't drop out of the OFF-mode view / preview on the next reload. (Was pending-only, so exposed /
     *  self-pinned messages stranded on the dead negative id and vanished on re-entry.) */
    public void replaceMessageId(long dialogId, int oldId, int newId) {
        if (oldId == newId) return;
        boolean changed = false;
        changed |= migrateIdInSet(exposedMessages.get(dialogId), oldId, newId);
        changed |= migrateIdInSet(pendingMessages.get(dialogId), oldId, newId);
        changed |= migrateIdInSet(selfPinnedMessages.get(dialogId), oldId, newId);
        if (changed) {
            persistExposed();
            persistPendingMessages();
            persistSelfPinned();
            notifyLeemenSync(); // the tag now lives on the syncable positive id → push it
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    private static boolean migrateIdInSet(Set<Integer> set, int oldId, int newId) {
        if (set != null && set.remove(oldId)) {
            set.add(newId);
            return true;
        }
        return false;
    }

    /** @deprecated use {@link #replaceMessageId} — kept for existing call sites. */
    public void replacePendingMessageId(long dialogId, int oldId, int newId) {
        replaceMessageId(dialogId, oldId, newId);
    }

    /** Which tracked dialog (if any) holds {@code messageId} in its exposed / pending / self-pinned set. */
    private Long findDialogHoldingMessage(int messageId) {
        for (Map.Entry<Long, Set<Integer>> e : pendingMessages.entrySet()) {
            if (e.getValue().contains(messageId)) return e.getKey();
        }
        for (Map.Entry<Long, Set<Integer>> e : exposedMessages.entrySet()) {
            if (e.getValue().contains(messageId)) return e.getKey();
        }
        for (Map.Entry<Long, Set<Integer>> e : selfPinnedMessages.entrySet()) {
            if (e.getValue().contains(messageId)) return e.getKey();
        }
        return null;
    }

    /** Backwards-compat alias used by the existing decision-flow paths that conceptually
     *  mean "wipe the chat-level pending state". With the new model that's just clearing
     *  every pending message id for the chat. */
    public void clearPendingOffModeWork(long dialogId) {
        clearAllPendingMessages(dialogId);
    }

    private void loadPendingMessages(String json) {
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long dialogId = Long.parseLong(key);
                JSONArray arr = obj.getJSONArray(key);
                Set<Integer> set = new HashSet<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    set.add(arr.getInt(i));
                }
                if (!set.isEmpty()) {
                    pendingMessages.put(dialogId, set);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void persistPendingMessages() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<Long, Set<Integer>> e : pendingMessages.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Integer id : e.getValue()) {
                    arr.put(id);
                }
                obj.put(String.valueOf(e.getKey()), arr);
            }
            getMessagesController().getMainSettings().edit().putString(PREF_PENDING_MESSAGES, obj.toString()).apply();
        } catch (Exception ignored) {
        }
        notifyLeemenSync();
    }

    // --- Self-pinned messages tracking ---

    private void loadSelfPinned(String json) {
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long dialogId = Long.parseLong(key);
                JSONArray arr = obj.getJSONArray(key);
                Set<Integer> set = new HashSet<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    set.add(arr.getInt(i));
                }
                if (!set.isEmpty()) {
                    selfPinnedMessages.put(dialogId, set);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void persistSelfPinned() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<Long, Set<Integer>> e : selfPinnedMessages.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Integer id : e.getValue()) {
                    arr.put(id);
                }
                obj.put(String.valueOf(e.getKey()), arr);
            }
            getMessagesController().getMainSettings().edit().putString(PREF_SELF_PINNED, obj.toString()).apply();
        } catch (Exception ignored) {
        }
        notifyLeemenSync();
    }

    public void addSelfPinnedMessage(long dialogId, int messageId) {
        if (!dialogIds.contains(dialogId)) return;
        Set<Integer> set = selfPinnedMessages.get(dialogId);
        if (set == null) {
            set = new HashSet<>();
            selfPinnedMessages.put(dialogId, set);
        }
        if (set.add(messageId)) {
            persistSelfPinned();
        }
    }

    public void removeSelfPinnedMessage(long dialogId, int messageId) {
        Set<Integer> set = selfPinnedMessages.get(dialogId);
        if (set != null && set.remove(messageId)) {
            if (set.isEmpty()) {
                selfPinnedMessages.remove(dialogId);
            }
            persistSelfPinned();
        }
    }

    public boolean isSelfPinnedMessage(long dialogId, int messageId) {
        Set<Integer> set = selfPinnedMessages.get(dialogId);
        return set != null && set.contains(messageId);
    }

    // --- Decision marker (banner trigger) ---

    public int getLastDecidedMessageId(long dialogId) {
        Integer v = lastDecidedMessageId.get(dialogId);
        return v == null ? 0 : v;
    }

    public void setLastDecidedMessageId(long dialogId, int messageId) {
        Integer prev = lastDecidedMessageId.get(dialogId);
        if (prev == null || prev < messageId) {
            lastDecidedMessageId.put(dialogId, messageId);
            persistLastDecided();
        }
    }

    // --- Persistence helpers ---

    private void persistDialogIds() {
        persistLongCsv(dialogIds, PREF_DIALOG_IDS);
        notifyLeemenSync();
    }

    private void persistLongCsv(Set<Long> ids, String key) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Long id : ids) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        getMessagesController().getMainSettings().edit().putString(key, sb.toString()).apply();
    }

    private void persistExposed() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<Long, Set<Integer>> e : exposedMessages.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Integer id : e.getValue()) {
                    arr.put(id);
                }
                obj.put(String.valueOf(e.getKey()), arr);
            }
            getMessagesController().getMainSettings().edit().putString(PREF_EXPOSED, obj.toString()).apply();
        } catch (Exception ignored) {
        }
        notifyLeemenSync();
    }

    private void persistLastDecided() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<Long, Integer> e : lastDecidedMessageId.entrySet()) {
                obj.put(String.valueOf(e.getKey()), e.getValue());
            }
            getMessagesController().getMainSettings().edit().putString(PREF_LAST_DECIDED, obj.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // --- Switch password (main account) ---
    //
    // Optional single PIN owned by this account when it acts as the *main* account. It gates
    // *switching INTO* any account this one hides ({@link #hiddenAccounts}). Configured in the
    // hidden-accounts block of this account's Private Space settings — not on the hidden target.
    // Independent of the Private Space PIN (which gates entering PS *within* an already-open
    // account). Same SHA-256 4–6-digit format.

    public boolean hasSwitchPassword() {
        return !TextUtils.isEmpty(switchPasswordHash);
    }

    public boolean verifySwitchPassword(String pin) {
        if (pin == null) return false;
        if (!hasSwitchPassword()) return true;
        return hashPassword(pin).equals(switchPasswordHash);
    }

    /** Pass empty/null to clear. */
    public void setSwitchPassword(String pin) {
        switchPasswordHash = TextUtils.isEmpty(pin) ? "" : hashPassword(pin);
        getMessagesController().getMainSettings().edit().putString(PREF_SWITCH_PASSWORD_HASH, switchPasswordHash).apply();
    }

    // --- Hidden other accounts ---
    //
    // Hide-list of OTHER account ids. Visibility rule: MODE_REAL is privileged (nothing
    // hidden); MODE_OFF hides the accounts in the list.

    public boolean isAccountHidden(int otherAccountNum) {
        return hiddenAccounts.contains(otherAccountNum);
    }

    public void setAccountHidden(int otherAccountNum, boolean hidden) {
        if (otherAccountNum == currentAccount) return;
        // An account that itself hides other accounts (a private-space "owner") can never be hidden.
        // This keeps the hide-graph acyclic at ANY depth: the edge that would close a cycle always
        // points at a node that already hides someone, so it is rejected right here. It also subsumes
        // the direct A↔B reciprocal case (if other hides us, it has hidden accounts).
        if (hidden && getInstance(otherAccountNum).hasHiddenAccounts()) {
            return;
        }
        boolean changed = hidden ? hiddenAccounts.add(otherAccountNum) : hiddenAccounts.remove(otherAccountNum);
        if (!changed) return;
        persistHiddenAccounts();
        getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceModeChanged);
        if (currentAccount != UserConfig.selectedAccount) {
            return;
        }
        boolean shouldHideNow = activeHiddenAccountsForMode(activeMode).contains(otherAccountNum);
        if (shouldHideNow) {
            NotificationsController.getInstance(otherAccountNum).hideNotifications();
        } else {
            NotificationsController.getInstance(otherAccountNum).showNotifications();
        }
        NotificationsController.getInstance(currentAccount).updateBadge();
    }

    public Set<Integer> getHiddenAccounts() {
        return Collections.unmodifiableSet(hiddenAccounts);
    }

    /** True when THIS account hides at least one other account (it is a private-space "owner"). */
    public boolean hasHiddenAccounts() {
        return !hiddenAccounts.isEmpty();
    }

    /** Called from logout cleanup — drop a deactivated account from the hide-list. */
    public void onOtherAccountLoggedOut(int otherAccountNum) {
        if (hiddenAccounts.remove(otherAccountNum)) {
            persistHiddenAccounts();
        }
    }

    /** Clear the DEVICE-LOCAL, slot-scoped private-space state when THIS account logs out: the
     *  hidden-account list (slot indices into this device's accounts) and the switch password that
     *  gates entry into them. Unlike the PS PIN and hidden chats — which are ACCOUNT-level and follow
     *  the account (re-synced on the next login) — these reference the device's account slots and must
     *  not survive into the next account that reuses this slot: a stale hide-list would silently defeat
     *  that account's auto-hide via the acyclic guard ({@link #hasHiddenAccounts}), and
     *  {@link #isAccountHiddenByAny} would match reused slot indices. This is NOT a full PS wipe —
     *  account-level state (PIN, hidden chats, premium) is deliberately preserved. */
    public void clearLocalAccountHideStateForLogout() {
        if (!hiddenAccounts.isEmpty()) {
            hiddenAccounts.clear();
            persistHiddenAccounts();
        }
        if (!TextUtils.isEmpty(switchPasswordHash)) {
            switchPasswordHash = "";
            getMessagesController().getMainSettings().edit().putString(PREF_SWITCH_PASSWORD_HASH, "").apply();
        }
    }

    private void persistHiddenAccounts() {
        persistIntCsv(hiddenAccounts, PREF_HIDDEN_ACCOUNTS);
    }

    private void persistIntCsv(Set<Integer> ids, String key) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer id : ids) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        getMessagesController().getMainSettings().edit().putString(key, sb.toString()).apply();
    }

    /**
     * Whether {@code target} should be hidden from {@code viewer}'s UI (account switcher, notifications,
     * call popups, widgets…). The viewer sees its OWN hidden account only inside the private space
     * (MODE_REAL). An account hidden by ANY OTHER account is hidden from everyone else — a private-space
     * account belongs to its owner and must never surface in another account's view (deniability).
     * Returns {@code false} for self.
     */
    public static boolean isHiddenFromAccount(int viewerAccountNum, int targetAccountNum) {
        if (targetAccountNum == viewerAccountNum) return false;
        SecondSpaceController viewer = getInstance(viewerAccountNum);
        if (viewer.isAccountHidden(targetAccountNum)) {
            // Owner: the hidden account is revealed only while the private space is open (MODE_REAL).
            return !viewer.isRealActive();
        }
        // Not ours, but hidden by some other account → never visible in this account's view.
        return isAccountHiddenByAny(targetAccountNum);
    }

    /**
     * Whether an account should be hidden from the currently selected account's UI. The owner sees its
     * own hidden account only in MODE_REAL; an account hidden by any other account is hidden from
     * everyone else. Returns {@code false} for self.
     */
    public static boolean isHiddenFromSelectedAccount(int targetAccountNum) {
        return isHiddenFromAccount(UserConfig.selectedAccount, targetAccountNum);
    }

    /**
     * Whether {@code account}'s notifications / launcher badge must be suppressed in the current device
     * state. Like {@link #isHiddenFromSelectedAccount} but with a self-backstop: an account hidden by
     * ANY other account stays silent even when it is itself the selected account — e.g. the app was
     * force-killed while a hidden account was current, before {@code LaunchActivity} bounces selection
     * off it. Mirrors the call-leak backstop in MessagesController (isHiddenFromSelectedAccount alone
     * returns false for the self case).
     */
    public static boolean isHiddenForNotifications(int account) {
        return isHiddenFromSelectedAccount(account) || isAccountHiddenByAny(account);
    }

    public static boolean isAccountHiddenByAny(int accountNum) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a == accountNum || !UserConfig.getInstance(a).isClientActivated()) continue;
            if (getInstance(a).isAccountHidden(accountNum)) return true;
        }
        return false;
    }
}
