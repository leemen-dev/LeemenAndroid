package org.telegram.messenger.leemen;

import android.util.Base64;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 2 — acquire the per-account K_master (the blob E2E key) and store it Keystore-wrapped.
 *
 * default mode: GET /v1/account/key returns either k_master (use it) or needs_wrap (we generate a 32-byte
 * CSPRNG key and POST /v1/account/wrap-default so the server KMS-wraps it for other devices). max mode
 * (user master-password unwrap) is NOT wired yet — it needs the password UI; logged + skipped for v1
 * (the vast majority of accounts are default).
 *
 * Idempotent + best-effort: requires an existing binding (token); the actual XChaCha20 blob crypto that
 * consumes getKMaster() arrives in the sync phase.
 */
public final class LeemenKey {

    private LeemenKey() {}

    private static final Set<Integer> inFlight = new HashSet<>();
    /** Accounts for which the max-mode unwrap prompt was already requested this process (avoid dialog spam). */
    private static final Set<Integer> maxPromptShown = java.util.Collections.synchronizedSet(new HashSet<>());

    /** Raw 32-byte K_master for an account, or null if not available yet. Caller should zero it after use. */
    public static byte[] getKMaster(int account) {
        String wrapped = LeemenAccount.getWrappedKMaster(account);
        byte[] key = wrapped == null ? null : LeemenKeyStore.unprotect(wrapped);
        if (key != null && key.length != LeemenCrypto.KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            return null;
        }
        return key;
    }

    /** Ensure this account has a usable K_master. Safe to call repeatedly; no-op once present. */
    public static void ensureKey(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        if (hasUsableKMaster(account)) return;
        // max mode needs the user's secret — prompt once per process; don't re-GET /account/key on every resume.
        if (LeemenAccount.isMaxPrivacy(account) && maxPromptShown.contains(account)) return;
        final String token = LeemenAccount.getToken(account);
        if (token == null) return; // must bind first (Phase 1)
        synchronized (inFlight) {
            if (!inFlight.add(account)) return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ACCOUNT_KEY, token, (resp, code, errCode, errMsg) -> {
            boolean delegated = false;
            try {
                if (!bindingMatches(account, token)) {
                    return;
                }
                if (resp == null || code < 200 || code >= 300) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: /account/key failed code=" + code + " err=" + errCode);
                    return;
                }
                String mode = optStr(resp, "mode", "default");
                if ("max".equals(mode)) {
                    acceptMaxKeyResponse(account, token, resp);
                } else if (resp.has("k_master") && !resp.get("k_master").isJsonNull()) {
                    byte[] k = Base64.decode(resp.get("k_master").getAsString(), Base64.NO_WRAP);
                    if (k.length == 32) {
                        storeKMaster(account, k, false);
                    } else {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("Leemen: account/key bad k_master length " + k.length);
                        }
                        Arrays.fill(k, (byte) 0);
                    }
                } else if (resp.has("needs_wrap") && !resp.get("needs_wrap").isJsonNull() && resp.get("needs_wrap").getAsBoolean()) {
                    wrapNewKey(account, token);
                    delegated = true; // wrapNewKey clears inFlight when its POST completes
                } else if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: account/key default mode but neither k_master nor needs_wrap present");
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (!delegated) clearInFlight(account);
            }
        });
    }

    /**
     * Accept the key union embedded in a validated /auth/telegram bootstrap without issuing GET /account/key.
     * The matching ciphertext snapshot must be staged in LeemenSync first; storeKMaster's normal sync kick then
     * consumes it instead of starting the legacy blob GET pair.
     */
    static boolean acceptAuthBootstrap(int account, String token, JsonObject keyResponse) {
        if (!bindingMatches(account, token) || keyResponse == null) return false;
        try {
            String mode = optStr(keyResponse, "mode", null);
            if ("max".equals(mode)) {
                if (!acceptMaxKeyResponse(account, token, keyResponse)) return false;
                if (hasUsableKMaster(account)) {
                    LeemenSync.onRemoteChanged(account);
                }
                return true;
            }
            if (!"default".equals(mode)) return false;
            LeemenAccount.setPrivacyMode(account, "default");
            if (keyResponse.has("k_master") && !keyResponse.get("k_master").isJsonNull()) {
                byte[] k = Base64.decode(keyResponse.get("k_master").getAsString(), Base64.NO_WRAP);
                if (k.length != LeemenCrypto.KEY_BYTES) {
                    Arrays.fill(k, (byte) 0);
                    return false;
                }
                // The server value is authoritative. A private-space reset on another device may rotate
                // K_master without creating a new Telegram account generation, so keeping an existing local
                // key here can otherwise make every bootstrap decrypt fail forever.
                byte[] current = getKMaster(account);
                boolean same = current != null && Arrays.equals(current, k);
                if (current != null) Arrays.fill(current, (byte) 0);
                if (same) {
                    Arrays.fill(k, (byte) 0);
                    LeemenSync.onRemoteChanged(account);
                    return true;
                }
                boolean stored = storeKMaster(account, k, false);
                return stored;
            }
            if (hasUsableKMaster(account)) {
                LeemenSync.onRemoteChanged(account);
                return true;
            }
            if (keyResponse.has("needs_wrap") && !keyResponse.get("needs_wrap").isJsonNull()
                    && keyResponse.get("needs_wrap").getAsBoolean()) {
                synchronized (inFlight) {
                    if (!inFlight.add(account)) return true;
                }
                wrapNewKey(account, token);
                return true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    private static boolean acceptMaxKeyResponse(int account, String token, JsonObject response) {
        if (!bindingMatches(account, token) || response == null) return false;
        String wrappedPw = optStr(response, "wrapped_k_master_pw", null);
        String saltPw = optStr(response, "salt_pw", null);
        String wrappedRecovery = optStr(response, "wrapped_k_master_recovery", null);
        String saltRecovery = optStr(response, "salt_recovery", null);
        Integer wrapVersion = optInt(response, "wrap_version");
        if (wrappedPw == null || saltPw == null || wrappedRecovery == null || saltRecovery == null
                || wrapVersion == null || wrapVersion <= 0) {
            return false;
        }
        LeemenAccount.setPrivacyMode(account, "max");
        LeemenAccount.setWrapVersion(account, wrapVersion);
        LeemenMaxPrivacy.cacheAuthBootstrapKey(account, token, response);
        // K_master is only recoverable with the user's secret. Signal the UI once per account generation.
        if (!hasUsableKMaster(account) && maxPromptShown.add(account)) {
            if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: account key mode=max — prompting for unwrap secret");
            org.telegram.messenger.NotificationCenter.getGlobalInstance()
                    .postNotificationName(org.telegram.messenger.NotificationCenter.leemenMaxKeyNeeded, account);
        }
        return true;
    }

    /**
     * True only when the persisted envelope currently decrypts to a valid key. Never retains raw bytes.
     *
     * A non-empty preference is not proof that K_master is usable: an account-slot value can outlive the
     * AndroidKeyStore entry that wrapped it (restore/reinstall), or come from a malformed older local state.
     * Presence-only checks make {@link #ensureKey(int)} return forever while every sync receives a null key.
     * Keep an unusable envelope until recovery succeeds: a Keystore/provider failure can be transient, and in
     * max mode that envelope may be the only device-local copy. A successful fetch/unwrap overwrites it.
     */
    public static boolean hasUsableKMaster(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return false;
        if (!LeemenAccount.hasKMaster(account)) return false;
        byte[] current = null;
        try {
            current = getKMaster(account);
            if (current == null && BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: unusable local K_master envelope; requesting recovery account " + account);
            }
            return current != null;
        } finally {
            if (current != null) Arrays.fill(current, (byte) 0);
        }
    }

    private static void wrapNewKey(int account, String token) {
        final byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        JsonObject body = new JsonObject();
        body.addProperty("k_master", Base64.encodeToString(k, Base64.NO_WRAP));
        LeemenRestClient.post(LeemenConfig.EP_WRAP_DEFAULT, token, body, (resp, code, errCode, errMsg) -> {
            try {
                if (!bindingMatches(account, token)) {
                    Arrays.fill(k, (byte) 0);
                } else if (resp != null && code >= 200 && code < 300 && resp.has("ok") && resp.get("ok").getAsBoolean()) {
                    storeKMaster(account, k, true);
                } else {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: /account/wrap-default failed code=" + code + " err=" + errCode);
                    Arrays.fill(k, (byte) 0);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                Arrays.fill(k, (byte) 0);
            } finally {
                clearInFlight(account);
            }
        });
    }

    private static boolean storeKMaster(int account, byte[] k, boolean created) {
        try {
            String wrapped = LeemenKeyStore.protect(k);
            if (wrapped == null) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: failed to wrap K_master for account " + account);
                return false;
            }
            LeemenAccount.setWrappedKMaster(account, wrapped);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: K_master ready for account " + account + (created ? " (generated + wrapped)" : " (fetched)"));
            }
            LeemenSync.onRemoteChanged(account); // key ready → kick off the first sync
            // onRemoteChanged posts the privacy-critical blob GETs to the UI queue. Defer device/realtime by one
            // UI turn so first-time device registration cannot take the last REST-pool slot ahead of them.
            final String expectedToken = LeemenAccount.getToken(account);
            AndroidUtilities.runOnUIThread(() -> {
                if (bindingMatches(account, expectedToken)
                        && LeemenAccount.hasKMaster(account)) {
                    LeemenDevice.ensureRegistered(account);
                    LeemenRealtime.connect(account);
                }
            });
            return true;
        } finally {
            Arrays.fill(k, (byte) 0);
        }
    }

    /** Store an externally-unwrapped K_master (max-mode unwrap path) — Keystore-wrap + kick the bootstrap
     *  chain (sync/device/realtime). Zeroes {@code k}. Safe no-op if k is not a 32-byte key. */
    public static boolean acceptKMaster(int account, byte[] k) {
        if (k == null || k.length != 32) {
            if (k != null) Arrays.fill(k, (byte) 0);
            return false;
        }
        return storeKMaster(account, k, false);
    }

    static void clearAccount(int account) {
        synchronized (inFlight) {
            inFlight.remove(account);
        }
        maxPromptShown.remove(account);
    }

    private static String optStr(JsonObject o, String key, String def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
        } catch (Throwable e) {
            return def;
        }
    }

    private static Integer optInt(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean bindingMatches(int account, String token) {
        return account >= 0
                && account < UserConfig.MAX_ACCOUNT_COUNT
                && UserConfig.getInstance(account).isClientActivated()
                && !LeemenAccount.isDisabled(account)
                && token != null
                && token.equals(LeemenAccount.getToken(account));
    }

    private static void clearInFlight(int account) {
        synchronized (inFlight) {
            inFlight.remove(account);
        }
    }
}
