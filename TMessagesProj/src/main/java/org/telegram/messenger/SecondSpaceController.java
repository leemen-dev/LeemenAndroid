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

public class SecondSpaceController extends BaseController {

    private static final String PREF_DIALOG_IDS = "second_space_dialogs";
    private static final String PREF_EXPOSED = "second_space_exposed";
    private static final String PREF_LAST_DECIDED = "second_space_last_decided";
    private static final String PREF_SHOW_ENTRY_BUTTON = "second_space_show_entry_button";
    private static final String PREF_PENDING_OFF_MODE = "second_space_pending_off_mode";
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

    private final Set<Long> dialogIds = new HashSet<>();
    private final Map<Long, Set<Integer>> exposedMessages = new HashMap<>();
    private final Map<Long, Integer> lastDecidedMessageId = new HashMap<>();
    private final Map<Long, MessageObject> lastExposedMessageCache = new HashMap<>();
    private final Set<Long> pendingOffModeWork = new HashSet<>();
    private final Set<Long> privateSearchDialogs = new HashSet<>();
    private String passwordHash;
    private final java.util.List<TabStep> tabSequence = new java.util.ArrayList<>();
    private boolean pinInSearchEnabled;
    private boolean shortcutTested;
    private int pinTimeoutMinutes;
    private long pinLastVerifiedAt;
    private boolean active;
    private String entryPasswordHash;
    private final Set<Integer> hiddenAccounts = new HashSet<>();

    private boolean entryButtonVisible;

    private SecondSpaceController(int num) {
        super(num);
        SharedPreferences prefs = getMessagesController().getMainSettings();
        // active is intentionally non-persistent: always starts false on app launch (deniability)
        active = false;
        entryButtonVisible = prefs.getBoolean(PREF_SHOW_ENTRY_BUTTON, true);
        loadDialogIds(prefs.getString(PREF_DIALOG_IDS, ""));
        loadExposed(prefs.getString(PREF_EXPOSED, ""));
        loadLastDecided(prefs.getString(PREF_LAST_DECIDED, ""));
        String pendingCsv = prefs.getString(PREF_PENDING_OFF_MODE, "");
        if (!TextUtils.isEmpty(pendingCsv)) {
            for (String s : pendingCsv.split(",")) {
                try {
                    pendingOffModeWork.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
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
        loadTabSequence(prefs.getString(PREF_TAB_SEQUENCE, ""));
        pinInSearchEnabled = prefs.getBoolean(PREF_PIN_IN_SEARCH, false);
        shortcutTested = prefs.getBoolean(PREF_SHORTCUT_TESTED, false);
        pinTimeoutMinutes = prefs.getInt(PREF_PIN_TIMEOUT_MIN, 0);
        pinLastVerifiedAt = prefs.getLong(PREF_PIN_LAST_OK_MS, 0L);
        entryPasswordHash = prefs.getString(PREF_ENTRY_PASSWORD_HASH, "");
        String hiddenAccountsCsv = prefs.getString(PREF_HIDDEN_ACCOUNTS, "");
        if (!TextUtils.isEmpty(hiddenAccountsCsv)) {
            for (String s : hiddenAccountsCsv.split(",")) {
                try {
                    int id = Integer.parseInt(s);
                    // Defense in depth: an account can never hide itself.
                    if (id != num) {
                        hiddenAccounts.add(id);
                    }
                } catch (NumberFormatException ignored) {
                }
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

    /** Should the PIN prompt be skipped right now because of a recent successful entry? */
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

    public boolean hasPassword() {
        return !TextUtils.isEmpty(passwordHash);
    }

    public boolean verifyPassword(String pin) {
        if (pin == null) return false;
        if (!hasPassword()) return true;
        return hashPassword(pin).equals(passwordHash);
    }

    /** Pass empty/null to clear. */
    public void setPassword(String pin) {
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

    private void loadDialogIds(String csv) {
        if (TextUtils.isEmpty(csv)) return;
        for (String s : csv.split(",")) {
            try {
                dialogIds.add(Long.parseLong(s));
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

    public void addToSecondSpace(long dialogId) {
        if (dialogIds.add(dialogId)) {
            TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(dialogId);
            if (dialog != null && dialog.top_message > 0 && !lastDecidedMessageId.containsKey(dialogId)) {
                lastDecidedMessageId.put(dialogId, dialog.top_message);
                persistLastDecided();
            }
            persistDialogIds();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void removeFromSecondSpace(long dialogId) {
        if (dialogIds.remove(dialogId)) {
            exposedMessages.remove(dialogId);
            lastDecidedMessageId.remove(dialogId);
            lastExposedMessageCache.remove(dialogId);
            persistDialogIds();
            persistExposed();
            persistLastDecided();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public Set<Long> getDialogIds() {
        return Collections.unmodifiableSet(dialogIds);
    }

    // --- Mode ---

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        // Intentionally NOT persisted: active is per-session, always false on next launch.
        // Re-evaluate FLAG_SECURE: snapshot capture / screenshots must be blocked while PS is on.
        try {
            org.telegram.ui.LaunchActivity la = org.telegram.ui.LaunchActivity.instance;
            if (la != null) la.invalidateFlagSecure();
        } catch (Throwable ignored) {
        }
        NotificationCenter nc = getNotificationCenter();
        nc.postNotificationName(NotificationCenter.secondSpaceModeChanged);
        nc.postNotificationName(NotificationCenter.dialogsNeedReload);
        // When this account is the selected one, accounts it hides must refresh their tray
        // notifications: dismiss on PS-off, re-show queued ones on PS-on. Also re-aggregate
        // the launcher badge across all accounts.
        if (currentAccount == UserConfig.selectedAccount && !hiddenAccounts.isEmpty()) {
            for (Integer hiddenAcc : hiddenAccounts) {
                if (value) {
                    NotificationsController.getInstance(hiddenAcc).showNotifications();
                } else {
                    NotificationsController.getInstance(hiddenAcc).hideNotifications();
                }
            }
            NotificationsController.getInstance(currentAccount).updateBadge();
        }
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

    // --- Pending off-mode work (per chat flag) ---
    //
    // Set when user sends a message in a private-space chat while OFF mode is on
    // (i.e. via search-by-name). On the next entry of the same chat in ACTIVE mode,
    // the decision dialog "what to do with these messages" should fire.

    public boolean hasPendingOffModeWork(long dialogId) {
        return pendingOffModeWork.contains(dialogId);
    }

    public void markPendingOffModeWork(long dialogId) {
        if (pendingOffModeWork.add(dialogId)) {
            persistPendingOffModeWork();
            // Make the hidden chat appear in the off-mode dialogs list immediately.
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    public void clearPendingOffModeWork(long dialogId) {
        if (pendingOffModeWork.remove(dialogId)) {
            persistPendingOffModeWork();
            getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    private void persistPendingOffModeWork() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Long id : pendingOffModeWork) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        getMessagesController().getMainSettings().edit().putString(PREF_PENDING_OFF_MODE, sb.toString()).apply();
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
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Long id : dialogIds) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        getMessagesController().getMainSettings().edit().putString(PREF_DIALOG_IDS, sb.toString()).apply();
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
    // Per-account list of OTHER account IDs that this account hides from the switcher /
    // notifications / badge while it itself is in off-mode. When this account is the
    // selected one and in PS-on, all accounts become visible again.

    public boolean isAccountHidden(int otherAccountNum) {
        return hiddenAccounts.contains(otherAccountNum);
    }

    public void setAccountHidden(int otherAccountNum, boolean hidden) {
        if (otherAccountNum == currentAccount) return;
        boolean changed = hidden ? hiddenAccounts.add(otherAccountNum) : hiddenAccounts.remove(otherAccountNum);
        if (changed) {
            persistHiddenAccounts();
            getNotificationCenter().postNotificationName(NotificationCenter.secondSpaceModeChanged);
            // If this account is currently selected and off-mode, hiding-state change must
            // immediately reflect in the tray for the toggled account.
            if (currentAccount == UserConfig.selectedAccount && !active) {
                if (hidden) {
                    NotificationsController.getInstance(otherAccountNum).hideNotifications();
                } else {
                    NotificationsController.getInstance(otherAccountNum).showNotifications();
                }
                NotificationsController.getInstance(currentAccount).updateBadge();
            }
        }
    }

    public Set<Integer> getHiddenAccounts() {
        return Collections.unmodifiableSet(hiddenAccounts);
    }

    /** Called from logout cleanup — drop a deactivated account from this account's hide-list. */
    public void onOtherAccountLoggedOut(int otherAccountNum) {
        if (hiddenAccounts.remove(otherAccountNum)) {
            persistHiddenAccounts();
        }
    }

    private void persistHiddenAccounts() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer id : hiddenAccounts) {
            if (!first) sb.append(',');
            sb.append(id);
            first = false;
        }
        getMessagesController().getMainSettings().edit().putString(PREF_HIDDEN_ACCOUNTS, sb.toString()).apply();
    }

    /**
     * Whether an account should be hidden from the currently selected account's UI.
     * True iff: (a) selected account is in off-mode AND (b) selected account's hide-list contains targetAccountNum.
     * Returns false if targetAccountNum == selectedAccount (you can never hide yourself from yourself).
     */
    public static boolean isHiddenFromSelectedAccount(int targetAccountNum) {
        int selected = UserConfig.selectedAccount;
        if (targetAccountNum == selected) return false;
        SecondSpaceController ssc = getInstance(selected);
        if (ssc.isActive()) return false;
        return ssc.isAccountHidden(targetAccountNum);
    }
}
