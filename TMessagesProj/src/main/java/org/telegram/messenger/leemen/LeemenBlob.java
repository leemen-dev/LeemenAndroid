package org.telegram.messenger.leemen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3b — the on-the-wire blob model (schema v2), the plaintext that gets XChaCha20-sealed under
 * K_master and stored as filter_blob / content_blob. This is the CRDT representation from the hardened
 * Sync Blob Schema: every mergeable value is an LWW register {c=lamport, d=device}; removable-set elements
 * carry s ∈ {present|removed} (tombstones kept). Membership lives ONLY in filter_blob.hidden_chat_ids
 * (authoritative); content_blob holds per-chat detail; chats_off_mode_visible is a derived cache.
 *
 * Pure data + Gson (de)serialization — no merge/crypto/transport here (those are LeemenMerge / LeemenSync).
 * Maps use String keys (dialog/msg ids) because JSON object keys are strings; values are parsed back to
 * long at the projection boundary. Unknown JSON fields are ignored by Gson, so older/newer peers coexist.
 */
public final class LeemenBlob {

    private LeemenBlob() {}

    public static final int SCHEMA_VERSION = 2;

    // --- LWW register variants ---
    /** Set-element / message-state register. s ∈ {present,removed} (sets) or {pending,exposed,hidden} (msg_state). */
    public static final class Reg {
        public String s;
        public long c;
        public String d;
        public Reg() {}
        public Reg(String s, long c, String d) { this.s = s; this.c = c; this.d = d; }
    }
    public static final class IntVal {
        public long v; public long c; public String d;
        public IntVal() {}
        public IntVal(long v, long c, String d) { this.v = v; this.c = c; this.d = d; }
    }
    public static final class BoolVal {
        public boolean v; public long c; public String d;
        public BoolVal() {}
        public BoolVal(boolean v, long c, String d) { this.v = v; this.c = c; this.d = d; }
    }
    /** PS PIN register; s ∈ {set,none}. Carries the Argon2id hash + salt when set. */
    public static final class PsPin {
        public String s;      // "set" | "none"
        public String hash;   // base64 argon2id digest
        public String salt;   // base64
        public String kdf = "argon2id";
        public long c;
        public String d;
    }

    public static final class FilterBlob {
        public int schema_version = SCHEMA_VERSION;
        public long lamport;
        public Map<String, Reg> hidden_chat_ids = new HashMap<>();
        public List<String> chats_off_mode_visible = new ArrayList<>(); // derived cache (recomputed from content)
        public String pad; // size-bucket padding (§7.4), filled at PUT time

        public static FilterBlob empty() { return new FilterBlob(); }
    }

    public static final class PerChat {
        public long cleared_at_c;                              // membership-remove cascade marker (§6.3)
        public Map<String, Reg> msg_state = new HashMap<>();   // msgId -> {pending|exposed|hidden}
        public Map<String, Reg> self_pinned = new HashMap<>(); // msgId -> {present|removed}
    }

    public static final class Settings {
        public IntVal pin_timeout_minutes;
        public BoolVal allow_screenshots;
    }

    public static final class ContentBlob {
        public int schema_version = SCHEMA_VERSION;
        public long lamport;
        public Map<String, PerChat> per_chat = new HashMap<>();
        public Map<String, Reg> private_search_dialog_ids = new HashMap<>();
        public Settings private_space_settings = new Settings();
        public PsPin ps_pin;            // null until the Argon2id PIN re-enrollment ships (§7.2 migration)
        public JsonObject platform;     // per-platform Tier-P sub-objects; PRESERVED verbatim (§6.6)

        public static ContentBlob empty() { return new ContentBlob(); }
    }

    // --- (de)serialization ---
    private static final Gson GSON = new Gson();

    public static byte[] toBytes(Object blob) {
        return GSON.toJson(blob).getBytes(StandardCharsets.UTF_8);
    }

    public static FilterBlob filterFromBytes(byte[] json) {
        if (json == null) return null;
        FilterBlob b = GSON.fromJson(new String(json, StandardCharsets.UTF_8), FilterBlob.class);
        if (b != null && b.hidden_chat_ids == null) b.hidden_chat_ids = new HashMap<>();
        if (b != null && b.chats_off_mode_visible == null) b.chats_off_mode_visible = new ArrayList<>();
        return b;
    }

    public static ContentBlob contentFromBytes(byte[] json) {
        if (json == null) return null;
        ContentBlob b = GSON.fromJson(new String(json, StandardCharsets.UTF_8), ContentBlob.class);
        if (b == null) return null;
        if (b.per_chat == null) b.per_chat = new HashMap<>();
        if (b.private_search_dialog_ids == null) b.private_search_dialog_ids = new HashMap<>();
        if (b.private_space_settings == null) b.private_space_settings = new Settings();
        return b;
    }

    // --- register state helpers (shared by merge + projection) ---
    public static final String PRESENT = "present";
    public static final String REMOVED = "removed";
    public static final String EXPOSED = "exposed";
    public static final String PENDING = "pending";
    public static final String HIDDEN = "hidden";
    public static final String SET = "set";
    public static final String NONE = "none";

    public static boolean isLive(Reg r) {
        return r != null && PRESENT.equals(r.s);
    }
}
