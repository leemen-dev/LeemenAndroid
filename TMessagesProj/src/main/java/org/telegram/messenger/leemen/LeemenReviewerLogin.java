package org.telegram.messenger.leemen;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Ephemeral handoff for Google Play review access.
 *
 * <p>This class is reachable only from the explicit reviewer gesture in {@code LoginActivity}; normal phone
 * login never calls LeemenBackend. Neither the reusable reviewer password nor Telegram's short-lived login
 * token is persisted or logged. Encoding is performed on a private byte copy which is wiped immediately; the
 * request body fields are removed when the HTTP request completes.</p>
 */
public final class LeemenReviewerLogin {

    private LeemenReviewerLogin() {}

    public interface Callback {
        void onResult(boolean accepted, int httpCode, @Nullable String errorCode);
    }

    public static void accept(String password, byte[] loginToken, Callback callback) {
        if (TextUtils.isEmpty(password) || password.length() < 24 || password.length() > 256
                || loginToken == null || loginToken.length < 16 || loginToken.length > 512) {
            callback.onResult(false, 400, "invalid_request");
            return;
        }

        byte[] tokenCopy = Arrays.copyOf(loginToken, loginToken.length);
        byte[] encodedBytes = null;
        final String encodedToken;
        try {
            encodedBytes = Base64.encode(
                    tokenCopy,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
            );
            encodedToken = new String(encodedBytes, StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(tokenCopy, (byte) 0);
            if (encodedBytes != null) {
                Arrays.fill(encodedBytes, (byte) 0);
            }
        }

        final JsonObject body = new JsonObject();
        body.addProperty("password", password);
        body.addProperty("login_token", encodedToken);
        LeemenRestClient.post(LeemenConfig.EP_REVIEWER_TELEGRAM_LOGIN, null, body,
                (response, httpCode, errorCode, errorMessage) -> {
                    // Drop the request-body field references after LeemenRestClient has serialized and sent them.
                    body.remove("password");
                    body.remove("login_token");
                    boolean ok = false;
                    try {
                        ok = httpCode >= 200 && httpCode < 300
                                && response != null
                                && response.has("ok")
                                && !response.get("ok").isJsonNull()
                                && response.get("ok").getAsBoolean();
                    } catch (RuntimeException ignored) {
                        // A malformed success envelope is a closed failure, never an implicit acceptance.
                    }
                    callback.onResult(ok, httpCode, errorCode);
                });
    }
}
