import com.jiuguan.client.SocksProxyServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/** Offline test adapter. It can connect only to two explicitly supplied loopback fixture ports. */
public final class RelayIntegrationFixture {
    public static void main(String[] args) throws Exception {
        int httpPort = Integer.parseInt(args[0]);
        int httpsPort = Integer.parseInt(args[1]);
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (SocksProxyServer relay = new SocksProxyServer(loopback, 0, "smoke", "offline-test", (host, port) -> {
            if (!"relay-target.invalid".equals(host) || (port != httpPort && port != httpsPort))
                throw new IOException("Only offline test targets are allowed");
            System.out.println("CONNECT " + host + " " + port);
            return new Socket(loopback, port);
        })) {
            relay.start();
            System.out.println("READY " + relay.port());
            new BufferedReader(new InputStreamReader(System.in)).readLine();
            relay.close();
            System.out.println("TOTAL " + relay.uploadedBytes() + " " + relay.downloadedBytes());
        }
    }
}
