package com.jiuguan.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlPolicyTest {
    @Test public void localAddressWithoutSchemeIsAccepted() {
        assertEquals("http://192.168.1.100:8000", UrlPolicy.normalize(" 192.168.1.100:8000 "));
    }
    @Test public void reverseProxyPathsAndIpv6ArePreserved() {
        assertEquals("https://example.com/tavern/?a=1#chat", UrlPolicy.normalize("https://example.com/tavern/?a=1#chat"));
        assertEquals("http://[fd00::1]:8003", UrlPolicy.normalize("http://[fd00::1]:8003"));
    }
    @Test public void originChecksSchemeHostAndEffectivePort() {
        assertTrue(UrlPolicy.sameOrigin("https://EXAMPLE.com", "https://example.com:443/path"));
        assertTrue(UrlPolicy.sameOrigin("http://example.com", "http://example.com:80/path"));
        assertFalse(UrlPolicy.sameOrigin("http://example.com:8003", "http://example.com:8004"));
        assertFalse(UrlPolicy.sameOrigin("https://example.com", "http://example.com"));
        assertFalse(UrlPolicy.sameOrigin("https://example.com", "https://example.com.evil.test"));
        assertFalse(UrlPolicy.sameOrigin("https://example.com", "https://user:pass@example.com"));
    }
    @Test public void invalidAndExecutableAddressesAreRejected() {
        String[] bad = {null, "", " ", "https://", "file:///etc/passwd", "javascript:alert(1)",
                "intent://example.com", "ftp://example.com", "http://user:secret@example.com", "http://example.com:0",
                "http://example.com:65536", "http://example.com:-2", "http://a b", "http://a\n.test"};
        for (String input : bad) {
            try { UrlPolicy.normalize(input); fail("Accepted invalid URL: " + input); }
            catch (IllegalArgumentException expected) { /* expected */ }
        }
    }
}
