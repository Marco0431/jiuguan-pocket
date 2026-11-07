package com.jiuguan.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** No Android dependencies so address validation can be tested on the host JVM. */
public final class UrlPolicy {
    private UrlPolicy() { }

    public static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("请填写酒馆地址");
        if (!value.contains("://")) value = "http://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1) {
                throw new IllegalArgumentException("请输入 http:// 或 https:// 地址，账号密码请在登录框填写");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("地址格式不正确，例如 http://192.168.1.100:8000");
        }
    }

    public static boolean sameOrigin(String first, String second) {
        try {
            URI a = new URI(normalize(first));
            URI b = new URI(normalize(second));
            return a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
        } catch (IllegalArgumentException | URISyntaxException e) {
            return false;
        }
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
    }
}
