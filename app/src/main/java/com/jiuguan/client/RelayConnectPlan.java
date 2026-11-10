package com.jiuguan.client;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A bounded, alternating-family TCP plan. DNS is supplied by the selected Android Network. */
final class RelayConnectPlan {
    static final int MAX_CANDIDATES = 4;
    static final int ATTEMPT_TIMEOUT_MS = 3_000;
    static final int TOTAL_BUDGET_MS = 12_000;

    interface Clock { long millis(); }
    interface ActiveCheck { void check() throws IOException; }
    interface Connector { Socket connect(InetAddress address, int timeoutMs) throws IOException; }

    static final class AddressRejectedException extends IOException {
        AddressRejectedException() { super("No permitted public destination"); }
    }
    static final class NetworkChangedException extends IOException {
        NetworkChangedException() { super("Relay network changed"); }
    }

    private final Clock clock;
    private final long startedAt;

    /** Construct before DNS: DNS elapsed time consumes the budget, although DNS cannot be interrupted. */
    RelayConnectPlan(Clock clock) {
        this.clock = clock;
        startedAt = clock.millis();
    }

    static List<InetAddress> candidates(InetAddress[] resolved) {
        return candidates(resolved, false);
    }

    static List<InetAddress> candidates(InetAddress[] resolved, boolean allowVpnFakeIp) {
        List<InetAddress> firstFamily = new ArrayList<>();
        List<InetAddress> secondFamily = new ArrayList<>();
        Set<InetAddress> unique = new LinkedHashSet<>();
        boolean firstIsIpv4 = false;
        for (InetAddress address : resolved) {
            if (!RelayAddressPolicy.isAllowedDestination(address, allowVpnFakeIp) || !unique.add(address)) continue;
            boolean ipv4 = address instanceof Inet4Address;
            if (unique.size() == 1) firstIsIpv4 = ipv4;
            (ipv4 == firstIsIpv4 ? firstFamily : secondFamily).add(address);
        }
        List<InetAddress> result = new ArrayList<>();
        for (int i = 0; result.size() < MAX_CANDIDATES
                && (i < firstFamily.size() || i < secondFamily.size()); i++) {
            if (i < firstFamily.size()) result.add(firstFamily.get(i));
            if (result.size() < MAX_CANDIDATES && i < secondFamily.size()) result.add(secondFamily.get(i));
        }
        return result;
    }

    Socket connect(InetAddress[] resolved, ActiveCheck active, Connector connector) throws IOException {
        return connect(resolved, active, connector, false);
    }

    Socket connect(InetAddress[] resolved, ActiveCheck active, Connector connector, boolean allowVpnFakeIp) throws IOException {
        List<InetAddress> candidates = candidates(resolved, allowVpnFakeIp);
        active.check();
        if (candidates.isEmpty()) throw new AddressRejectedException();
        IOException lastError = new IOException("No destination could be reached");
        for (InetAddress address : candidates) {
            active.check();
            long remaining = TOTAL_BUDGET_MS - Math.max(0, clock.millis() - startedAt);
            if (remaining <= 0) throw budgetExpired();
            int timeout = (int) Math.min(ATTEMPT_TIMEOUT_MS, remaining);
            Socket socket = null;
            boolean handedOver = false;
            try {
                socket = connector.connect(address, timeout);
                active.check();
                if (clock.millis() - startedAt >= TOTAL_BUDGET_MS) throw budgetExpired();
                handedOver = true;
                return socket;
            } catch (IOException e) {
                lastError = e;
            } finally {
                if (!handedOver && socket != null) try { socket.close(); } catch (IOException ignored) { }
            }
            // Cancellation must not be converted into another attempt after a failed dial.
            active.check();
        }
        throw lastError;
    }

    private static SocketTimeoutException budgetExpired() {
        return new SocketTimeoutException("Relay connection budget exceeded");
    }
}
