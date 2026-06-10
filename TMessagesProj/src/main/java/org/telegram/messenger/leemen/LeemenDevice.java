package org.telegram.messenger.leemen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 5a — device registration. One device-global Ed25519 keypair (public key registered with the
 * backend; secret key Keystore-wrapped for future device-auth/signing) is registered once per bound
 * account via POST /v1/devices/register. Idempotent: skips accounts already registered.
 */
public final class LeemenDevice {

    private LeemenDevice() {}

    private static final String PREFS = "leemen_device";
    private static final Set<Integer> inFlight = new HashSet<>();

    public static void ensureRegisteredAll() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated() && LeemenAccount.hasBinding(a)) {
                    ensureRegistered(a);
                }
            } catch (Throwable ignore) {}
        }
    }

    public static void ensureRegistered(final int account) {
        if (LeemenAccount.isDisabled(account)) return;
        final String token = LeemenAccount.getToken(account);
        if (token == null) return;
        if (prefs().getString("device_id_" + account, null) != null) return; // already registered
        synchronized (inFlight) {
            if (!inFlight.add(account)) return;
        }
        String pkB64 = ensureKeypairPublicB64();
        if (pkB64 == null) { clearInFlight(account); return; }

        JsonObject body = new JsonObject();
        body.addProperty("public_key", pkB64);
        body.addProperty("platform", "android");
        body.addProperty("device_name", Build.MODEL == null ? "Android" : Build.MODEL);
        LeemenRestClient.post(LeemenConfig.EP_DEVICES_REGISTER, token, body, (resp, code, ec, em) -> {
            try {
                if (resp != null && code >= 200 && code < 300 && resp.has("device_id") && !resp.get("device_id").isJsonNull()) {
                    prefs().edit().putString("device_id_" + account, resp.get("device_id").getAsString()).apply();
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: device registered account " + account);
                } else if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: device register failed account " + account + " code=" + code + " err=" + ec);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                clearInFlight(account);
            }
        });
    }

    /** Device-global Ed25519 public key (b64). Generates + stores the keypair on first call (sk Keystore-wrapped). */
    private static synchronized String ensureKeypairPublicB64() {
        SharedPreferences p = prefs();
        String pk = p.getString("pk", null);
        if (pk != null) return pk;
        byte[][] kp = LeemenCrypto.signKeypair();
        if (kp == null) return null;
        String pkB64 = Base64.encodeToString(kp[0], Base64.NO_WRAP);
        String skWrapped = LeemenKeyStore.protect(kp[1]);
        Arrays.fill(kp[1], (byte) 0);
        p.edit().putString("pk", pkB64).putString("sk", skWrapped).apply();
        return pkB64;
    }

    private static void clearInFlight(int account) {
        synchronized (inFlight) {
            inFlight.remove(account);
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
