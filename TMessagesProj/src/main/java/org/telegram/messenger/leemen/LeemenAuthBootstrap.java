package org.telegram.messenger.leemen;

import android.util.Base64;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Validated, fail-closed snapshot optionally returned by {@code POST /auth/telegram}.
 *
 * A rolling backend deploy may omit {@code bootstrap}; malformed/unknown snapshots are deliberately treated
 * exactly like an omitted one so the established /account/key + /content + /filter path remains the fallback.
 * In particular, a missing/null blob is never interpreted as "empty": only an explicit {@code state=absent}
 * is conclusive.
 */
final class LeemenAuthBootstrap {

    static final int SCHEMA_VERSION = 1;

    final JsonObject key;
    final JsonObject filter;
    final JsonObject content;

    private LeemenAuthBootstrap(JsonObject key, JsonObject filter, JsonObject content) {
        this.key = key;
        this.filter = filter;
        this.content = content;
    }

    static LeemenAuthBootstrap parse(JsonObject authResponse) {
        try {
            if (authResponse == null
                    || !authResponse.has("bootstrap")
                    || !authResponse.get("bootstrap").isJsonObject()) {
                return null;
            }
            JsonObject bootstrap = authResponse.getAsJsonObject("bootstrap");
            Integer schemaVersion = integer(bootstrap, "schema_version");
            if (schemaVersion == null
                    || schemaVersion != SCHEMA_VERSION
                    || !bootstrap.has("key")
                    || !bootstrap.get("key").isJsonObject()
                    || !bootstrap.has("filter")
                    || !bootstrap.get("filter").isJsonObject()
                    || !bootstrap.has("content")
                    || !bootstrap.get("content").isJsonObject()) {
                return null;
            }
            JsonObject key = bootstrap.getAsJsonObject("key");
            JsonObject filter = bootstrap.getAsJsonObject("filter");
            JsonObject content = bootstrap.getAsJsonObject("content");
            if (!validKey(key) || !validBlobState(filter) || !validBlobState(content)) {
                return null;
            }
            return new LeemenAuthBootstrap(key.deepCopy(), filter.deepCopy(), content.deepCopy());
        } catch (Throwable ignore) {
            return null;
        }
    }

    static boolean isAbsent(JsonObject state) {
        return "absent".equals(string(state, "state"));
    }

    private static boolean validKey(JsonObject key) {
        String mode = string(key, "mode");
        if ("default".equals(mode)) {
            boolean needsWrap = bool(key, "needs_wrap");
            byte[] raw = decoded(key, "k_master");
            if (raw != null) {
                boolean valid = raw.length == LeemenCrypto.KEY_BYTES && !needsWrap;
                java.util.Arrays.fill(raw, (byte) 0);
                return valid;
            }
            return needsWrap && !hasNonNull(key, "k_master");
        }
        if (!"max".equals(mode)) {
            return false;
        }
        byte[] wrappedPw = decoded(key, "wrapped_k_master_pw");
        byte[] saltPw = decoded(key, "salt_pw");
        byte[] wrappedRecovery = decoded(key, "wrapped_k_master_recovery");
        byte[] saltRecovery = decoded(key, "salt_recovery");
        Integer wrapVersion = integer(key, "wrap_version");
        int wrappedBytes = LeemenCrypto.NONCE_BYTES + LeemenCrypto.KEY_BYTES + LeemenCrypto.TAG_BYTES;
        return wrappedPw != null && wrappedPw.length == wrappedBytes
                && saltPw != null && saltPw.length == LeemenCrypto.SALT_BYTES
                && wrappedRecovery != null && wrappedRecovery.length == wrappedBytes
                && saltRecovery != null && saltRecovery.length == LeemenCrypto.SALT_BYTES
                && wrapVersion != null && wrapVersion > 0;
    }

    private static boolean validBlobState(JsonObject state) {
        String kind = string(state, "state");
        if ("absent".equals(kind)) {
            Long version = longInteger(state, "version");
            return version != null && version == 0
                    && !hasNonNull(state, "encrypted_data")
                    && !hasNonNull(state, "nonce");
        }
        if (!"present".equals(kind)) {
            return false;
        }
        byte[] encrypted = decoded(state, "encrypted_data");
        byte[] nonce = decoded(state, "nonce");
        Long version = longInteger(state, "version");
        String updatedAt = string(state, "updated_at");
        return encrypted != null && encrypted.length >= LeemenCrypto.TAG_BYTES
                && nonce != null && nonce.length == LeemenCrypto.NONCE_BYTES
                && version != null && version > 0
                && updatedAt != null && updatedAt.endsWith("Z");
    }

    private static byte[] decoded(JsonObject object, String name) {
        String value = string(object, name);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Base64.decode(value, Base64.NO_WRAP);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String string(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    ? value.getAsString() : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean bool(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                    && value.getAsBoolean();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean hasNonNull(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value != null && !value.isJsonNull();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static Integer integer(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? new java.math.BigDecimal(value.getAsString()).intValueExact() : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Long longInteger(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    ? new java.math.BigDecimal(value.getAsString()).longValueExact() : null;
        } catch (Throwable ignore) {
            return null;
        }
    }
}
