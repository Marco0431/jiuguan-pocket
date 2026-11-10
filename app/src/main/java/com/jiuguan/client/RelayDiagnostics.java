package com.jiuguan.client;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Thread-safe metadata only: never retains a host, exception message, credential, URL or payload. */
final class RelayDiagnostics {
    enum Route { LAN_PAGE, COMPUTER_PUBLIC, PAGE_PUBLIC }
    enum Stage {
        DNS("正在解析 DNS"), DNS_FAILED("DNS 解析失败"),
        RESOLVED("DNS 已返回，准备连接"), POLICY_REJECTED("策略拒绝：没有允许的公网地址"),
        TCP_CONNECTING("正在连接 TCP"), TCP_TIMEOUT("TCP 连接超时或连接预算耗尽"),
        TCP_FAILED("TCP 连接失败"), PERMISSION("网络权限或系统联网限制阻止了连接"),
        CONNECTED("TCP 已连通"), NETWORK_CHANGED("网络已变化，本次连接已取消");
        final String text;
        Stage(String text) { this.text = text; }
    }

    static final class Attempt {
        final long generation;
        final Route route;
        Stage stage = Stage.DNS;
        int ipv4;
        int ipv6;
        int rejected;
        String family = "";
        boolean countedConnected;
        Attempt(long generation, Route route) { this.generation = generation; this.route = route; }
    }

    private long generation;
    private final long[] attempts = new long[Route.values().length];
    private final long[] connected = new long[Route.values().length];
    private Attempt latest;
    private String lastFailure = "尚无记录";

    synchronized void networkChanged(long generation) {
        this.generation = generation;
        if (latest != null) latest.stage = Stage.NETWORK_CHANGED;
    }

    synchronized Attempt begin(long expectedGeneration, Route route) {
        Attempt attempt = new Attempt(expectedGeneration, route);
        if (expectedGeneration != generation) return attempt;
        attempts[route.ordinal()]++;
        if (route != Route.LAN_PAGE) latest = attempt;
        return attempt;
    }

    synchronized void resolved(Attempt attempt, InetAddress[] addresses) {
        resolved(attempt, addresses, false);
    }

    synchronized void resolved(Attempt attempt, InetAddress[] addresses, boolean allowVpnFakeIp) {
        if (!current(attempt)) return;
        attempt.stage = Stage.RESOLVED;
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) attempt.ipv4++;
            else if (address != null) attempt.ipv6++;
            if (!RelayAddressPolicy.isAllowedDestination(address, allowVpnFakeIp)) attempt.rejected++;
        }
    }

    synchronized void connecting(Attempt attempt, InetAddress address) {
        if (!current(attempt)) return;
        attempt.family = family(address);
        attempt.stage = Stage.TCP_CONNECTING;
    }

    synchronized void connected(Attempt attempt, InetAddress address) {
        if (!current(attempt) || attempt.countedConnected) return;
        attempt.countedConnected = true;
        connected[attempt.route.ordinal()]++;
        attempt.family = family(address);
        attempt.stage = Stage.CONNECTED;
    }

    synchronized void failed(Attempt attempt, Throwable error) {
        if (!current(attempt)) return;
        attempt.stage = failureStage(error);
        if (attempt.route != Route.LAN_PAGE) lastFailure = routeName(attempt.route) + " · " + describe(attempt);
    }

    synchronized String summary() {
        StringBuilder text = new StringBuilder("连接计数（本次应用会话累计，尝试 / TCP 已连接）：");
        for (Route route : Route.values()) text.append('\n').append(routeName(route)).append("：")
                .append(attempts[route.ordinal()]).append(" / ").append(connected[route.ordinal()]);
        text.append("\n最近开始的外网请求：");
        if (latest == null) text.append("尚无记录");
        else text.append(routeName(latest.route)).append(" · ").append(describe(latest))
                .append("\nDNS 地址：IPv4 ").append(latest.ipv4).append("，IPv6 ").append(latest.ipv6)
                .append("；策略过滤 ").append(latest.rejected);
        return text.append("\n最近一次外网失败：").append(lastFailure).toString();
    }

    static Stage failureStage(Throwable error) {
        // Inspect only known categories; exception text is never retained or displayed.
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof SecurityException) return Stage.PERMISSION;
            String message = cause.getMessage();
            if (cause instanceof SocketException && message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("eacces") || lower.contains("eperm") || lower.contains("permission denied"))
                    return Stage.PERMISSION;
            }
        }
        if (error instanceof UnknownHostException) return Stage.DNS_FAILED;
        if (error instanceof RelayConnectPlan.AddressRejectedException) return Stage.POLICY_REJECTED;
        if (error instanceof RelayConnectPlan.NetworkChangedException) return Stage.NETWORK_CHANGED;
        if (error instanceof SocketTimeoutException) return Stage.TCP_TIMEOUT;
        return Stage.TCP_FAILED;
    }

    private boolean current(Attempt attempt) { return attempt.generation == generation; }
    private static String describe(Attempt attempt) {
        return attempt.stage.text + (attempt.family.isEmpty() ? "" : "（" + attempt.family + "）");
    }
    private static String family(InetAddress address) {
        return address == null ? "" : address instanceof Inet4Address ? "IPv4" : "IPv6";
    }
    private static String routeName(Route route) {
        switch (route) {
            case LAN_PAGE: return "局域网页面";
            case COMPUTER_PUBLIC: return "电脑外网";
            default: return "页面外网";
        }
    }
}
