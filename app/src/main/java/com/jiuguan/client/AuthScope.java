package com.jiuguan.client;

import java.net.URI;
import java.util.Locale;

/** Scheme, host, effective port and HTTP realm form a credential's protection space. */
public final class AuthScope {
    private AuthScope() { }

    public static String origin(String address) {
        URI uri = URI.create(UrlPolicy.normalize(address));
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = bareHost(uri.getHost());
        if (host.contains(":")) host = "[" + host + "]";
        int port = uri.getPort() >= 0 ? uri.getPort() : (scheme.equals("https") ? 443 : 80);
        return scheme + "://" + host + ":" + port;
    }

    public static String key(String address, String challengeHost, String realm) {
        URI uri = URI.create(UrlPolicy.normalize(address));
        if (challengeHost == null || !bareHost(uri.getHost()).equals(bareHost(challengeHost)))
            throw new IllegalArgumentException("认证主机与酒馆地址不一致");
        String value = realm == null ? "" : realm;
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.length() > 1024)
            throw new IllegalArgumentException("认证区域无效");
        return origin(address) + "\n" + value;
    }

    private static String bareHost(String host) {
        String result = host.toLowerCase(Locale.ROOT);
        if (result.startsWith("[") && result.endsWith("]")) result = result.substring(1, result.length() - 1);
        return result;
    }
}
