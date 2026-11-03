package org.example.p2prelay;

/**
 * Chạy relay: java -jar p2p-relay-server.jar [--port 8080]
 * Mọi cấu hình khác đọc từ biến môi trường RELAY_* (xem deploy/relay.env.example).
 */
public final class RelayMain {

    private RelayMain() {}

    public static void main(String[] args) throws Exception {
        RelayConfig cfg = RelayConfig.fromEnvironment();
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--port".equals(args[i])) cfg.port = Integer.parseInt(args[++i]);
            else if ("--bind".equals(args[i])) cfg.bindAddress = args[++i];
        }
        RelayServer server = new RelayServer(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "relay-shutdown"));
        server.start();
    }
}
