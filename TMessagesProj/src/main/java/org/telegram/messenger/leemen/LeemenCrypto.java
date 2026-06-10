package org.telegram.messenger.leemen;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.goterl.lazysodium.interfaces.AEAD;
import com.goterl.lazysodium.interfaces.PwHash;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Phase 3a — libsodium primitives for the sync layer, matching the backend byte-for-byte.
 *
 *  - Blob E2E: XChaCha20-Poly1305 IETF (24-byte nonce, 16-byte tag). {@link #encrypt} returns exactly the
 *    backend's `encrypted_data` (ciphertext‖tag); the nonce is carried separately (the `nonce` field).
 *  - PS PIN: Argon2id (interactive params), salted — the salt rides in the blob's ps_pin register.
 *
 * lazysodium loads the bundled libsodium .so via JNA; {@link #selfTestOk()} verifies that on-device.
 */
public final class LeemenCrypto {

    private LeemenCrypto() {}

    public static final int KEY_BYTES   = AEAD.XCHACHA20POLY1305_IETF_KEYBYTES;   // 32
    public static final int NONCE_BYTES = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES;  // 24
    public static final int TAG_BYTES   = AEAD.XCHACHA20POLY1305_IETF_ABYTES;     // 16
    public static final int SALT_BYTES  = PwHash.SALTBYTES;                       // 16
    public static final int PIN_HASH_BYTES = 32;

    private static volatile LazySodiumAndroid sodium;
    private static final SecureRandom RNG = new SecureRandom();

    private static LazySodiumAndroid ls() {
        if (sodium == null) {
            synchronized (LeemenCrypto.class) {
                if (sodium == null) {
                    sodium = new LazySodiumAndroid(new SodiumAndroid());
                }
            }
        }
        return sodium;
    }

    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_BYTES];
        RNG.nextBytes(n);
        return n;
    }

    public static byte[] randomSalt() {
        byte[] s = new byte[SALT_BYTES];
        RNG.nextBytes(s);
        return s;
    }

    /** Seal plaintext under K_master with the given 24-byte nonce → ciphertext‖tag, or null on failure. */
    public static byte[] encrypt(byte[] plaintext, byte[] nonce, byte[] key) {
        if (plaintext == null || nonce == null || key == null
                || nonce.length != NONCE_BYTES || key.length != KEY_BYTES) {
            return null;
        }
        try {
            byte[] c = new byte[plaintext.length + TAG_BYTES];
            long[] cLen = new long[1];
            boolean ok = ls().cryptoAeadXChaCha20Poly1305IetfEncrypt(
                    c, cLen, plaintext, plaintext.length, null, 0L, null, nonce, key);
            if (!ok) return null;
            return cLen[0] == c.length ? c : Arrays.copyOf(c, (int) cLen[0]);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Open ciphertext‖tag under K_master + nonce → plaintext, or null on auth failure. */
    public static byte[] decrypt(byte[] ciphertext, byte[] nonce, byte[] key) {
        if (ciphertext == null || nonce == null || key == null
                || nonce.length != NONCE_BYTES || key.length != KEY_BYTES || ciphertext.length < TAG_BYTES) {
            return null;
        }
        try {
            byte[] m = new byte[ciphertext.length - TAG_BYTES];
            long[] mLen = new long[1];
            boolean ok = ls().cryptoAeadXChaCha20Poly1305IetfDecrypt(
                    m, mLen, null, ciphertext, ciphertext.length, null, 0L, nonce, key);
            if (!ok) return null;
            return mLen[0] == m.length ? m : Arrays.copyOf(m, (int) mLen[0]);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Argon2id(pin, salt) → 32 bytes, deterministic for fixed (interactive) params. null on failure. */
    public static byte[] argon2id(byte[] password, byte[] salt) {
        if (password == null || salt == null || salt.length != SALT_BYTES) return null;
        try {
            byte[] out = new byte[PIN_HASH_BYTES];
            boolean ok = ls().cryptoPwHash(
                    out, out.length, password, password.length, salt,
                    PwHash.OPSLIMIT_INTERACTIVE, PwHash.MEMLIMIT_INTERACTIVE,
                    PwHash.Alg.PWHASH_ALG_ARGON2ID13);
            return ok ? out : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Verify the native lib loads and AEAD + Argon2id round-trip. Cheap; safe to call once at startup. */
    public static boolean selfTestOk() {
        try {
            byte[] key = new byte[KEY_BYTES];
            RNG.nextBytes(key);
            byte[] nonce = randomNonce();
            byte[] msg = "leemen-crypto-selftest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] ct = encrypt(msg, nonce, key);
            byte[] pt = ct == null ? null : decrypt(ct, nonce, key);
            boolean aeadOk = pt != null && Arrays.equals(pt, msg);
            byte[] salt = randomSalt();
            byte[] h1 = argon2id("1234".getBytes(java.nio.charset.StandardCharsets.UTF_8), salt);
            byte[] h2 = argon2id("1234".getBytes(java.nio.charset.StandardCharsets.UTF_8), salt);
            boolean argonOk = h1 != null && Arrays.equals(h1, h2);
            boolean ok = aeadOk && argonOk;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("Leemen: crypto self-test " + (ok ? "OK" : "FAILED") + " (aead=" + aeadOk + " argon2id=" + argonOk + ")");
            }
            return ok;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }
}
