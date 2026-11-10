package com.jiuguan.client;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class RelayConnectPlanTest {
    @Test public void filtersBeforeLimitDeduplicatesAndAlternatesFamilies() throws Exception {
        InetAddress v6a = ipv6(1), v6b = ipv6(2), v6c = ipv6(3);
        InetAddress v4a = ipv4(1), v4b = ipv4(2), v4c = ipv4(3);
        InetAddress blocked = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        assertEquals(Arrays.asList(v6a, v4a, v6b, v4b), RelayConnectPlan.candidates(new InetAddress[]{
                blocked, blocked, blocked, blocked, v6a, v6a, v6b, v6c, v4a, v4b, v4c}));
        assertEquals(Arrays.asList(v4a, v6a, v4b, v6b), RelayConnectPlan.candidates(new InetAddress[]{
                v4a, v4b, v6a, v6b}));
    }

    @Test public void unreachableFirstIpv6FallsBackToIpv4AfterThreeSeconds() throws Exception {
        FakeClock clock = new FakeClock();
        RelayConnectPlan plan = new RelayConnectPlan(clock);
        InetAddress first = ipv6(1), second = ipv4(1);
        List<InetAddress> attempted = new ArrayList<>();
        try (Socket success = new Socket()) {
            Socket result = plan.connect(new InetAddress[]{first, ipv6(2), second}, () -> { }, (address, timeout) -> {
                attempted.add(address);
                assertEquals(3_000, timeout);
                if (address.equals(first)) {
                    clock.now += timeout;
                    throw new SocketTimeoutException();
                }
                return success;
            });
            assertSame(success, result);
            assertFalse(success.isClosed());
            assertEquals(Arrays.asList(first, second), attempted);
            assertEquals(3_000, clock.now);
        }
    }

    @Test public void fourTimeoutsUseTwelveSecondsInsteadOfPcTwentySecondBudget() throws Exception {
        FakeClock clock = new FakeClock();
        RelayConnectPlan plan = new RelayConnectPlan(clock);
        List<Integer> timeouts = new ArrayList<>();
        try {
            plan.connect(new InetAddress[]{ipv6(1), ipv6(2), ipv6(3), ipv4(1), ipv4(2)}, () -> { }, (address, timeout) -> {
                timeouts.add(timeout);
                clock.now += timeout;
                throw new SocketTimeoutException();
            });
            fail("Unreachable destinations succeeded");
        } catch (SocketTimeoutException expected) { }
        assertEquals(Arrays.asList(3_000, 3_000, 3_000, 3_000), timeouts);
        assertEquals(12_000, clock.now);
        assertTrue(clock.now < 20_000);
    }

    @Test public void dnsAndOtherElapsedTimeReduceRemainingTcpBudget() throws Exception {
        FakeClock clock = new FakeClock();
        RelayConnectPlan plan = new RelayConnectPlan(clock);
        clock.now = 10_500; // Simulated network-specific DNS, with no wall-clock sleep or DNS access.
        List<Integer> timeouts = new ArrayList<>();
        try {
            plan.connect(new InetAddress[]{ipv6(1), ipv4(1)}, () -> { }, (address, timeout) -> {
                timeouts.add(timeout);
                clock.now += timeout;
                throw new SocketTimeoutException();
            });
            fail("Expired budget attempted another address");
        } catch (SocketTimeoutException expected) { }
        assertEquals(Arrays.asList(1_500), timeouts);
        assertEquals(12_000, clock.now);
    }

    @Test public void dnsReturningAfterBudgetDoesNotOpenAnySocket() throws Exception {
        FakeClock clock = new FakeClock();
        RelayConnectPlan plan = new RelayConnectPlan(clock);
        clock.now = 25_000;
        try {
            plan.connect(new InetAddress[]{ipv4(1)}, () -> { }, (address, timeout) -> {
                fail("Late DNS must not start an already expired connection"); return null;
            });
            fail("Late DNS succeeded");
        } catch (SocketTimeoutException expected) { }
    }

    @Test public void changedNetworkClosesLateSocketAndDoesNotTryAnotherAddress() throws Exception {
        FakeClock clock = new FakeClock();
        RelayConnectPlan plan = new RelayConnectPlan(clock);
        boolean[] active = {true};
        int[] calls = {0};
        Socket late = new Socket();
        try {
            plan.connect(new InetAddress[]{ipv6(1), ipv4(1)}, () -> {
                if (!active[0]) throw new RelayConnectPlan.NetworkChangedException();
            }, (address, timeout) -> {
                calls[0]++;
                active[0] = false;
                return late;
            });
            fail("Socket from old network escaped cancellation");
        } catch (RelayConnectPlan.NetworkChangedException expected) { }
        assertTrue(late.isClosed());
        assertEquals(1, calls[0]);
    }

    @Test public void policyAndPermissionFailuresDoNotReachAnotherDestination() throws Exception {
        RelayConnectPlan plan = new RelayConnectPlan(new FakeClock());
        try {
            plan.connect(new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 1})},
                    () -> { }, (address, timeout) -> { fail("Private address reached connector"); return null; });
            fail("Private-only result accepted");
        } catch (RelayConnectPlan.AddressRejectedException expected) { }
        int[] calls = {0};
        try {
            plan.connect(new InetAddress[]{ipv6(1), ipv4(1)}, () -> { }, (address, timeout) -> {
                calls[0]++;
                throw new SecurityException("Blocked by system policy");
            });
            fail("Permission failure succeeded");
        } catch (SecurityException expected) { }
        assertEquals(1, calls[0]);
    }

    @Test public void vpnFakeIpCanConnectButDefaultPlanAndPrivateAddressesRemainBlocked() throws Exception {
        InetAddress fake = InetAddress.getByAddress(new byte[]{(byte) 198, 18, 0, 1});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        RelayConnectPlan plan = new RelayConnectPlan(new FakeClock());
        try {
            plan.connect(new InetAddress[]{fake}, () -> { }, (address, timeout) -> {
                fail("Cellular mode must not dial fake IP"); return null;
            });
            fail("Default plan accepted fake IP");
        } catch (RelayConnectPlan.AddressRejectedException expected) { }
        boolean allowFakeIp = RelayAddressPolicy.allowsVpnFakeIpHost("oauth2.googleapis.com");
        try (Socket success = new Socket()) {
            assertSame(success, plan.connect(new InetAddress[]{privateAddress, fake, fake}, () -> { }, (address, timeout) -> {
                assertEquals(fake, address);
                assertEquals(3_000, timeout);
                return success;
            }, allowFakeIp));
        }
        assertEquals(Arrays.asList(fake), RelayConnectPlan.candidates(new InetAddress[]{privateAddress, fake, fake}, true));
    }

    @Test public void vpnFakeIpStillUsesRemainingBudgetAndRejectsAnExpiredResolution() throws Exception {
        InetAddress fake = InetAddress.getByAddress(new byte[]{(byte) 198, 19, 0, 2});
        FakeClock clock = new FakeClock();
        RelayConnectPlan plan = new RelayConnectPlan(clock);
        clock.now = 11_500;
        try {
            plan.connect(new InetAddress[]{fake}, () -> { }, (address, timeout) -> {
                assertEquals(500, timeout);
                clock.now += timeout;
                throw new SocketTimeoutException();
            }, true);
            fail("Timed-out VPN destination succeeded");
        } catch (SocketTimeoutException expected) { }
        try {
            plan.connect(new InetAddress[]{fake}, () -> { }, (address, timeout) -> {
                fail("VPN must not bypass expired DNS/connection budget"); return null;
            }, true);
            fail("Expired VPN plan succeeded");
        } catch (SocketTimeoutException expected) { }
    }

    private static InetAddress ipv4(int suffix) throws IOException {
        return InetAddress.getByAddress(new byte[]{8, 8, 8, (byte) suffix});
    }
    private static InetAddress ipv6(int suffix) throws IOException {
        byte[] bytes = new byte[16];
        bytes[0] = 0x26; bytes[1] = 0x06; bytes[2] = 0x47; bytes[3] = 0x00;
        bytes[15] = (byte) suffix;
        return InetAddress.getByAddress(bytes);
    }
    private static final class FakeClock implements RelayConnectPlan.Clock {
        long now;
        @Override public long millis() { return now; }
    }
}
