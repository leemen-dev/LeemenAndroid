package org.telegram.messenger.leemen;

import android.util.Base64;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SecondSpaceController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Max-privacy (zero-knowledge) orchestration. K_master is wrapped under TWO independent secrets and stored
 * OPAQUE on the backend: a dedicated Leemen passphrase (everyday) + a BIP-39 recovery phrase (backup). In max
 * the server holds no decryptable copy (kms=NULL). Variant A+: NOT the Telegram cloud password (it can change
 * out-of-band and orphan the wrap). Heavy Argon2id runs off the UI thread; callbacks always fire on the UI thread.
 *
 * Backend contract (live): POST /account/upgrade-privacy, GET /account/key (max → both wraps+salts+wrap_version),
 * PUT /account/wrap-pw (CAS), POST /account/downgrade-privacy {k_master}, POST /security/reset-private-space.
 */
public final class LeemenMaxPrivacy {

    private LeemenMaxPrivacy() {}

    public interface Callback { void onResult(boolean ok, String error); }
    public interface UpgradeCallback { void onResult(boolean ok, String mnemonic, String error); }

    private static final class CachedKey {
        final String token;
        final JsonObject response;

        CachedKey(String token, JsonObject response) {
            this.token = token;
            this.response = response;
        }
    }

    private static final CachedKey[] cachedKeys = new CachedKey[UserConfig.MAX_ACCOUNT_COUNT];

    /** Keep opaque max-mode wraps from auth/account-key in memory so the unlock prompt does not GET them again. */
    static void cacheAuthBootstrapKey(int account, String token, JsonObject response) {
        if (account < 0 || account >= cachedKeys.length || token == null || response == null) return;
        cachedKeys[account] = new CachedKey(token, response.deepCopy());
    }

    static void clearAuthBootstrapKey(int account) {
        if (account >= 0 && account < cachedKeys.length) cachedKeys[account] = null;
    }

    /** default → max. Generates the recovery phrase; on success returns it (show ONCE) via the callback. */
    public static void upgrade(int account, String passphrase, UpgradeCallback cb) {
        final String token = LeemenAccount.getToken(account);
        final byte[] kmaster = LeemenKey.getKMaster(account);
        if (token == null || kmaster == null || passphrase == null || passphrase.isEmpty()) {
            zero(kmaster);
            AndroidUtilities.runOnUIThread(() -> cb.onResult(false, null, kmaster == null ? "no_key" : "bad_input"));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            byte[] kekPw = null, kekRec = null, pwBytes = null, mnBytes = null;
            try {
                final String mnemonic = LeemenMnemonic.generate();
                byte[] saltPw = LeemenCrypto.randomSalt();
                byte[] saltRec = LeemenCrypto.randomSalt();
                pwBytes = utf8(passphrase);
                mnBytes = mnemonic == null ? null : utf8(LeemenMnemonic.normalize(mnemonic));
                kekPw = mnemonic == null ? null : LeemenCrypto.argon2idKek(pwBytes, saltPw);
                kekRec = mnBytes == null ? null : LeemenCrypto.argon2idKek(mnBytes, saltRec);
                byte[] wrappedPw = kekPw == null ? null : LeemenCrypto.wrapKey(kmaster, kekPw);
                byte[] wrappedRec = kekRec == null ? null : LeemenCrypto.wrapKey(kmaster, kekRec);
                if (mnemonic == null || wrappedPw == null || wrappedRec == null) {
                    AndroidUtilities.runOnUIThread(() -> cb.onResult(false, null, "wrap_fail"));
                    return;
                }
                JsonObject body = new JsonObject();
                body.addProperty("wrapped_k_master_pw", b64(wrappedPw));
                body.addProperty("salt_pw", b64(saltPw));
                body.addProperty("wrapped_k_master_recovery", b64(wrappedRec));
                body.addProperty("salt_recovery", b64(saltRec));
                LeemenRestClient.post(LeemenConfig.EP_UPGRADE_PRIVACY, token, body, (resp, code, ec, em) -> {
                    boolean ok = okResp(resp, code);
                    if (ok) {
                        LeemenAccount.setPrivacyMode(account, "max");
                        applyWrapVersion(account, resp);
                    }
                    cb.onResult(ok, ok ? mnemonic : null, ok ? null : err(ec, code));
                });
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> cb.onResult(false, null, "wrap_fail"));
            } finally {
                zero(kmaster); zero(kekPw); zero(kekRec); zero(pwBytes); zero(mnBytes);
            }
        });
    }

    public static void unwrapWithPassphrase(int account, String passphrase, Callback cb) {
        unwrap(account, passphrase, false, cb);
    }

    public static void unwrapWithRecovery(int account, String phrase, Callback cb) {
        unwrap(account, LeemenMnemonic.normalize(phrase), true, cb);
    }

    private static void unwrap(int account, String secret, boolean recovery, Callback cb) {
        final String token = LeemenAccount.getToken(account);
        if (token == null || secret == null || secret.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> cb.onResult(false, "bad_input"));
            return;
        }
        JsonObject cached = cachedKey(account, token);
        if (cached != null) {
            unwrapResponse(account, token, cached, secret, recovery, cb);
            return;
        }
        LeemenRestClient.get(LeemenConfig.EP_ACCOUNT_KEY, token, (resp, code, ec, em) -> {
            if (!is2xx(resp, code)) { cb.onResult(false, err(ec, code)); return; }
            cacheAuthBootstrapKey(account, token, resp);
            unwrapResponse(account, token, resp, secret, recovery, cb);
        });
    }

    private static void unwrapResponse(int account, String token, JsonObject response, String secret,
                                       boolean recovery, Callback cb) {
        if (!"max".equals(optStr(response, "mode", "default"))) {
            cb.onResult(false, "not_max");
            return;
        }
        final String wrappedB64 = optStr(response,
                recovery ? "wrapped_k_master_recovery" : "wrapped_k_master_pw", null);
        final String saltB64 = optStr(response, recovery ? "salt_recovery" : "salt_pw", null);
        if (wrappedB64 == null || saltB64 == null) {
            cb.onResult(false, "missing_wrap");
            return;
        }
        final Integer wrapVer = optInt(response, "wrap_version");
        Utilities.globalQueue.postRunnable(() -> {
            byte[] kek = null, kmaster = null, secretBytes = null;
            try {
                secretBytes = utf8(secret);
                kek = LeemenCrypto.argon2idKek(secretBytes, Base64.decode(saltB64, Base64.NO_WRAP));
                kmaster = kek == null ? null : LeemenCrypto.unwrapKey(Base64.decode(wrappedB64, Base64.NO_WRAP), kek);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            zero(kek); zero(secretBytes);
            final byte[] k = kmaster;
            AndroidUtilities.runOnUIThread(() -> {
                if (k == null || k.length != LeemenCrypto.KEY_BYTES) {
                    zero(k);
                    cb.onResult(false, "wrong_secret");
                    return;
                }
                if (!token.equals(LeemenAccount.getToken(account))) {
                    zero(k);
                    cb.onResult(false, "stale_session");
                    return;
                }
                if (wrapVer != null) LeemenAccount.setWrapVersion(account, wrapVer);
                LeemenAccount.setPrivacyMode(account, "max");
                boolean stored = LeemenKey.acceptKMaster(account, k); // zeroes k and consumes staged auth blobs
                if (stored) clearAuthBootstrapKey(account);
                cb.onResult(stored, stored ? null : "store_failed");
            });
        });
    }

    private static JsonObject cachedKey(int account, String token) {
        if (account < 0 || account >= cachedKeys.length || token == null) return null;
        CachedKey cached = cachedKeys[account];
        if (cached == null || !token.equals(cached.token)) return null;
        return cached.response.deepCopy();
    }

    /** Change the everyday passphrase (re-wrap pw only; CAS on wrap_version). Needs K_master loaded. */
    public static void changeCode(int account, String newPassphrase, Callback cb) {
        final String token = LeemenAccount.getToken(account);
        final byte[] kmaster = LeemenKey.getKMaster(account);
        if (token == null || kmaster == null || newPassphrase == null || newPassphrase.isEmpty()) {
            zero(kmaster);
            AndroidUtilities.runOnUIThread(() -> cb.onResult(false, kmaster == null ? "no_key" : "bad_input"));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            byte[] kek = null, pwBytes = null;
            try {
                byte[] saltPw = LeemenCrypto.randomSalt();
                pwBytes = utf8(newPassphrase);
                kek = LeemenCrypto.argon2idKek(pwBytes, saltPw);
                byte[] wrapped = kek == null ? null : LeemenCrypto.wrapKey(kmaster, kek);
                if (wrapped == null) { AndroidUtilities.runOnUIThread(() -> cb.onResult(false, "wrap_fail")); return; }
                JsonObject body = new JsonObject();
                body.addProperty("wrapped_k_master_pw", b64(wrapped));
                body.addProperty("salt_pw", b64(saltPw));
                body.addProperty("prev_wrap_version", LeemenAccount.getWrapVersion(account));
                LeemenRestClient.put(LeemenConfig.EP_WRAP_PW, token, body, (resp, code, ec, em) -> {
                    boolean ok = okResp(resp, code);
                    if (ok) {
                        applyWrapVersion(account, resp);
                    } else if (code == 409) {
                        // CAS conflict — resync wrap_version from current_wrap_version so a retry can succeed.
                        Integer cur = optInt(resp, "current_wrap_version");
                        if (cur != null) LeemenAccount.setWrapVersion(account, cur);
                    }
                    cb.onResult(ok, ok ? null : err(ec, code));
                });
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> cb.onResult(false, "wrap_fail"));
            } finally {
                zero(kmaster); zero(kek); zero(pwBytes);
            }
        });
    }

    /** max → default. Sends the (client-decrypted) K_master so the backend KMS-wraps it again. */
    public static void downgrade(int account, Callback cb) {
        final String token = LeemenAccount.getToken(account);
        final byte[] kmaster = LeemenKey.getKMaster(account);
        if (token == null || kmaster == null) {
            zero(kmaster);
            AndroidUtilities.runOnUIThread(() -> cb.onResult(false, kmaster == null ? "no_key" : "bad_input"));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("k_master", b64(kmaster));
        zero(kmaster);
        LeemenRestClient.post(LeemenConfig.EP_DOWNGRADE_PRIVACY, token, body, (resp, code, ec, em) -> {
            boolean ok = okResp(resp, code);
            if (ok) {
                LeemenAccount.setPrivacyMode(account, "default");
                applyWrapVersion(account, resp);
            }
            cb.onResult(ok, ok ? null : err(ec, code));
        });
    }

    /** Both secrets lost: server wipes PS blobs + re-keys to bootstrap-default. DESTRUCTIVE (PS starts fresh). */
    public static void reset(int account, Callback cb) {
        final String token = LeemenAccount.getToken(account);
        if (token == null) { AndroidUtilities.runOnUIThread(() -> cb.onResult(false, "bad_input")); return; }
        JsonObject body = new JsonObject();
        body.addProperty("confirm", "RESET");
        LeemenRestClient.post(LeemenConfig.EP_RESET_PRIVATE_SPACE, token, body, (resp, code, ec, em) -> {
            boolean ok = okResp(resp, code);
            if (ok) {
                try {
                    LeemenSync.clearAccount(account);
                    SecondSpaceController.getInstance(account).wipeAllLocalData();
                    LeemenAccount.dropKMaster(account);
                    LeemenAccount.setPrivacyMode(account, "default");
                    LeemenAccount.setWrapVersion(account, 0);
                    LeemenKey.ensureKey(account); // fresh K_master + wrap-default
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            cb.onResult(ok, ok ? null : err(ec, code));
        });
    }

    // --- helpers ---
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static void zero(byte[] b) { if (b != null) Arrays.fill(b, (byte) 0); }
    private static boolean is2xx(JsonObject resp, int code) { return resp != null && code >= 200 && code < 300; }
    private static boolean okResp(JsonObject resp, int code) {
        return is2xx(resp, code) && resp.has("ok") && !resp.get("ok").isJsonNull() && resp.get("ok").getAsBoolean();
    }
    private static String err(String ec, int code) { return ec != null ? ec : ("http_" + code); }
    private static void applyWrapVersion(int account, JsonObject resp) {
        Integer v = optInt(resp, "wrap_version");
        if (v != null) LeemenAccount.setWrapVersion(account, v);
    }
    private static String optStr(JsonObject o, String key, String def) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def; }
        catch (Throwable e) { return def; }
    }
    private static Integer optInt(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : null; }
        catch (Throwable e) { return null; }
    }
}
