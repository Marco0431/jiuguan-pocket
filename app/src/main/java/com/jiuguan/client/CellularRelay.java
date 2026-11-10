package com.jiuguan.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps LAN Wi-Fi separate from cellular or the app's active VPN; never binds the whole process. */
public final class CellularRelay implements Closeable {
    public interface Listener { void onRelayChanged(); }

    private static final int LAN_PORT = 10808;
    private static final int LAN_CONNECT_TIMEOUT_MS = 10000;
    private static final String USERNAME = "jiuguan";
    private static final String TOKEN_SCOPE = "jiuguan-relay\nproxy-v1";
    private final ConnectivityManager connectivity;
    private final CredentialStore credentials;
    private final Listener listener;
    private final RelayDiagnostics diagnostics = new RelayDiagnostics();
    private final RelayRoutePolicy<Network> routes = new RelayRoutePolicy<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "JiuguanRelaySetup");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long revision;
    private volatile boolean enabled;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String status = "手机流量中转已关闭";
    private volatile String proxyUrl = "";
    private volatile String localProxyRule = "";
    private volatile Network wifi;
    private volatile Network cell;
    private InetAddress wifiAddress;
    private String tavernHost;
    private long requestGeneration;
    private boolean building;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback cellCallback;
    private ConnectivityManager.NetworkCallback defaultCallback;
    private NetworkCapabilities defaultCapabilities;
    private RelaySession session;
    private long retiredUploaded;
    private long retiredDownloaded;

    public CellularRelay(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        connectivity = app.getSystemService(ConnectivityManager.class);
        credentials = new CredentialStore(app);
        this.listener = listener;
    }

    /** Called by the visible UI only after the owning service has entered foreground mode. */
    public void start(String tavernAddress) {
        requireMainThread();
        if (closed) return;
        final String host;
        try { host = normalizeHost(URI.create(UrlPolicy.normalize(tavernAddress)).getHost()); }
        catch (IllegalArgumentException e) {
            fail("请先填写正确的局域网酒馆地址。");
            return;
        }
        if (enabled && host.equals(tavernHost)) return;
        stopInternal();
        enabled = true;
        tavernHost = host;
        status = "正在连接局域网 Wi-Fi 和手机流量…";
        long generation = ++requestGeneration;
        ++revision;
        diagnostics.networkChanged(revision);
        notifyChanged();
        if (connectivity == null) {
            fail("系统网络服务不可用，无法启动手机流量中转。");
            return;
        }
        wifiCallback = createCallback(true, generation);
        cellCallback = createCallback(false, generation);
        defaultCallback = createDefaultCallback(generation);
        try {
            // The initial snapshot is outside callbacks. Later state comes only from ordered events.
            Network initialDefault = connectivity.getActiveNetwork();
            defaultCapabilities = initialDefault == null ? null : connectivity.getNetworkCapabilities(initialDefault);
            routes.seed(initialDefault, defaultCapabilities != null,
                    defaultCapabilities != null && defaultCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    defaultCapabilities != null && defaultCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            connectivity.registerDefaultNetworkCallback(defaultCallback, main);
            NetworkRequest wifiRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            NetworkRequest cellRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            connectivity.requestNetwork(wifiRequest, wifiCallback, main);
            connectivity.requestNetwork(cellRequest, cellCallback, main);
        } catch (SecurityException e) {
            fail("未获得网络切换权限，无法启动中转。请更新应用后重试。");
            return;
        } catch (RuntimeException e) {
            fail("系统未能开启双网络。请连接局域网 Wi-Fi、打开移动数据，再重试。");
            return;
        }
        main.postDelayed(() -> {
            if (currentRequest(generation) && !ready && !building) {
                status = "尚未连接双网络：请保持局域网 Wi-Fi 连接并打开移动数据；无外网的 Wi-Fi 请选择继续连接。";
                notifyChanged();
            }
        }, 30000);
    }

    public void stop() {
        requireMainThread();
        stopInternal();
        status = "手机流量中转已关闭";
        notifyChanged();
    }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        stopInternal();
        status = "手机流量中转已关闭";
        builder.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    public boolean isEnabled() { return enabled; }
    public boolean isReady() { return ready; }
    public String status() { return status; }
    /** Contains the pairing secret. Only expose in explicit copy/share controls, never logs. */
    public String proxyUrl() { return proxyUrl; }
    public String localProxyRule() { return localProxyRule; }
    public long uploadedBytes() { return retiredUploaded + (session == null ? 0 : session.uploadedBytes()); }
    public long downloadedBytes() { return retiredDownloaded + (session == null ? 0 : session.downloadedBytes()); }
    public int activeConnections() { return session == null ? 0 : session.activeConnections(); }

    /** Safe to show or copy: contains no host, pairing secret, URL, payload or exception text. */
    public String diagnostics() {
        return "局域网 Wi-Fi：" + networkDiagnostic(wifi)
                + "\n手机蜂窝网络：" + networkDiagnostic(cell)
                + "\n（Wi-Fi 未通过系统联网验证，仍可能正常访问局域网。）"
                + "\n" + outboundDiagnostic()
                + "\nVPN 底层由代理应用选择；酒馆口袋保持蜂窝可用，但不能替其他 VPN 强制选择流量。"
                + "\nVPN 需包含酒馆口袋，并允许访问局域网；严格阻止非 VPN 连接可能影响电脑连接。"
                + "\n\n" + diagnostics.summary()
                + "\n公网连接：IPv4/IPv6 交错，最多 4 个地址，每次最多 3 秒，连接预算共 12 秒。"
                + "\nDNS 在后台执行并计入预算，但系统 DNS 调用本身无法强制限时；其耗时可能超过 12 秒。"
                + "\nTCP 已连通只表示网络连接成功，不代表 HTTPS 握手、API 鉴权或模型请求成功。";
    }

    private String networkDiagnostic(Network network) {
        if (connectivity == null || network == null) return "尚未获取";
        try {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null) return "已不可用";
            return "可用；系统联网验证（VALIDATED）："
                    + (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "已通过" : "未通过");
        } catch (RuntimeException e) {
            return "系统未允许读取状态";
        }
    }

    private String outboundDiagnostic() {
        RelayRoutePolicy.Selection<Network> selected = routes.select(cell);
        switch (selected.kind) {
            case VPN:
                String transports = defaultCapabilities == null ? "未知"
                        : (defaultCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "蜂窝 " : "")
                        + (defaultCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi " : "");
                return "当前公网出口：本应用的活动 VPN\nVPN 网络：" + networkDiagnostic(selected.network)
                        + "\nVPN 报告的底层传输：" + (transports.trim().isEmpty() ? "未报告" : transports.trim());
            case WAIT_DEFAULT: return "当前公网出口：等待系统默认网络信息；尚未选择出口";
            case WAIT_VPN: return "当前公网出口：VPN 暂不可用或被系统阻止；未回退蜂窝直连";
            case WAIT_CELLULAR: return "当前公网出口：等待蜂窝网络";
            default: return "当前公网出口：蜂窝直连；未检测到覆盖本应用的活动 VPN";
        }
    }

    private ConnectivityManager.NetworkCallback createDefaultCallback(long generation) {
        return new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (!currentRequest(generation)) return;
                if (routes.available(network)) {
                    defaultCapabilities = null;
                    invalidateSession();
                    attemptStart();
                }
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (!currentRequest(generation) || !routes.isCurrentDefault(network)) return;
                boolean transportChanged = defaultCapabilities != null
                        && defaultCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && (defaultCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                != capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || defaultCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                != capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                defaultCapabilities = new NetworkCapabilities(capabilities);
                if (routes.capabilities(network, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) || transportChanged)
                    invalidateSession();
                attemptStart();
                notifyChanged();
            }

            @Override public void onBlockedStatusChanged(Network network, boolean blocked) {
                if (!currentRequest(generation)) return;
                if (routes.blocked(network, blocked)) { invalidateSession(); attemptStart(); }
            }

            @Override public void onLost(Network network) {
                if (!currentRequest(generation) || !routes.lost(network)) return;
                defaultCapabilities = null;
                invalidateSession();
                attemptStart();
            }
        };
    }

    private ConnectivityManager.NetworkCallback createCallback(boolean forWifi, long generation) {
        return new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (!currentRequest(generation)) return;
                Network previous = forWifi ? wifi : cell;
                if (!network.equals(previous)) {
                    if (forWifi) { wifi = network; wifiAddress = null; }
                    else cell = network;
                    invalidateSession();
                }
                // Wi-Fi setup waits for onLinkPropertiesChanged, not an immediate racing query.
                attemptStart();
            }

            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (!currentRequest(generation) || !forWifi || !network.equals(wifi)) return;
                InetAddress address = privateIpv4Address(properties);
                if (!java.util.Objects.equals(address, wifiAddress)) {
                    wifiAddress = address;
                    invalidateSession();
                }
                attemptStart();
            }

            @Override public void onLost(Network network) {
                if (!currentRequest(generation)) return;
                if (forWifi && network.equals(wifi)) { wifi = null; wifiAddress = null; }
                else if (!forWifi && network.equals(cell)) cell = null;
                else return;
                invalidateSession();
                attemptStart();
            }

            @Override public void onUnavailable() {
                if (!currentRequest(generation)) return;
                fail(forWifi ? "无法保留局域网 Wi-Fi，请重新连接 Wi-Fi 后开启中转。"
                        : "手机流量不可用，请打开移动数据并确认 SIM 卡可上网，再开启中转。");
            }
        };
    }

    private void attemptStart() {
        if (!enabled || closed) return;
        if (wifi == null || wifiAddress == null || cell == null) {
            if (wifi == null) status = "等待局域网 Wi-Fi，请连接电脑所在的 Wi-Fi。";
            else if (wifiAddress == null) status = "当前 Wi-Fi 没有可用的内网 IPv4 地址，请使用 192.168.x.x、10.x.x 或 172.16–31.x.x 的局域网。";
            else status = "等待手机流量，请打开移动数据并确认 SIM 卡可上网。";
            notifyChanged();
            return;
        }
        if (ready || building) return;
        RelayRoutePolicy.Selection<Network> selected = routes.select(cell);
        if (selected.network == null) {
            status = selected.kind == RelayRoutePolicy.Kind.WAIT_VPN
                    ? "VPN 暂不可用或被系统阻止，请检查手机代理；本次未回退蜂窝直连。"
                    : "正在确认手机的默认网络，请稍候…";
            notifyChanged();
            return;
        }
        building = true;
        status = "双网络已连接，正在准备中转…";
        notifyChanged();
        long expectedRevision = revision;
        Network wifiSnapshot = wifi;
        Network publicSnapshot = selected.network;
        boolean vpnSnapshot = selected.kind == RelayRoutePolicy.Kind.VPN;
        InetAddress addressSnapshot = wifiAddress;
        String hostSnapshot = tavernHost;
        builder.execute(() -> {
            RelaySession built = null;
            String failure = null;
            try {
                String token = pairingToken();
                List<InetAddress> tavernAddresses = new ArrayList<>();
                for (InetAddress address : wifiSnapshot.getAllByName(hostSnapshot)) {
                    if (isPrivateLanAddress(address) && !address.equals(addressSnapshot)) tavernAddresses.add(address);
                }
                if (tavernAddresses.isEmpty()) {
                    failure = "酒馆地址没有指向局域网电脑，请填写电脑的内网 IP 后重试。";
                } else {
                    built = new RelaySession(expectedRevision, wifiSnapshot, publicSnapshot,
                            addressSnapshot, hostSnapshot, tavernAddresses, token, vpnSnapshot);
                    built.start();
                }
            } catch (UnknownHostException e) {
                failure = "无法在局域网解析酒馆地址，请改填电脑的内网 IP 后重试。";
            } catch (BindException e) {
                failure = "中转端口 10808 已被占用或 Wi-Fi 地址已变化，请关闭其他代理后重试。";
            } catch (GeneralSecurityException e) {
                failure = "无法安全保存中转配对密钥，请检查手机存储空间后重试。";
            } catch (IOException | RuntimeException e) {
                failure = "中转启动失败，请保持局域网 Wi-Fi 和移动数据连接后重试。";
            }
            RelaySession result = built;
            String error = failure;
            main.post(() -> {
                building = false;
                if (closed || !enabled || revision != expectedRevision) {
                    if (result != null) result.close();
                    if (!closed) attemptStart();
                    return;
                }
                if (error != null || result == null) {
                    if (result != null) result.close();
                    fail(error == null ? "中转启动失败，请重新开启。" : error);
                    return;
                }
                session = result;
                proxyUrl = "socks5h://" + USERNAME + ":" + result.token + "@" + addressSnapshot.getHostAddress() + ":" + result.lan.port();
                localProxyRule = "socks://127.0.0.1:" + result.local.port();
                ready = true;
                status = vpnSnapshot ? "中转已就绪 · 外网经手机 VPN，酒馆页面走局域网"
                        : "中转已就绪 · 外网走手机流量，酒馆页面走局域网";
                notifyChanged();
            });
        });
    }

    private void invalidateSession() {
        ++revision;
        diagnostics.networkChanged(revision);
        ready = false;
        // Keep the last loopback rule so a transient outage cannot make WebView silently go direct.
        proxyUrl = "";
        if (session != null) {
            session.close();
            retiredUploaded += session.uploadedBytes();
            retiredDownloaded += session.downloadedBytes();
            session = null;
        }
    }

    private void stopInternal() {
        enabled = false;
        ++requestGeneration;
        invalidateSession();
        unregister(wifiCallback);
        unregister(cellCallback);
        unregister(defaultCallback);
        wifiCallback = null;
        cellCallback = null;
        defaultCallback = null;
        defaultCapabilities = null;
        routes.seed(null, true, false, false);
        wifi = null;
        cell = null;
        wifiAddress = null;
        localProxyRule = "";
        proxyUrl = "";
    }

    private void fail(String message) {
        stopInternal();
        status = message;
        notifyChanged();
    }

    private void unregister(ConnectivityManager.NetworkCallback callback) {
        if (connectivity == null || callback == null) return;
        try { connectivity.unregisterNetworkCallback(callback); }
        catch (RuntimeException ignored) { /* Registration may have failed or already expired. */ }
    }

    private boolean currentRequest(long generation) { return enabled && !closed && generation == requestGeneration; }
    private void notifyChanged() { if (!closed && listener != null) listener.onRelayChanged(); }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Relay lifecycle must run on the main thread");
    }

    private String pairingToken() throws GeneralSecurityException {
        CredentialStore.Entry entry = credentials.get(TOKEN_SCOPE);
        if (entry != null && USERNAME.equals(entry.username) && entry.password.matches("[0-9a-f]{64}")) return entry.password;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder(64);
        for (byte value : random) token.append(String.format(Locale.ROOT, "%02x", value & 255));
        credentials.save(TOKEN_SCOPE, USERNAME, token.toString(), false);
        Arrays.fill(random, (byte) 0);
        return token.toString();
    }

    private static InetAddress privateIpv4Address(LinkProperties properties) {
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (address instanceof Inet4Address && isPrivateLanAddress(address)) return address;
        }
        return null;
    }

    private static boolean isPrivateLanAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 255, second = bytes[1] & 255;
            return first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) normalized = normalized.substring(1, normalized.length() - 1);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private final class RelaySession implements Closeable {
        private final long expectedRevision;
        private final Network localNetwork;
        private final Network mobileNetwork;
        private final boolean vpnRouted;
        private final String host;
        private final Set<String> computerAddresses = new HashSet<>();
        private final List<InetAddress> computerAddressList;
        private final String token;
        private final Set<Socket> connectingSockets = ConcurrentHashMap.newKeySet();
        private volatile boolean active = true;
        private final SocksProxyServer lan;
        private final SocksProxyServer local;

        RelaySession(long expectedRevision, Network wifi, Network cell, InetAddress bindAddress,
                     String host, List<InetAddress> addresses, String token, boolean vpnRouted) throws IOException {
            this.expectedRevision = expectedRevision;
            localNetwork = wifi;
            mobileNetwork = cell;
            this.vpnRouted = vpnRouted;
            this.host = host;
            this.token = token;
            computerAddressList = addresses;
            for (InetAddress address : addresses) computerAddresses.add(normalizeHost(address.getHostAddress()));
            lan = new SocksProxyServer(bindAddress, LAN_PORT, USERNAME, token,
                    (target, port) -> connect(target, port, false));
            local = new SocksProxyServer(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0, null, null,
                    (target, port) -> connect(target, port, true));
        }

        void start() throws IOException {
            checkActive();
            try { local.start(); lan.start(); checkActive(); }
            catch (IOException | RuntimeException e) { close(); throw e; }
        }

        Socket connect(String target, int port, boolean fromWebView) throws IOException {
            checkActive();
            String normalized = normalizeHost(target);
            boolean computer = fromWebView && (normalized.equals(host) || computerAddresses.contains(normalized));
            Network network = computer ? localNetwork : mobileNetwork;
            RelayDiagnostics.Attempt attempt = diagnostics.begin(expectedRevision, computer
                    ? RelayDiagnostics.Route.LAN_PAGE : fromWebView
                    ? RelayDiagnostics.Route.PAGE_PUBLIC : RelayDiagnostics.Route.COMPUTER_PUBLIC);
            try {
                final Socket connected;
                if (computer) {
                    connected = connectComputer(network, port, attempt);
                } else {
                    // Start before DNS so slow resolution cannot be followed by another full TCP budget.
                    // Android's network-specific DNS call itself cannot be forcibly time limited here.
                    RelayConnectPlan plan = new RelayConnectPlan(SystemClock::elapsedRealtime);
                    InetAddress[] addresses = network.getAllByName(target);
                    checkActive();
                    boolean allowFakeIp = vpnRouted && RelayAddressPolicy.allowsVpnFakeIpHost(target);
                    diagnostics.resolved(attempt, addresses, allowFakeIp);
                    connected = plan.connect(addresses, this::checkActive, (address, timeout) -> {
                        diagnostics.connecting(attempt, address);
                        return openSocket(network, address, port, timeout);
                    }, allowFakeIp);
                }
                diagnostics.connected(attempt, connected.getInetAddress());
                return connected;
            } catch (IOException | RuntimeException e) {
                diagnostics.failed(attempt, e);
                throw e;
            }
        }

        private Socket connectComputer(Network network, int port, RelayDiagnostics.Attempt attempt) throws IOException {
            IOException lastError = new IOException("Computer is unavailable on the local network");
            for (int i = 0; i < Math.min(computerAddressList.size(), 4); i++) {
                checkActive();
                InetAddress address = computerAddressList.get(i);
                diagnostics.connecting(attempt, address);
                try { return openSocket(network, address, port, LAN_CONNECT_TIMEOUT_MS); }
                catch (IOException e) { lastError = e; }
            }
            throw lastError;
        }

        private Socket openSocket(Network network, InetAddress address, int port, int timeout) throws IOException {
            checkActive();
            Socket socket = network.getSocketFactory().createSocket();
            connectingSockets.add(socket);
            boolean handedOver = false;
            try {
                checkActive();
                socket.connect(new InetSocketAddress(address, port), timeout);
                checkActive();
                handedOver = true;
                return socket;
            } finally {
                connectingSockets.remove(socket);
                if (!handedOver) closeSocket(socket);
            }
        }

        private void checkActive() throws IOException {
            if (!active || !enabled || closed || revision != expectedRevision)
                throw new RelayConnectPlan.NetworkChangedException();
        }

        long uploadedBytes() { return lan.uploadedBytes() + local.uploadedBytes(); }
        long downloadedBytes() { return lan.downloadedBytes() + local.downloadedBytes(); }
        int activeConnections() { return lan.activeConnections() + local.activeConnections(); }

        @Override public void close() {
            active = false;
            for (Socket socket : connectingSockets) closeSocket(socket);
            connectingSockets.clear();
            lan.close();
            local.close();
        }
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
