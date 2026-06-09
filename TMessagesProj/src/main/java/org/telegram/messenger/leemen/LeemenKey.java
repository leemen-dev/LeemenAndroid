package org.telegram.messenger.leemen;

import android.util.Base64;

import com.google.gson.JsonObject;

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

    /** Raw 32-byte K_master for an account, or null if not available yet. Caller should zero it after use. */
    public static byte[] getKMaster(int account) {
        String wrapped = LeemenAccount.getWrappedKMaster(account);
        return wrapped == null ? null : LeemenKeyStore.unprotect(wrapped);
    }

    /** Ensure this account has a usable K_master. Safe to call repeatedly; no-op once present. */
    public static void ensureKey(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        if (LeemenAccount.hasKMaster(account)) return;
        final String token = LeemenAccount.getToken(account);
        if (token == null) return; // must bind first (Phase 1)
        synchronized (inFlight) {
            if (!inFlight.add(account)) return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ACCOUNT_KEY, token, (resp, code, errCode, errMsg) -> {
            boolean delegated = false;
            try {
                if (resp == null || code < 200 || code >= 300) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: /account/key failed code=" + code + " err=" + errCode);
                    return;
                }
                String mode = optStr(resp, "mode", "default");
                if ("max".equals(mode)) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: account/key mode=max — client unwrap not yet implemented");
                } else if (resp.has("k_master") && !resp.get("k_master").isJsonNull()) {
                    byte[] k = Base64.decode(resp.get("k_master").getAsString(), Base64.NO_WRAP);
                    if (k.length == 32) {
                        storeKMaster(account, k, false);
                    } else if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("Leemen: account/key bad k_master length " + k.length);
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

    private static void wrapNewKey(int account, String token) {
        final byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        JsonObject body = new JsonObject();
        body.addProperty("k_master", Base64.encodeToString(k, Base64.NO_WRAP));
        LeemenRestClient.post(LeemenConfig.EP_WRAP_DEFAULT, token, body, (resp, code, errCode, errMsg) -> {
            try {
                if (resp != null && code >= 200 && code < 300 && resp.has("ok") && resp.get("ok").getAsBoolean()) {
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

    private static void storeKMaster(int account, byte[] k, boolean created) {
        try {
            String wrapped = LeemenKeyStore.protect(k);
            if (wrapped == null) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: failed to wrap K_master for account " + account);
                return;
            }
            LeemenAccount.setWrappedKMaster(account, wrapped);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: K_master ready for account " + account + (created ? " (generated + wrapped)" : " (fetched)"));
            }
        } finally {
            Arrays.fill(k, (byte) 0);
        }
    }

    private static String optStr(JsonObject o, String key, String def) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
        } catch (Throwable e) {
            return def;
        }
    }

    private static void clearInFlight(int account) {
        synchronized (inFlight) {
            inFlight.remove(account);
        }
    }
}
