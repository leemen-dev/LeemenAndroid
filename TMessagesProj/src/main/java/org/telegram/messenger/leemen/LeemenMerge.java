package org.telegram.messenger.leemen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.telegram.messenger.leemen.LeemenBlob.BoolVal;
import static org.telegram.messenger.leemen.LeemenBlob.ContentBlob;
import static org.telegram.messenger.leemen.LeemenBlob.FilterBlob;
import static org.telegram.messenger.leemen.LeemenBlob.IntVal;
import static org.telegram.messenger.leemen.LeemenBlob.PerChat;
import static org.telegram.messenger.leemen.LeemenBlob.PsPin;
import static org.telegram.messenger.leemen.LeemenBlob.Reg;

/**
 * Phase 3d — field-level CRDT merge, identical across clients (the heart of the hardened Sync Blob Schema).
 *
 * Rules:
 *  - Every register merges by max lamport `c`; equal `c` → higher device `d` wins — EXCEPT msg_state, where
 *    equal `c` resolves to the SAFER state (hidden > pending > exposed) so a concurrent hide is never
 *    overridden into exposure.
 *  - filter.hidden_chat_ids is the authoritative membership set (LWW-element-set, tombstones kept); it is
 *    NEVER derived from content.
 *  - per_chat.cleared_at_c = max (membership-remove cascade). Children with c < cleared_at_c are dead — that
 *    domination is applied at the projection boundary and in the chats_off_mode_visible recompute, not by
 *    mutating registers here (so tombstones/causality survive further merges).
 *  - chats_off_mode_visible is a derived cache, recomputed from content after every merge — never merged.
 *  - platform.<p> sub-objects are preserved verbatim (union; never clobber another platform's section).
 *
 * All merges are IN-PLACE into `local` (the device's working copy), folding in `remote` (freshly GET'd).
 */
public final class LeemenMerge {

    private LeemenMerge() {}

    // ---- scalar register merges ----
    static int cmpD(String a, String b) {
        return (a == null ? "" : a).compareTo(b == null ? "" : b);
    }

    /** Generic LWW: max c; tie → higher d. */
    static Reg mergeReg(Reg a, Reg b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.c != b.c) return a.c > b.c ? a : b;
        return cmpD(a.d, b.d) >= 0 ? a : b;
    }

    /** msg_state LWW with safe tiebreak: max c; equal c → safer (hidden>pending>exposed); then higher d. */
    static Reg mergeMsgState(Reg a, Reg b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.c != b.c) return a.c > b.c ? a : b;
        int ra = safeRank(a.s), rb = safeRank(b.s);
        if (ra != rb) return ra > rb ? a : b;
        return cmpD(a.d, b.d) >= 0 ? a : b;
    }

    private static int safeRank(String s) {
        if (LeemenBlob.HIDDEN.equals(s)) return 2;
        if (LeemenBlob.PENDING.equals(s)) return 1;
        return 0; // exposed (least safe)
    }

    static IntVal mergeIntVal(IntVal a, IntVal b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.c != b.c) return a.c > b.c ? a : b;
        return cmpD(a.d, b.d) >= 0 ? a : b;
    }

    static BoolVal mergeBoolVal(BoolVal a, BoolVal b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.c != b.c) return a.c > b.c ? a : b;
        return cmpD(a.d, b.d) >= 0 ? a : b;
    }

    static PsPin mergePsPin(PsPin a, PsPin b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.c != b.c) return a.c > b.c ? a : b;
        return cmpD(a.d, b.d) >= 0 ? a : b;
    }

    // ---- map merges ----
    private static void mergeRegMap(Map<String, Reg> into, Map<String, Reg> other) {
        if (other == null) return;
        for (Map.Entry<String, Reg> e : other.entrySet()) {
            into.put(e.getKey(), mergeReg(into.get(e.getKey()), e.getValue()));
        }
    }

    private static void mergeMsgStateMap(Map<String, Reg> into, Map<String, Reg> other) {
        if (other == null) return;
        for (Map.Entry<String, Reg> e : other.entrySet()) {
            into.put(e.getKey(), mergeMsgState(into.get(e.getKey()), e.getValue()));
        }
    }

    // ---- blob merges ----
    public static void mergeFilter(FilterBlob local, FilterBlob remote) {
        if (remote == null) return;
        local.lamport = Math.max(local.lamport, remote.lamport);
        if (local.hidden_chat_ids == null) local.hidden_chat_ids = new java.util.HashMap<>();
        mergeRegMap(local.hidden_chat_ids, remote.hidden_chat_ids);
        // chats_off_mode_visible is NOT merged — recompute from content after this (recomputeOffModeVisible).
    }

    public static void mergeContent(ContentBlob local, ContentBlob remote) {
        if (remote == null) return;
        local.lamport = Math.max(local.lamport, remote.lamport);

        if (remote.per_chat != null) {
            for (Map.Entry<String, PerChat> e : remote.per_chat.entrySet()) {
                PerChat lp = local.per_chat.get(e.getKey());
                if (lp == null) {
                    lp = new PerChat();
                    local.per_chat.put(e.getKey(), lp);
                }
                PerChat rp = e.getValue();
                lp.cleared_at_c = Math.max(lp.cleared_at_c, rp.cleared_at_c);
                mergeMsgStateMap(lp.msg_state, rp.msg_state);
                mergeRegMap(lp.self_pinned, rp.self_pinned);
            }
        }
        mergeRegMap(local.private_search_dialog_ids, remote.private_search_dialog_ids);

        if (remote.private_space_settings != null) {
            if (local.private_space_settings == null) local.private_space_settings = new LeemenBlob.Settings();
            local.private_space_settings.pin_timeout_minutes =
                    mergeIntVal(local.private_space_settings.pin_timeout_minutes, remote.private_space_settings.pin_timeout_minutes);
            local.private_space_settings.allow_screenshots =
                    mergeBoolVal(local.private_space_settings.allow_screenshots, remote.private_space_settings.allow_screenshots);
        }
        local.ps_pin = mergePsPin(local.ps_pin, remote.ps_pin);
        local.platform = mergePlatform(local.platform, remote.platform);
    }

    /** Per-platform last-writer-wins (§6.6): union across platforms, but for a section present on BOTH
     *  sides take the one with the higher lamport "c" (tiebreak by "dev"). This is what lets two
     *  same-platform devices actually converge on a Tier-P setting (e.g. the tab gesture) instead of each
     *  keeping its own section forever. (The old union-only logic never propagated a same-platform change.) */
    private static JsonObject mergePlatform(JsonObject local, JsonObject remote) {
        if (remote == null) return local;
        if (local == null) return remote.deepCopy();
        for (Map.Entry<String, JsonElement> e : remote.entrySet()) {
            String plat = e.getKey();
            JsonElement rEl = e.getValue();
            if (!local.has(plat)) {
                local.add(plat, rEl);
            } else {
                long lc = platLamport(local.get(plat));
                long rc = platLamport(rEl);
                if (rc > lc || (rc == lc && platDev(rEl).compareTo(platDev(local.get(plat))) > 0)) {
                    local.add(plat, rEl); // JsonObject.add replaces the existing section
                }
            }
        }
        return local;
    }

    private static long platLamport(JsonElement el) {
        try { return el.getAsJsonObject().get("c").getAsLong(); } catch (Throwable e) { return 0; }
    }

    private static String platDev(JsonElement el) {
        try { return el.getAsJsonObject().get("dev").getAsString(); } catch (Throwable e) { return ""; }
    }

    // ---- derived cache: chats_off_mode_visible ----
    /** A child register is live only if it is at/after the per-chat cascade marker. */
    public static boolean afterClear(Reg r, long clearedAtC) {
        return r != null && r.c >= clearedAtC;
    }

    /**
     * Recompute the off-mode-visible hint: dialogs whose membership is live AND that hold ≥1 live
     * exposed/pending message at/after cleared_at_c. Excludes transient local in-flight placeholders
     * (never in the blob). Advisory lower bound — overwrites the array, never element-merged.
     */
    public static void recomputeOffModeVisible(FilterBlob f, ContentBlob c) {
        List<String> out = new ArrayList<>();
        if (c != null && c.per_chat != null && f != null && f.hidden_chat_ids != null) {
            for (Map.Entry<String, PerChat> e : c.per_chat.entrySet()) {
                String dialogId = e.getKey();
                if (!LeemenBlob.isLive(f.hidden_chat_ids.get(dialogId))) continue; // membership authority
                PerChat pc = e.getValue();
                if (pc.msg_state == null) continue;
                for (Reg r : pc.msg_state.values()) {
                    if (!afterClear(r, pc.cleared_at_c)) continue;
                    if (LeemenBlob.EXPOSED.equals(r.s) || LeemenBlob.PENDING.equals(r.s)) {
                        out.add(dialogId);
                        break;
                    }
                }
            }
        }
        if (f != null) f.chats_off_mode_visible = out;
    }
}
