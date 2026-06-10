package org.telegram.messenger.leemen;

import android.util.Base64;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3 orchestrator — keeps SecondSpaceController (plain sets) and the E2E CRDT blob in sync with the
 * backend. All state mutation runs on the UI thread (LeemenRestClient delivers callbacks on the UI thread);
 * only the REST IO is async, so there are no data races on the per-account working copy. XChaCha20 over a
 * few-KB blob is sub-millisecond, so crypto on the UI thread is fine.
 *
 * Cycle order (critical for correctness — see Sync Blob Schema §6.5):
 *   1. PULL content+filter (raw, not yet merged) and observe their lamport.
 *   2. RECONCILE controller→local baseline: local edits are stamped with lamport > everything just seen, so
 *      a fresh local change (esp. a hide) survives the merge instead of losing to a higher remote lamport.
 *   3. MERGE the pulled remotes into the local working copy.
 *   4. RECOMPUTE the off-mode-visible cache, PROJECT the merged result into the controller, persist.
 *   5. PUSH with CAS. Order respects tear-tolerance: membership REMOVES push filter-first-then-content;
 *      everything else pushes content-first-then-filter.
 *
 * Pushes are debounced (~5 s) and coalesced; a mutation mid-cycle sets a dirty bit and re-runs at the end.
 *
 * Deferred (intentional, v1): Tier-P platform settings and ps_pin are PRESERVED verbatim by the merge but
 * not yet reconciled/projected — ps_pin waits on the SHA-256→Argon2id PIN re-enrollment (§7.2); platform
 * sync is a follow-up. Neither is dropped on round-trip.
 */
public final class LeemenSync {

    private LeemenSync() {}

    private static final int N = UserConfig.MAX_ACCOUNT_COUNT;
    // Short coalescing window: collapses the multi-write storm of a single PS operation (e.g. un-hide
    // fires 5 persist calls) into one push, while still feeling instant. Not a throttle.
    private static final long DEBOUNCE_MS = 500;
    private static final int MAX_CAS_RETRY = 4;

    private static final LeemenSyncState[] STATES = new LeemenSyncState[N];
    private static final boolean[] busy = new boolean[N];
    private static final boolean[] dirty = new boolean[N];
    private static final Runnable[] debounce = new Runnable[N];
    private static final Runnable[] watchdog = new Runnable[N];
    private static final boolean[] pending = new boolean[N]; // OFF-mode list gated until first sync (anti-flash)

    private interface RawCb { void on(Object blob, long version); }

    // ===== public entry points =====

    /** A local PS-state change happened — schedule a debounced push for this account. Thread-safe. */
    public static void onLocalMutation(final int account) {
        if (!inRange(account)) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!ready(account)) return;
            if (debounce[account] != null) AndroidUtilities.cancelRunOnUIThread(debounce[account]);
            debounce[account] = () -> { debounce[account] = null; syncAccount(account); };
            AndroidUtilities.runOnUIThread(debounce[account], DEBOUNCE_MS);
        });
    }

    /** Pull+push every ready account (app foreground / Realtime reconnect). Thread-safe. */
    public static void syncAll() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < N; a++) {
                if (ready(a)) syncAccount(a);
            }
        });
    }

    /** Sync now (key just became ready / Realtime blob_changed notify). Thread-safe. */
    public static void onRemoteChanged(final int account) {
        AndroidUtilities.runOnUIThread(() -> { if (ready(account)) syncAccount(account); });
    }

    /** Drop all sync state for an account (call on logout, before the slot is reused). */
    public static void clearAccount(int account) {
        if (!inRange(account)) return;
        STATES[account] = null;
        busy[account] = false;
        dirty[account] = false;
        if (debounce[account] != null) {
            AndroidUtilities.cancelRunOnUIThread(debounce[account]);
            debounce[account] = null;
        }
        cancelWatchdog(account);
        pending[account] = false;
        LeemenSyncState.clear(account);
    }

    /** Suppress the OFF-mode chat list for this account until its first sync resolves (no hidden-chat
     *  flash on fresh login, when the local hidden set is still empty). Auto-clears after the first sync
     *  projects the real set, or after a fallback timeout if sync never completes (e.g. offline login),
     *  so the list is never stuck empty. */
    public static void markSyncPending(final int account) {
        if (!inRange(account)) return;
        pending[account] = true;
        reloadDialogs(account);
        AndroidUtilities.runOnUIThread(() -> clearPending(account), 15000);
    }

    public static boolean isInitialSyncPending(int account) {
        return inRange(account) && pending[account];
    }

    private static void clearPending(int account) {
        if (!inRange(account) || !pending[account]) return;
        pending[account] = false;
        reloadDialogs(account);
    }

    private static void reloadDialogs(int account) {
        try {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        } catch (Throwable ignore) {}
    }

    // ===== orchestration (UI thread) =====

    private static void syncAccount(final int account) {
        if (!ready(account)) return;
        if (busy[account]) { dirty[account] = true; return; }
        busy[account] = true;
        scheduleWatchdog(account);
        if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: sync start account " + account);

        final byte[] key = LeemenKey.getKMaster(account);
        final String token = LeemenAccount.getToken(account);
        if (key == null || token == null) { finish(account); return; }

        final LeemenSyncState st = state(account);

        // 1. pull both blobs raw (no merge yet), then 2..5
        pullRaw(LeemenConfig.EP_CONTENT, false, key, token, (rc, cver) ->
            pullRaw(LeemenConfig.EP_FILTER, true, key, token, (rf, fver) -> {
                try {
                    long remoteLamport = Math.max(
                            rc instanceof LeemenBlob.ContentBlob ? ((LeemenBlob.ContentBlob) rc).lamport : 0,
                            rf instanceof LeemenBlob.FilterBlob ? ((LeemenBlob.FilterBlob) rf).lamport : 0);
                    st.observeLamport(remoteLamport);

                    boolean membershipRemoved = reconcileFromController(account, st);

                    if (rc instanceof LeemenBlob.ContentBlob) LeemenMerge.mergeContent(st.content, (LeemenBlob.ContentBlob) rc);
                    if (rf instanceof LeemenBlob.FilterBlob) LeemenMerge.mergeFilter(st.filter, (LeemenBlob.FilterBlob) rf);
                    if (cver >= 0) st.contentVersion = cver;
                    if (fver >= 0) st.filterVersion = fver;

                    final Runnable projectAndPush = () -> {
                        pending[account] = false; // gate off: we now have the latest hidden set to project
                        LeemenMerge.recomputeOffModeVisible(st.filter, st.content);
                        projectToController(account, st);
                        st.persist();
                        final Runnable done = () -> {
                            if (BuildVars.LOGS_ENABLED) {
                                int hidden = 0;
                                for (LeemenBlob.Reg r : st.filter.hidden_chat_ids.values()) if (LeemenBlob.isLive(r)) hidden++;
                                FileLog.d("Leemen: sync done account " + account + " filterV=" + st.filterVersion
                                        + " contentV=" + st.contentVersion + " hiddenChats=" + hidden);
                            }
                            Arrays.fill(key, (byte) 0);
                            st.persist();
                            finish(account);
                        };
                        if (membershipRemoved) {
                            // removes: filter first (membership gone), then content (cascade)
                            pushBlob(LeemenConfig.EP_FILTER, true, st, key, token, MAX_CAS_RETRY, () ->
                                    pushBlob(LeemenConfig.EP_CONTENT, false, st, key, token, MAX_CAS_RETRY, done));
                        } else {
                            // adds/updates: content first (detail), then filter (membership)
                            pushBlob(LeemenConfig.EP_CONTENT, false, st, key, token, MAX_CAS_RETRY, () -> {
                                LeemenMerge.recomputeOffModeVisible(st.filter, st.content);
                                pushBlob(LeemenConfig.EP_FILTER, true, st, key, token, MAX_CAS_RETRY, done);
                            });
                        }
                    };
                    // §6.5 self-heal: filter ahead of content (a torn remote write) → re-GET content before
                    // making any exposure/membership projection, so we never act on a stale content blob.
                    if (st.filter.lamport > st.content.lamport) {
                        pullRaw(LeemenConfig.EP_CONTENT, false, key, token, (rc2, cver2) -> {
                            if (rc2 instanceof LeemenBlob.ContentBlob) LeemenMerge.mergeContent(st.content, (LeemenBlob.ContentBlob) rc2);
                            if (cver2 >= 0) st.contentVersion = cver2;
                            projectAndPush.run();
                        });
                    } else {
                        projectAndPush.run();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    Arrays.fill(key, (byte) 0);
                    finish(account);
                }
            }));
    }

    private static void finish(int account) {
        cancelWatchdog(account);
        busy[account] = false;
        if (dirty[account]) { dirty[account] = false; syncAccount(account); }
    }

    /** Safety net: if a sync cycle never completes (a callback that never fires), reset busy so the
     *  account isn't permanently blocked. Timeout is far longer than any legitimate cycle. */
    private static void scheduleWatchdog(int account) {
        cancelWatchdog(account);
        watchdog[account] = () -> {
            watchdog[account] = null;
            if (busy[account]) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: sync watchdog reset stuck busy for account " + account);
                busy[account] = false;
                if (dirty[account]) { dirty[account] = false; syncAccount(account); }
            }
        };
        AndroidUtilities.runOnUIThread(watchdog[account], 300000);
    }

    private static void cancelWatchdog(int account) {
        if (watchdog[account] != null) {
            AndroidUtilities.cancelRunOnUIThread(watchdog[account]);
            watchdog[account] = null;
        }
    }

    // ===== transport =====

    /** GET + decrypt + parse a blob, WITHOUT merging. version = server version (0 if 404/create, -1 if unknown). */
    private static void pullRaw(String path, boolean isFilter, byte[] key, String token, RawCb cb) {
        LeemenRestClient.get(path, token, (body, code, ec, em) -> {
            Object blob = null;
            long version = -1;
            try {
                if (code == 200 && body != null && body.has("encrypted_data")) {
                    version = longField(body, "version");
                    byte[] plain = decryptBody(body, key);
                    if (plain != null) {
                        blob = isFilter ? LeemenBlob.filterFromBytes(plain) : LeemenBlob.contentFromBytes(plain);
                    } else if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: decrypt failed on GET " + path);
                    }
                } else if (code == 404) {
                    version = 0; // not created yet → next PUT creates with prev_version=0
                } else if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: GET " + path + " code=" + code + " err=" + ec);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            cb.on(blob, version);
        });
    }

    private static void pushBlob(String path, boolean isFilter, LeemenSyncState st,
                                 byte[] key, String token, int retriesLeft, Runnable onDone) {
        try {
            Object blob = isFilter ? st.filter : st.content;
            if (isFilter) padToBucket(st.filter);
            byte[] plain = LeemenBlob.toBytes(blob);
            byte[] nonce = LeemenCrypto.randomNonce();
            byte[] ct = LeemenCrypto.encrypt(plain, nonce, key);
            if (ct == null) { onDone.run(); return; }
            long prev = isFilter ? st.filterVersion : st.contentVersion;

            JsonObject reqBody = new JsonObject();
            reqBody.addProperty("encrypted_data", Base64.encodeToString(ct, Base64.NO_WRAP));
            reqBody.addProperty("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP));
            reqBody.addProperty("prev_version", prev);

            LeemenRestClient.put(path, token, reqBody, (body, code, ec, em) -> {
                if (code >= 200 && code < 300 && body != null && body.has("version")) {
                    long v = longField(body, "version");
                    if (isFilter) st.filterVersion = v; else st.contentVersion = v;
                    onDone.run();
                } else if (code == 409 && retriesLeft > 0) {
                    // CAS conflict: pull latest, merge, retry with the new version
                    pullRaw(path, isFilter, key, token, (rb, ver) -> {
                        if (rb instanceof LeemenBlob.FilterBlob) {
                            LeemenMerge.mergeFilter(st.filter, (LeemenBlob.FilterBlob) rb);
                            LeemenMerge.recomputeOffModeVisible(st.filter, st.content); // membership changed → refresh cache
                        } else if (rb instanceof LeemenBlob.ContentBlob) {
                            LeemenMerge.mergeContent(st.content, (LeemenBlob.ContentBlob) rb);
                            LeemenMerge.recomputeOffModeVisible(st.filter, st.content);
                        }
                        if (ver >= 0) { if (isFilter) st.filterVersion = ver; else st.contentVersion = ver; }
                        pushBlob(path, isFilter, st, key, token, retriesLeft - 1, onDone);
                    });
                } else {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: PUT " + path + " code=" + code + " err=" + ec + " (giving up this cycle)");
                    onDone.run();
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
            onDone.run();
        }
    }

    private static byte[] decryptBody(JsonObject body, byte[] key) {
        try {
            byte[] ct = Base64.decode(body.get("encrypted_data").getAsString(), Base64.NO_WRAP);
            byte[] nonce = Base64.decode(body.get("nonce").getAsString(), Base64.NO_WRAP);
            return LeemenCrypto.decrypt(ct, nonce, key);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ===== reconcile: controller -> blob registers. Returns true iff a membership REMOVE was stamped. =====

    private static boolean reconcileFromController(int account, LeemenSyncState st) {
        SecondSpaceController c = SecondSpaceController.getInstance(account);
        String dev = st.deviceId();
        long[] lam = {0};
        boolean[] membershipRemoved = {false};

        Set<Long> members = new HashSet<>(c.getDialogIds());

        // membership adds
        for (Long id : members) {
            String k = String.valueOf(id);
            if (!LeemenBlob.isLive(st.filter.hidden_chat_ids.get(k))) {
                st.filter.hidden_chat_ids.put(k, new LeemenBlob.Reg(LeemenBlob.PRESENT, lam(st, lam), dev));
            }
        }
        // membership removes + cascade
        for (Map.Entry<String, LeemenBlob.Reg> e : new ArrayList<>(st.filter.hidden_chat_ids.entrySet())) {
            if (LeemenBlob.isLive(e.getValue()) && !members.contains(parseLong(e.getKey()))) {
                long l = lam(st, lam);
                st.filter.hidden_chat_ids.put(e.getKey(), new LeemenBlob.Reg(LeemenBlob.REMOVED, l, dev));
                LeemenBlob.PerChat pc = perChat(st, e.getKey());
                pc.cleared_at_c = Math.max(pc.cleared_at_c, l);
                membershipRemoved[0] = true;
            }
        }

        // per-chat msg_state + self_pinned, only for live members
        for (Long id : members) {
            String dk = String.valueOf(id);
            LeemenBlob.PerChat pc = perChat(st, dk);
            Set<Integer> exposed = c.getExposedMessageIds(id);
            Set<Integer> pending = c.getPendingMessages(id);
            Set<Integer> pins = c.getSelfPinnedMessageIds(id);

            // exposed (takes precedence over pending if a message is transiently in both)
            for (Integer m : nz(exposed)) {
                if (m == null || m <= 0) continue; // §4b: never sync negative local placeholder ids
                LeemenBlob.Reg r = pc.msg_state.get(String.valueOf(m));
                if (!(r != null && LeemenBlob.EXPOSED.equals(r.s) && LeemenMerge.afterClear(r, pc.cleared_at_c))) {
                    pc.msg_state.put(String.valueOf(m), new LeemenBlob.Reg(LeemenBlob.EXPOSED, lam(st, lam), dev));
                }
            }
            for (Integer m : nz(pending)) {
                if (m == null || m <= 0) continue; // §4b: never sync negative local placeholder ids
                if (exposed != null && exposed.contains(m)) continue; // exposed wins
                LeemenBlob.Reg r = pc.msg_state.get(String.valueOf(m));
                if (!(r != null && LeemenBlob.PENDING.equals(r.s) && LeemenMerge.afterClear(r, pc.cleared_at_c))) {
                    pc.msg_state.put(String.valueOf(m), new LeemenBlob.Reg(LeemenBlob.PENDING, lam(st, lam), dev));
                }
            }
            // live exposed/pending registers whose message is no longer exposed/pending locally -> hidden
            for (Map.Entry<String, LeemenBlob.Reg> e : new ArrayList<>(pc.msg_state.entrySet())) {
                LeemenBlob.Reg r = e.getValue();
                if (!LeemenMerge.afterClear(r, pc.cleared_at_c)) continue;
                if (LeemenBlob.EXPOSED.equals(r.s) || LeemenBlob.PENDING.equals(r.s)) {
                    int m = (int) parseLong(e.getKey());
                    boolean live = (exposed != null && exposed.contains(m)) || (pending != null && pending.contains(m));
                    if (!live) {
                        pc.msg_state.put(e.getKey(), new LeemenBlob.Reg(LeemenBlob.HIDDEN, lam(st, lam), dev));
                    }
                }
            }
            // self_pinned adds
            for (Integer m : nz(pins)) {
                if (m == null || m <= 0) continue; // §4b: never sync negative local placeholder ids
                LeemenBlob.Reg r = pc.self_pinned.get(String.valueOf(m));
                if (!(LeemenBlob.isLive(r) && LeemenMerge.afterClear(r, pc.cleared_at_c))) {
                    pc.self_pinned.put(String.valueOf(m), new LeemenBlob.Reg(LeemenBlob.PRESENT, lam(st, lam), dev));
                }
            }
            // self_pinned removes
            for (Map.Entry<String, LeemenBlob.Reg> e : new ArrayList<>(pc.self_pinned.entrySet())) {
                LeemenBlob.Reg r = e.getValue();
                if (LeemenBlob.isLive(r) && LeemenMerge.afterClear(r, pc.cleared_at_c)) {
                    int m = (int) parseLong(e.getKey());
                    if (pins == null || !pins.contains(m)) {
                        pc.self_pinned.put(e.getKey(), new LeemenBlob.Reg(LeemenBlob.REMOVED, lam(st, lam), dev));
                    }
                }
            }
        }

        // private search
        Set<Long> psDialogs = c.getPrivateSearchDialogIds();
        for (Long id : psDialogs) {
            String k = String.valueOf(id);
            if (!LeemenBlob.isLive(st.content.private_search_dialog_ids.get(k))) {
                st.content.private_search_dialog_ids.put(k, new LeemenBlob.Reg(LeemenBlob.PRESENT, lam(st, lam), dev));
            }
        }
        for (Map.Entry<String, LeemenBlob.Reg> e : new ArrayList<>(st.content.private_search_dialog_ids.entrySet())) {
            if (LeemenBlob.isLive(e.getValue()) && !psDialogs.contains(parseLong(e.getKey()))) {
                st.content.private_search_dialog_ids.put(e.getKey(), new LeemenBlob.Reg(LeemenBlob.REMOVED, lam(st, lam), dev));
            }
        }

        // settings
        int pinTimeout = c.getPinTimeoutMinutes();
        LeemenBlob.IntVal iv = st.content.private_space_settings.pin_timeout_minutes;
        if (iv == null || iv.v != pinTimeout) {
            st.content.private_space_settings.pin_timeout_minutes = new LeemenBlob.IntVal(pinTimeout, lam(st, lam), dev);
        }
        boolean allowSs = c.isScreenshotsAllowed();
        LeemenBlob.BoolVal bv = st.content.private_space_settings.allow_screenshots;
        if (bv == null || bv.v != allowSs) {
            st.content.private_space_settings.allow_screenshots = new LeemenBlob.BoolVal(allowSs, lam(st, lam), dev);
        }

        return membershipRemoved[0];
    }

    // ===== project: blob registers -> controller =====

    private static void projectToController(int account, LeemenSyncState st) {
        SecondSpaceController c = SecondSpaceController.getInstance(account);

        Set<Long> members = new HashSet<>();
        for (Map.Entry<String, LeemenBlob.Reg> e : st.filter.hidden_chat_ids.entrySet()) {
            if (LeemenBlob.isLive(e.getValue())) members.add(parseLong(e.getKey()));
        }

        Map<Long, Set<Integer>> exposed = new HashMap<>();
        Map<Long, Set<Integer>> pending = new HashMap<>();
        Map<Long, Set<Integer>> selfPinned = new HashMap<>();
        for (Long id : members) {
            LeemenBlob.PerChat pc = st.content.per_chat.get(String.valueOf(id));
            if (pc == null) continue;
            for (Map.Entry<String, LeemenBlob.Reg> e : pc.msg_state.entrySet()) {
                LeemenBlob.Reg r = e.getValue();
                if (!LeemenMerge.afterClear(r, pc.cleared_at_c)) continue;
                int m = (int) parseLong(e.getKey());
                if (LeemenBlob.EXPOSED.equals(r.s)) add(exposed, id, m);
                else if (LeemenBlob.PENDING.equals(r.s)) add(pending, id, m);
            }
            for (Map.Entry<String, LeemenBlob.Reg> e : pc.self_pinned.entrySet()) {
                LeemenBlob.Reg r = e.getValue();
                if (LeemenBlob.isLive(r) && LeemenMerge.afterClear(r, pc.cleared_at_c)) {
                    add(selfPinned, id, (int) parseLong(e.getKey()));
                }
            }
        }

        Set<Long> privateSearch = new HashSet<>();
        for (Map.Entry<String, LeemenBlob.Reg> e : st.content.private_search_dialog_ids.entrySet()) {
            if (LeemenBlob.isLive(e.getValue())) privateSearch.add(parseLong(e.getKey()));
        }

        Integer pinTimeout = st.content.private_space_settings.pin_timeout_minutes != null
                ? (int) st.content.private_space_settings.pin_timeout_minutes.v : null;
        Boolean allowSs = st.content.private_space_settings.allow_screenshots != null
                ? st.content.private_space_settings.allow_screenshots.v : null;

        c.applySyncedState(members, exposed, pending, selfPinned, privateSearch, pinTimeout, allowSs);
    }

    // ===== helpers =====

    private static boolean inRange(int account) {
        return account >= 0 && account < N;
    }

    private static boolean ready(int account) {
        return inRange(account)
                && UserConfig.getInstance(account).isClientActivated()
                && LeemenAccount.hasBinding(account)
                && LeemenAccount.hasKMaster(account);
    }

    private static LeemenSyncState state(int account) {
        if (STATES[account] == null) STATES[account] = LeemenSyncState.load(account);
        return STATES[account];
    }

    private static long lam(LeemenSyncState st, long[] holder) {
        if (holder[0] == 0) holder[0] = st.nextLamport();
        return holder[0];
    }

    private static LeemenBlob.PerChat perChat(LeemenSyncState st, String dialogKey) {
        LeemenBlob.PerChat pc = st.content.per_chat.get(dialogKey);
        if (pc == null) {
            pc = new LeemenBlob.PerChat();
            st.content.per_chat.put(dialogKey, pc);
        }
        return pc;
    }

    private static void add(Map<Long, Set<Integer>> map, long dialog, int msg) {
        Set<Integer> s = map.get(dialog);
        if (s == null) { s = new HashSet<>(); map.put(dialog, s); }
        s.add(msg);
    }

    private static Set<Integer> nz(Set<Integer> s) {
        return s == null ? java.util.Collections.emptySet() : s;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Throwable e) { return 0; }
    }

    private static long longField(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0; } catch (Throwable e) { return 0; }
    }

    /** Pad filter_blob to the next size bucket (§7.4) to damp the #hidden-chats size side-channel. */
    private static void padToBucket(LeemenBlob.FilterBlob f) {
        f.pad = null;
        int len = LeemenBlob.toBytes(f).length;
        int target = len <= 4096 ? 4096 : (len <= 16384 ? 16384 : 65536);
        int padLen = target - len - 12; // ~overhead of adding the "pad" field
        if (padLen > 0) {
            char[] buf = new char[padLen];
            Arrays.fill(buf, 'a');
            f.pad = new String(buf);
        }
    }
}
