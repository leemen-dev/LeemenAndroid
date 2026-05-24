package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

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
    private static final String PREF_PRIVATE_SEARCHES = "second_space_private_searches";
    private static final String PREF_PASSWORD_HASH = "second_space_password_hash";
    private static final String PREF_TAB_SEQUENCE = "second_space_tab_sequence";
    private static final String PREF_PIN_IN_SEARCH = "second_space_pin_in_search";
    private static final String PREF_SHORTCUT_TESTED = "second_space_shortcut_tested";
    private static final String PREF_PIN_TIMEOUT_MIN = "second_space_pin_timeout_min";
    private static final String PREF_PIN_LAST_OK_MS = "second_space_pin_last_ok_ms";
    private static final String PREF_ENTRY_PASSWORD_HASH = "second_space_entry_password_hash";
    private static final String PREF_HIDDEN_ACCOUNTS = "second_space_hidden_accounts";
    private static final String PREF_EYE_HINT_SHOWN = "second_space_eye_hint_shown";
    private static final String PREF_TOOLBAR_HINT_SHOWN = "second_space_toolbar_hint_shown";
    // Fake (decoy) space — separate PIN, membership, hidden-account list. Sequence /
    // pin-in-search / entry button / pin-timeout / shortcut-tested stay shared between
    // real and fake spaces — they describe HOW to reach a space, the PIN at the gate
    // is what discriminates which space gets unlocked.
    private static final String PREF_FAKE_PASSWORD_HASH = "second_space_fake_password_hash";
    private static final String PREF_FAKE_DIALOG_IDS = "second_space_fake_dialogs";
    private static final String PREF_FAKE_HIDDEN_ACCOUNTS = "second_space_fake_hidden_accounts";

    public static final int MODE_OFF = 0;
    public static final int MODE_REAL = 1;
    public static final int MODE_FAKE = 2;

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

    /** Real-space chat membership. Kept under the original {@code dialogIds} name so the
     *  many references throughout the project keep compiling. */
    private final Set<Long> dialogIds = new HashSet<>();
    /** Fake (decoy) space chat membership. */
    private final Set<Long> fakeDialogIds = new HashSet<>();
    private final Map<Long, Set<Integer>> exposedMessages = new HashMap<>();
    private final Map<Long, Integer> lastDecidedMessageId = new HashMap<>();
    private final Map<Long, MessageObject> lastExposedMessageCache = new HashMap<>();
    /** Per-chat set of message ids the user sent while in OFF mode, still awaiting a decision.
     *  Off-mode-only sends go here directly so they can never be confused with normal active-
     *  mode sends — those simply don't get tracked. */
    private final Map<Long, Set<Integer>> pendingMessages = new HashMap<>();
    private final Set<Long> privateSearchDialogs = new HashSet<>();
    private String passwordHash;
    /** Optional fake-PIN. Empty when not configured. Same SHA-256 hash format as the real
     *  PIN. Configured / cleared from the real-mode settings activity; the fake-mode UI
     *  can change its own value once entered (it sees only the fake PIN, no awareness of
     *  the real one). */
    private String fakePasswordHash;
    private final java.util.List<TabStep> tabSequence = new java.util.ArrayList<>();
    private boolean pinInSearchEnabled;
    private boolean shortcutTested;
    private int pinTimeoutMinutes;
    private long pinLastVerifiedAt;
    /** Active mode: {@link #MODE_OFF}, {@link #MODE_REAL}, or {@link #MODE_FAKE}.
     *  Non-persistent — always {@code MODE_OFF} on next launch (deniability). */
    private int activeMode = MODE_OFF;
    private String entryPasswordHash;
    /** Real-space hidden-other-accounts list — accounts whose tray notifications / switcher
     *  presence are suppressed while *this* account's real space is NOT the active mode
     *  (i.e. in OFF and FAKE modes). Kept under the original {@code hiddenAccounts} name. */
    private final Set<Integer> hiddenAccounts = new HashSet<>();
    /** Fake-space hidden-other-accounts list. Suppressed while NOT in FAKE mode (i.e. in
     *  OFF and REAL). Configured from inside fake mode by whoever happens to be using
     *  the decoy. */
    private final Set<Integer> fakeHiddenAccounts = new HashSet<>();

    private boolean entryButtonVisible;

    private SecondSpaceController(int num) {
        super(num);
        SharedPreferences prefs = getMessagesController().getMainSettings();
        // activeMode is intentionally non-persistent: always starts MODE_OFF on app launch (deniability)
        entryButtonVisible = prefs.getBoolean(PREF_SHOW_ENTRY_BUTTON, true);
        loadDialogIds(dialogIds, prefs.getString(PREF_DIALOG_IDS, ""));
        loadDialogIds(fakeDialogIds, prefs.getString(PREF_FAKE_DIALOG_IDS, ""));
        loadExposed(prefs.getString(PREF_EXPOSED, ""));
        loadLastDecided(prefs.getString(PREF_LAST_DECIDED, ""));
        loadPendingMessages(prefs.getString(PREF_PENDING_MESSAGES, ""));
        String searchCsv = prefs.getString(PREF_PRIVATE_SEARCHES, "");
        if (!TextUtils.isEmpty(searchCsv)) {
            for (String s : searchCsv.split(",")) {
                try {
                    privateSearchDialogs.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        passwordHash = prefs.getString(PREF_PASSWORD_HASH, "");
        fakePasswordHash = prefs.getString(PREF_FAKE_PASSWORD_HASH, "");
        loadTabSequence(prefs.getString(PREF_TAB_SEQUENCE, ""));
        pinInSearchEnabled = prefs.getBoolean(PREF_PIN_IN_SEARCH, false);
        shortcutTested = prefs.getBoolean(PREF_SHORTCUT_TESTED, false);
        pinTimeoutMinutes = prefs.getInt(PREF_PIN_TIMEOUT_MIN, 0);
        pinLastVerifiedAt = prefs.getLong(PREF_PIN_LAST_OK_MS, 0L);
        entryPasswordHash = prefs.getString(PREF_ENTRY_PASSWORD_HASH, "");
        loadHiddenAccounts(hiddenAccounts, prefs.getString(PREF_HIDDEN_ACCOUNTS, ""), num);
        loadHiddenAccounts(fakeHiddenAccounts, prefs.getString(PREF_FAKE_HIDDEN_ACCOUNTS, ""), num);
        // We need to track placeholder-id → server-id renames globally, not just while a
        // ChatActivity for that dialog happens to be open. Otherwise: user sends in off
        // mode, exits chat before the round-trip completes, server confirms → ChatActivity
        // is gone → no rename happens, pending set still holds the stale negative id,
        // re-entering the chat in active mode shows the message without the eye badge.
        getNotificationCenter().addObserver(this, NotificationCenter.messageReceivedByServer);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messageReceivedByServer) {
            Integer oldId = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : null;
            Integer newId = args.length > 1 && args[1] instanceof Integer ? (Integer) args[1] : null;
            if (oldId == null || newId == null || oldId.equals(newId)) return;
            // Placeholder ids are negative and unique per account, so a single iteration to
            // find which chat (if any) holds the renamed id is fine — there's at most one.
            Long owningDialog = null;
            for (Map.Entry<Long, Set<Integer>> e : pendingMessages.entrySet()) {
                if (e.getValue().contains(oldId)) {
                    owningDialog = e.getKey();
                    break;
                }
            }
            if (owningDialog != null) {
                replacePendingMessageId(owningDialog, oldId, newId);
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
    }

    /** In-session memory of the last-verified mode. Non-persistent on purpose — the skip
     *  is only meaningful within an active app session; restarts always re-prompt. */
    private int pinLastVerifiedMode = MODE_OFF;

    /** Should the PIN prompt be skipped right now because of a recent successful entry?
     *  Returns {@code true} only when the mode is known (current session) AND the timeout
     *  window hasn't elapsed. {@link #getPinLastVerifiedMode()} reveals which mode to
     *  enter when callers honor the skip. */
    public boolean isPinPromptSkippable() {
        if (pinTimeoutMinutes <= 0 || pinLastVerifiedAt <= 0) return false;
        if (pinLastVerifiedMode == MODE_OFF) return false;
        long deadlineMs = pinLastVerifiedAt + pinTimeoutMinutes * 60_000L;
        return System.currentTimeMillis() < deadlineMs;
    }

    public int getPinLastVerifiedMode() {
        return pinLastVerifiedMode;
    }

    public void recordPinVerified() {
        recordPinVerified(MODE_REAL);
    }

    public void recordPinVerified(int mode) {
        pinLastVerifiedAt = System.currentTimeMillis();
        pinLastVerifiedMode = mode;
        getMessagesController().getMainSettings().edit().putLong(PREF_PIN_LAST_OK_MS, pinLastVerifiedAt).apply();
    }

    public void clearPinVerified() {
        pinLastVerifiedMode = MODE_OFF;
        if (pinLastVerifiedAt == 0L) return;
        pinLastVerifiedAt = 0L;
        getMessagesController().getMainSettings().edit().remove(PREF_PIN_LAST_OK_MS).apply();
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
        }
    }

    private static boolean sameSequence(java.util.List<TabStep> a, java.util.List<TabStep> b) {
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
    //
    // Mode-aware helpers — {@link #hasPassword()} / {@link #verifyPassword(String)} /
    // {@link #setPassword(String)} route to the CURRENT mode's PIN store, so any caller
    // that holds an "ssc" reference and edits the PIN sees the right space without having
    // to know which mode is active. Callers that explicitly need the real PIN (e.g. the
    // fake-PIN config row inside real settings) use the explicit *Real/*Fake methods.

    public boolean hasPassword() {
        return !TextUtils.isEmpty(activePasswordHash());
    }

    public boolean verifyPassword(String pin) {
        if (pin == null) return false;
        String hash = activePasswordHash();
        if (TextUtils.isEmpty(hash)) return true;
        return hashPassword(pin).equals(hash);
    }

    /** Pass empty/null to clear. Edits the PIN of the current mode (REAL by default when
     *  no PS is active — settings activity always opens inside an active space, so OFF
     *  here is a fallback that targets the real PIN). */
    public void setPassword(String pin) {
        if (activeMode == MODE_FAKE) {
            setFakePassword(pin);
        } else {
            setRealPassword(pin);
        }
    }

    private String activePasswordHash() {
        return activeMode == MODE_FAKE ? fakePasswordHash : passwordHash;
    }

    public boolean hasRealPassword() {
        return !TextUtils.isEmpty(passwordHash);
    }

    public boolean hasFakePassword() {
        return !TextUtils.isEmpty(fakePasswordHash);
    }

    /** Check {@code pin} against both the real and fake PINs.
     *  @return {@link #MODE_REAL} or {@link #MODE_FAKE} on match, {@link #MODE_OFF} when
     *  nothing matches. When neither PIN is set, returns {@code MODE_REAL} (legacy
     *  passwordless entry path). */
    public int verifyAnyPassword(String pin) {
        if (pin == null) return MODE_OFF;
        boolean realSet = hasRealPassword();
        boolean fakeSet = hasFakePassword();
        if (!realSet && !fakeSet) {
            return MODE_REAL;
        }
        String hashed = hashPassword(pin);
        if (realSet && hashed.equals(passwordHash)) return MODE_REAL;
        if (fakeSet && hashed.equals(fakePasswordHash)) return MODE_FAKE;
        return MODE_OFF;
    }

    public void setRealPassword(String pin) {
        String newHash = TextUtils.isEmpty(pin) ? "" : hashPassword(pin);
        boolean changed = !newHash.equals(passwordHash);
        passwordHash = newHash;
        getMessagesController().getMainSettings().edit().putString(PREF_PASSWORD_HASH, passwordHash).apply();
        // PIN changed → invalidate any cached «recently verified» state.
        clearPinVerified();
        // Entry method changed → user must re-verify before the entry button can be hidden.
        if (changed) {
            clearShortcutTested();
        }
    }

    /** Set / clear the fake-space PIN. Empty/null clears.
     *  Rejected if {@code pin} matches the current real PIN — collisions would route
     *  ambiguous entries (a PIN that fits both is a footgun). Returns {@code true} on
     *  success, {@code false} if the candidate collides with the real PIN. */
    public boolean setFakePassword(String pin) {
        String newHash = TextUtils.isEmpty(pin) ? "" : hashPassword(pin);
        if (!TextUtils.isEmpty(newHash) && newHash.equals(passwordHash)) {
            return false;
        }
        boolean changed = !newHash.equals(fakePasswordHash);
        fakePasswordHash = newHash;
        getMessagesController().getMainSettings().edit().putString(PREF_FAKE_PASSWORD_HASH, fakePasswordHash).apply();
        clearPinVerified();
        if (changed) {
            clearShortcutTested();
        }
        return true;
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
    //
    // Two parallel sets: {@code dialogIds} for the real space, {@code fakeDialogIds} for
    // the decoy. The legacy {@link #isInSecondSpace(long)} and {@link #getDialogIds()}
    // return the UNION so that filters which mean «this chat is private (any space)»
    // keep working unchanged across the codebase. Code that genuinely needs to know
    // WHICH space a chat lives in uses {@link #isInRealSpace(long)} / {@link #isInFakeSpace(long)}.

    public boolean isInSecondSpace(long dialogId) {
        return dialogIds.contains(dialogId) || fakeDialogIds.contains(dialogId);
    }

    public boolean isInRealSpace(long dialogId) {
        return dialogIds.contains(dialogId);
    }

    public boolean isInFakeSpace(long dialogId) {
        return fakeDialogIds.contains(dialogId);
    }

    /** True iff {@code dialogId} is hidden from whichever view the user currently sees
     *  (per the asymmetric isolation rule: real-active sees everything, fake-active hides
     *  real-space chats, off hides both spaces' chats). */
    public boolean isHiddenFromCurrentView(long dialogId) {
        switch (activeMode) {
            case MODE_REAL:
                return false;
            case MODE_FAKE:
                return dialogIds.contains(dialogId) && !fakeDialogIds.contains(dialogId);
            case MODE_OFF:
            default:
                return dialogIds.contains(dialogId) || fakeDialogIds.contains(dialogId);
        }
    }

    public void addToSecondSpace(long dialogId) {
        addToCurrentSpace(dialogId);
    }

    public void removeFromSecondSpace(long dialogId) {
        removeFromCurrentSpace(dialogId);
    }

    /** Add {@code dialogId} to the currently-active space's chat list. Defaults to the
     *  real space when no space is active (settings UI is guarded to only open while
     *  inside a space, but the fallback keeps this defensive). */
    public void addToCurrentSpace(long dialogId) {
        Set<Long> target = currentDialogSet();
        // Asymmetric isolation: a chat can only live in one space at a time. Adding to
        // fake removes from real and vice versa, so the user can move a chat between
        // spaces without doubling membership.
        Set<Long> other = target == dialogIds ? fakeDialogIds : dialogIds;
        boolean changed = target.add(dialogId);
        boolean otherChanged = other.remove(dialogId);
        if (changed) {
            persistDialogIds();
            persistFakeDialogIds();
        } else if (otherChanged) {
            persistDialogIds();
            persistFakeDialogIds();
        }
        if (changed || otherChanged) {
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void removeFromCurrentSpace(long dialogId) {
        Set<Long> target = currentDialogSet();
        if (target.remove(dialogId)) {
            // Exposure / pending / cached-preview state is per-chat (shared store), so it
            // only makes sense to drop when the chat leaves PS membership entirely.
            if (!isInSecondSpace(dialogId)) {
                exposedMessages.remove(dialogId);
                lastDecidedMessageId.remove(dialogId);
                lastExposedMessageCache.remove(dialogId);
                pendingMessages.remove(dialogId);
                persistExposed();
                persistLastDecided();
                persistPendingMessages();
            }
            if (target == dialogIds) persistDialogIds(); else persistFakeDialogIds();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    private Set<Long> currentDialogSet() {
        return activeMode == MODE_FAKE ? fakeDialogIds : dialogIds;
    }

    /** Union of both spaces' chat ids. Used by filters / iterators that need to act on
     *  «any private chat» (badge subtraction, off-mode hide list, widget filter). */
    public Set<Long> getDialogIds() {
        if (fakeDialogIds.isEmpty()) {
            return Collections.unmodifiableSet(dialogIds);
        }
        if (dialogIds.isEmpty()) {
            return Collections.unmodifiableSet(fakeDialogIds);
        }
        Set<Long> union = new HashSet<>(dialogIds.size() + fakeDialogIds.size());
        union.addAll(dialogIds);
        union.addAll(fakeDialogIds);
        return Collections.unmodifiableSet(union);
    }

    /** Chats in the real space only. */
    public Set<Long> getRealSpaceDialogIds() {
        return Collections.unmodifiableSet(dialogIds);
    }

    /** Chats in the fake space only. */
    public Set<Long> getFakeSpaceDialogIds() {
        return Collections.unmodifiableSet(fakeDialogIds);
    }

    /** Chats in the currently-active space (for settings UI). */
    public Set<Long> getCurrentSpaceDialogIds() {
        return activeMode == MODE_FAKE
                ? Collections.unmodifiableSet(fakeDialogIds)
                : Collections.unmodifiableSet(dialogIds);
    }

    // --- Mode ---

    public boolean isActive() {
        return activeMode != MODE_OFF;
    }

    public boolean isRealActive() {
        return activeMode == MODE_REAL;
    }

    public boolean isFakeActive() {
        return activeMode == MODE_FAKE;
    }

    public int getActiveMode() {
        return activeMode;
    }

    /** Backwards-compatible toggle: {@code true} → {@link #MODE_REAL}, {@code false} →
     *  {@link #MODE_OFF}. Callers that explicitly need to enter the fake space use
     *  {@link #setActiveMode(int)}. */
    public void setActive(boolean value) {
        setActiveMode(value ? MODE_REAL : MODE_OFF);
    }

    public void setActiveMode(int mode) {
        if (mode != MODE_OFF && mode != MODE_REAL && mode != MODE_FAKE) {
            return;
        }
        int oldMode = activeMode;
        if (oldMode == mode) {
            return;
        }
        activeMode = mode;
        // Intentionally NOT persisted: activeMode is per-session, always MODE_OFF on next launch.
        // Re-evaluate FLAG_SECURE: snapshot capture / screenshots must be blocked while PS is on.
        try {
            org.telegram.ui.LaunchActivity la = org.telegram.ui.LaunchActivity.instance;
            if (la != null) la.invalidateFlagSecure();
        } catch (Throwable ignored) {
        }
        NotificationCenter nc = getNotificationCenter();
        nc.postNotificationName(NotificationCenter.secondSpaceModeChanged);
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
     *  Real-active is privileged (sees everything); off + fake-active hide the union of both
     *  spaces' hide lists so that whatever the casual viewer / decoy attacker sees, nothing
     *  the real user marked sensitive leaks through. */
    private Set<Integer> activeHiddenAccountsForMode(int mode) {
        if (mode == MODE_REAL) {
            return Collections.emptySet();
        }
        if (hiddenAccounts.isEmpty()) {
            return new HashSet<>(fakeHiddenAccounts);
        }
        if (fakeHiddenAccounts.isEmpty()) {
            return new HashSet<>(hiddenAccounts);
        }
        Set<Integer> union = new HashSet<>(hiddenAccounts.size() + fakeHiddenAccounts.size());
        union.addAll(hiddenAccounts);
        union.addAll(fakeHiddenAccounts);
        return union;
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

    public MessageObject getLastExposedMessageCached(long dialogId) {
        return lastExposedMessageCache.get(dialogId);
    }

    public void cacheLastExposedMessage(long dialogId, MessageObject message) {
        if (message == null) {
            return;
        }
        MessageObject existing = lastExposedMessageCache.get(dialogId);
        // Replace cache when (a) nothing cached yet, (b) new message has higher id (server
        // confirmed something newer), or (c) new is a local outgoing placeholder — id < 0 but
        // represents the user's freshest action and should override an older positive-id cache.
        boolean localOutgoing = message.getId() < 0 && message.isOut();
        if (existing == null || message.getId() > existing.getId() || localOutgoing) {
            lastExposedMessageCache.put(dialogId, message);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
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

    /** Drop the cached "last exposed" — used when the cached message gets unexposed. */
    public void invalidateLastExposedCache(long dialogId) {
        if (lastExposedMessageCache.remove(dialogId) != null) {
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
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
            persistExposed();
            // The cached preview hint may point at one of the just-removed messages; invalidate
            // so the dialogs-list preview falls back to nothing rather than a stale exposed msg.
            invalidateLastExposedCache(dialogId);
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
            persistPendingMessages();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void clearAllPendingMessages(long dialogId) {
        if (pendingMessages.remove(dialogId) != null) {
            persistPendingMessages();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    /** Telegram replaces a placeholder's negative local id with the server-assigned positive
     *  id at confirmation time (mutates the same MessageObject). Anything tracking that id
     *  must follow the rename — otherwise the set holds a phantom negative id and the now-
     *  positive-id message looks "not pending" to the filter. */
    public void replacePendingMessageId(long dialogId, int oldId, int newId) {
        if (oldId == newId) return;
        Set<Integer> set = pendingMessages.get(dialogId);
        if (set != null && set.remove(oldId)) {
            set.add(newId);
            persistPendingMessages();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
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
    }

    private void persistFakeDialogIds() {
        persistLongCsv(fakeDialogIds, PREF_FAKE_DIALOG_IDS);
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

    // --- Entry password (own account) ---
    //
    // Optional per-account PIN that gates *switching INTO* this account from any other
    // selected account. Independent of the Private Space PIN (which gates entering PS
    // *within* an already-open account). Same SHA-256 4–6-digit format.

    public boolean hasEntryPassword() {
        return !TextUtils.isEmpty(entryPasswordHash);
    }

    public boolean verifyEntryPassword(String pin) {
        if (pin == null) return false;
        if (!hasEntryPassword()) return true;
        return hashPassword(pin).equals(entryPasswordHash);
    }

    /** Pass empty/null to clear. */
    public void setEntryPassword(String pin) {
        entryPasswordHash = TextUtils.isEmpty(pin) ? "" : hashPassword(pin);
        getMessagesController().getMainSettings().edit().putString(PREF_ENTRY_PASSWORD_HASH, entryPasswordHash).apply();
    }

    // --- Hidden other accounts ---
    //
    // Each PS has its own hide-list of OTHER account ids: {@code hiddenAccounts} for the
    // real space, {@code fakeHiddenAccounts} for the fake space. Visibility rule (see
    // {@link #activeHiddenAccountsForMode(int)}): real-active is privileged (nothing
    // hidden); off and fake-active hide the union of both lists. This keeps the real
    // user's deniability list active for an attacker who landed in fake, while still
    // letting the fake user configure their own decoy hide-list.

    public boolean isAccountHidden(int otherAccountNum) {
        // UI-facing checker: returns membership in the CURRENT mode's hide-list, so the
        // settings activity check-state always reflects the space the user is editing.
        return currentHiddenAccountSet().contains(otherAccountNum);
    }

    public void setAccountHidden(int otherAccountNum, boolean hidden) {
        if (otherAccountNum == currentAccount) return;
        Set<Integer> target = currentHiddenAccountSet();
        boolean changed = hidden ? target.add(otherAccountNum) : target.remove(otherAccountNum);
        if (!changed) return;
        if (target == fakeHiddenAccounts) {
            persistFakeHiddenAccounts();
        } else {
            persistHiddenAccounts();
        }
        getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceModeChanged);
        if (currentAccount != UserConfig.selectedAccount) {
            return;
        }
        // Apply the change to the tray *only* when the toggled list is currently active.
        boolean affectsCurrentView = activeHiddenAccountsForMode(activeMode).contains(otherAccountNum)
                == hidden; // i.e. the toggle's outcome matches what the current mode would do
        // Simpler: recompute, see if the account ends up hidden right now, sync notifications.
        boolean shouldHideNow = activeHiddenAccountsForMode(activeMode).contains(otherAccountNum);
        if (shouldHideNow) {
            NotificationsController.getInstance(otherAccountNum).hideNotifications();
        } else {
            NotificationsController.getInstance(otherAccountNum).showNotifications();
        }
        NotificationsController.getInstance(currentAccount).updateBadge();
    }

    private Set<Integer> currentHiddenAccountSet() {
        return activeMode == MODE_FAKE ? fakeHiddenAccounts : hiddenAccounts;
    }

    public Set<Integer> getHiddenAccounts() {
        return Collections.unmodifiableSet(hiddenAccounts);
    }

    public Set<Integer> getFakeHiddenAccounts() {
        return Collections.unmodifiableSet(fakeHiddenAccounts);
    }

    /** Called from logout cleanup — drop a deactivated account from BOTH hide-lists. */
    public void onOtherAccountLoggedOut(int otherAccountNum) {
        if (hiddenAccounts.remove(otherAccountNum)) {
            persistHiddenAccounts();
        }
        if (fakeHiddenAccounts.remove(otherAccountNum)) {
            persistFakeHiddenAccounts();
        }
    }

    private void persistHiddenAccounts() {
        persistIntCsv(hiddenAccounts, PREF_HIDDEN_ACCOUNTS);
    }

    private void persistFakeHiddenAccounts() {
        persistIntCsv(fakeHiddenAccounts, PREF_FAKE_HIDDEN_ACCOUNTS);
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
     * Whether an account should be hidden from the currently selected account's UI.
     * Per-mode: real-active reveals everything; off and fake-active hide the union of
     * both spaces' hide-lists. Returns {@code false} for self.
     */
    public static boolean isHiddenFromSelectedAccount(int targetAccountNum) {
        int selected = UserConfig.selectedAccount;
        if (targetAccountNum == selected) return false;
        SecondSpaceController ssc = getInstance(selected);
        return ssc.activeHiddenAccountsForMode(ssc.activeMode).contains(targetAccountNum);
    }
}
