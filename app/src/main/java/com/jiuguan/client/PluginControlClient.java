package com.jiuguan.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Native LAN-only plugin control. No WebView JS bridge, redirects, or generated-request retries. */
public final class PluginControlClient {
    public interface Callback { void completed(Result result); }
    public static final class Result {
        public final boolean success, unauthorized;
        public final String message;
        public final List<String> cookies;
        Result(boolean success, boolean unauthorized, String message, List<String> cookies) {
            this.success = success; this.unauthorized = unauthorized; this.message = message;
            this.cookies = Collections.unmodifiableList(new ArrayList<>(cookies));
        }
    }
    private static final String PREFIX = "/api/plugins/jiuguan-relay/";
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "JiuguanPluginControl"); thread.setDaemon(true); return thread;
    });
    private boolean closing;
    private int pendingJobs;
    private ConnectivityManager.NetworkCallback retention;
    private Network lastWifi;

    public PluginControlClient(Context context) {
        connectivity = context.getApplicationContext().getSystemService(ConnectivityManager.class);
    }

    public void submit(PluginControlState.Operation operation, String clientId, String cookie,
                       String authorization, BooleanSupplier mayEnable, Callback callback) {
        if (closing) return;
        retainWifi();
        worker.execute(() -> {
            Result result = perform(operation, clientId, cookie, authorization, mayEnable);
            main.post(() -> {
                releaseWifi();
                callback.completed(result);
            });
        });
    }

    /** Already queued disable is allowed to finish; new commands are rejected. */
    public void closeAfterPending() { closing = true; worker.shutdown(); }

    private Result perform(PluginControlState.Operation operation, String clientId, String cookie,
                           String authorization, BooleanSupplier mayEnable) {
        List<String> updates = new ArrayList<>();
        try {
            if (operation.enable() && !mayEnable.getAsBoolean()) return new Result(false, false, "连接模式已变化", updates);
            Network wifi = wifiNetwork(operation.proxyUrl);
            if (wifi == null) return new Result(false, false, "无法连接电脑，请保持局域网 Wi-Fi 连接后重试。", updates);
            URI origin = URI.create(operation.origin);
            CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            if (cookie != null && !cookie.isEmpty()) {
                for (String part : cookie.split(";")) {
                    int equals = part.indexOf('=');
                    if (equals <= 0) continue;
                    HttpCookie item = new HttpCookie(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
                    item.setVersion(0); item.setPath("/"); item.setDomain(origin.getHost());
                    jar.getCookieStore().add(origin, item);
                }
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            JSONObject status = request(wifi, origin, PREFIX + "status", null, null, authorization, jar, updates, deadline);
            String problem = PluginControlState.statusProblem(status.optInt("protocol", -1), status.optString("mode"),
                    !operation.enable() || status.optBoolean("supported"), status.optString("message"));
            if (!problem.isEmpty()) throw new ControlFailure(problem, false);
            // Always enqueue disable on the server, even if status is currently normal: an older
            // enable whose HTTP response timed out may still be waiting for its phone probe.
            JSONObject csrf = request(wifi, origin, "/csrf-token", null, null, authorization, jar, updates, deadline);
            String token = csrf.optString("token", "");
            if ("disabled".equals(token)) throw new ControlFailure("请先在电脑酒馆开启 CSRF 保护，再重试连接。", false);
            if (token.isEmpty() || token.length() > 1024) throw new ControlFailure("无法取得酒馆安全令牌，请重新登录后重试。", false);
            if (operation.enable() && !mayEnable.getAsBoolean()) return new Result(false, false, "连接模式已变化", updates);
            JSONObject body = new JSONObject().put("clientId", clientId);
            if (operation.enable()) body.put("proxyUrl", operation.proxyUrl);
            JSONObject response = request(wifi, origin, PREFIX + (operation.enable() ? "enable" : "disable"),
                    body.toString(), token, authorization, jar, updates, deadline);
            String expected = operation.enable() ? "mobile" : "normal";
            if (!response.optBoolean("ok") || !expected.equals(response.optString("mode")))
                throw new ControlFailure("电脑插件未确认切换成功，请重试。", false);
            return new Result(true, false, operation.enable() ? "手机与电脑已连接，酒馆外网走手机流量" : "电脑网络已恢复", updates);
        } catch (ControlFailure failure) {
            return new Result(false, failure.unauthorized, failure.getMessage(), updates);
        } catch (Exception ignored) {
            return new Result(false, false, "暂时无法联系电脑插件，请检查酒馆和局域网连接后重试。", updates);
        }
    }

    private void retainWifi() {
        if (pendingJobs++ != 0 || connectivity == null) return;
        retention = new ConnectivityManager.NetworkCallback() { };
        try {
            connectivity.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), retention);
        } catch (RuntimeException ignored) { retention = null; }
    }

    private void releaseWifi() {
        if (--pendingJobs != 0 || retention == null || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(retention); }
        catch (RuntimeException ignored) { }
        retention = null;
    }

    private Network wifiNetwork(String proxyUrl) {
        if (connectivity == null) return null;
        String phoneAddress = proxyUrl.isEmpty() ? "" : URI.create(proxyUrl).getHost();
        Network fallback = null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                if (fallback == null || network.equals(lastWifi)) fallback = network;
                if (!phoneAddress.isEmpty()) {
                    LinkProperties properties = connectivity.getLinkProperties(network);
                    if (properties != null) for (LinkAddress address : properties.getLinkAddresses())
                        if (phoneAddress.equals(address.getAddress().getHostAddress())) { lastWifi = network; return network; }
                }
            }
        }
        if (phoneAddress.isEmpty()) { lastWifi = fallback; return fallback; }
        return null;
    }

    private JSONObject request(Network wifi, URI origin, String path, String body, String csrf,
                               String authorization, CookieManager jar, List<String> updates,
                               long deadline) throws Exception {
        int remaining = (int) ((deadline - System.nanoTime()) / 1_000_000L);
        if (remaining <= 0) throw new ControlFailure("电脑插件响应超时，请重试。", false);
        URL url = origin.resolve(path).toURL();
        HttpURLConnection connection = (HttpURLConnection) wifi.openConnection(url, Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(Math.min(2000, remaining));
        connection.setReadTimeout(Math.min(2000, remaining));
        connection.setRequestMethod(body == null ? "GET" : "POST");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Origin", origin.toString());
        if (authorization != null && !authorization.isEmpty()) connection.setRequestProperty("Authorization", authorization);
        for (Map.Entry<String, List<String>> entry : jar.get(url.toURI(), Collections.emptyMap()).entrySet())
            if (entry.getKey().equalsIgnoreCase("Cookie")) connection.setRequestProperty("Cookie", String.join("; ", entry.getValue()));
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("x-csrf-token", csrf);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int code = connection.getResponseCode();
            Map<String, List<String>> headers = connection.getHeaderFields();
            jar.put(url.toURI(), headers);
            for (Map.Entry<String, List<String>> header : headers.entrySet())
                if (header.getKey() != null && header.getKey().equalsIgnoreCase("Set-Cookie")) updates.addAll(header.getValue());
            if (code == 404) throw new ControlFailure("电脑尚未安装或启用酒馆口袋插件，请先安装配套插件并重启酒馆。", false);
            if (code == 401 || code == 403 || (code >= 300 && code < 400))
                throw new ControlFailure("请先登录酒馆管理员账号，再点“重试连接”。", true);
            if (code != 200) throw new ControlFailure("电脑插件未能切换连接，请检查插件状态后重试。", false);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 16384 || System.nanoTime() > deadline)
                        throw new ControlFailure("电脑插件响应异常，请重试。", false);
                    out.write(buffer, 0, count);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }

    private static final class ControlFailure extends Exception {
        final boolean unauthorized;
        ControlFailure(String message, boolean unauthorized) { super(message); this.unauthorized = unauthorized; }
    }
}
