package com.jiuguan.client;

import java.net.URI;

/** One automatic connection per launch, using only a previously successful configuration. */
public final class StartupPolicy {
    private boolean considered;

    public StartupPolicy(boolean consumed) { considered = consumed; }

    public boolean considered() { return considered; }

    /** Manual navigation/settings and restored attempts must never schedule another auto-open. */
    public void consume() { considered = true; }

    public boolean claim(boolean foreground, boolean serviceReady, boolean stayInSettings,
                         boolean busyOrLoaded, boolean hasMatchingSuccess) {
        if (considered || !foreground || !serviceReady) return false;
        // Consume even when no configuration is eligible. A later resume is not a new launch.
        considered = true;
        return !stayInSettings && !busyOrLoaded && hasMatchingSuccess;
    }

    public static boolean hasMatchingSuccess(String address, boolean mobile, boolean background,
                                             String successfulAddress, boolean successfulMobile,
                                             boolean successfulBackground) {
        if (mobile != successfulMobile || background != successfulBackground) return false;
        try {
            return canonical(address).equals(canonical(successfulAddress));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String canonical(String address) {
        URI uri = URI.create(UrlPolicy.normalize(address));
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return AuthScope.origin(address) + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    }
}
