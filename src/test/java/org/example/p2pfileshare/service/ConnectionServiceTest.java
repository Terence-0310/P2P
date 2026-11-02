package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.util.DownloadHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kết nối 2 chiều giữa 2 peer thật trên localhost: A gửi yêu cầu, B chấp nhận -> cả 2 đều đã kết nối;
 * ngắt ở bất kỳ phía nào thì cả 2 phía cùng ngắt.
 */
@Timeout(30)
class ConnectionServiceTest {

    @TempDir Path tempRoot;

    private Peer a;
    private Peer b;

    /** 1 peer đầy đủ: ControlServer + ControlClient + kho file + ConnectionService */
    private final class Peer {
        final String id;
        final ControlServer server;
        final ControlClient client;
        final FileShareService files;
        final ConnectionService connection;
        final PeerInfo info;   // địa chỉ của peer này, như peer khác quét LAN thấy
        final AtomicBoolean accepts = new AtomicBoolean(true);
        final AtomicInteger askCount = new AtomicInteger();
        final List<String> events = new CopyOnWriteArrayList<>();

        Peer(String id, String name, String fileName) throws Exception {
            this.id = id;
            Path share = Files.createDirectory(tempRoot.resolve("share-" + id));
            Files.writeString(share.resolve(fileName), "nội dung của " + name);

            int ctrlPort = freePort();
            int filePort = freePort();
            server = new ControlServer(ctrlPort, displayName -> {
                askCount.incrementAndGet();
                return accepts.get();
            });
            files = new FileShareService(filePort,
                    new HistoryService(new DownloadHistoryManager(tempRoot.resolve("history-" + id + ".json"))));
            files.setMyDisplayName(name);
            files.setShareFolder(share.toFile());
            files.setAccessChecker(server::isAccepted);
            server.setFileShareService(files);
            server.start();
            waitForPort(ctrlPort);

            client = new ControlClient(id, name);
            client.setMyPorts(ctrlPort, filePort);
            connection = new ConnectionService(client, server);
            connection.addListener(new ConnectionService.Listener() {
                @Override
                public void onConnected(PeerInfo peer, boolean incoming) {
                    events.add("connected:" + peer.getPeerId() + (incoming ? ":incoming" : ":outgoing"));
                }

                @Override
                public void onDisconnected(String peerId, boolean byRemote) {
                    events.add("disconnected:" + peerId + (byRemote ? ":remote" : ":local"));
                }
            });
            info = new PeerInfo(id, name, "127.0.0.1", filePort, ctrlPort, "Online");
        }

        void stop() {
            server.stop();
            files.stopServer();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        a = new Peer("peer-A", "Máy A", "a.txt");
        b = new Peer("peer-B", "Máy B", "b.txt");
    }

    @AfterEach
    void tearDown() {
        if (a != null) a.stop();
        if (b != null) b.stop();
    }

    @Test
    void acceptingMakesBothSidesConnectedAndBothCanBrowseFiles() {
        assertTrue(a.connection.connect(b.info));

        assertTrue(a.connection.isConnected(b.id));
        assertTrue(b.connection.isConnected(a.id), "B đã chấp nhận thì B cũng phải thấy đã kết nối");
        assertEquals(1, b.askCount.get());
        assertTrue(a.events.contains("connected:peer-B:outgoing"));
        assertTrue(b.events.contains("connected:peer-A:incoming"), "B phải được báo để mở tab và cập nhật bảng");

        // 2 chiều: A xem file của B và B xem file của A, không phải bấm kết nối lần nữa
        assertEquals(List.of("b.txt"), names(a.client.listFiles(b.info)));
        PeerInfo aAsSeenByB = b.connection.getConnectedPeer(a.id);
        assertNotNull(aAsSeenByB, "B phải biết địa chỉ của A từ CONNECT_REQUEST");
        assertEquals(List.of("a.txt"), names(b.client.listFiles(aAsSeenByB)));
        assertEquals(0, a.askCount.get(), "A là bên gửi yêu cầu, không bị hỏi lại");
    }

    @Test
    void disconnectOnAcceptingSideDisconnectsBothSides() throws Exception {
        assertTrue(a.connection.connect(b.info));

        // máy 2 (bên chấp nhận) bấm Ngắt kết nối
        assertTrue(b.connection.disconnect(b.connection.getConnectedPeer(a.id)));

        assertFalse(b.connection.isConnected(a.id));
        waitUntil(() -> !a.connection.isConnected(b.id));
        assertTrue(a.events.contains("disconnected:peer-B:remote"), "A phải được báo B đã ngắt");
        assertTrue(a.client.listFiles(b.info).isEmpty(), "A không còn xem được file của B");
        assertTrue(b.client.listFiles(a.info).isEmpty(), "B không còn xem được file của A");
    }

    @Test
    void disconnectOnRequestingSideDisconnectsBothSides() throws Exception {
        assertTrue(a.connection.connect(b.info));

        assertTrue(a.connection.disconnect(b.info));

        assertFalse(a.connection.isConnected(b.id));
        waitUntil(() -> !b.connection.isConnected(a.id));
        assertTrue(b.events.contains("disconnected:peer-A:remote"));
        assertTrue(b.connection.getConnectedPeers().isEmpty());
    }

    @Test
    void reconnectAfterDisconnectAsksAgain() throws Exception {
        assertTrue(a.connection.connect(b.info));
        assertTrue(b.connection.disconnect(b.connection.getConnectedPeer(a.id)));
        waitUntil(() -> !a.connection.isConnected(b.id));

        b.accepts.set(false);
        assertFalse(a.connection.connect(b.info), "đã ngắt thì kết nối lại phải hỏi lại");
        assertEquals(2, b.askCount.get());
        assertFalse(a.connection.isConnected(b.id));
        assertFalse(b.connection.isConnected(a.id));
    }

    @Test
    void rejectionLeavesBothSidesDisconnected() {
        b.accepts.set(false);
        assertFalse(a.connection.connect(b.info));
        assertFalse(a.connection.isConnected(b.id));
        assertFalse(b.connection.isConnected(a.id));
        assertTrue(b.events.isEmpty());
    }

    @Test
    void connectedPeersListHasNoDuplicates() {
        assertTrue(a.connection.connect(b.info));
        assertEquals(1, a.connection.getConnectedPeers().size());
        assertEquals(1, b.connection.getConnectedPeers().size());
    }

    // ---------------- helpers ----------------

    private static List<String> names(List<ControlClient.RemoteFile> files) {
        return files.stream().map(f -> f.name).toList();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("Hết thời gian chờ");
            Thread.sleep(20);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitForPort(int port) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        fail("Server không khởi động được trên cổng " + port);
    }
}
