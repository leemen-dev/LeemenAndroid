package org.telegram.messenger.leemen;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal REST client for the Leemen backend gateway (api[-dev].leemen.app/v1). JSON in/out, optional
 * Bearer auth, error envelope {error:{code,message}}. Network runs off the main thread on
 * Utilities.globalQueue; the callback is delivered on the UI thread.
 *
 * Intentionally tiny (HttpURLConnection + Gson, both already in the project) — no OkHttp/Retrofit added.
 * Blob PUT/GET (which need raw status codes + headers) layer their own helpers on top in the sync phase.
 */
public final class LeemenRestClient {

    private LeemenRestClient() {}

    private static final Gson GSON = new Gson();

    public interface Callback {
        /**
         * @param body       parsed JSON response body (may be null on transport error / empty body)
         * @param httpCode   HTTP status, or -1 on a transport/IO failure
         * @param errorCode  error.code from the envelope, or "network_error" on transport failure, else null
         * @param errorMsg   error.message, or the exception message on transport failure, else null
         */
        void onResult(@Nullable JsonObject body, int httpCode, @Nullable String errorCode, @Nullable String errorMsg);
    }

    public static void get(String path, @Nullable String bearer, Callback cb) {
        request("GET", path, bearer, null, cb);
    }

    public static void post(String path, @Nullable String bearer, @Nullable Object jsonBody, Callback cb) {
        request("POST", path, bearer, jsonBody, cb);
    }

    private static void request(String method, String path, @Nullable String bearer, @Nullable Object jsonBody, Callback cb) {
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(LeemenConfig.BASE_URL + path).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("Accept", "application/json");
                if (bearer != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearer);
                }
                if (jsonBody != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    byte[] payload = GSON.toJson(jsonBody).getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload);
                    }
                }

                int code = conn.getResponseCode();
                String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

                JsonObject body = null;
                try {
                    if (text != null && !text.isEmpty()) {
                        body = GSON.fromJson(text, JsonObject.class);
                    }
                } catch (Exception parseError) {
                    // non-JSON body (e.g. gateway HTML); leave body null, surface the http code
                }

                String errCode = null, errMsg = null;
                if (body != null && body.has("error") && body.get("error").isJsonObject()) {
                    JsonObject err = body.getAsJsonObject("error");
                    if (err.has("code")) errCode = optString(err, "code");
                    if (err.has("message")) errMsg = optString(err, "message");
                }

                final JsonObject fBody = body;
                final int fCode = code;
                final String fErrCode = errCode, fErrMsg = errMsg;
                AndroidUtilities.runOnUIThread(() -> cb.onResult(fBody, fCode, fErrCode, fErrMsg));
            } catch (Exception e) {
                FileLog.e(e);
                final String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() -> cb.onResult(null, -1, "network_error", msg));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static String optString(JsonObject o, String key) {
        try {
            return o.get(key).isJsonNull() ? null : o.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
