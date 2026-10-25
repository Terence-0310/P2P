package org.example.p2pfileshare.network.control;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.HistoryService;
import org.example.p2pfileshare.util.DownloadHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Chạy ControlServer + ControlClient thật trên localhost. */
class ControlChannelTest {

    private static final String SERVER_ID = "server-peer";
    private static final String CLIENT_ID = "client-peer";

    @TempDir Path tempRoot;
    private Path shareDir;

    private ControlServer server;
    private FileShareService fileShareService;
    private PeerInfo serverPeer;
    private ControlClient client;

    private final AtomicBoolean acceptDecision = new AtomicBoolean(true);
    private final AtomicInteger askCount = new AtomicInteger();
    private final List<String> askedNames = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        shareDir = Files.createDirectory(tempRoot.resolve("share"));
        Files.writeString(shareDir.resolve("Bài giảng mạng.txt"),
                "Socket là điểm cuối của kết nối. TCP tin cậy. UDP nhanh. Multicast gửi nhiều máy.",
                StandardCharsets.UTF_8);
        Files.createDirectory(shareDir.resolve("Chương 2"));
        Files.write(shareDir.resolve("Chương 2").resolve("lab socket.pdf"), new byte[10]);

        fileShareService = new FileShareService(freePort(),
                new HistoryService(new DownloadHistoryManager(tempRoot.resolve("history.json"))));
        fileShareService.setMyDisplayName("Máy chủ");
        fileShareService.setShareFolder(shareDir.toFile());

        int port = freePort();
        server = new ControlServer(port, displayName -> {
            askCount.incrementAndGet();
            askedNames.add(displayName);
            return acceptDecision.get();
        });
        server.setFileShareService(fileShareService);
        server.start();
        waitForPort(port);

        serverPeer = new PeerInfo(SERVER_ID, "Máy chủ", "127.0.0.1", 0, port, "Online");
        client = new ControlClient(CLIENT_ID, "Người dùng | Việt");
        client.setMyPorts(45678, 45679);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
        if (fileShareService != null) fileShareService.stopServer();
    }

    @Test
    void acceptedConnectionRegistersPeerInfoFromRequest() {
        assertTrue(client.sendConnectRequest(serverPeer));

        assertTrue(client.isConnectedTo(SERVER_ID));
        assertTrue(server.isAccepted(CLIENT_ID));
        // tên hiển thị đã bỏ ký tự '|' để không làm hỏng giao thức, tiếng Việt giữ nguyên
        assertEquals(List.of("Người dùng / Việt"), askedNames);

        // server biết cách liên lạc lại client dù chưa từng quét LAN
        List<PeerInfo> accepted = server.getAcceptedPeers(List.of());
        assertEquals(1, accepted.size());
        PeerInfo info = accepted.get(0);
        assertEquals("127.0.0.1", info.getIp());
        assertEquals(45678, info.getControlPort());
        assertEquals(45679, info.getFileServerPort());
        assertNotNull(server.getAcceptedAt(CLIENT_ID));
    }

    @Test
    void reconnectingAcceptedPeerDoesNotAskAgain() {
        assertTrue(client.sendConnectRequest(serverPeer));
        assertTrue(client.sendConnectRequest(serverPeer));
        assertEquals(1, askCount.get());
    }

    @Test
    void rejectedPeerCannotListFiles() {
        acceptDecision.set(false);
        assertFalse(client.sendConnectRequest(serverPeer));
        assertFalse(client.isConnectedTo(SERVER_ID));
        assertTrue(client.listFiles(serverPeer).isEmpty());
    }

    @Test
    void listFilesIncludesSubfoldersWithForwardSlashes() {
        assertTrue(client.sendConnectRequest(serverPeer));
        List<ControlClient.RemoteFile> files = client.listFiles(serverPeer);

        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(f -> f.relativePath.equals("Chương 2/lab socket.pdf")));
        assertTrue(files.stream().anyMatch(f -> f.name.equals("Bài giảng mạng.txt")));
    }

    @Test
    void summaryKeepsVietnameseCharacters() {
        assertTrue(client.sendConnectRequest(serverPeer));
        String resp = client.sendSummarizeRequest(serverPeer, "Bài giảng mạng.txt");

        assertNotNull(resp);
        String[] parts = resp.split("\\|", 3);
        String text = new String(Base64.getDecoder().decode(parts[2].trim()), StandardCharsets.UTF_8);
        assertTrue(text.contains("điểm cuối"), text);
    }

    @Test
    void summaryRejectsUnacceptedPeerAndPathTraversal() throws IOException {
        Files.writeString(tempRoot.resolve("secret.txt"), "Bí mật. Không được đọc. Tuyệt đối. Thật đấy.", StandardCharsets.UTF_8);

        String denied = client.sendSummarizeRequest(serverPeer, "Bài giảng mạng.txt");
        assertTrue(decode(denied).contains("chưa được"), decode(denied));

        assertTrue(client.sendConnectRequest(serverPeer));
        String traversal = client.sendSummarizeRequest(serverPeer, "../secret.txt");
        assertTrue(decode(traversal).contains("không tồn tại"), decode(traversal));
    }

    @Test
    void searchIsIgnoredUntilAccepted() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        server.setOnSearchRequestReceived((sender, keyword) -> latch.countDown());

        client.sendSearchRequest(serverPeer, "socket");
        assertFalse(latch.await(500, TimeUnit.MILLISECONDS));

        assertTrue(client.sendConnectRequest(serverPeer));
        client.sendSearchRequest(serverPeer, "socket");
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    void disconnectRemovesAccessOnBothSides() {
        assertTrue(client.sendConnectRequest(serverPeer));
        assertTrue(client.sendDisconnectRequest(serverPeer));

        assertFalse(client.isConnectedTo(SERVER_ID));
        assertFalse(server.isAccepted(CLIENT_ID));
        assertTrue(server.getAcceptedPeers(List.of()).isEmpty());
    }

    @Test
    void disconnectFromOfflinePeerStillClearsLocalState() throws Exception {
        assertTrue(client.sendConnectRequest(serverPeer));
        PeerInfo offline = new PeerInfo(SERVER_ID, "Máy chủ", "127.0.0.1", 0, freePort(), "Online");

        assertFalse(client.sendDisconnectRequest(offline));
        assertFalse(client.isConnectedTo(SERVER_ID));
    }

    @Test
    void filesChangedCommandIsRoutedToSystemCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();
        server.setOnSystemMessageReceived((sender, msg) -> {
            received.add(sender + "=" + msg);
            latch.countDown();
        });

        client.sendFilesChanged(serverPeer);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(CLIENT_ID + "=" + ControlProtocol.CMD_FILES_CHANGED + "|*", received.get(0));
    }

    private static String decode(String resp) {
        assertNotNull(resp);
        String[] parts = resp.split("\\|", 3);
        return new String(Base64.getDecoder().decode(parts[2].trim()), StandardCharsets.UTF_8);
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
        fail("Server không khởi động được");
    }
}
