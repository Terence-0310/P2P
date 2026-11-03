package org.example.p2pfileshare.internet;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.network.relay.IdentityKey;
import org.example.p2pfileshare.network.relay.RelaySettings;
import org.example.p2pfileshare.network.transport.TransportRouter;
import org.example.p2pfileshare.service.ConnectionService;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.HistoryService;
import org.example.p2pfileshare.service.InternetModeService;
import org.example.p2pfileshare.service.PeerHealthMonitor;
import org.example.p2pfileshare.util.DownloadHistoryManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1 "ứng dụng" P2PFileShare đầy đủ tầng nghiệp vụ, không có JavaFX: nối dây giống RootController.
 */
final class TestPeerApp implements AutoCloseable {

    final String name;
    final IdentityKey identity = IdentityKey.generate();
    final Path share;
    final Path downloads;
    final Path dir;
    final int controlPort;
    final int filePort;
    final ControlServer server;
    final ControlClient client;
    final FileShareService files;
    final ConnectionService connections;
    final TransportRouter router = new TransportRouter();
    final PeerHealthMonitor monitor;
    final InternetModeService internet;

    final AtomicBoolean accepts = new AtomicBoolean(true);
    final AtomicInteger askCount = new AtomicInteger();
    final List<String> askedNames = new CopyOnWriteArrayList<>();
    final Map<String, PeerHealthMonitor.Health> health = new ConcurrentHashMap<>();
    final List<String> events = new CopyOnWriteArrayList<>();

    TestPeerApp(String name, Path root) throws IOException {
        this.name = name;
        dir = Files.createDirectories(root.resolve(name.replace(' ', '_')));
        controlPort = freePort();
        filePort = freePort();
        share = Files.createDirectories(dir.resolve("share"));
        downloads = Files.createDirectories(dir.resolve("downloads"));

        server = new ControlServer(controlPort, displayName -> {
            askCount.incrementAndGet();
            askedNames.add(displayName);
            return accepts.get();
        });
        files = new FileShareService(filePort,
                new HistoryService(new DownloadHistoryManager(dir.resolve("history.json"))));
        files.setMyDisplayName(name);
        files.setMyPeerId(identity.peerId());
        files.setAccessChecker(server::isAcceptedFrom);
        files.setTransport(router);
        files.setShareFolder(share.toFile());
        server.setFileShareService(files);
        server.start();

        client = new ControlClient(identity.peerId(), name);
        client.setTransport(router);
        connections = new ConnectionService(client, server);
        connections.addListener(new ConnectionService.Listener() {
            @Override
            public void onConnected(PeerInfo peer, boolean incoming) {
                events.add("connected:" + peer.getPeerId() + (incoming ? ":incoming" : ":outgoing"));
            }

            @Override
            public void onDisconnected(String peerId, boolean byRemote) {
                events.add("disconnected:" + peerId + (byRemote ? ":remote" : ":local"));
            }
        });

        monitor = new PeerHealthMonitor(connections, client, 300, 2000);
        monitor.setOnReachable(files::retryWaitingDownloads);
        monitor.addListener((peerId, h) -> health.put(peerId, h));
        monitor.start();

        internet = new InternetModeService(identity, () -> name, server, files, connections, router, monitor);
    }

    String id() {
        return identity.peerId();
    }

    /** Kết nối relay với heartbeat ngắn để test chạy nhanh */
    void connectRelay(int relayPort) {
        RelaySettings s = RelaySettings.forUrl("ws://127.0.0.1:" + relayPort + "/ws");
        s.heartbeatSeconds = 1;
        s.timeoutSeconds = 3;
        s.connectTimeoutSeconds = 3;
        s.requestTimeoutSeconds = 10;
        s.backoffInitialMs = 200;
        s.backoffMaxMs = 1000;
        s.maxReconnectAttempts = 0; // test tự dừng
        internet.connect(s);
    }

    @Override
    public void close() {
        internet.close();
        monitor.close();
        server.stop();
        files.stopServer();
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
