package com.jiuguan.client;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A bounded SOCKS5 CONNECT relay. The connector owns DNS, routing and destination policy. */
public final class SocksProxyServer implements Closeable {
    public interface Connector {
        Socket connect(String host, int port) throws IOException;
    }

    private static final int MAX_CONNECTIONS = 16;
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int STREAM_IDLE_TIMEOUT_MS = 15 * 60_000;
    private final InetAddress bindAddress;
    private final int requestedPort;
    private final byte[] username;
    private final byte[] password;
    private final Connector connector;
    private final Object stateLock = new Object();
    private final Set<Session> sessions = new HashSet<>();
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicLong downloaded = new AtomicLong();
    private final ThreadPoolExecutor requests = pool("JiuguanSocksRequest");
    private final ThreadPoolExecutor responses = pool("JiuguanSocksResponse");
    private ServerSocket listener;
    private boolean closed;
    private boolean started;

    public SocksProxyServer(InetAddress bindAddress, int port, String username,
                            String password, Connector connector) {
        if (bindAddress == null || bindAddress.isAnyLocalAddress() || connector == null
                || port < 0 || port > 65535 || (username == null) != (password == null))
            throw new IllegalArgumentException("Invalid relay configuration");
        if (username == null && !bindAddress.isLoopbackAddress())
            throw new IllegalArgumentException("Unauthenticated relay must bind loopback");
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.username = username == null ? null : credentialBytes(username);
        this.password = password == null ? null : credentialBytes(password);
        this.connector = connector;
    }

    private static byte[] credentialBytes(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 255)
            throw new IllegalArgumentException("Invalid relay credential length");
        return bytes;
    }

    private static ThreadPoolExecutor pool(String name) {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    public void start() throws IOException {
        synchronized (stateLock) {
            if (closed || started) throw new IOException("Relay cannot be started again");
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(bindAddress, requestedPort), MAX_CONNECTIONS);
            } catch (IOException e) {
                socket.close();
                throw e;
            }
            listener = socket;
            started = true;
            Thread acceptor = new Thread(this::acceptLoop, "JiuguanSocksAccept");
            acceptor.setDaemon(true);
            acceptor.start();
        }
    }

    public int port() {
        synchronized (stateLock) { return listener == null ? requestedPort : listener.getLocalPort(); }
    }
    public long uploadedBytes() { return uploaded.get(); }
    public long downloadedBytes() { return downloaded.get(); }
    public int activeConnections() { synchronized (stateLock) { return sessions.size(); } }

    private void acceptLoop() {
        while (true) {
            Socket socket;
            try { socket = listener.accept(); }
            catch (IOException e) { close(); return; }
            Session session = new Session(socket);
            synchronized (stateLock) {
                if (closed || sessions.size() >= MAX_CONNECTIONS) {
                    closeSocket(socket);
                    if (closed) return;
                    continue;
                }
                sessions.add(session);
            }
            try { requests.execute(() -> serve(session)); }
            catch (RuntimeException e) { session.close(); }
        }
    }

    private void serve(Session session) {
        try {
            Socket client = session.client;
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            if (!authenticate(input, output)) return;
            int version = readByte(input), command = readByte(input), reserved = readByte(input);
            int addressType = readByte(input);
            if (version != 5 || reserved != 0) { reply(output, 1); return; }
            if (command != 1) { reply(output, 7); return; }
            final String host;
            if (addressType == 1) host = InetAddress.getByAddress(readBytes(input, 4)).getHostAddress();
            else if (addressType == 4) host = InetAddress.getByAddress(readBytes(input, 16)).getHostAddress();
            else if (addressType == 3) {
                byte[] domain = readBytes(input, readByte(input));
                host = new String(domain, StandardCharsets.US_ASCII);
                if (domain.length == 0 || domain.length > 253 || !host.matches("[A-Za-z0-9._-]+")) {
                    reply(output, 8); return;
                }
            } else { reply(output, 8); return; }
            int port = (readByte(input) << 8) | readByte(input);
            if (port == 0) { reply(output, 1); return; }
            final Socket upstream;
            try {
                upstream = connector.connect(host, port);
                if (upstream == null || !upstream.isConnected() || upstream.isClosed()) {
                    closeSocket(upstream);
                    reply(output, 5); return;
                }
            } catch (IOException | RuntimeException e) { reply(output, 5); return; }
            if (!session.attach(upstream)) return;
            upstream.setSoTimeout(30_000);
            upstream.setTcpNoDelay(true);
            client.setSoTimeout(30_000);
            reply(output, 0);
            CountDownLatch responseFinished = new CountDownLatch(1);
            try {
                responses.execute(() -> {
                    try { pump(session, upstream, client, downloaded); }
                    catch (IOException e) { session.close(); }
                    finally { responseFinished.countDown(); }
                });
            } catch (RuntimeException e) { return; }
            try { pump(session, client, upstream, uploaded); }
            catch (IOException e) { session.close(); }
            // Upload EOF is a half-close: the peer can still return a complete response.
            try { responseFinished.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } catch (IOException | RuntimeException ignored) {
            // Never log credentials, destination URLs, or payloads.
        } finally { session.close(); }
    }

    private boolean authenticate(InputStream input, OutputStream output) throws IOException {
        if (readByte(input) != 5) return false;
        byte[] methods = readBytes(input, readByte(input));
        int selected = username == null ? 0 : 2;
        boolean supported = false;
        for (byte method : methods) if ((method & 255) == selected) supported = true;
        output.write(new byte[]{5, (byte) (supported ? selected : 255)});
        output.flush();
        if (!supported) return false;
        if (selected == 0) return true;
        if (readByte(input) != 1) return false;
        byte[] suppliedUsername = readBytes(input, readByte(input));
        byte[] suppliedPassword = readBytes(input, readByte(input));
        boolean accepted = MessageDigest.isEqual(username, suppliedUsername)
                & MessageDigest.isEqual(password, suppliedPassword);
        output.write(new byte[]{1, (byte) (accepted ? 0 : 1)});
        output.flush();
        return accepted;
    }

    private static void reply(OutputStream output, int result) throws IOException {
        // A wildcard BND.ADDR is permitted; it is not the externally exposed listening address.
        output.write(new byte[]{5, (byte) result, 0, 1, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
        return bytes;
    }

    private static void pump(Session session, Socket from, Socket to, AtomicLong count) throws IOException {
        InputStream input = from.getInputStream();
        OutputStream output = to.getOutputStream();
        byte[] buffer = new byte[16 * 1024];
        while (true) {
            final int size;
            try { size = input.read(buffer); }
            catch (SocketTimeoutException e) {
                // SSE responses can keep flowing while the request side sends nothing for hours.
                if (System.nanoTime() - session.lastActivity < TimeUnit.MILLISECONDS.toNanos(STREAM_IDLE_TIMEOUT_MS))
                    continue;
                throw e;
            }
            if (size == -1) break;
            output.write(buffer, 0, size);
            count.addAndGet(size);
            session.lastActivity = System.nanoTime();
        }
        output.flush();
        if (!to.isClosed() && !to.isOutputShutdown()) to.shutdownOutput();
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private final class Session implements Closeable {
        final Socket client;
        private Socket upstream;
        private boolean stopped;
        volatile long lastActivity = System.nanoTime();
        Session(Socket client) { this.client = client; }
        synchronized boolean attach(Socket socket) {
            if (stopped) { closeSocket(socket); return false; }
            upstream = socket;
            return true;
        }
        @Override public void close() {
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                closeSocket(client);
                closeSocket(upstream);
            }
            synchronized (stateLock) { sessions.remove(this); }
        }
    }

    @Override public void close() {
        Session[] current;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            if (listener != null) try { listener.close(); } catch (IOException ignored) { }
            current = sessions.toArray(new Session[0]);
        }
        for (Session session : current) session.close();
        requests.shutdownNow();
        responses.shutdownNow();
    }
}
