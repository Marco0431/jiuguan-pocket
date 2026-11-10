package com.jiuguan.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import java.util.UUID;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;

/** Owns the browser independently of the Activity. Never pauses active page timers on Home/lock. */
public class BrowserService extends Service {
    public static final String ENABLE = "com.jiuguan.client.ENABLE";
    public static final String STOP = "com.jiuguan.client.STOP";
    private static final String CHANNEL = "active_session";
    private static final int NOTIFICATION = 7;
    private static final int MAX_EXPORT_BYTES = 16 * 1024 * 1024;
    private static final long LOCK_TIMEOUT = 2 * 60 * 60 * 1000L;

    public interface Client {
        void progress(int value);
        void pageError(String message);
        void httpAuth(HttpAuthHandler handler, String host, String realm, String scope, boolean rejected);
        void chooseFile(WebChromeClient.FileChooserParams params);
        void openExternal(Uri uri);
        void exportReady(String name, String mime);
        void sessionStopped();
        void relayChanged();
        default void connectionChanged() { }
    }

    public class LocalBinder extends Binder {
        public BrowserService getService() { return BrowserService.this; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final String exportToken = UUID.randomUUID().toString();
    private MutableContextWrapper browserContext;
    private WebView browser;
    private Client client;
    private String address = "";
    private boolean keepingAlive;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] exportBytes;
    private String exportName;
    private String exportMime;
    private boolean exportBusy;
    private CredentialStore credentials;
    private final AuthSession authSession = new AuthSession();
    // Auth callbacks omit URL/port. Only use saved credentials before any page subresource runs.
    private volatile boolean mainAuthenticationPhase;
    private HttpAuthHandler pendingAuth;
    private String pendingAuthScope, pendingAuthHost, pendingAuthRealm;
    private boolean pendingAuthRejected;
    private CellularRelay relay;
    private String relayFailure = "";
    private String requestedProxyRule = "";
    private String appliedProxyRule = "";
    private String pendingNavigation;
    private int proxyRevision;
    private boolean clearingProxy;
    private volatile boolean destroyed;
    private PluginControlClient pluginClient;
    private final PluginControlState pluginState = new PluginControlState();
    private SharedPreferences pluginPreferences;
    private String pluginClientId;
    private String controlOrigin = "";
    private String controlAuthorization = "";
    private boolean controlPageReady, controlPageCommitted, controlPageFinished, controlPageFailed;
    private volatile boolean mobileRequested;
    private boolean pluginNetworkWasReady;
    private final ConnectionState connectionState = new ConnectionState();
    private Runnable connectionTimeout;
    private long pendingNavigationGeneration;
    private final Runnable refreshPluginControl = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (((mobileRequested && isRelayReady()) || (!mobileRequested && pluginState.disablePending()))
                    && controlPageReady && !pluginState.working()) {
                pluginState.retry();
                syncPlugin(false);
            }
            main.postDelayed(this, 60000);
        }
    };

    private final Runnable renewLock = new Runnable() {
        @Override public void run() {
            if (!keepingAlive) return;
            acquireLocks();
            main.postDelayed(this, LOCK_TIMEOUT / 2);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        credentials = new CredentialStore(this);
        pluginPreferences = getSharedPreferences("plugin_control", MODE_PRIVATE);
        pluginClientId = pluginPreferences.getString("clientId", "");
        if (!pluginClientId.matches("[0-9a-fA-F-]{36}")) {
            pluginClientId = UUID.randomUUID().toString();
            pluginPreferences.edit().putString("clientId", pluginClientId).apply();
        }
        pluginClient = new PluginControlClient(this);
        main.postDelayed(refreshPluginControl, 60000);
        relay = new CellularRelay(this, this::onRelayChanged);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "酒馆后台保持", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("后台运行时保留酒馆页面和连接，可随时停止");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        browserContext = new MutableContextWrapper(this);
        browser = new WebView(browserContext);
        browser.setBackgroundColor(0xff111820);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Android document picker uploads only.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        // This bridge only submits an in-memory export to Android's user-confirmed save picker.
        // It cannot read files, execute intents, change settings, or silently write a destination.
        browser.addJavascriptInterface(new ExportBridge(), "JiuguanExport");
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                boolean currentAttempt = connectionState.pageStarted(url);
                if (connectionState.isConnecting() && !currentAttempt) return;
                if (sameControlOrigin(url)) {
                    controlPageReady = false;
                    controlPageCommitted = false;
                    controlPageFinished = false;
                    controlPageFailed = false;
                    if (isLoginPage(url)) controlAuthorization = "";
                }
                if (client != null) client.progress(5);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (connectionState.isConnecting()) {
                    if (!connectionState.pageFinished(url)) return;
                    clearConnectionTimeout();
                } else if (!connectionState.hasLoadedPage()) return;
                if (!UrlPolicy.sameOrigin(address, url)) return;
                mainAuthenticationPhase = false;
                // A successfully finished main document is enough even while the WebView is
                // behind the setup screen; visible-commit can wait until Activity reveals it.
                saveAcceptedLogin(authSession.pageCommitted(url));
                saveAcceptedLogin(authSession.finish(url));
                CookieManager.getInstance().flush();
                if (UrlPolicy.sameOrigin(address, url)) installExportHook();
                if (sameControlOrigin(url)) {
                    controlPageFinished = true;
                    finishControlPage(url);
                }
                if (client != null) client.progress(100);
                notifyConnectionChanged();
            }
            @Override public void onPageCommitVisible(WebView view, String url) {
                if (!connectionState.hasLoadedPage() && !connectionState.currentDocument(url)) return;
                mainAuthenticationPhase = false;
                saveAcceptedLogin(authSession.pageCommitted(url));
                if (sameControlOrigin(url)) {
                    controlPageCommitted = true;
                    finishControlPage(url);
                }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) mainAuthenticationPhase = false;
                if ("/api/users/logout".equals(request.getUrl().getPath()) && sameControlOrigin(request.getUrl().toString()))
                    main.post(() -> { controlAuthorization = ""; controlPageReady = false; pluginState.retry(); });
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (UrlPolicy.sameOrigin(address, uri.toString())) return false;
                if (client != null && request.hasGesture()) client.openExternal(uri);
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && connectionState.currentDocument(request.getUrl().toString())) {
                    authSession.fail();
                    controlPageFailed = true;
                    controlPageReady = false;
                    failConnection(networkFailureMessage(error.getErrorCode()));
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && connectionState.currentDocument(request.getUrl().toString())) {
                    if (response.getStatusCode() == 401 && connectionState.waitingForAuth()) return;
                    authSession.fail(); controlPageFailed = true; controlPageReady = false;
                    if (response.getStatusCode() == 401 || response.getStatusCode() == 403) controlAuthorization = "";
                    failConnection(httpFailureMessage(response.getStatusCode()));
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                // SSL callbacks have no main-frame flag: a failed image/script must not fail
                // the whole connection. Only the exact current document owns this attempt.
                if (connectionState.currentDocument(error.getUrl())) {
                    authSession.fail(); controlPageFailed = true; controlPageReady = false;
                    failConnection("HTTPS 证书验证失败。请检查电脑证书，或改用你信任的局域网 HTTP 地址后重试。");
                }
            }
            @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                receiveAuth(handler, host, realm);
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (client != null) client.progress(progress);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                completeFileChooser(null);
                if (client == null) { callback.onReceiveValue(null); return true; }
                fileCallback = callback;
                client.chooseFile(params);
                return true;
            }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                if (!isUserGesture || client == null) return false;
                WebView popup = new WebView(browserContext);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        if (UrlPolicy.sameOrigin(address, uri.toString())) navigate(uri.toString());
                        else if (client != null) client.openExternal(uri);
                        main.post(child::destroy);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        browser.setDownloadListener((url, agent, disposition, mime, size) -> {
            if (url.startsWith("blob:") || url.startsWith("data:") || UrlPolicy.sameOrigin(address, url)) {
                String name = URLUtil.guessFileName(url, disposition, mime);
                browser.evaluateJavascript("window.__jiuguanSaveUrl && window.__jiuguanSaveUrl("
                        + JSONObject.quote(url) + "," + JSONObject.quote(name) + ")", null);
            } else if (client != null) client.openExternal(Uri.parse(url));
        });
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { disconnect(); return START_NOT_STICKY; }
        if (intent != null && ENABLE.equals(intent.getAction())) enableKeepingAlive();
        // A killed session cannot resume a streamed POST safely. Never silently reload or regenerate.
        return START_NOT_STICKY;
    }

    public WebView browser() { return browser; }
    public String address() { return address; }
    public boolean isKeepingAlive() { return keepingAlive; }
    public CredentialStore credentials() { return credentials; }
    public boolean isConnecting() { return connectionState.isConnecting(); }
    public boolean hasLoadedPage() { return connectionState.hasLoadedPage(); }
    public String connectionMessage() { return connectionState.message(); }

    public void diagnoseRelay(java.util.function.Consumer<String> callback) {
        String heading = "酒馆口袋 1.3.4 连接诊断\n这里只显示连接阶段和计数，不读取模型密钥或请求正文。\n\n";
        if (controlOrigin.isEmpty()) {
            callback.accept(heading + relay.diagnostics() + "\n\n电脑：尚未打开酒馆，请先连接并登录后查看。" );
            return;
        }
        String origin = controlOrigin;
        String cookie = CookieManager.getInstance().getCookie(origin + "/");
        pluginClient.diagnose(origin, relayProxyUrl(), cookie, controlAuthorization, result -> {
            if (destroyed) return;
            callback.accept(heading + relay.diagnostics() + "\n\n电脑诊断：\n" + result.message
                    + "\n\n请先在酒馆重现模型列表或发送失败，再刷新诊断。诊断本身不会调用模型。" );
        });
    }

    /** Only remember an authenticated page with its selected network mode confirmed. */
    public boolean canRememberStartup() {
        return hasLoadedPage() && controlPageReady && (mobileRequested ? isComputerRelayReady()
                : !isRelayEnabled() && !pluginState.disablePending());
    }

    /** A real same-origin HTML login page should be shown so the user can sign in. */
    public boolean startupNeedsLogin() {
        if (!hasLoadedPage() || controlPageFailed || !controlPageFinished || browser == null) return false;
        String url = browser.getUrl();
        if (!sameControlOrigin(url)) return false;
        String path = Uri.parse(url).getPath();
        return path != null && (path.equals("/login") || path.startsWith("/login/"));
    }

    /** Waiting for network/login is not itself an error. */
    public String startupProblem() {
        return !relayFailure.isEmpty() ? relayFailure : pluginState.error();
    }

    /** Called only while the Activity is validating an automatic startup. */
    public void cancelStartupConnection(String message) {
        if (canRememberStartup() || startupNeedsLogin()) return;
        String reason = message == null || message.trim().isEmpty()
                ? "自动连接未完成，请检查地址和连接方式后重试。" : message;
        if (connectionState.rejectSetup(connectionState.generation(), reason)) endUnfinishedConnection();
    }

    public void cancelConnection() {
        if (!connectionState.cancel()) return;
        endUnfinishedConnection();
    }

    private void notifyConnectionChanged() { if (client != null) client.connectionChanged(); }

    private void clearConnectionTimeout() {
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        connectionTimeout = null;
    }

    private void armConnectionTimeout() {
        clearConnectionTimeout();
        if (!isConnecting() || connectionState.waitingForAuth()) return;
        long generation = connectionState.generation();
        connectionTimeout = () -> {
            if (!destroyed && connectionState.timeOut(generation, SystemClock.elapsedRealtime()))
                endUnfinishedConnection();
        };
        main.postDelayed(connectionTimeout, Math.max(1, connectionState.deadline() - SystemClock.elapsedRealtime()));
    }

    private void failConnection(String message) {
        if (connectionState.fail(connectionState.generation(), message)) endUnfinishedConnection();
    }

    private void endUnfinishedConnection() {
        // State is already terminal before any callback produced by cancellation can arrive.
        clearConnectionTimeout();
        pendingNavigation = null; pendingNavigationGeneration = 0;
        mainAuthenticationPhase = false;
        cancelPendingAuth();
        browser.stopLoading();
        completeFileChooser(null);
        stopRelay(); // Native LAN control makes a best-effort disable before local teardown.
        disableKeepingAlive();
        controlPageReady = false; controlPageFailed = true;
        browser.loadUrl("about:blank");
        notifyConnectionChanged();
    }

    private static String networkFailureMessage(int code) {
        if (code == WebViewClient.ERROR_HOST_LOOKUP)
            return "无法解析酒馆地址。请填写电脑的局域网 IP，并检查手机是否连接了同一 Wi-Fi。";
        if (code == WebViewClient.ERROR_CONNECT)
            return "连接不上电脑酒馆。请先启动酒馆，核对 IP 和端口，并检查电脑防火墙是否允许访问。";
        if (code == WebViewClient.ERROR_TIMEOUT)
            return "酒馆响应超时。请检查电脑是否休眠、手机与电脑是否仍在同一局域网，再重试。";
        if (code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE)
            return "无法建立 HTTPS 连接。请检查电脑证书和网址协议后重试。";
        return "酒馆连接已中断。请检查局域网、电脑上的酒馆服务和连接方式，再重试。";
    }

    private static String httpFailureMessage(int code) {
        if (code == 401) return "账号或密码未通过验证（HTTP 401）。请重新连接并输入正确的酒馆账号密码。";
        if (code == 403) return "电脑拒绝访问（HTTP 403）。请检查酒馆白名单是否允许手机的局域网 IP，以及账号权限。";
        if (code == 404) return "没有找到酒馆页面（HTTP 404）。请检查地址、端口和网址路径。";
        return "电脑酒馆返回 HTTP " + code + "。请查看电脑酒馆窗口中的错误，再重新连接。";
    }
    public boolean isRelayEnabled() { return relay != null && relay.isEnabled(); }
    public boolean isRelayReady() {
        return relay != null && relay.isReady() && !appliedProxyRule.isEmpty()
                && appliedProxyRule.equals(relay.localProxyRule()) && !clearingProxy;
    }
    public String relayStatus() {
        if (!relayFailure.isEmpty()) return relayFailure;
        if (relay.isReady() && !isRelayReady()) return "正在设置网页的网络连接…";
        if (mobileRequested && isRelayReady()) {
            if (isComputerRelayReady()) return "电脑已切换到手机代理；API 连通性请查看“连接诊断”";
            if (!pluginState.error().isEmpty()) return pluginState.error();
            if (!controlPageReady) return "手机中转已就绪，请先登录酒馆，随后会自动连接电脑。";
            return "正在自动连接电脑上的酒馆插件…";
        }
        if (!mobileRequested && pluginState.disablePending())
            return pluginState.error().isEmpty() ? "正在通知电脑恢复原来的网络…"
                    : "电脑网络恢复尚未确认：" + pluginState.error();
        return relay.status();
    }
    public boolean isComputerRelayReady() { return controlPageReady && isRelayReady() && pluginState.mobileConfirmed(); }
    public String relayProxyUrl() { return isRelayReady() ? relay.proxyUrl() : ""; }
    public long relayUploadedBytes() { return relay.uploadedBytes(); }
    public long relayDownloadedBytes() { return relay.downloadedBytes(); }

    public void startRelay(String serverAddress) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            relayFailure = "系统 WebView 太旧，无法设置网页中转。请先更新 Android System WebView / Chrome。";
            notifyRelayChanged();
            return;
        }
        try { serverAddress = UrlPolicy.normalize(serverAddress); }
        catch (IllegalArgumentException e) {
            relayFailure = "请先填写有效的电脑酒馆地址。";
            notifyRelayChanged();
            return;
        }
        relayFailure = "";
        try { enableKeepingAlive(); }
        catch (RuntimeException e) {
            relayFailure = "后台服务未能启动，请允许应用后台运行后重新开启中转。";
            notifyRelayChanged();
            return;
        }
        selectControlOrigin(serverAddress);
        mobileRequested = true;
        relay.start(serverAddress);
        if (relay.isEnabled() && requestedProxyRule.isEmpty()) {
            // No direct fallback while offline Wi-Fi/cellular are being acquired.
            applyWebProxy("socks://127.0.0.1:9");
        }
        onRelayChanged();
    }

    public void stopRelay() {
        pendingNavigation = null;
        relayFailure = "";
        mobileRequested = false;
        syncPlugin(true);
        relay.stop();
        clearWebProxy();
        notifyRelayChanged();
    }

    private void onRelayChanged() {
        if (destroyed) return;
        if (relay.isReady()) {
            String rule = relay.localProxyRule();
            if (!rule.equals(requestedProxyRule)) applyWebProxy(rule);
        }
        if (!relay.isEnabled() && pendingNavigation != null && isConnecting()) {
            failConnection(relayStatus());
            return;
        }
        // A lost network leaves the old, closed local proxy installed: no accidental direct fallback.
        notifyRelayChanged();
        navigateWhenRelayReady();
        syncPlugin(false);
    }

    private void navigateWhenRelayReady() {
        if (isRelayReady() && pendingNavigation != null) {
            String url = pendingNavigation;
            long generation = pendingNavigationGeneration;
            pendingNavigation = null;
            navigateNow(url, generation);
        }
    }

    private void applyWebProxy(String rule) {
        int revision = ++proxyRevision;
        requestedProxyRule = rule;
        appliedProxyRule = "";
        clearingProxy = false;
        try {
            ProxyConfig config = new ProxyConfig.Builder().addProxyRule(rule).removeImplicitRules().build();
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().setProxyOverride(config, main::post, () -> {
                    if (destroyed || revision != proxyRevision || !relay.isEnabled()) return;
                    appliedProxyRule = rule;
                    notifyRelayChanged();
                    navigateWhenRelayReady();
                    syncPlugin(false);
                });
            } else throw new UnsupportedOperationException("WebView proxy unsupported");
        } catch (RuntimeException e) {
            relayFailure = "网页中转设置失败，请更新系统 WebView 后重新开启。";
            if (isConnecting()) { failConnection(relayFailure); return; }
            pendingNavigation = null; pendingNavigationGeneration = 0;
            relay.stop();
            clearWebProxy();
            notifyRelayChanged();
        }
    }

    private void clearWebProxy() {
        int revision = ++proxyRevision;
        requestedProxyRule = "";
        appliedProxyRule = "";
        clearingProxy = true;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(main::post, () -> {
                    if (destroyed || revision != proxyRevision) return;
                    clearingProxy = false;
                    if (pendingNavigation != null && !isRelayEnabled()) {
                        String url = pendingNavigation;
                        long generation = pendingNavigationGeneration;
                        pendingNavigation = null;
                        navigateNow(url, generation);
                    }
                    notifyRelayChanged();
                });
            } else clearingProxy = false;
        } catch (RuntimeException e) { clearingProxy = false; }
    }

    private void notifyRelayChanged() {
        if (keepingAlive && !destroyed) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        if (client != null) client.relayChanged();
    }

    /** Retry only control commands. Never reloads the page or replays a model request. */
    public void requestPluginSync() {
        if (destroyed || pluginState.working()) return;
        pluginState.retry();
        syncPlugin(true);
    }

    private boolean sameControlOrigin(String url) {
        try { return !controlOrigin.isEmpty() && controlOrigin.equals(AuthScope.origin(url)); }
        catch (IllegalArgumentException e) { return false; }
    }

    private static boolean isLoginPage(String url) {
        String path = Uri.parse(url).getPath();
        return path != null && (path.equals("/login") || path.startsWith("/login/") || path.equals("/logout"));
    }

    private String pendingPluginKey(String origin) { return "pluginMayBeEnabled:" + origin; }

    private void selectControlOrigin(String url) {
        String next = AuthScope.origin(url);
        if (next.equals(controlOrigin)) return;
        if (!controlOrigin.isEmpty() && pluginPreferences.getBoolean(pendingPluginKey(controlOrigin), false))
            submitPluginOperation(new PluginControlState.Operation(-1, controlOrigin, ""), false);
        controlOrigin = next;
        controlAuthorization = "";
        controlPageReady = false; controlPageCommitted = false; controlPageFinished = false; controlPageFailed = false;
        pluginState.target(next, "", pluginPreferences.getBoolean(pendingPluginKey(next), false));
    }

    private void finishControlPage(String url) {
        if (controlPageFailed || !controlPageFinished || isLoginPage(url)
                || (!controlPageCommitted && !hasLoadedPage())) return;
        boolean wasReady = controlPageReady;
        controlPageReady = true;
        // WebView may reuse its HTTP auth cache without sending another challenge.
        if (controlAuthorization.isEmpty()) {
            String scope = pluginPreferences.getString("basicScope:" + controlOrigin, "");
            if (scope.startsWith(controlOrigin + "\n")) {
                CredentialStore.Entry entry = credentials.get(scope);
                if (entry != null) rememberControlAuthorization(entry.username, entry.password);
            }
        }
        if (!wasReady) pluginState.retry();
        syncPlugin(false);
    }

    private void rememberControlAuthorization(String username, String password) {
        controlAuthorization = "Basic " + Base64.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private void syncPlugin(boolean allowKnownSession) {
        if (destroyed || controlOrigin.isEmpty()) return;
        // Losing cellular connectivity leaves the remote proxy in place, failing closed.
        if (mobileRequested && !isRelayReady()) {
            if (pluginNetworkWasReady) { pluginState.retry(); pluginNetworkWasReady = false; }
            notifyRelayChanged(); return;
        }
        if (mobileRequested && !pluginNetworkWasReady) { pluginState.retry(); pluginNetworkWasReady = true; }
        if (!mobileRequested) pluginNetworkWasReady = false;
        String targetProxy = mobileRequested ? relay.proxyUrl() : "";
        boolean mayBeEnabled = pluginPreferences.getBoolean(pendingPluginKey(controlOrigin), false);
        pluginState.target(controlOrigin, targetProxy, mobileRequested || mayBeEnabled);
        PluginControlState.Operation operation = pluginState.next(controlPageReady ||
                (!mobileRequested && allowKnownSession && mayBeEnabled));
        if (operation != null) submitPluginOperation(operation, true);
        notifyRelayChanged();
    }

    private void submitPluginOperation(PluginControlState.Operation operation, boolean tracked) {
        String cookie = CookieManager.getInstance().getCookie(operation.origin + "/");
        String authorization = operation.origin.equals(controlOrigin) ? controlAuthorization : "";
        if (operation.enable()) {
            // Persist before enqueue, so a later cold start in normal mode can clean up after a kill.
            if (!pluginPreferences.edit().putBoolean(pendingPluginKey(operation.origin), true).commit()) {
                pluginState.complete(operation, false, "无法保存电脑连接状态，请检查手机存储空间后重试。");
                return;
            }
        }
        pluginClient.submit(operation, pluginClientId, cookie, authorization,
                () -> !destroyed && mobileRequested && relay.isReady() && pluginState.current(operation), result -> {
            // Apply only to the untouched, same browser session; never overwrite a newer login.
            if (!destroyed && operation.origin.equals(controlOrigin)
                    && java.util.Objects.equals(cookie, CookieManager.getInstance().getCookie(operation.origin + "/"))) {
                for (String update : result.cookies) CookieManager.getInstance().setCookie(operation.origin + "/", update);
                if (!result.cookies.isEmpty()) CookieManager.getInstance().flush();
            }
            if (result.success && !operation.enable()
                    && !(mobileRequested && operation.origin.equals(controlOrigin)))
                pluginPreferences.edit().putBoolean(pendingPluginKey(operation.origin), false).apply();
            if (destroyed) return;
            boolean current = tracked && pluginState.complete(operation, result.success, result.message);
            if (current && result.unauthorized) {
                controlAuthorization = "";
                controlPageReady = false;
            }
            notifyRelayChanged();
        });
    }

    public void attach(android.app.Activity activity, Client callback) {
        browserContext.setBaseContext(activity);
        client = callback;
        if (exportBytes != null) client.exportReady(exportName, exportMime);
        deliverPendingAuth();
        notifyConnectionChanged();
    }

    public void detach(Client callback) {
        if (client != callback) return;
        client = null;
        // Pending Basic login belongs to the retained browser; the next Activity can display it.
        completeFileChooser(null);
        if (browser.getParent() instanceof ViewGroup) ((ViewGroup) browser.getParent()).removeView(browser);
        browserContext.setBaseContext(this);
    }

    public void navigate(String url) {
        url = UrlPolicy.normalize(url);
        long generation = connectionState.begin(url, SystemClock.elapsedRealtime());
        clearConnectionTimeout();
        pendingNavigation = null; pendingNavigationGeneration = 0;
        cancelPendingAuth();
        browser.stopLoading();
        address = url;
        armConnectionTimeout();
        notifyConnectionChanged();
        selectControlOrigin(url);
        if (clearingProxy || (isRelayEnabled() && !isRelayReady())) {
            pendingNavigation = url;
            pendingNavigationGeneration = generation;
            connectionState.waitingNetwork(clearingProxy ? "正在切换网络连接…"
                    : "正在等待 Wi-Fi 和手机流量。请同时开启两者；15 秒内未连上会返回设置。");
            notifyConnectionChanged();
            return;
        }
        navigateNow(url, generation);
    }

    private void navigateNow(String url, long generation) {
        if (!connectionState.loading(generation)) return;
        selectControlOrigin(url);
        if (client != null) client.pageError("");
        cancelPendingAuth();
        address = UrlPolicy.normalize(url);
        authSession.start(address);
        mainAuthenticationPhase = true;
        controlPageFailed = false; controlPageFinished = false; controlPageCommitted = false;
        notifyConnectionChanged();
        browser.loadUrl(address);
    }

    public void reload() {
        if (clearingProxy || (isRelayEnabled() && !isRelayReady())) {
            if (client != null) client.pageError("中转尚未就绪，请查看“手机流量中转”中的网络状态。");
            return;
        }
        cancelPendingAuth();
        long generation = connectionState.begin(address, SystemClock.elapsedRealtime());
        connectionState.loading(generation);
        armConnectionTimeout();
        authSession.start(address);
        mainAuthenticationPhase = true;
        notifyConnectionChanged();
        browser.reload();
    }

    private void saveAcceptedLogin(AuthSession.Submission accepted) {
        if (accepted == null || !accepted.remember) return;
        try {
            credentials.save(accepted.scope, accepted.username, accepted.password, accepted.autoLogin);
            String origin = accepted.scope.substring(0, accepted.scope.indexOf('\n'));
            pluginPreferences.edit().putString("basicScope:" + origin, accepted.scope).apply();
        } catch (GeneralSecurityException e) {
            if (client != null) client.pageError("已登录，但本机未能安全保存密码；下次需要重新输入。");
        }
    }

    private void receiveAuth(HttpAuthHandler handler, String host, String realm) {
        final String scope;
        try { scope = AuthScope.key(address, host, realm); }
        catch (IllegalArgumentException e) { handler.cancel(); return; }
        // Do not fill credentials into frames/resources: WebView's callback cannot identify their port.
        if (!mainAuthenticationPhase) {
            handler.cancel();
            if (client != null) client.pageError("页面内的资源要求单独登录。为避免误用账号，请从菜单刷新后登录，或使用浏览器打开。");
            return;
        }
        boolean rejected = authSession.challenge(scope);
        if (rejected) {
            controlAuthorization = "";
            controlPageReady = false;
            try { credentials.setAutoLoginForServer(address, false); }
            catch (GeneralSecurityException ignored) { /* The current navigation still cannot auto-retry. */ }
        }
        CredentialStore.Entry saved = credentials.get(scope);
        if (!rejected && saved != null && saved.autoLogin && authSession.tryAutomatic(scope)) {
            authSession.submit(scope, saved.username, saved.password, true, true);
            controlPageFailed = false;
            rememberControlAuthorization(saved.username, saved.password);
            connectionState.resumeAfterAuth(SystemClock.elapsedRealtime());
            armConnectionTimeout();
            notifyConnectionChanged();
            handler.proceed(saved.username, saved.password);
            return;
        }
        if (pendingAuth != null) pendingAuth.cancel();
        pendingAuth = handler;
        pendingAuthScope = scope;
        pendingAuthHost = host;
        pendingAuthRealm = realm;
        pendingAuthRejected = rejected;
        connectionState.pauseForAuth();
        clearConnectionTimeout();
        notifyConnectionChanged();
        deliverPendingAuth();
    }

    public void deliverPendingAuth() {
        if (client != null && pendingAuth != null)
            client.httpAuth(pendingAuth, pendingAuthHost, pendingAuthRealm, pendingAuthScope, pendingAuthRejected);
    }

    public void submitAuth(HttpAuthHandler handler, String scope, String username, String password,
                           boolean remember, boolean automatic) {
        if (pendingAuth != handler || !scope.equals(pendingAuthScope)) return;
        pendingAuth = null;
        if (!remember) credentials.clearServer(address);
        authSession.submit(scope, username, password, remember, automatic);
        controlPageFailed = false;
        rememberControlAuthorization(username, password);
        connectionState.resumeAfterAuth(SystemClock.elapsedRealtime());
        armConnectionTimeout();
        notifyConnectionChanged();
        handler.proceed(username, password);
    }

    public void cancelAuth(HttpAuthHandler handler) {
        if (pendingAuth == handler) {
            cancelPendingAuth();
            failConnection("已取消登录。可修改地址或连接方式后重新打开酒馆。");
        }
    }

    private void cancelPendingAuth() {
        if (pendingAuth != null) pendingAuth.cancel();
        pendingAuth = null;
        authSession.invalidate();
    }

    public void forgetLogin(String serverAddress) {
        authSession.invalidate();
        credentials.clearServer(serverAddress);
    }

    public void setAutomaticLogin(String serverAddress, boolean enabled) throws GeneralSecurityException {
        authSession.invalidate();
        credentials.setAutoLoginForServer(serverAddress, enabled);
    }

    private void enableKeepingAlive() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification);
        keepingAlive = true;
        main.removeCallbacks(renewLock);
        renewLock.run();
    }

    public void disableKeepingAlive() {
        if (isRelayEnabled()) return; // The user-visible relay requires its foreground service.
        keepingAlive = false;
        main.removeCallbacks(renewLock);
        releaseLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(); // An attached Activity's binding still owns the current browser.
    }

    public void disconnect() {
        connectionState.reset();
        clearConnectionTimeout();
        stopRelay();
        cancelPendingAuth();
        mainAuthenticationPhase = false;
        disableKeepingAlive();
        browser.stopLoading();
        browser.loadUrl("about:blank");
        browser.clearHistory();
        address = "";
        controlAuthorization = ""; controlPageReady = false;
        clearExport();
        if (client != null) client.sessionStopped();
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BrowserService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.jiuguan.client.R.drawable.ic_notification)
                .setContentTitle(isRelayEnabled() ? "酒馆手机流量中转中" : "酒馆后台保持中")
                .setContentText(isRelayEnabled() ? relayStatus() : "页面和连接正在保留 · 点此返回酒馆")
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止并断开", stop).build())
                .build();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager manager = getSystemService(PowerManager.class);
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jiuguan:BackgroundSession");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(LOCK_TIMEOUT);
        if (wifiLock == null) {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) {
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Jiuguan:LocalNetwork");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    public void completeFileChooser(Uri[] result) {
        if (fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null;
            callback.onReceiveValue(result);
        }
    }

    public byte[] pendingExport() { return exportBytes; }
    public void deliverPendingExport() {
        if (client != null && exportBytes != null) client.exportReady(exportName, exportMime);
    }
    public void clearExport() { exportBytes = null; exportName = null; exportMime = null; exportBusy = false; }

    private void installExportHook() {
        String script = "(()=>{if(window.__jiuguanExportInstalled)return;window.__jiuguanExportInstalled=true;"
                + "const token=" + JSONObject.quote(exportToken) + ";"
                + "const save=async(url,name)=>{try{const r=await fetch(url,{credentials:'same-origin'});if(!r.ok)throw Error('下载失败');"
                + "const b=await r.blob();if(b.size>16777216)throw Error('文件超过16MB，请使用浏览器导出');"
                + "const reader=new FileReader();reader.onload=()=>JiuguanExport.save(token,name||'download',b.type||'application/octet-stream',reader.result.split(',')[1]);reader.readAsDataURL(b);"
                + "}catch(e){alert('保存文件失败：'+e.message)}};window.__jiuguanSaveUrl=save;"
                + "const intercept=a=>{if(a&&a.hasAttribute('download')&&/^(blob:|data:)/.test(a.href)){save(a.href,a.download);return true}return false};"
                + "const click=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(!intercept(this))return click.apply(this,arguments)};"
                + "document.addEventListener('click',e=>{const a=e.target.closest&&e.target.closest('a[download]');if(intercept(a)){e.preventDefault();e.stopImmediatePropagation()}},true)})()";
        browser.evaluateJavascript(script, null);
    }

    private class ExportBridge {
        @JavascriptInterface public void save(String token, String name, String mime, String data) {
            if (!exportToken.equals(token) || data == null || data.length() > (MAX_EXPORT_BYTES * 4L / 3L + 8)) return;
            final byte[] bytes;
            try { bytes = Base64.decode(data, Base64.DEFAULT); } catch (IllegalArgumentException e) { return; }
            if (bytes.length > MAX_EXPORT_BYTES) return;
            main.post(() -> {
                if (exportBusy || address.isEmpty()) return;
                exportBusy = true;
                exportBytes = bytes;
                exportName = name == null ? "download" : name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
                if (exportName.length() > 160) exportName = exportName.substring(0, 160);
                exportMime = mime != null && mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? mime : "application/octet-stream";
                if (client != null) client.exportReady(exportName, exportMime);
            });
        }
    }

    @Override public void onDestroy() {
        connectionState.reset();
        clearConnectionTimeout();
        mobileRequested = false;
        syncPlugin(true);
        if (pluginClient != null) pluginClient.closeAfterPending();
        destroyed = true;
        pendingNavigation = null;
        if (relay != null) relay.close();
        clearWebProxy();
        cancelPendingAuth();
        keepingAlive = false;
        main.removeCallbacksAndMessages(null);
        releaseLocks();
        completeFileChooser(null);
        client = null;
        if (browser != null) {
            if (browser.getParent() instanceof ViewGroup) ((ViewGroup) browser.getParent()).removeView(browser);
            browser.removeJavascriptInterface("JiuguanExport");
            browser.stopLoading();
            browser.destroy();
        }
        super.onDestroy();
    }
}
