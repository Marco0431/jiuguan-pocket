package com.jiuguan.client;

import java.net.InetAddress;

/** Applies to each resolved destination before connecting, including IPv4 embedded in IPv6. */
public final class RelayAddressPolicy {
    private RelayAddressPolicy() { }

    public static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return publicIpv4(bytes, 0);
        if (bytes.length != 16) return false;
        int first = bytes[0] & 255, second = bytes[1] & 255;
        if ((first & 0xfe) == 0xfc) return false; // Unique-local IPv6.
        boolean leadingZero = true;
        for (int i = 0; i < 10; i++) leadingZero &= bytes[i] == 0;
        if (leadingZero && ((bytes[10] == 0 && bytes[11] == 0)
                || ((bytes[10] & 255) == 255 && (bytes[11] & 255) == 255)))
            return publicIpv4(bytes, 12);
        // Standard NAT64 remains usable on IPv6-only mobile networks.
        if (first == 0x00 && second == 0x64 && (bytes[2] & 255) == 0xff && (bytes[3] & 255) == 0x9b) {
            boolean standardPrefix = true;
            for (int i = 4; i < 12; i++) standardPrefix &= bytes[i] == 0;
            if (standardPrefix) return publicIpv4(bytes, 12);
        }
        if (first == 0x20 && second == 0x02) return publicIpv4(bytes, 2); // 6to4.
        if (first == 0x20 && second == 0x01 && (bytes[2] & 255) == 0x0d
                && (bytes[3] & 255) == 0xb8) return false; // Documentation prefix.
        if (first == 0x01 && second == 0) {
            boolean discard = true;
            for (int i = 2; i < 8; i++) discard &= bytes[i] == 0;
            if (discard) return false; // Discard-only 100::/64.
        }
        return true;
    }

    private static boolean publicIpv4(byte[] bytes, int offset) {
        int a = bytes[offset] & 255, b = bytes[offset + 1] & 255, c = bytes[offset + 2] & 255;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)))) return false;
        if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false;
        return !(a == 203 && b == 0 && c == 113);
    }
}
