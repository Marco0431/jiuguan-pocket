package com.jiuguan.client;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class RelayAddressPolicyTest {
    @Test public void fakeIpRangeNeedsVpnOptInWithoutAllowingOtherPrivateDestinations() throws Exception {
        for (String address : new String[]{"198.18.0.0", "198.18.0.1", "198.19.255.255"}) {
            InetAddress ip = InetAddress.getByName(address);
            assertFalse(address, RelayAddressPolicy.isAllowedDestination(ip, false));
            assertTrue(address, RelayAddressPolicy.isAllowedDestination(ip, true));
        }
        for (String address : new String[]{"10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
                "192.168.1.1", "100.64.0.1", "198.51.100.1", "::1", "fc00::1", "fdab::1",
                "fe80::1", "64:ff9b::c612:1"})
            assertFalse(address, RelayAddressPolicy.isAllowedDestination(InetAddress.getByName(address), true));
        assertFalse(RelayAddressPolicy.isAllowedDestination(null, true));
        assertTrue(RelayAddressPolicy.isAllowedDestination(InetAddress.getByName("8.8.8.8"), false));
    }

    @Test public void fakeIpOptInRequiresPublicDomainSyntaxRatherThanAnIpOrLocalName() {
        for (String host : new String[]{"oauth2.googleapis.com", "API.Example.COM.", "api.xn--fiqs8s"})
            assertTrue(host, RelayAddressPolicy.allowsVpnFakeIpHost(host));
        for (String host : new String[]{null, "", "localhost", "router", "router.lan", "printer.local",
                "api.localhost", "api.internal", "api.intranet", "device.home", "device.home.arpa",
                "198.18.0.1", "127.1", "2130706433", "0177.0.0.1", "0x7f000001", "1.0x7f000001",
                "[::1]", "fc00::1", "https://api.example.com", "user@api.example.com", "api.example.com:443",
                "api..example.com", "-api.example.com", "api-.example.com", " api.example.com", "api.example.com "})
            assertFalse(String.valueOf(host), RelayAddressPolicy.allowsVpnFakeIpHost(host));
    }

    @Test public void rejectsPrivateLocalAndSpecialIpv4() throws Exception {
        for (String address : new String[]{"0.0.0.0", "0.1.2.3", "10.0.0.1", "127.0.0.1",
                "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "100.64.0.1", "100.127.255.255", "198.18.0.1", "198.19.1.1",
                "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255"})
            assertFalse(address, RelayAddressPolicy.isPublicAddress(InetAddress.getByName(address)));
    }

    @Test public void acceptsPublicIpv4AndIpv6IncludingCellularNat64() throws Exception {
        for (String address : new String[]{"1.1.1.1", "8.8.8.8", "172.32.0.1", "100.128.0.1",
                "2606:4700:4700::1111", "2001:4860:4860::8888", "64:ff9b::808:808",
                "::ffff:8.8.8.8"})
            assertTrue(address, RelayAddressPolicy.isPublicAddress(InetAddress.getByName(address)));
    }

    @Test public void rejectsPrivateAndEmbeddedPrivateIpv6() throws Exception {
        for (String address : new String[]{"::", "::1", "fe80::1", "fec0::1", "fc00::1", "fdab::1",
                "ff02::1", "::ffff:192.168.1.1", "::10.0.0.1", "64:ff9b::a00:1",
                "2002:c0a8:101::1", "2001:db8::1", "100::1"})
            assertFalse(address, RelayAddressPolicy.isPublicAddress(InetAddress.getByName(address)));
        assertFalse(RelayAddressPolicy.isPublicAddress(null));
    }
}
