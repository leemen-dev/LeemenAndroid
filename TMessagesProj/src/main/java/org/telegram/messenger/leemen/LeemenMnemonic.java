package org.telegram.messenger.leemen;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * BIP-39 (English) recovery phrase for max-privacy mode: 12 words = 128-bit entropy + 4-bit checksum.
 *
 * The normalized phrase STRING is the KDF input for the recovery wrap (Argon2id → KEK); we deliberately do
 * NOT run BIP-39's PBKDF2 seed step — we only need a memorable, checksummed secret, not wallet interop.
 * Wordlist = the canonical bitcoin/bips english.txt (sha256 2f5eed53…), bundled as res/raw/bip39_english.txt.
 */
public final class LeemenMnemonic {

    private LeemenMnemonic() {}

    private static final int ENTROPY_BYTES = 16; // 128-bit → 12 words
    private static volatile String[] WORDS;
    private static volatile Map<String, Integer> INDEX;
    private static final SecureRandom RNG = new SecureRandom();

    private static boolean load() {
        if (WORDS != null) return true;
        synchronized (LeemenMnemonic.class) {
            if (WORDS != null) return true;
            try {
                InputStream is = ApplicationLoader.applicationContext.getResources()
                        .openRawResource(org.telegram.messenger.R.raw.bip39_english);
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                ArrayList<String> list = new ArrayList<>(2048);
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) list.add(line);
                }
                r.close();
                if (list.size() != 2048) {
                    FileLog.e("Leemen: bip39 wordlist size " + list.size() + " (expected 2048)");
                    return false;
                }
                Map<String, Integer> idx = new HashMap<>(4096);
                for (int i = 0; i < list.size(); i++) idx.put(list.get(i), i);
                WORDS = list.toArray(new String[0]);
                INDEX = idx;
                return true;
            } catch (Throwable e) {
                FileLog.e(e);
                return false;
            }
        }
    }

    /** Normalize for display/compare/KDF: trim, lowercase, collapse internal whitespace to single spaces. */
    public static String normalize(String phrase) {
        if (phrase == null) return "";
        return phrase.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Generate a fresh 12-word phrase, or null on failure. */
    public static String generate() {
        if (!load()) return null;
        byte[] entropy = new byte[ENTROPY_BYTES];
        RNG.nextBytes(entropy);
        try {
            return encode(entropy);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    private static String encode(byte[] entropy) throws Exception {
        byte[] hash = sha256(entropy);
        int csBits = (entropy.length * 8) / 32;          // 4
        int totalBits = entropy.length * 8 + csBits;     // 132
        boolean[] bits = new boolean[totalBits];
        for (int i = 0; i < entropy.length; i++)
            for (int b = 0; b < 8; b++)
                bits[i * 8 + b] = ((entropy[i] >> (7 - b)) & 1) == 1;
        for (int b = 0; b < csBits; b++)
            bits[entropy.length * 8 + b] = ((hash[0] >> (7 - b)) & 1) == 1;
        StringBuilder sb = new StringBuilder();
        int words = totalBits / 11;
        for (int w = 0; w < words; w++) {
            int idx = 0;
            for (int b = 0; b < 11; b++) idx = (idx << 1) | (bits[w * 11 + b] ? 1 : 0);
            if (w > 0) sb.append(' ');
            sb.append(WORDS[idx]);
        }
        return sb.toString();
    }

    /** True iff the phrase is a valid 12-word BIP-39 phrase (all words known + checksum holds). */
    public static boolean isValid(String phrase) {
        if (!load()) return false;
        String[] parts = normalize(phrase).split(" ");
        if (parts.length != 12) return false;
        int totalBits = parts.length * 11;        // 132
        boolean[] bits = new boolean[totalBits];
        for (int w = 0; w < parts.length; w++) {
            Integer idx = INDEX.get(parts[w]);
            if (idx == null) return false;
            for (int b = 0; b < 11; b++) bits[w * 11 + b] = ((idx >> (10 - b)) & 1) == 1;
        }
        int entBits = totalBits * 32 / 33;        // 128
        int entBytes = entBits / 8;               // 16
        byte[] entropy = new byte[entBytes];
        for (int i = 0; i < entBytes; i++) {
            int v = 0;
            for (int b = 0; b < 8; b++) v = (v << 1) | (bits[i * 8 + b] ? 1 : 0);
            entropy[i] = (byte) v;
        }
        try {
            byte[] hash = sha256(entropy);
            int csBits = totalBits - entBits;     // 4
            for (int b = 0; b < csBits; b++) {
                boolean expected = ((hash[0] >> (7 - b)) & 1) == 1;
                if (bits[entBits + b] != expected) return false;
            }
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    private static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }
}
