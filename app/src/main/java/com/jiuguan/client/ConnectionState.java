package com.jiuguan.client;

import java.net.URI;

/** User-visible connection attempt, independent of Activity visibility and WebView drawing. */
public final class ConnectionState {
    public static final long TIMEOUT_MS = 15000;
    private enum Phase { IDLE, WAITING_NETWORK, LOADING, AUTH, LOADED, FAILED }
    private Phase phase = Phase.IDLE;
    private long generation;
    private long deadline;
    private String target = "", document = "", message = "填写酒馆地址，选择连接方式后打开。";

    public long begin(String url, long now) {
        ++generation; target = UrlPolicy.normalize(url); document = "";
        phase = Phase.WAITING_NETWORK; deadline = now + TIMEOUT_MS;
        message = "正在准备连接…";
        return generation;
    }
    public long generation() { return generation; }
    public long deadline() { return deadline; }
    public boolean isConnecting() { return phase == Phase.WAITING_NETWORK || phase == Phase.LOADING || phase == Phase.AUTH; }
    public boolean hasLoadedPage() { return phase == Phase.LOADED; }
    public boolean waitingForAuth() { return phase == Phase.AUTH; }
    public String message() { return message; }

    public void waitingNetwork(String text) {
        if (phase != Phase.WAITING_NETWORK) return;
        message = text;
    }
    public boolean loading(long attempt) {
        if (attempt != generation || !isConnecting() || phase == Phase.AUTH) return false;
        phase = Phase.LOADING; document = ""; message = "正在加载酒馆页面…";
        return true;
    }
    public boolean pageStarted(String url) {
        if (phase != Phase.LOADING || !UrlPolicy.sameOrigin(target, url)) return false;
        document = canonical(url);
        return true;
    }
    public boolean currentDocument(String url) {
        return (phase == Phase.LOADING || phase == Phase.AUTH) && !document.isEmpty() && document.equals(canonical(url));
    }
    public boolean pageFinished(String url) {
        if (phase != Phase.LOADING || !currentDocument(url)) return false;
        phase = Phase.LOADED; deadline = 0; message = "酒馆已连接";
        return true;
    }
    public boolean pauseForAuth() {
        if (phase != Phase.LOADING) return false;
        if (document.isEmpty()) document = canonical(target);
        phase = Phase.AUTH; deadline = 0; message = "等待登录：请填写账号密码，或取消返回连接设置。";
        return true;
    }
    public boolean resumeAfterAuth(long now) {
        if (phase != Phase.AUTH && phase != Phase.LOADING) return false;
        phase = Phase.LOADING; deadline = now + TIMEOUT_MS; message = "正在验证账号并加载酒馆…";
        return true;
    }
    public boolean fail(long attempt, String reason) {
        if (attempt != generation || !isConnecting()) return false;
        phase = Phase.FAILED; deadline = 0; message = reason;
        return true;
    }
    public boolean timeOut(long attempt, long now) {
        return phase != Phase.AUTH && deadline > 0 && now >= deadline
                && fail(attempt, "连接超过 15 秒。请检查酒馆是否已启动、地址和端口是否正确；使用手机流量时还需同时开启 Wi-Fi 和移动数据。可修改设置后重试。");
    }
    public boolean cancel() { return fail(generation, "已取消连接，可修改地址或连接方式后重试。"); }
    /** Explicit startup validation failure, distinct from an old page/network callback. */
    public boolean rejectSetup(long attempt, String reason) {
        if (attempt != generation || (!isConnecting() && phase != Phase.LOADED)) return false;
        phase = Phase.FAILED; deadline = 0; message = reason;
        return true;
    }
    public void reset() {
        ++generation; phase = Phase.IDLE; deadline = 0; target = ""; document = "";
        message = "填写酒馆地址，选择连接方式后打开。";
    }
    private static String canonical(String url) {
        try {
            URI parsed = URI.create(UrlPolicy.normalize(url));
            String path = parsed.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            return AuthScope.origin(url) + path + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        } catch (IllegalArgumentException e) { return ""; }
    }
}
