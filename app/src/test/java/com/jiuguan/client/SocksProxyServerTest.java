package com.jiuguan.client;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SocksProxyServerTest {
    private static final InetAddress LOOPBACK = numeric("127.0.0.1");

    @Test public void authenticatedListenerRejectsNoAuthenticationAndWrongPassword() throws Exception {
        AtomicInteger called = new AtomicInteger();
        try (SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, "user", "secret", (h, p) -> {
            called.incrementAndGet(); throw new IOException();
        })) {
            proxy.start();
            try (Socket socket = connect(proxy)) {
                socket.getOutputStream().write(new byte[]{5, 1, 0});
                assertArrayEquals(new byte[]{5, (byte) 255}, read(socket, 2));
                assertEquals(-1, socket.getInputStream().read());
            }
            try (Socket socket = connect(proxy)) {
                socket.getOutputStream().write(new byte[]{5, 2, 0, 2});
                assertArrayEquals(new byte[]{5, 2}, read(socket, 2));
                authPayload(socket, "user", "wrong");
                assertArrayEquals(new byte[]{1, 1}, read(socket, 2));
                assertEquals(-1, socket.getInputStream().read());
            }
            assertEquals(0, called.get());
        }
    }

    @Test public void domainIsNotLocallyResolvedAndBinaryResponseSurvivesUploadHalfClose() throws Exception {
        AtomicReference<String> requestedHost = new AtomicReference<>();
        AtomicInteger requestedPort = new AtomicInteger();
        AtomicReference<Throwable> upstreamError = new AtomicReference<>();
        byte[] payload = new byte[100_003];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 71);
        byte[] trailer = new byte[]{0, (byte) 255, 7, 9};
        CountDownLatch upstreamDone = new CountDownLatch(1);
        try (ServerSocket target = new ServerSocket(0, 1, LOOPBACK);
             SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, "user", "secret", (host, port) -> {
                 requestedHost.set(host); requestedPort.set(port);
                 return new Socket(LOOPBACK, target.getLocalPort());
             })) {
            daemon(() -> {
                try (Socket peer = target.accept()) {
                    peer.setSoTimeout(3000);
                    byte[] received = peer.getInputStream().readAllBytes();
                    assertArrayEquals(payload, received);
                    // The response is intentionally emitted only after the full request EOF.
                    peer.getOutputStream().write(received);
                    peer.getOutputStream().flush();
                    peer.getOutputStream().write(trailer);
                } catch (Throwable t) { upstreamError.set(t); }
                finally { upstreamDone.countDown(); }
            });
            proxy.start();
            try (Socket socket = connect(proxy)) {
                authenticate(socket);
                domainRequest(socket, 1, "unresolvable.example.invalid", 443);
                assertEquals(0, read(socket, 10)[1]);
                socket.getOutputStream().write(payload);
                socket.shutdownOutput();
                byte[] response = socket.getInputStream().readAllBytes();
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                expected.write(payload); expected.write(trailer);
                assertArrayEquals(expected.toByteArray(), response);
            }
            assertTrue(upstreamDone.await(3, TimeUnit.SECONDS));
            assertNull(upstreamError.get());
            assertEquals("unresolvable.example.invalid", requestedHost.get());
            assertEquals(443, requestedPort.get());
            awaitConnections(proxy, 0);
            assertEquals(payload.length, proxy.uploadedBytes());
            assertEquals(payload.length + trailer.length, proxy.downloadedBytes());
        }
    }

    @Test public void upstreamHalfCloseDoesNotDiscardRemainingClientUpload() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (ServerSocket target = new ServerSocket(0, 1, LOOPBACK);
             SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, null, null,
                     (h, p) -> new Socket(LOOPBACK, target.getLocalPort()))) {
            daemon(() -> {
                try (Socket peer = target.accept()) {
                    peer.setSoTimeout(3000);
                    peer.getOutputStream().write(new byte[]{42});
                    peer.shutdownOutput();
                    received.set(new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (Throwable t) { failure.set(t); }
                finally { done.countDown(); }
            });
            proxy.start();
            try (Socket socket = connect(proxy)) {
                noAuthentication(socket);
                domainRequest(socket, 1, "example.invalid", 80);
                assertEquals(0, read(socket, 10)[1]);
                assertArrayEquals(new byte[]{42}, socket.getInputStream().readAllBytes());
                socket.getOutputStream().write("after-half-close".getBytes(StandardCharsets.UTF_8));
                socket.shutdownOutput();
                assertTrue(done.await(3, TimeUnit.SECONDS));
                assertNull(failure.get());
                assertEquals("after-half-close", received.get());
            }
        }
    }

    @Test public void unsupportedCommandAndBadDomainNeverReachConnector() throws Exception {
        AtomicInteger called = new AtomicInteger();
        try (SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, null, null, (h, p) -> {
            called.incrementAndGet(); throw new IOException();
        })) {
            proxy.start();
            try (Socket socket = connect(proxy)) {
                noAuthentication(socket);
                domainRequest(socket, 3, "example.invalid", 53); // UDP ASSOCIATE is unsupported.
                assertEquals(7, read(socket, 10)[1]);
            }
            try (Socket socket = connect(proxy)) {
                noAuthentication(socket);
                domainRequest(socket, 1, "bad\r\nhost", 443);
                assertEquals(8, read(socket, 10)[1]);
            }
            assertEquals(0, called.get());
        }
    }

    @Test public void numericAddressesReachConnectorWithoutDns() throws Exception {
        AtomicReference<String> host = new AtomicReference<>();
        try (SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, null, null, (h, p) -> {
            host.set(h); throw new IOException();
        })) {
            proxy.start();
            try (Socket socket = connect(proxy)) {
                noAuthentication(socket);
                socket.getOutputStream().write(new byte[]{5, 1, 0, 1, 8, 8, 4, 4, 1, (byte) 187});
                assertEquals(5, read(socket, 10)[1]);
                assertEquals("8.8.4.4", host.get());
            }
            try (Socket socket = connect(proxy)) {
                noAuthentication(socket);
                byte[] ipv6 = numeric("2606:4700:4700::1111").getAddress();
                socket.getOutputStream().write(new byte[]{5, 1, 0, 4});
                socket.getOutputStream().write(ipv6);
                socket.getOutputStream().write(new byte[]{1, (byte) 187});
                assertEquals(5, read(socket, 10)[1]);
                assertEquals(numeric("2606:4700:4700::1111"), numeric(host.get()));
            }
        }
    }

    @Test public void closingStopsActiveTunnelAndUnfinishedHandshake() throws Exception {
        CountDownLatch targetClosed = new CountDownLatch(1);
        try (ServerSocket target = new ServerSocket(0, 1, LOOPBACK);
             SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, null, null,
                     (h, p) -> new Socket(LOOPBACK, target.getLocalPort()))) {
            daemon(() -> {
                try (Socket peer = target.accept()) {
                    peer.setSoTimeout(3000);
                    if (peer.getInputStream().read() == -1) targetClosed.countDown();
                } catch (IOException ignored) { targetClosed.countDown(); }
            });
            proxy.start();
            try (Socket tunnel = connect(proxy); Socket handshake = connect(proxy)) {
                noAuthentication(tunnel);
                domainRequest(tunnel, 1, "example.invalid", 443);
                assertEquals(0, read(tunnel, 10)[1]);
                awaitConnections(proxy, 2);
                proxy.close();
                assertEquals(-1, tunnel.getInputStream().read());
                assertEquals(-1, handshake.getInputStream().read());
                assertEquals(0, proxy.activeConnections());
                assertTrue(targetClosed.await(3, TimeUnit.SECONDS));
                proxy.close(); // Idempotent.
                try { proxy.start(); fail("Closed server restarted"); } catch (IOException expected) { }
            }
        }
    }

    @Test public void boundedHandshakeConnectionsRejectOverflow() throws Exception {
        try (SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, null, null,
                (h, p) -> { throw new IOException(); })) {
            proxy.start();
            List<Socket> clients = new ArrayList<>();
            try {
                for (int i = 0; i < 16; i++) clients.add(connect(proxy));
                awaitConnections(proxy, 16);
                try (Socket overflow = connect(proxy)) {
                    assertEquals(-1, overflow.getInputStream().read());
                }
                assertEquals(16, proxy.activeConnections());
                proxy.close();
                assertEquals(0, proxy.activeConnections());
            } finally { for (Socket client : clients) client.close(); }
        }
    }

    @Test public void socketReturnedAfterStopIsClosedInsteadOfStartingATunnel() throws Exception {
        CountDownLatch connectorEntered = new CountDownLatch(1);
        CountDownLatch releaseConnector = new CountDownLatch(1);
        CountDownLatch returnedSocketClosed = new CountDownLatch(1);
        try (ServerSocket target = new ServerSocket(0, 1, LOOPBACK);
             SocksProxyServer proxy = new SocksProxyServer(LOOPBACK, 0, null, null, (h, p) -> {
                 Socket result = new Socket(LOOPBACK, target.getLocalPort());
                 connectorEntered.countDown();
                 // Simulate a platform connect/DNS completion arriving after cancellation.
                 boolean released = false;
                 while (!released) {
                     try { released = releaseConnector.await(3, TimeUnit.SECONDS); }
                     catch (InterruptedException ignored) { /* Completion still arrives. */ }
                 }
                 return result;
             })) {
            daemon(() -> {
                try (Socket peer = target.accept()) {
                    peer.setSoTimeout(3000);
                    if (peer.getInputStream().read() == -1) returnedSocketClosed.countDown();
                } catch (IOException ignored) { returnedSocketClosed.countDown(); }
            });
            proxy.start();
            try (Socket socket = connect(proxy)) {
                noAuthentication(socket);
                domainRequest(socket, 1, "example.invalid", 443);
                assertTrue(connectorEntered.await(3, TimeUnit.SECONDS));
                proxy.close();
                assertEquals(-1, socket.getInputStream().read());
                releaseConnector.countDown();
                assertTrue(returnedSocketClosed.await(3, TimeUnit.SECONDS));
                assertEquals(0, proxy.activeConnections());
            } finally { releaseConnector.countDown(); }
        }
    }

    @Test public void onlyExplicitLoopbackCanBeUnauthenticated() {
        try {
            new SocksProxyServer(numeric("192.168.1.2"), 10808, null, null, (h, p) -> null);
            fail("Unauthenticated LAN proxy accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            new SocksProxyServer(numeric("0.0.0.0"), 10808, "user", "secret", (h, p) -> null);
            fail("Wildcard bind accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void awaitConnections(SocksProxyServer proxy, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (proxy.activeConnections() != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, proxy.activeConnections());
    }
    private static InetAddress numeric(String ip) {
        try { return InetAddress.getByName(ip); } catch (IOException e) { throw new AssertionError(e); }
    }
    private static Socket connect(SocksProxyServer proxy) throws IOException {
        Socket socket = new Socket(LOOPBACK, proxy.port());
        socket.setSoTimeout(3000);
        return socket;
    }
    private static void authenticate(Socket socket) throws IOException {
        socket.getOutputStream().write(new byte[]{5, 1, 2});
        assertArrayEquals(new byte[]{5, 2}, read(socket, 2));
        authPayload(socket, "user", "secret");
        assertArrayEquals(new byte[]{1, 0}, read(socket, 2));
    }
    private static void authPayload(Socket socket, String user, String pass) throws IOException {
        byte[] username = user.getBytes(StandardCharsets.UTF_8), password = pass.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(new byte[]{1, (byte) username.length});
        socket.getOutputStream().write(username);
        socket.getOutputStream().write(password.length);
        socket.getOutputStream().write(password);
    }
    private static void noAuthentication(Socket socket) throws IOException {
        socket.getOutputStream().write(new byte[]{5, 1, 0});
        assertArrayEquals(new byte[]{5, 0}, read(socket, 2));
    }
    private static void domainRequest(Socket socket, int command, String host, int port) throws IOException {
        byte[] domain = host.getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write(new byte[]{5, (byte) command, 0, 3, (byte) domain.length});
        socket.getOutputStream().write(domain);
        socket.getOutputStream().write(new byte[]{(byte) (port >>> 8), (byte) port});
    }
    private static byte[] read(Socket socket, int count) throws IOException {
        InputStream input = socket.getInputStream();
        byte[] bytes = input.readNBytes(count);
        assertEquals(count, bytes.length);
        return bytes;
    }
    private static void daemon(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }
}
