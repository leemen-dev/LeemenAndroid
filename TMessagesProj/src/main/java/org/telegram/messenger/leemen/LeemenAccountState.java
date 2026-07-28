package org.telegram.messenger.leemen;

import android.text.TextUtils;

import com.google.gson.JsonObject;

import org.telegram.messenger.UserConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Reconciles server-authoritative account state that does not belong in the encrypted PS blob:
 * Premium entitlements and the default/max privacy mode. Realtime is notify-only, so every event is
 * confirmed with GET /me; the foreground cadence is the missed-event correctness backstop.
 */
public final class LeemenAccountState {

    private LeemenAccountState() {}

    /** Token-scoped so a reused local account slot cannot receive an older generation's response. */
    private static final Set<String> inFlight = new HashSet<>();
    /** A Realtime event racing an older GET must force one fresh snapshot after that GET settles. */
    private static final Set<String> pending = new HashSet<>();

    public static void refresh(final int account) {
        refresh(account, false);
    }

    public static void onRemoteChanged(final int account) {
        refresh(account, true);
    }

    private static void refresh(final int account, boolean ensureFreshAfterInFlight) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT
                || !UserConfig.getInstance(account).isClientActivated()
                || LeemenAccount.isDisabled(account)) {
            return;
        }
        final String token = LeemenAccount.getToken(account);
        if (TextUtils.isEmpty(token)) return;
        synchronized (inFlight) {
            if (!inFlight.add(token)) {
                if (ensureFreshAfterInFlight) pending.add(token);
                return;
            }
        }
        LeemenRestClient.get(LeemenConfig.EP_ME, token, (resp, code, errorCode, errorMessage) -> {
            boolean repeat;
            synchronized (inFlight) {
                inFlight.remove(token);
                repeat = pending.remove(token);
            }
            if (token.equals(LeemenAccount.getToken(account))
                    && UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)
                    && resp != null && code >= 200 && code < 300) {
                applyPrivacyMode(account, resp);
                LeemenBilling.applyEntitlementsFromMe(account, resp);
            }
            if (repeat
                    && token.equals(LeemenAccount.getToken(account))
                    && UserConfig.getInstance(account).isClientActivated()
                    && !LeemenAccount.isDisabled(account)) {
                refresh(account, false);
            }
        });
    }

    private static void applyPrivacyMode(int account, JsonObject response) {
        try {
            if (!response.has("account") || !response.get("account").isJsonObject()) return;
            JsonObject accountObject = response.getAsJsonObject("account");
            if (!accountObject.has("privacy_mode") || accountObject.get("privacy_mode").isJsonNull()) return;
            String mode = accountObject.get("privacy_mode").getAsString();
            if (!"default".equals(mode) && !"max".equals(mode)) return;
            LeemenAccount.setPrivacyMode(account, mode);
            if (!LeemenAccount.hasKMaster(account)) {
                // In max mode this raises the unwrap prompt; in default it restores/bootstrap-wraps K_master.
                LeemenKey.ensureKey(account);
            }
        } catch (Throwable ignored) {
        }
    }
}
