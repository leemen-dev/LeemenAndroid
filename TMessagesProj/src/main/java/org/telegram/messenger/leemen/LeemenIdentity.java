package org.telegram.messenger.leemen;

import android.net.Uri;
import android.text.TextUtils;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 1 — invisible per-Telegram-account Leemen identity binding.
 *
 * After a Telegram login (and for already-logged-in accounts on app start), we obtain a Telegram-signed
 * statement of the user id WITHOUT opening a WebView: resolve @leemen_auth_bot, call MTProto
 * messages.requestWebView headless, pull the `tgWebAppData` (= initData) out of the returned URL fragment,
 * and POST it to /v1/auth/telegram. The backend verifies the HMAC, finds/creates the master_account +
 * sync_account, and returns {token, sync_account_id, privacy_mode}. We persist that per account.
 *
 * Best-effort + idempotent: skips bound accounts, dedupes concurrent attempts, never throws into callers.
 */
public final class LeemenIdentity {

    private LeemenIdentity() {}

    private static final Set<Integer> inFlight = new HashSet<>();

    /** App-start safety net: bind every activated account that isn't bound yet. */
    public static void bindAllActivated() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    bindIfNeeded(a);
                }
            } catch (Throwable ignore) {}
        }
    }

    /** Idempotent, best-effort bind for one account. Safe to call repeatedly. */
    public static void bindIfNeeded(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        try {
            if (!UserConfig.getInstance(account).isClientActivated()) return;
            if (LeemenAccount.isDisabled(account)) return; // user deleted their Leemen account
            if (LeemenAccount.hasBinding(account)) {
                LeemenKey.ensureKey(account); // bound already; make sure the key was fetched too
                return;
            }
            synchronized (inFlight) {
                if (inFlight.contains(account)) return;
                inFlight.add(account);
            }
            resolveBotThenBind(account);
        } catch (Throwable e) {
            FileLog.e(e);
            clearInFlight(account);
        }
    }

    private static void clearInFlight(int account) {
        synchronized (inFlight) {
            inFlight.remove(account);
        }
    }

    private static void resolveBotThenBind(int account) {
        MessagesController.getInstance(account).getUserNameResolver().resolve(LeemenConfig.AUTH_BOT_USERNAME, peerId -> {
            try {
                if (peerId == null || peerId <= 0) {
                    if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: auth-bot resolve failed (peerId=" + peerId + ")");
                    clearInFlight(account);
                    return;
                }
                requestInitData(account, peerId);
            } catch (Throwable e) {
                FileLog.e(e);
                clearInFlight(account);
            }
        });
    }

    private static void requestInitData(int account, long botId) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_requestWebView req = new TLRPC.TL_messages_requestWebView();
        req.peer = mc.getInputPeer(botId);
        req.bot = mc.getInputUser(botId);
        req.platform = "android";
        req.url = LeemenConfig.AUTH_WEBAPP_URL;
        req.flags |= 2; // url is flags.1

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_webViewResultUrl)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: requestWebView failed: " + (error != null ? error.text : "null response"));
                }
                clearInFlight(account);
                return;
            }
            String initData = extractInitData(((TLRPC.TL_webViewResultUrl) response).url);
            if (TextUtils.isEmpty(initData)) {
                if (BuildVars.LOGS_ENABLED) FileLog.d("Leemen: no tgWebAppData in requestWebView result url");
                clearInFlight(account);
                return;
            }
            postAuth(account, initData);
        }));
    }

    /** Extract the tgWebAppData value (= raw initData querystring) from the result URL fragment. */
    private static String extractInitData(String resultUrl) {
        if (TextUtils.isEmpty(resultUrl)) return null;
        try {
            String fragment = Uri.parse(resultUrl).getEncodedFragment();
            if (TextUtils.isEmpty(fragment)) {
                int h = resultUrl.indexOf('#');
                fragment = h >= 0 ? resultUrl.substring(h + 1) : null;
            }
            if (TextUtils.isEmpty(fragment)) return null;
            for (String part : fragment.split("&")) {
                if (part.startsWith("tgWebAppData=")) {
                    String v = part.substring("tgWebAppData=".length());
                    return URLDecoder.decode(v, StandardCharsets.UTF_8.name());
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private static void postAuth(int account, String initData) {
        JsonObject body = new JsonObject();
        body.addProperty("initData", initData);
        LeemenRestClient.post(LeemenConfig.EP_AUTH_TELEGRAM, null, body, (resp, code, errCode, errMsg) -> {
            try {
                if (resp != null && code >= 200 && code < 300 && resp.has("token") && resp.has("sync_account_id")) {
                    String token = resp.get("token").getAsString();
                    String syncId = resp.get("sync_account_id").getAsString();
                    String privacy = resp.has("privacy_mode") && !resp.get("privacy_mode").isJsonNull()
                            ? resp.get("privacy_mode").getAsString() : null;
                    LeemenAccount.save(account, token, syncId, privacy);
                    LeemenKey.ensureKey(account); // chain Phase 2: acquire K_master right after bind
                    // A session token now exists → let the Terms/Privacy acceptance gate run (LaunchActivity).
                    try {
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.leemenBindCompleted, account);
                    } catch (Throwable ignore) {}
                    if (BuildVars.LOGS_ENABLED) {
                        // NB: never log sync_account_id — it's the Realtime channel secret (LeemenConfig).
                        FileLog.d("Leemen: bound account " + account
                                + " mode=" + privacy + " created=" + (resp.has("created") ? resp.get("created") : "?"));
                    }
                } else if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("Leemen: /auth/telegram failed code=" + code + " err=" + errCode + " " + errMsg);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                clearInFlight(account);
            }
        });
    }
}
