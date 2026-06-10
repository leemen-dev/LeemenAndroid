package org.telegram.messenger.leemen;

import com.google.gson.JsonObject;

/**
 * Phase 5b — promo code redemption. POST /v1/promo/redeem {code} (authed) → entitlement on success.
 * UI-callable API (no entry point wired yet); the callback is delivered on the UI thread.
 */
public final class LeemenPromo {

    private LeemenPromo() {}

    public interface Result {
        void onResult(boolean ok, String entitlementId, String kind, String expiresAt, String reason);
    }

    public static void redeem(int account, String code, final Result cb) {
        String token = LeemenAccount.getToken(account);
        if (token == null) {
            cb.onResult(false, null, null, null, "not_bound");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        LeemenRestClient.post(LeemenConfig.EP_PROMO_REDEEM, token, body, (resp, httpCode, ec, em) -> {
            if (resp != null && httpCode >= 200 && httpCode < 300 && resp.has("ok") && resp.get("ok").getAsBoolean()) {
                cb.onResult(true, str(resp, "entitlement_id"), str(resp, "kind"), str(resp, "expires_at"), null);
            } else {
                String reason = resp != null && resp.has("reason") ? str(resp, "reason") : (ec != null ? ec : ("http_" + httpCode));
                cb.onResult(false, null, null, null, reason);
            }
        });
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
        } catch (Throwable e) {
            return null;
        }
    }
}
