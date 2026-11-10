package com.jiuguan.client;

import org.junit.Test;

import static org.junit.Assert.*;

public class RelayRoutePolicyTest {
    @Test public void physicalWifiDefaultStillUsesExplicitCellular() {
        RelayRoutePolicy<String> routes = new RelayRoutePolicy<>();
        routes.seed("wifi", true, false, true);
        assertEquals(RelayRoutePolicy.Kind.CELLULAR, routes.select("cell").kind);
        assertEquals("cell", routes.select("cell").network);
    }

    @Test public void newlyAnnouncedVpnWaitsForCapabilitiesInsteadOfLeakingDirect() {
        RelayRoutePolicy<String> routes = new RelayRoutePolicy<>();
        routes.seed("wifi", true, false, true);
        assertTrue(routes.available("vpn"));
        assertEquals(RelayRoutePolicy.Kind.WAIT_DEFAULT, routes.select("cell").kind);
        assertNull(routes.select("cell").network);
        assertTrue(routes.capabilities("vpn", true, true));
        assertEquals(RelayRoutePolicy.Kind.VPN, routes.select("cell").kind);
        assertEquals("vpn", routes.select("cell").network);
    }

    @Test public void lateCallbacksFromOldDefaultCannotReplaceCurrentVpn() {
        RelayRoutePolicy<String> routes = new RelayRoutePolicy<>();
        routes.seed("old-vpn", true, true, true);
        routes.available("new-vpn");
        routes.capabilities("new-vpn", true, true);
        assertFalse(routes.lost("old-vpn"));
        assertFalse(routes.capabilities("old-vpn", false, true));
        assertFalse(routes.blocked("old-vpn", true));
        assertEquals("new-vpn", routes.select("cell").network);
    }

    @Test public void blockedOrUnusableCurrentVpnDoesNotFallBackToCellular() {
        RelayRoutePolicy<String> routes = new RelayRoutePolicy<>();
        routes.seed("vpn", true, true, true);
        routes.blocked("vpn", true);
        assertEquals(RelayRoutePolicy.Kind.WAIT_VPN, routes.select("cell").kind);
        assertNull(routes.select("cell").network);
        routes.blocked("vpn", false);
        assertEquals(RelayRoutePolicy.Kind.VPN, routes.select("cell").kind);
        routes.capabilities("vpn", true, false);
        assertEquals(RelayRoutePolicy.Kind.WAIT_VPN, routes.select("cell").kind);
    }

    @Test public void vpnDisconnectChangesOnlyNewSelectionAndOldEventsStayIgnored() {
        RelayRoutePolicy<String> routes = new RelayRoutePolicy<>();
        routes.seed("vpn", true, true, true);
        RelayRoutePolicy.Selection<String> prior = routes.select("cell");
        assertTrue(routes.lost("vpn"));
        assertEquals("cell", routes.select("cell").network);
        assertEquals("vpn", prior.network); // A session snapshot stays tied to its old network.
        assertFalse(routes.capabilities("vpn", true, true));
        assertEquals(RelayRoutePolicy.Kind.CELLULAR, routes.select("cell").kind);
    }

    @Test public void cellularRetentionRemainsRequiredEvenWithVpnAndRestoresSelection() {
        RelayRoutePolicy<String> routes = new RelayRoutePolicy<>();
        routes.seed("vpn", true, true, true);
        assertEquals(RelayRoutePolicy.Kind.WAIT_CELLULAR, routes.select(null).kind);
        assertEquals("vpn", routes.select("new-cell").network);
        routes.seed(null, true, false, false);
        assertEquals("new-cell", routes.select("new-cell").network);
    }
}
