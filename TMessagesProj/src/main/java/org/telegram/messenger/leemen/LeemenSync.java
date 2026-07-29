package org.telegram.messenger.leemen;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * Pushes are debounced (~500 ms) and coalesced; a mutation mid-cycle sets a dirty bit and re-runs at the end.
 *
 * Core state, ps_pin, and this client's Tier-P Android entry settings are reconciled/projected. Unknown
 * platform sections are preserved verbatim so another client platform's settings survive round-trips.
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
    /** Incremented on logout/delete so callbacks captured by the previous slot generation become inert. */
    private static final long[] lifecycleGeneration = new long[N];
    private static final Runnable[] debounce = new Runnable[N];
    private static final Runnable[] watchdog = new Runnable[N];
    // ===== Anti-leak gate (cache-first, else FAIL-CLOSED) =====
    // OFF-mode visibility uses CACHED data when we have it: once an account has EVER conclusively synced on
    // this install (everSynced, persisted), we trust the locally-persisted hidden set (dialogIds) and show
    // non-hidden chats immediately — even offline, even before this session's sync completes. Only when there
    // is NO cache yet (fresh install / never synced) do we fail closed: hide EVERY non-service chat until the
    // server conclusively delivers the hidden set. There is NO timer that reveals without server confirmation.
    private static final boolean[] everSynced = new boolean[N];      // in-memory cache of the persisted flag
    private static final boolean[] everSyncedLoaded = new boolean[N];
    private static final boolean[] loginPending = new boolean[N];    // forced gate from onAuthSuccess until 1st sync
    // The preview-warmup launch gate (hold the OFF-mode list until exposed previews are warmed) lives in
    // SecondSpaceController (isWarmupGateActive) — it owns the warmup state. isInitialSyncPending just ORs it in.
    private static final int[] gateLogState = new int[N];            // debug: last-logged gate state per account
    static { java.util.Arrays.fill(gateLogState, -1); }

    private static android.content.SharedPreferences gatePrefs() {
        return org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("leemen_sync_gate", android.content.Context.MODE_PRIVATE);
    }

    /** Has this account ever completed a conclusive sync on this install? (Persisted; false on fresh install
     *  / after logout. Cheap: in-memory after first read.) */
    private static boolean hasEverSynced(int account) {
        if (!inRange(account)) return false;
        if (!everSyncedLoaded[account]) {
            everSynced[account] = gatePrefs().getBoolean("ever_synced_" + account, false);
            everSyncedLoaded[account] = true;
        }
        return everSynced[account];
    }

    /** True once this account has completed a conclusive initial sync on this install (the OFF-mode fail-closed
     *  gate has opened). Used by the post-login bind retry to know when the chain is done. */
    public static boolean hasInitialSyncCompleted(int account) {
        return hasEverSynced(account);
    }

    private static void markEverSynced(int account) {
        if (!inRange(account)) return;
        loginPending[account] = false; // the first conclusive sync supersedes the login-window force
        if (hasEverSynced(account)) return;
        everSynced[account] = true;
        everSyncedLoaded[account] = true;
        gatePrefs().edit().putBoolean("ever_synced_" + account, true).apply();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Leemen: anti-leak gate OPENED (first conclusive sync) account " + account);
        }
    }

    private static void resetEverSynced(int account) {
        if (!inRange(account)) return;
        everSynced[account] = false;
        everSyncedLoaded[account] = true;
        gatePrefs().edit().remove("ever_synced_" + account).apply();
    }


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

    /** Realtime blob_changed notify. IGNORES the echo of our OWN write — a version we already hold — so we
     *  don't loop push → server-broadcast → pull → push forever (the version-bump loop seen when realtime is
     *  connected). Only a version NEWER than what we hold (a genuine remote change) re-syncs. Unknown
     *  table/version falls through to a normal sync (fail-safe). */
    public static void onRemoteChanged(final int account, final String table, final long version) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!ready(account)) return;
            if (version > 0 && table != null) {
                LeemenSyncState st = state(account);
                long held = "filter".equals(table) ? st.filterVersion
                        : "content".equals(table) ? st.contentVersion : -1;
                if (held >= version) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: realtime echo ignored " + table + " v" + version + " (held " + held + ")");
                    }
                    return;
                }
            }
            syncAccount(account);
        });
    }

    /** Drop all sync state for an account (call on logout, before the slot is reused). */
    public static void clearAccount(int account) {
        if (!inRange(account)) return;
        lifecycleGeneration[account]++;
        STATES[account] = null;
        busy[account] = false;
        dirty[account] = false;
        if (debounce[account] != null) {
            AndroidUtilities.cancelRunOnUIThread(debounce[account]);
            debounce[account] = null;
        }
        cancelWatchdog(account);
        loginPending[account] = false;
        // Logout/delete wipes the cached hidden set, so the next login must fail closed again until it
        // re-syncs (don't trust a stale/absent cache).
        resetEverSynced(account);
        LeemenSyncState.clear(account);
    }

    /**
     * A backend session JWT was replaced in-place for the same account generation.
     * Invalidate only the transport cycle captured with the rejected token; keep the CRDT working copy,
     * anti-leak gate, PIN and every protected-space setting intact, then restart with the fresh token.
     */
    public static void onSessionRenewed(final int account) {
        if (!inRange(account)) return;
        AndroidUtilities.runOnUIThread(() -> {
            lifecycleGeneration[account]++;
            busy[account] = false;
            dirty[account] = false;
            if (debounce[account] != null) {
                AndroidUtilities.cancelRunOnUIThread(debounce[account]);
                debounce[account] = null;
            }
            cancelWatchdog(account);
            if (ready(account)) {
                syncAccount(account);
            }
        });
    }

    /** Called at onAuthSuccess: force the fail-closed gate closed for this freshly-logged-in account RIGHT
     *  NOW, before the first sync (and even before isClientActivated may have flipped true), so a server-hidden
     *  chat can't show in the gap between login and the first conclusive sync. Cleared by markEverSynced. */
    public static void markSyncPending(final int account) {
        if (!inRange(account)) return;
        loginPending[account] = true;
        reloadDialogs(account);
    }

    /** True while the OFF-mode list must stay FAIL-CLOSED (hide every non-service chat): the account is logged
     *  in (or a fresh login is in progress) AND has NO cached hidden set yet (never conclusively synced on
     *  this install). Once we have a cache (everSynced), this returns false and hiding falls back to the cached
     *  dialogIds — so a returning account shows its chats instantly, even offline, while sync refreshes. */
    public static boolean isInitialSyncPending(int account) {
        if (!inRange(account)) return false;
        boolean activated = loginPending[account] || UserConfig.getInstance(account).isClientActivated();
        boolean pending = activated && !hasEverSynced(account);
        if (!pending && SecondSpaceController.getInstance(account).isWarmupGateActive()) {
            // Security gate open (hidden set known) — but hold the list until the INITIAL preview warmup settles,
            // so chats never appear with empty previews. SecondSpaceController owns that state and lifts it
            // reactively the moment the warmup completes (no timer). The system chat stays visible throughout.
            pending = true;
        }
        if (BuildVars.LOGS_ENABLED && gateLogState[account] != (pending ? 1 : 0)) {
            gateLogState[account] = pending ? 1 : 0;
            FileLog.d("Leemen: gate pending=" + pending + " (loginPending=" + loginPending[account]
                    + " activated=" + UserConfig.getInstance(account).isClientActivated()
                    + " everSynced=" + hasEverSynced(account)
                    + " warmupGate=" + SecondSpaceController.getInstance(account).isWarmupGateActive() + ") account " + account);
        }
        return pending;
    }

    /** Open the gate permanently for this install: a conclusive sync has projected the real hidden set, so
     *  from now on (this session AND future launches) we trust the cached set. Persisted. No reloadDialogs
     *  here — the caller's projectToController applies the set and posts the reload (so the list never
     *  rebuilds with the gate open before the hidden set is in place). */
    private static void markSyncConfirmed(int account) {
        markEverSynced(account);
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
        final long generation = lifecycleGeneration[account];
        busy[account] = true;
        scheduleWatchdog(account, generation);
        if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: sync start account " + account);

        final byte[] key = LeemenKey.getKMaster(account);
        final String token = LeemenAccount.getToken(account);
        if (key == null || token == null) { finish(account, generation); return; }

        final LeemenSyncState st = state(account);

        // 1. pull both blobs raw (no merge yet), then 2..5
        pullRaw(LeemenConfig.EP_CONTENT, false, key, token, (rc, cver) -> {
            if (!isCycleCurrent(account, generation, token)) {
                Arrays.fill(key, (byte) 0);
                return;
            }
            pullRaw(LeemenConfig.EP_FILTER, true, key, token, (rf, fver) -> {
                if (!isCycleCurrent(account, generation, token)) {
                    Arrays.fill(key, (byte) 0);
                    return;
                }
                try {
                    long remoteLamport = Math.max(
                            rc instanceof LeemenBlob.ContentBlob ? ((LeemenBlob.ContentBlob) rc).lamport : 0,
                            rf instanceof LeemenBlob.FilterBlob ? ((LeemenBlob.FilterBlob) rf).lamport : 0);
                    st.observeLamport(remoteLamport);

                    final long[] reconcileLam = {0};
                    boolean membershipRemoved = reconcileFromController(account, st, reconcileLam);
                    // reconcile consumes a lamport ONLY when it stamps a local change. Still 0 ⇒ nothing local
                    // to propagate ⇒ skip the push, so a no-op sync doesn't bump the blob version + broadcast
                    // (that unconditional push is what created the realtime push→echo→push loop, and a
                    // cross-device variant where two online devices ping-pong no-op pushes forever).
                    final boolean reconcileChanged = reconcileLam[0] != 0;

                    if (rc instanceof LeemenBlob.ContentBlob) LeemenMerge.mergeContent(st.content, (LeemenBlob.ContentBlob) rc);
                    if (rf instanceof LeemenBlob.FilterBlob) LeemenMerge.mergeFilter(st.filter, (LeemenBlob.FilterBlob) rf);
                    if (cver >= 0) st.contentVersion = cver;
                    if (fver >= 0) st.filterVersion = fver;

                    // Open the fail-closed gate ONLY when we actually KNOW the hidden set. Membership lives
                    // solely in the filter blob, so that means either: the filter DECODED (rf is a FilterBlob —
                    // 200 + successful decrypt), OR a definitive 404 (fver == 0 → no blob → genuinely no hidden
                    // chats). A 200 whose decrypt FAILED (fver >= 1 but rf == null) is NOT conclusive: the
                    // hidden set is unknown, so we must keep the gate closed instead of revealing everything.
                    final boolean conclusivePull = (rf instanceof LeemenBlob.FilterBlob) || (fver == 0);
                    final Runnable projectAndPush = () -> {
                        if (!isCycleCurrent(account, generation, token)) {
                            Arrays.fill(key, (byte) 0);
                            return;
                        }
                        if (conclusivePull) {
                            markSyncConfirmed(account); // open the fail-closed gate: the real hidden set is known
                        }
                        // The preview-warmup launch gate is armed inside applySyncedState (projectToController),
                        // which both populates the hidden set and kicks the warmup — so it owns the hold/lift.
                        LeemenMerge.recomputeOffModeVisible(st.filter, st.content);
                        projectToController(account, st);
                        st.persist();
                        final Runnable done = () -> {
                            if (!isCycleCurrent(account, generation, token)) {
                                Arrays.fill(key, (byte) 0);
                                return;
                            }
                            if (BuildVars.LOGS_ENABLED) {
                                int hidden = 0;
                                for (LeemenBlob.Reg r : st.filter.hidden_chat_ids.values()) if (LeemenBlob.isLive(r)) hidden++;
                                FileLog.d("Leemen: sync done account " + account + " filterV=" + st.filterVersion
                                        + " contentV=" + st.contentVersion + " hiddenChats=" + hidden);
                            }
                            Arrays.fill(key, (byte) 0);
                            st.persist();
                            finish(account, generation);
                        };
                        if (!reconcileChanged) {
                            // Nothing local changed (we just pulled/merged remote state, or it was a no-op
                            // realtime notify) → don't push; just finish. Stops the version-bump loop.
                            done.run();
                        } else if (membershipRemoved) {
                            // removes: filter first (membership gone), then content (cascade)
                            pushBlob(account, generation, LeemenConfig.EP_FILTER, true, st, key, token, MAX_CAS_RETRY, () ->
                                    pushBlob(account, generation, LeemenConfig.EP_CONTENT, false, st, key, token, MAX_CAS_RETRY, done));
                        } else {
                            // adds/updates: content first (detail), then filter (membership)
                            pushBlob(account, generation, LeemenConfig.EP_CONTENT, false, st, key, token, MAX_CAS_RETRY, () -> {
                                if (!isCycleCurrent(account, generation, token)) {
                                    done.run();
                                    return;
                                }
                                LeemenMerge.recomputeOffModeVisible(st.filter, st.content);
                                pushBlob(account, generation, LeemenConfig.EP_FILTER, true, st, key, token, MAX_CAS_RETRY, done);
                            });
                        }
                    };
                    // §6.5 self-heal: filter ahead of content (a torn remote write) → re-GET content before
                    // making any exposure/membership projection, so we never act on a stale content blob.
                    if (st.filter.lamport > st.content.lamport) {
                        pullRaw(LeemenConfig.EP_CONTENT, false, key, token, (rc2, cver2) -> {
                            if (!isCycleCurrent(account, generation, token)) {
                                Arrays.fill(key, (byte) 0);
                                return;
                            }
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
                    finish(account, generation);
                }
            });
        });
    }

    private static void finish(int account, long generation) {
        if (lifecycleGeneration[account] != generation) return;
        cancelWatchdog(account);
        busy[account] = false;
        if (dirty[account]) { dirty[account] = false; syncAccount(account); }
    }

    /** Safety net: if a sync cycle never completes (a callback that never fires), reset busy so the
     *  account isn't permanently blocked. Timeout is far longer than any legitimate cycle. */
    private static void scheduleWatchdog(int account, long generation) {
        cancelWatchdog(account);
        watchdog[account] = () -> {
            watchdog[account] = null;
            if (lifecycleGeneration[account] == generation && busy[account]) {
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

    private static void pushBlob(int account, long generation, String path, boolean isFilter, LeemenSyncState st,
                                 byte[] key, String token, int retriesLeft, Runnable onDone) {
        try {
            if (!isCycleCurrent(account, generation, token)) {
                onDone.run();
                return;
            }
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
                if (!isCycleCurrent(account, generation, token)) {
                    onDone.run();
                    return;
                }
                if (code >= 200 && code < 300 && body != null && body.has("version")) {
                    long v = longField(body, "version");
                    if (isFilter) st.filterVersion = v; else st.contentVersion = v;
                    onDone.run();
                } else if (code == 409 && retriesLeft > 0) {
                    // CAS conflict: pull latest, merge, retry with the new version
                    pullRaw(path, isFilter, key, token, (rb, ver) -> {
                        if (!isCycleCurrent(account, generation, token)) {
                            onDone.run();
                            return;
                        }
                        if (rb instanceof LeemenBlob.FilterBlob) {
                            LeemenMerge.mergeFilter(st.filter, (LeemenBlob.FilterBlob) rb);
                            LeemenMerge.recomputeOffModeVisible(st.filter, st.content); // membership changed → refresh cache
                        } else if (rb instanceof LeemenBlob.ContentBlob) {
                            LeemenMerge.mergeContent(st.content, (LeemenBlob.ContentBlob) rb);
                            LeemenMerge.recomputeOffModeVisible(st.filter, st.content);
                        }
                        if (ver >= 0) { if (isFilter) st.filterVersion = ver; else st.contentVersion = ver; }
                        pushBlob(account, generation, path, isFilter, st, key, token, retriesLeft - 1, onDone);
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

    private static boolean reconcileFromController(int account, LeemenSyncState st, long[] lam) {
        SecondSpaceController c = SecondSpaceController.getInstance(account);
        String dev = st.deviceId();
        boolean[] membershipRemoved = {false};

        Set<Long> members = new HashSet<>();
        for (Long id : c.getDialogIds()) {
            // never let the Telegram service chat (login codes) into the hidden set / blob, even if a prior
            // version or corruption left it in dialogIds — it would tombstone-clean out of the server blob.
            if (!org.telegram.messenger.UserObject.isService(id)) members.add(id);
        }

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

        // PS PIN (§7.2): Argon2id hash+salt register. Mirror pin_timeout — write a fresh register iff the
        // controller's PIN differs from the blob. State "" (never enrolled) leaves the blob untouched so a
        // PIN-less device can never clobber a PIN synced from elsewhere; "none" (explicit removal) tombstones it.
        String pinState = c.getPsPinState();
        LeemenBlob.PsPin curPin = st.content.ps_pin;
        if (LeemenBlob.SET.equals(pinState)) {
            String ph = c.getPsPinHashB64();
            String psalt = c.getPsPinSaltB64();
            boolean differs = curPin == null || !LeemenBlob.SET.equals(curPin.s)
                    || !ph.equals(curPin.hash) || !psalt.equals(curPin.salt);
            if (differs) {
                LeemenBlob.PsPin p = new LeemenBlob.PsPin();
                p.s = LeemenBlob.SET;
                p.hash = ph;
                p.salt = psalt;
                p.kdf = "argon2id";
                p.c = lam(st, lam);
                p.d = dev;
                st.content.ps_pin = p;
            }
        } else if (LeemenBlob.NONE.equals(pinState)) {
            if (curPin != null && LeemenBlob.SET.equals(curPin.s)) {
                LeemenBlob.PsPin p = new LeemenBlob.PsPin();
                p.s = LeemenBlob.NONE;
                p.c = lam(st, lam);
                p.d = dev;
                st.content.ps_pin = p;
            }
        }

        // Tier-P (platform-specific) — synced ONLY between same-platform devices. The complete Android entry
        // config is one LWW block: gesture + PIN-in-search + fallback-button + verified gate.
        AndroidPlatformState localPlatformState = canonicalLocalAndroidPlatformState(c);
        if (platformAndroidStateDiffers(st.content.platform, localPlatformState)) {
            if (st.content.platform == null) st.content.platform = new JsonObject();
            JsonObject android = st.content.platform.has(PLATFORM_ANDROID)
                    && st.content.platform.get(PLATFORM_ANDROID).isJsonObject()
                    ? st.content.platform.getAsJsonObject(PLATFORM_ANDROID).deepCopy()
                    : new JsonObject();
            android.addProperty("c", lam(st, lam));
            android.addProperty("dev", dev);
            android.add("tab_sequence", tabSequenceToJson(localPlatformState.tabSequence));
            android.addProperty("pin_in_search", localPlatformState.pinInSearch);
            android.addProperty("entry_button_visible", localPlatformState.entryButtonVisible);
            android.addProperty("shortcut_tested", localPlatformState.shortcutTested);
            st.content.platform.add(PLATFORM_ANDROID, android);
        }

        return membershipRemoved[0];
    }

    // ===== project: blob registers -> controller =====

    private static void projectToController(int account, LeemenSyncState st) {
        SecondSpaceController c = SecondSpaceController.getInstance(account);

        Set<Long> members = new HashSet<>();
        for (Map.Entry<String, LeemenBlob.Reg> e : st.filter.hidden_chat_ids.entrySet()) {
            if (LeemenBlob.isLive(e.getValue())) {
                long id = parseLong(e.getKey());
                // defense-in-depth: a server bug / corrupt blob must never hide the service chat
                if (!org.telegram.messenger.UserObject.isService(id)) members.add(id);
            }
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

        // PS PIN (§7.2): apply the synced register to the controller (set → store the Argon2id hash+salt;
        // none → clear). Absent ps_pin leaves the local PIN untouched. Self-guarded against back-push.
        boolean pinChanged = false;
        if (st.content.ps_pin != null) {
            LeemenBlob.PsPin p = st.content.ps_pin;
            if (LeemenBlob.SET.equals(p.s)) {
                pinChanged = c.applySyncedPin(LeemenBlob.SET, p.hash, p.salt);
            } else if (LeemenBlob.NONE.equals(p.s)) {
                pinChanged = c.applySyncedPin(LeemenBlob.NONE, null, null);
            }
        }

        // Tier-P: apply the complete same-platform entry block under one remote-sync guard.
        AndroidPlatformState androidState = platformAndroidStateFromBlob(st.content.platform);
        c.applySyncedState(members, exposed, pending, selfPinned, privateSearch, pinTimeout, allowSs,
                androidState != null ? androidState.tabSequence : null,
                androidState != null ? androidState.pinInSearch : null,
                androidState != null ? androidState.entryButtonVisible : null,
                androidState != null ? androidState.shortcutTested : null,
                pinChanged);
    }

    // ===== helpers =====

    private static boolean inRange(int account) {
        return account >= 0 && account < N;
    }

    private static boolean ready(int account) {
        return inRange(account)
                && UserConfig.getInstance(account).isClientActivated()
                && LeemenAccount.hasBinding(account)
                && LeemenAccount.hasKMaster(account)
                && !LeemenAccount.isDisabled(account);
    }

    private static boolean isCycleCurrent(int account, long generation, String token) {
        return lifecycleGeneration[account] == generation
                && ready(account)
                && token != null
                && token.equals(LeemenAccount.getToken(account));
    }

    private static LeemenSyncState state(int account) {
        if (STATES[account] == null) STATES[account] = LeemenSyncState.load(account);
        return STATES[account];
    }

    private static long lam(LeemenSyncState st, long[] holder) {
        if (holder[0] == 0) holder[0] = st.nextLamport();
        return holder[0];
    }

    // ---- Tier-P: Android entry config, synced only within this platform's blob sub-object ----
    private static final String PLATFORM_ANDROID = "android";

    private static final class AndroidPlatformState {
        java.util.List<SecondSpaceController.TabStep> tabSequence = new java.util.ArrayList<>();
        boolean pinInSearch;
        boolean entryButtonVisible = true;
        boolean shortcutTested;
    }

    private static JsonArray tabSequenceToJson(java.util.List<SecondSpaceController.TabStep> seq) {
        JsonArray arr = new JsonArray();
        if (seq != null) {
            for (SecondSpaceController.TabStep s : seq) {
                JsonObject o = new JsonObject();
                o.addProperty("t", s.tabIndex);
                o.addProperty("l", s.longPress);
                arr.add(o);
            }
        }
        return arr;
    }

    /** Parse Android's platform block. Missing fields use the anti-lockout defaults from schema §6.6. */
    private static AndroidPlatformState platformAndroidStateFromBlob(JsonObject platform) {
        try {
            if (platform == null || !platform.has(PLATFORM_ANDROID)) return null;
            JsonObject android = platform.getAsJsonObject(PLATFORM_ANDROID);
            if (android == null) return null;
            AndroidPlatformState out = new AndroidPlatformState();
            if (android.has("tab_sequence") && android.get("tab_sequence").isJsonArray()) {
                JsonArray arr = android.getAsJsonArray("tab_sequence");
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    out.tabSequence.add(new SecondSpaceController.TabStep(
                            o.get("t").getAsInt(), o.get("l").getAsBoolean()));
                }
            }
            out.pinInSearch = boolField(android, "pin_in_search", false);
            out.entryButtonVisible = boolField(android, "entry_button_visible", true);
            out.shortcutTested = boolField(android, "shortcut_tested", false);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private static AndroidPlatformState canonicalLocalAndroidPlatformState(SecondSpaceController controller) {
        AndroidPlatformState out = new AndroidPlatformState();
        out.tabSequence.addAll(controller.getTabSequence());
        out.pinInSearch = controller.isPinInSearchEnabled() && controller.hasPassword();
        boolean hasShortcut = !out.tabSequence.isEmpty() || out.pinInSearch;
        out.shortcutTested = hasShortcut && controller.isShortcutTested();
        out.entryButtonVisible = !hasShortcut || !out.shortcutTested || controller.isEntryButtonVisible();
        return out;
    }

    /** Semantic comparison prevents a serialize/round-trip from causing lamport churn every sync cycle. */
    private static boolean platformAndroidStateDiffers(JsonObject platform, AndroidPlatformState local) {
        AndroidPlatformState blob = platformAndroidStateFromBlob(platform);
        if (blob == null) {
            return !local.tabSequence.isEmpty() || local.pinInSearch
                    || !local.entryButtonVisible || local.shortcutTested;
        }
        return !SecondSpaceController.sameSequence(blob.tabSequence, local.tabSequence)
                || blob.pinInSearch != local.pinInSearch
                || blob.entryButtonVisible != local.entryButtonVisible
                || blob.shortcutTested != local.shortcutTested;
    }

    private static boolean boolField(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsBoolean() : fallback;
        } catch (Throwable e) {
            return fallback;
        }
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
