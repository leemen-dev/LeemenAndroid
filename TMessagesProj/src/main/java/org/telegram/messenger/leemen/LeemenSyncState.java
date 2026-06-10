package org.telegram.messenger.leemen;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Per-account local CRDT working copy: the last-known filter/content blobs (with their LWW registers +
 * lamport), the CAS versions we last saw, and a stable per-install device id (the register tiebreak `d`).
 *
 * Persisted locally so that (a) lamport stays monotonic across restarts, and (b) offline edits accumulate
 * against a known baseline rather than being lost on the next GET. This is plaintext PS state — identical
 * in sensitivity to the SecondSpaceController prefs it mirrors — so plain SharedPreferences is fine; the
 * E2E encryption applies only to the copy that leaves the device (LeemenSync seals it under K_master).
 */
public final class LeemenSyncState {

    private static final String PREFS = "leemen_sync";

    private final int account;
    public LeemenBlob.FilterBlob filter;
    public LeemenBlob.ContentBlob content;
    public long filterVersion;   // last-seen CAS version of filter_blob (0 = not created yet)
    public long contentVersion;  // last-seen CAS version of content_blob
    private final String deviceId;

    private LeemenSyncState(int account, String deviceId) {
        this.account = account;
        this.deviceId = deviceId;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static LeemenSyncState load(int account) {
        SharedPreferences p = prefs();
        LeemenSyncState s = new LeemenSyncState(account, ensureDeviceId(p));
        String fj = p.getString("filter_" + account, null);
        String cj = p.getString("content_" + account, null);
        s.filter = fj != null ? LeemenBlob.filterFromBytes(fj.getBytes(StandardCharsets.UTF_8)) : null;
        s.content = cj != null ? LeemenBlob.contentFromBytes(cj.getBytes(StandardCharsets.UTF_8)) : null;
        if (s.filter == null) s.filter = LeemenBlob.FilterBlob.empty();
        if (s.content == null) s.content = LeemenBlob.ContentBlob.empty();
        s.filterVersion = p.getLong("fver_" + account, 0);
        s.contentVersion = p.getLong("cver_" + account, 0);
        return s;
    }

    public void persist() {
        prefs().edit()
                .putString("filter_" + account, new String(LeemenBlob.toBytes(filter), StandardCharsets.UTF_8))
                .putString("content_" + account, new String(LeemenBlob.toBytes(content), StandardCharsets.UTF_8))
                .putLong("fver_" + account, filterVersion)
                .putLong("cver_" + account, contentVersion)
                .apply();
    }

    public String deviceId() {
        return deviceId;
    }

    /** Allocate the next lamport for a local mutation and stamp both blobs (they share the clock). */
    public long nextLamport() {
        long next = Math.max(filter.lamport, content.lamport) + 1;
        filter.lamport = next;
        content.lamport = next;
        return next;
    }

    /** Adopt the higher lamport seen from a remote blob (no local mutation). */
    public void observeLamport(long remote) {
        if (remote > filter.lamport) filter.lamport = remote;
        if (remote > content.lamport) content.lamport = remote;
    }

    public static void clear(int account) {
        prefs().edit()
                .remove("filter_" + account)
                .remove("content_" + account)
                .remove("fver_" + account)
                .remove("cver_" + account)
                .apply();
    }

    private static String ensureDeviceId(SharedPreferences p) {
        String id = p.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }
}
