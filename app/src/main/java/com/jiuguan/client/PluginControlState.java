package com.jiuguan.client;

/** Serial-operation identity; late replies cannot confirm a newer toggle or network. */
public final class PluginControlState {
    public static final class Operation {
        public final long revision;
        public final String origin, proxyUrl;
        Operation(long revision, String origin, String proxyUrl) {
            this.revision = revision; this.origin = origin; this.proxyUrl = proxyUrl;
        }
        public boolean enable() { return !proxyUrl.isEmpty(); }
    }
    private long revision;
    private long attempted = -1;
    private String origin = "", proxy = "", confirmed = "", error = "";
    private boolean needsDisable;
    private boolean working;

    public synchronized void target(String newOrigin, String newProxy, boolean disableNeeded) {
        if (origin.equals(newOrigin) && proxy.equals(newProxy) && needsDisable == disableNeeded) return;
        origin = newOrigin; proxy = newProxy; needsDisable = disableNeeded;
        ++revision; attempted = -1; confirmed = ""; error = ""; working = false;
    }
    public synchronized void retry() { ++revision; attempted = -1; error = ""; working = false; }
    public synchronized Operation next(boolean pageAuthenticated) {
        if (!pageAuthenticated || origin.isEmpty() || (proxy.isEmpty() && !needsDisable) || attempted == revision) return null;
        attempted = revision; working = true;
        return new Operation(revision, origin, proxy);
    }
    public synchronized boolean current(Operation operation) {
        return operation.revision == revision && operation.origin.equals(origin) && operation.proxyUrl.equals(proxy);
    }
    public synchronized boolean complete(Operation operation, boolean success, String message) {
        if (!current(operation)) return false;
        working = false;
        if (success) {
            confirmed = operation.proxyUrl;
            if (!operation.enable()) needsDisable = false;
            error = "";
        } else { confirmed = ""; error = message; }
        return true;
    }
    public synchronized boolean mobileConfirmed() { return !proxy.isEmpty() && proxy.equals(confirmed); }
    public synchronized boolean disablePending() { return proxy.isEmpty() && needsDisable; }
    public synchronized boolean working() { return working; }
    public synchronized String error() { return error; }

    /** Accepts the plugin's v1 status contract, which intentionally has no `ok` field. */
    public static String statusProblem(int protocol, String mode, boolean supported, String message) {
        if (protocol != 1 || !("normal".equals(mode) || "mobile".equals(mode)))
            return "电脑插件版本不兼容，请重新安装配套插件并重启酒馆。";
        if (supported) return "";
        if (message != null && message.startsWith("需要 Node.js"))
            return "电脑 Node.js 版本过旧，请升级到 24.14+ 或 25.4+ 后重启酒馆。";
        if (message != null && message.startsWith("当前酒馆版本"))
            return "当前酒馆版本的网络接口不兼容，请更新酒馆后重试。";
        return "电脑插件依赖不完整，请重新安装插件或更新酒馆依赖。";
    }
}
