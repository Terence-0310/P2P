package org.example.p2pfileshare;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.network.discovery.PeerDiscovery;
import org.example.p2pfileshare.network.transfer.ChunkedFileClient;
import org.example.p2pfileshare.network.transfer.DownloadControl;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.HistoryService;
import org.example.p2pfileshare.util.DownloadHistoryManager;
import org.example.p2pfileshare.util.FileHashUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Kiểm thử end-to-end 2 peer trên cùng 1 máy: A tải file, B chia sẻ.
 * Mỗi peer có ControlServer/ControlClient và FileShareService (ChunkedFileServer) thật, cổng riêng trên localhost.
 */
@Timeout(60)
class TwoPeerEndToEndTest {

    private static final String PEER_A = "peer-A";
    private static final String PEER_B = "peer-B";
    private static final String FILE = "Bài giảng mạng.pdf";

    @TempDir Path tempRoot;
    private Path shareB;
    private Path downloadsA;
    private byte[] content;
    private String originalSha256;

    // Peer B (chia sẻ)
    private FileShareService fileShareB;
    private ControlServer controlServerB;
    private PeerInfo peerB;
    private final AtomicBoolean bAccepts = new AtomicBoolean(true);

    // Peer A (tải)
    private ControlServer controlServerA;
    private ControlClient clientA;
    private PeerInfo peerAasSeenByB;
    private final CountDownLatch aNotifiedOfDisconnect = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        shareB = Files.createDirectory(tempRoot.resolve("share-B"));
        downloadsA = Files.createDirectory(tempRoot.resolve("downloads-A"));

        // 6,5 MB -> 7 chunk 1 MB, đủ để 4 worker cùng tải
        content = new byte[6 * 1024 * 1024 + 512 * 1024];
        new Random(2280602799L).nextBytes(content);
        Files.write(shareB.resolve(FILE), content);
        originalSha256 = FileHashUtil.sha256(content);

        int fileB = freePort();
        int ctrlB = freePort();
        controlServerB = new ControlServer(ctrlB, displayName -> bAccepts.get());
        fileShareB = new FileShareService(fileB,
                new HistoryService(new DownloadHistoryManager(tempRoot.resolve("history-B.json"))));
        fileShareB.setMyDisplayName("Máy B");
        // giống RootController: kênh file chỉ phục vụ peer đã được ControlServer chấp nhận
        fileShareB.setAccessChecker(controlServerB::isAccepted);
        fileShareB.setShareFolder(shareB.toFile());
        controlServerB.setFileShareService(fileShareB);
        controlServerB.start();
        waitForPort(ctrlB);
        waitForPort(fileB);
        peerB = new PeerInfo(PEER_B, "Máy B", "127.0.0.1", fileB, ctrlB, "Online");

        int ctrlA = freePort();
        controlServerA = new ControlServer(ctrlA, displayName -> true);
        controlServerA.setOnDisconnectNotify(msg -> aNotifiedOfDisconnect.countDown());
        controlServerA.start();
        waitForPort(ctrlA);
        clientA = new ControlClient(PEER_A, "Máy A");
        clientA.setMyPorts(ctrlA, freePort());
        peerAasSeenByB = new PeerInfo(PEER_A, "Máy A", "127.0.0.1", 0, ctrlA, "Online");
    }

    @AfterEach
    void tearDown() {
        if (controlServerA != null) controlServerA.stop();
        if (controlServerB != null) controlServerB.stop();
        if (fileShareB != null) fileShareB.stopServer();
    }

    @Test
    void discoveryFindsPeerRunningOnSameMachine() throws Exception {
        assumeTrue(hasMulticastInterface(),
                "Bỏ qua: máy không có card mạng đang bật hỗ trợ multicast IPv4 (PeerDiscovery bỏ qua loopback)");

        PeerDiscovery.startResponder(PEER_B, () -> "Máy B", peerB.getFileServerPort(), peerB.getControlPort());
        try {
            PeerInfo found = null;
            // responder chạy trên luồng riêng, cho tối đa 3 lần quét để nó kịp joinGroup
            for (int attempt = 0; attempt < 3 && found == null; attempt++) {
                found = PeerDiscovery.discoverPeers(PEER_A, 1500).stream()
                        .filter(p -> PEER_B.equals(p.getPeerId()))
                        .findFirst().orElse(null);
            }
            assertNotNull(found, "Không tìm thấy peer-B qua multicast. Kiểm tra Windows Firewall cho Java (mạng Private)");
            assertEquals("Máy B", found.getDisplayName());
            assertEquals(peerB.getFileServerPort(), found.getFileServerPort());
            assertEquals(peerB.getControlPort(), found.getControlPort());

            // quét từ chính peer-B thì không thấy chính mình
            assertTrue(PeerDiscovery.discoverPeers(PEER_B, 1000).stream()
                    .noneMatch(p -> PEER_B.equals(p.getPeerId())));
        } finally {
            PeerDiscovery.stopResponder();
        }
    }

    @Test
    void fullFlowRejectConnectListDownloadThenBlock() throws Exception {
        Path saveTo = downloadsA.resolve(FILE);

        // 1) Chưa kết nối: kênh file từ chối
        IOException denied = assertThrows(IOException.class, () -> download(saveTo, new DownloadControl(), null));
        assertTrue(denied.getMessage().contains("Access denied"), denied.getMessage());

        // 2) B từ chối kết nối
        bAccepts.set(false);
        assertFalse(clientA.sendConnectRequest(peerB));
        assertFalse(controlServerB.isAccepted(PEER_A));

        // 3) B chấp nhận, A thấy file trong danh sách
        bAccepts.set(true);
        assertTrue(clientA.sendConnectRequest(peerB));
        assertTrue(controlServerB.isAccepted(PEER_A));
        List<ControlClient.RemoteFile> files = clientA.listFiles(peerB);
        assertTrue(files.stream().anyMatch(f -> f.name.equals(FILE) && f.size == content.length));

        // 4) Tải và so SHA-256 với file gốc
        assertTrue(download(saveTo, new DownloadControl(), null));
        assertEquals(originalSha256, FileHashUtil.sha256(saveTo));

        // 5) B chặn A: A được báo, lần tải sau bị từ chối
        controlServerB.disconnectPeer(peerAasSeenByB, PEER_B);
        assertTrue(aNotifiedOfDisconnect.await(5, TimeUnit.SECONDS), "A không nhận được DISCONNECT_NOTIFY");
        assertFalse(controlServerB.isAccepted(PEER_A));
        IOException blocked = assertThrows(IOException.class,
                () -> download(downloadsA.resolve("lan-2.pdf"), new DownloadControl(), null));
        assertTrue(blocked.getMessage().contains("Access denied"), blocked.getMessage());
    }

    @Test
    void pauseStopsProgressAndResumeCompletesWithCorrectHash() throws Exception {
        assertTrue(clientA.sendConnectRequest(peerB));
        Path saveTo = downloadsA.resolve(FILE);
        DownloadControl control = new DownloadControl();
        AtomicReference<Double> lastProgress = new AtomicReference<>(0.0);
        AtomicBoolean pausedOnce = new AtomicBoolean(false);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = exec.submit(() -> download(saveTo, control, p -> {
                lastProgress.accumulateAndGet(p, Math::max);
                // tạm dừng ngay sau chunk đầu tiên tải xong
                if (p > 0 && p < 1 && pausedOnce.compareAndSet(false, true)) control.pause();
            }));

            waitUntil(pausedOnce::get, 10_000);
            // chờ các worker đang tải dở dừng lại ở checkpoint
            Thread.sleep(500);
            double progressWhenPaused = lastProgress.get();
            Thread.sleep(700);

            assertFalse(result.isDone(), "Đang tạm dừng thì không được tải xong");
            assertEquals(progressWhenPaused, lastProgress.get(), 1e-9, "Tiến độ vẫn tăng khi đang tạm dừng");
            assertTrue(progressWhenPaused < 1.0);
            assertTrue(Files.exists(Path.of(saveTo + ".part")));

            control.resume();
            assertTrue(result.get(30, TimeUnit.SECONDS));
            assertEquals(1.0, lastProgress.get(), 1e-9);
            assertEquals(originalSha256, FileHashUtil.sha256(saveTo));
            assertFalse(Files.exists(Path.of(saveTo + ".part")));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void interruptedDownloadResumesFromBitmapAfterReconnect() throws Exception {
        assertTrue(clientA.sendConnectRequest(peerB));
        Path saveTo = downloadsA.resolve(FILE);
        AtomicBoolean blockedOnce = new AtomicBoolean(false);

        // Lần 1: sau chunk đầu tiên, B chặn A -> các chunk còn lại bị từ chối
        boolean first = download(saveTo, new DownloadControl(), p -> {
            if (p > 0 && p < 1 && blockedOnce.compareAndSet(false, true)) {
                controlServerB.disconnectPeer(peerAasSeenByB, PEER_B);
            }
        });
        assertFalse(first, "Tải phải thất bại khi bị chặn giữa chừng");
        assertFalse(Files.exists(saveTo));
        assertTrue(Files.exists(Path.of(saveTo + ".part")), "Phải giữ .part để tải tiếp");
        assertTrue(Files.exists(Path.of(saveTo + ".bitmap")), "Phải giữ .bitmap để tải tiếp");

        // Lần 2: kết nối lại, tải tiếp từ bitmap
        assertTrue(clientA.sendConnectRequest(peerB));
        AtomicReference<Double> initialProgress = new AtomicReference<>();
        assertTrue(download(saveTo, new DownloadControl(), p -> initialProgress.compareAndSet(null, p)));

        assertNotNull(initialProgress.get());
        assertTrue(initialProgress.get() > 0, "Lần 2 phải bắt đầu từ các chunk đã tải, không tải lại từ đầu");
        assertEquals(originalSha256, FileHashUtil.sha256(saveTo));
        assertFalse(Files.exists(Path.of(saveTo + ".bitmap")));
    }

    // ---------------- helpers ----------------

    private boolean download(Path saveTo, DownloadControl control, java.util.function.Consumer<Double> progress)
            throws IOException {
        return ChunkedFileClient.downloadFile(peerB.getIp(), peerB.getFileServerPort(), PEER_A, FILE, saveTo,
                progress, control);
    }

    private static boolean hasMulticastInterface() throws IOException {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast()
                    && ni.getInterfaceAddresses().stream().anyMatch(a -> a.getAddress() instanceof Inet4Address)) {
                return true;
            }
        }
        return false;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
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
