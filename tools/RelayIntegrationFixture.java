import com.jiuguan.client.SocksProxyServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/** Offline test adapter. It can connect only to two explicitly supplied loopback fixture ports. */
public final class RelayIntegrationFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 6 && args.length != 7)
            throw new IllegalArgumentException("Expected two fixture ports and optional bind IP/port/username/password");
        int httpPort = Integer.parseInt(args[0]);
        int httpsPort = Integer.parseInt(args[1]);
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        InetAddress bindAddress = args.length >= 6 ? InetAddress.getByName(args[2]) : loopback;
        int bindPort = args.length >= 6 ? Integer.parseInt(args[3]) : 0;
        String username = args.length >= 6 ? args[4] : "smoke";
        String password = args.length >= 6 ? args[5] : "offline-test";
        boolean vertexFixtures = args.length == 7 && "vertex-fixtures".equals(args[6]);
        if (args.length == 7 && !vertexFixtures)
            throw new IllegalArgumentException("Unknown fixture target profile");
        if (!bindAddress.isLoopbackAddress() && (!bindAddress.isSiteLocalAddress() || password.length() < 32))
            throw new IllegalArgumentException("LAN fixture requires a private address and a long random test credential");
        try (SocksProxyServer relay = new SocksProxyServer(bindAddress, bindPort, username, password, (host, port) -> {
            boolean vertex = vertexFixtures && port == 443 && ("oauth2.googleapis.com".equals(host)
                    || "aiplatform.googleapis.com".equals(host) || "us-central1-aiplatform.googleapis.com".equals(host));
            boolean ordinary = "relay-target.invalid".equals(host) && (port == httpPort || port == httpsPort);
            if (!ordinary && !vertex)
                throw new IOException("Only offline test targets are allowed");
            System.out.println("CONNECT " + host + " " + port);
            return new Socket(loopback, vertex ? httpsPort : port);
        })) {
            relay.start();
            System.out.println("READY " + relay.port());
            new BufferedReader(new InputStreamReader(System.in)).readLine();
            relay.close();
            System.out.println("TOTAL " + relay.uploadedBytes() + " " + relay.downloadedBytes());
        }
    }
}
