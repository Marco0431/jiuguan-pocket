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

/** Keeps independent Wi-Fi and cellular networks; never changes process-wide routing. */
public final class CellularRelay implements Closeable {
    public interface Listener { void onRelayChanged(); }

    private static final int LAN_PORT = 10808;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final String USERNAME = "jiuguan";
    private static final String TOKEN_SCOPE = "jiuguan-relay\nproxy-v1";
    private final ConnectivityManager connectivity;
    private final CredentialStore credentials;
    private final Listener listener;
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
    private Network wifi;
    private Network cell;
    private InetAddress wifiAddress;
    private String tavernHost;
    private long requestGeneration;
    private boolean building;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback cellCallback;
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
        notifyChanged();
        if (connectivity == null) {
            fail("系统网络服务不可用，无法启动手机流量中转。");
            return;
        }
        wifiCallback = createCallback(true, generation);
        cellCallback = createCallback(false, generation);
        try {
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
        building = true;
        status = "双网络已连接，正在准备中转…";
        notifyChanged();
        long expectedRevision = revision;
        Network wifiSnapshot = wifi;
        Network cellSnapshot = cell;
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
                    built = new RelaySession(expectedRevision, wifiSnapshot, cellSnapshot,
                            addressSnapshot, hostSnapshot, tavernAddresses, token);
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
                status = "中转已就绪 · 外网走手机流量，酒馆页面走局域网";
                notifyChanged();
            });
        });
    }

    private void invalidateSession() {
        ++revision;
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
        wifiCallback = null;
        cellCallback = null;
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
        private final String host;
        private final Set<String> computerAddresses = new HashSet<>();
        private final List<InetAddress> computerAddressList;
        private final String token;
        private final Set<Socket> connectingSockets = ConcurrentHashMap.newKeySet();
        private volatile boolean active = true;
        private final SocksProxyServer lan;
        private final SocksProxyServer local;

        RelaySession(long expectedRevision, Network wifi, Network cell, InetAddress bindAddress,
                     String host, List<InetAddress> addresses, String token) throws IOException {
            this.expectedRevision = expectedRevision;
            localNetwork = wifi;
            mobileNetwork = cell;
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
            // SOCKS5 remote DNS resolution remains on the same network as the outbound socket.
            InetAddress[] addresses = computer ? computerAddressList.toArray(new InetAddress[0]) : network.getAllByName(target);
            checkActive();
            IOException lastError = new IOException("Target is unavailable on the selected network");
            for (int i = 0; i < Math.min(addresses.length, 4); i++) {
                InetAddress address = addresses[i];
                if (!computer && !RelayAddressPolicy.isPublicAddress(address)) continue;
                checkActive();
                Socket socket = network.getSocketFactory().createSocket();
                connectingSockets.add(socket);
                boolean handedOver = false;
                try {
                    checkActive();
                    socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
                    checkActive();
                    handedOver = true;
                    return socket;
                } catch (IOException e) {
                    lastError = e;
                } finally {
                    connectingSockets.remove(socket);
                    if (!handedOver) closeSocket(socket);
                }
            }
            throw lastError;
        }

        private void checkActive() throws IOException {
            if (!active || !enabled || closed || revision != expectedRevision) throw new IOException("Relay network changed");
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
