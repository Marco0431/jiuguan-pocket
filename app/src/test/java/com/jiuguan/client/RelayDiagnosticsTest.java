package com.jiuguan.client;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.*;

public class RelayDiagnosticsTest {
    @Test public void lanSuccessDoesNotImplyComputerInternetSuccess() throws Exception {
        RelayDiagnostics diagnostics = new RelayDiagnostics();
        diagnostics.networkChanged(1);
        RelayDiagnostics.Attempt lan = diagnostics.begin(1, RelayDiagnostics.Route.LAN_PAGE);
        diagnostics.connected(lan, InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 2}));
        RelayDiagnostics.Attempt computer = diagnostics.begin(1, RelayDiagnostics.Route.COMPUTER_PUBLIC);
        diagnostics.failed(computer, new UnknownHostException("api.invalid"));
        RelayDiagnostics.Attempt page = diagnostics.begin(1, RelayDiagnostics.Route.PAGE_PUBLIC);
        InetAddress publicAddress = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        diagnostics.connected(page, publicAddress);
        diagnostics.connected(page, publicAddress); // Completion notifications must not double-count.
        String text = diagnostics.summary();
        assertTrue(text.contains("局域网页面：1 / 1"));
        assertTrue(text.contains("电脑外网：1 / 0"));
        assertTrue(text.contains("页面外网：1 / 1"));
        assertTrue(text.contains("最近一次外网失败：电脑外网 · DNS 解析失败"));
    }

    @Test public void diagnosticsKeepOnlyCategoriesAndAddressFamilies() throws Exception {
        RelayDiagnostics diagnostics = new RelayDiagnostics();
        diagnostics.networkChanged(7);
        RelayDiagnostics.Attempt attempt = diagnostics.begin(7, RelayDiagnostics.Route.COMPUTER_PUBLIC);
        byte[] v6 = new byte[16]; v6[0] = 0x26; v6[1] = 0x06; v6[15] = 1;
        InetAddress address = InetAddress.getByAddress("private-host.example", v6);
        diagnostics.resolved(attempt, new InetAddress[]{address,
                InetAddress.getByAddress(new byte[]{8, 8, 8, 8}),
                InetAddress.getByAddress(new byte[]{10, 0, 0, 1})});
        diagnostics.connecting(attempt, address);
        diagnostics.failed(attempt, new SocketException("EACCES https://private-host.example/path?password=SECRET request-body"));
        String text = diagnostics.summary();
        assertTrue(text.contains("IPv4 2，IPv6 1；策略过滤 1"));
        assertTrue(text.contains("权限或系统联网限制"));
        assertTrue(text.contains("（IPv6）"));
        for (String secret : new String[]{"private-host", "SECRET", "request-body", "https://", "EACCES"})
            assertFalse(secret, text.contains(secret));
    }

    @Test public void oldNetworkCallbacksCannotOverwriteNewNetworkResult() throws Exception {
        RelayDiagnostics diagnostics = new RelayDiagnostics();
        diagnostics.networkChanged(1);
        RelayDiagnostics.Attempt old = diagnostics.begin(1, RelayDiagnostics.Route.COMPUTER_PUBLIC);
        diagnostics.networkChanged(2);
        RelayDiagnostics.Attempt current = diagnostics.begin(2, RelayDiagnostics.Route.COMPUTER_PUBLIC);
        InetAddress address = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        diagnostics.connected(current, address);
        diagnostics.failed(old, new SocketTimeoutException("stale failure"));
        diagnostics.connected(old, address);
        String text = diagnostics.summary();
        assertTrue(text.contains("电脑外网：2 / 1"));
        assertTrue(text.contains("TCP 已连通（IPv4）"));
        assertTrue(text.contains("最近一次外网失败：尚无记录"));
        assertFalse(text.contains("超时"));
    }

    @Test public void failureCategoriesAreActionableWithoutRawExceptions() {
        assertEquals(RelayDiagnostics.Stage.DNS_FAILED,
                RelayDiagnostics.failureStage(new UnknownHostException("eacces.example")));
        assertEquals(RelayDiagnostics.Stage.TCP_TIMEOUT,
                RelayDiagnostics.failureStage(new SocketTimeoutException("private data")));
        assertEquals(RelayDiagnostics.Stage.PERMISSION,
                RelayDiagnostics.failureStage(new IOException(new SecurityException("private data"))));
        assertEquals(RelayDiagnostics.Stage.POLICY_REJECTED,
                RelayDiagnostics.failureStage(new RelayConnectPlan.AddressRejectedException()));
        assertEquals(RelayDiagnostics.Stage.NETWORK_CHANGED,
                RelayDiagnostics.failureStage(new RelayConnectPlan.NetworkChangedException()));
        assertEquals(RelayDiagnostics.Stage.TCP_FAILED,
                RelayDiagnostics.failureStage(new IOException("connection refused")));
    }
}
