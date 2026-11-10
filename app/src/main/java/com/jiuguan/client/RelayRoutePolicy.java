package com.jiuguan.client;

import java.util.Objects;

/** Tracks the app's default network without ever selecting an unrelated app's VPN. */
final class RelayRoutePolicy<T> {
    enum Kind { WAIT_CELLULAR, WAIT_DEFAULT, WAIT_VPN, CELLULAR, VPN }
    static final class Selection<T> {
        final Kind kind;
        final T network;
        Selection(Kind kind, T network) { this.kind = kind; this.network = network; }
    }

    private T currentDefault;
    private boolean known = true, vpn, internet, blocked;

    synchronized void seed(T network, boolean capabilitiesKnown, boolean isVpn, boolean hasInternet) {
        currentDefault = network;
        known = network == null || capabilitiesKnown;
        vpn = network != null && isVpn;
        internet = hasInternet;
        blocked = false;
    }

    synchronized boolean available(T network) {
        if (Objects.equals(currentDefault, network)) return false;
        currentDefault = network;
        known = false;
        vpn = false;
        internet = false;
        blocked = false;
        return true;
    }

    synchronized boolean capabilities(T network, boolean isVpn, boolean hasInternet) {
        if (!Objects.equals(currentDefault, network)) return false;
        boolean changed = !known || vpn != isVpn || internet != hasInternet;
        known = true;
        vpn = isVpn;
        internet = hasInternet;
        return changed;
    }

    synchronized boolean blocked(T network, boolean value) {
        if (!Objects.equals(currentDefault, network) || blocked == value) return false;
        blocked = value;
        return vpn;
    }

    synchronized boolean lost(T network) {
        if (!Objects.equals(currentDefault, network)) return false;
        seed(null, true, false, false);
        return true;
    }

    synchronized boolean isCurrentDefault(T network) { return Objects.equals(currentDefault, network); }

    synchronized Selection<T> select(T cellular) {
        if (cellular == null) return new Selection<>(Kind.WAIT_CELLULAR, null);
        if (!known) return new Selection<>(Kind.WAIT_DEFAULT, null);
        if (vpn) return new Selection<>(internet && !blocked ? Kind.VPN : Kind.WAIT_VPN,
                internet && !blocked ? currentDefault : null);
        return new Selection<>(Kind.CELLULAR, cellular);
    }
}
