package org.example.p2pfileshare.internet;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.relay.RelayClient;
import org.example.p2pfileshare.network.relay.RelayException;
import org.example.p2pfileshare.network.relay.RelaySettings;
import org.example.p2pfileshare.network.transfer.ChunkedFileClient;
import org.example.p2pfileshare.network.transport.Channel;
import org.example.p2pfileshare.service.DownloadJob;
import org.example.p2pfileshare.service.PeerHealthMonitor;
import org.example.p2pfileshare.service.SearchService;
import org.example.p2pfileshare.util.FileHashUtil;
import org.example.p2prelay.RelayConfig;
import org.example.p2prelay.RelayServer;
import org.example.p2prelay.protocol.RelayProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test Internet Mode: relay thật (WebSocket qua TCP) chạy cục bộ + các "ứng dụng" đầy đủ tầng nghiệp vụ.
 *
 * Lưu ý: chạy trên 1 máy (localhost), chứng minh giao thức và logic. Kiểm thử 2 máy ở 2 mạng khác nhau thật
 * (Wi-Fi + 4G qua relay công khai) xem docs/CROSS_NETWORK_TEST.md.
 */
@Timeout(120)
class InternetModeIntegrationTest {

    private static final String LINK_SECRET = "integration-test-link-secret-0123456789abcdef";

    @TempDir Path tempRoot;
    private RelayServer relay;
    private int relayPort;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void startRelay() throws IOException {
        relay = startRelay(0, c -> {});
        relayPort = relay.getPort();
    }

    @AfterEach
    void tearDown() {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            try {
                closeables.get(i).close();
            } catch (Exception ignored) {
            }
        }
        if (relay != null) relay.close();
    }

    // =============== 1. đăng ký với relay ===============

    @Test
    void twoPeersRegisterWithRelayUsingSignatureDerivedIds() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        a.connectRelay(relayPort);
        b.connectRelay(relayPort);
        waitUntil(() -> a.internet.isRelayOnline() && b.internet.isRelayOnline(), 10_000, "2 peer phải online");

        assertEquals(2, relay.onlineCount());
        assertEquals(RelayClient.State.ONLINE, a.internet.getState());
        assertTrue(RelayProtocol.isValidPeerId(a.id()));
        assertNotEquals(a.id(), b.id());
        assertEquals(a.id(), a.internet.getRelayClient().myPeerId());
    }

    // =============== 2, 3, 6: yêu cầu kết nối, chấp nhận, liệt kê, tìm kiếm ===============

    @Test
    void connectByInviteAcceptedThenBothSidesListAndSearchFiles() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        Files.writeString(a.share.resolve("a.txt"), "của A");
        Files.createDirectories(b.share.resolve("Chương 3"));
        Files.writeString(b.share.resolve("Chương 3").resolve("socket b.txt"), "của B");
        online(a, b);

        RelayClient.Invite inv = b.internet.createInvite();
        assertTrue(inv.displayCode().matches("[A-Z2-9]{4}-[A-Z2-9]{4}"), inv.displayCode());
        assertTrue(inv.expiresAtEpochSec() * 1000 > System.currentTimeMillis());

        // người dùng gõ chữ thường, có khoảng trắng vẫn được
        PeerInfo peerB = a.internet.connectByCode(" " + inv.displayCode().toLowerCase() + " ");
        assertTrue(peerB.isRelay());
        assertEquals(b.id(), peerB.getPeerId(), "danh tính do relay xác thực");
        assertEquals("Máy B", peerB.getName());
        assertEquals(List.of("Máy A (qua Internet)"), b.askedNames, "B phải được hỏi Chấp nhận / Từ chối");

        assertTrue(a.connections.isConnected(b.id()));
        waitUntil(() -> b.connections.isConnected(a.id()), 5000, "B cũng phải thấy đã kết nối");
        assertTrue(b.events.contains("connected:" + a.id() + ":incoming"));

        // A xem kho của B và ngược lại, đều qua relay
        List<ControlClient.RemoteFile> filesOfB = a.client.listFiles(peerB);
        assertEquals(List.of("Chương 3/socket b.txt"), filesOfB.stream().map(f -> f.relativePath).toList());
        PeerInfo aSeenByB = b.connections.getConnectedPeer(a.id());
        assertTrue(aSeenByB.isRelay());
        assertEquals(List.of("a.txt"), b.client.listFiles(aSeenByB).stream().map(f -> f.name).toList());

        // tìm kiếm qua relay
        CountDownLatch found = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        a.server.setOnSearchResultReceived((sender, data) -> {
            results.add(sender + "=" + data);
            found.countDown();
        });
        SearchService searchB = new SearchService(b.files);
        b.server.setOnSearchRequestReceived((sender, keyword) ->
                b.client.sendSearchResponse(b.server.getAcceptedPeerInfo(sender), searchB.buildResponsePayload(keyword)));
        a.client.sendSearchRequest(peerB, "socket");
        assertTrue(found.await(5, TimeUnit.SECONDS), "không nhận được kết quả tìm kiếm qua relay");
        assertTrue(results.get(0).startsWith(b.id() + "="));
        assertTrue(results.get(0).contains("socket b.txt"));

        // heartbeat thấy 2 bên online
        waitUntil(() -> a.health.get(b.id()) == PeerHealthMonitor.Health.ONLINE, 5000, "A phải thấy B online");

        // mã mời chỉ dùng 1 lần
        RelayException reused = assertThrows(RelayException.class, () -> a.internet.connectByCode(inv.code()));
        assertEquals(RelayProtocol.E_INVITE_INVALID, reused.getCode());
    }

    // =============== 4. từ chối ===============

    @Test
    void rejectedConnectionLeavesNoAccessAndNoLink() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        Files.writeString(b.share.resolve("b.txt"), "bí mật");
        online(a, b);
        b.accepts.set(false);

        IOException e = assertThrows(IOException.class,
                () -> a.internet.connectByCode(b.internet.createInvite().code()));
        assertTrue(e.getMessage().contains("từ chối"), e.getMessage());
        assertEquals(1, b.askCount.get());
        assertFalse(a.connections.isConnected(b.id()));
        assertFalse(b.server.isAccepted(a.id()));
        assertFalse(a.internet.getRelayClient().isLinked(b.id()), "từ chối thì huỷ liên kết trên relay");

        // không còn liên kết -> relay không chuyển tiếp nữa
        PeerInfo peerB = PeerInfo.viaRelay(b.id(), "B");
        RelayException notLinked = assertThrows(RelayException.class, () -> a.router.open(peerB, Channel.CONTROL));
        assertEquals(RelayProtocol.E_NOT_LINKED, notLinked.getCode());
    }

    // =============== 5. chưa có quyền / mạo danh ===============

    @Test
    void linkedButUnacceptedPeerCannotReadFilesNorImpersonateAcceptedPeer() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        TestPeerApp c = app("Máy C");
        Files.writeString(b.share.resolve("secret.txt"), "chỉ cho A");
        online(a, b, c);
        connect(a, b);

        // C có mã mời (liên kết trên relay) nhưng B chưa bấm Chấp nhận
        c.internet.getRelayClient().resolveInvite(b.internet.createInvite().code());
        PeerInfo bForC = PeerInfo.viaRelay(b.id(), "B");
        assertTrue(c.client.listFiles(bForC).isEmpty(), "chưa được chấp nhận thì không xem được danh sách");
        IOException denied = assertThrows(IOException.class, () -> ChunkedFileClient.requestMetadata(
                () -> c.router.open(bForC, Channel.FILE), c.id(), "secret.txt"));
        assertTrue(denied.getMessage().contains("Access denied"), denied.getMessage());

        // C tự khai là A (A đã được chấp nhận): relay đóng dấu danh tính thật của C nên bị chặn
        ControlClient fakeA = new ControlClient(a.id(), "Giả làm A");
        fakeA.setTransport(c.router);
        assertTrue(fakeA.listFiles(bForC).isEmpty());
        IOException spoof = assertThrows(IOException.class, () -> ChunkedFileClient.requestMetadata(
                () -> c.router.open(bForC, Channel.FILE), a.id(), "secret.txt"));
        assertTrue(spoof.getMessage().contains("Access denied"), spoof.getMessage());

        // id của A được chấp nhận qua relay cũng không dùng được từ kết nối LAN chưa xác thực
        IOException lan = assertThrows(IOException.class, () -> ChunkedFileClient.requestMetadata(
                "127.0.0.1", b.filePort, a.id(), "secret.txt"));
        assertTrue(lan.getMessage().contains("Access denied"), lan.getMessage());

        // A thật vẫn tải được
        PeerInfo bForA = a.connections.getConnectedPeer(b.id());
        assertEquals(1, ChunkedFileClient.requestMetadata(() -> a.router.open(bForA, Channel.FILE), a.id(),
                "secret.txt").getTotalChunks());

        // peer không có mã mời thì relay không cho mở luồng tới B
        TestPeerApp d = app("Máy D");
        d.connectRelay(relayPort);
        waitUntil(d.internet::isRelayOnline, 10_000, "D online");
        RelayException notLinked = assertThrows(RelayException.class,
                () -> d.router.open(PeerInfo.viaRelay(b.id(), "B"), Channel.CONTROL));
        assertEquals(RelayProtocol.E_NOT_LINKED, notLinked.getCode());
    }

    // =============== 7, 8, 9: file nhỏ, file lớn nhiều chunk, SHA-256 ===============

    @Test
    void downloadsSmallAndMultiChunkFilesOverRelayAndVerifiesSha256() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        byte[] small = "Ghi chú buổi 1: TCP 3-way handshake".getBytes(StandardCharsets.UTF_8);
        byte[] large = randomBytes(12 * 1024 * 1024 + 300 * 1024, 1); // 13 chunk 1 MiB
        Files.write(b.share.resolve("ghi chú.txt"), small);
        Files.write(b.share.resolve("Bài giảng mạng.pdf"), large);
        online(a, b);
        PeerInfo peerB = connect(a, b);

        for (String name : List.of("ghi chú.txt", "Bài giảng mạng.pdf")) {
            List<Double> progress = new CopyOnWriteArrayList<>();
            DownloadJob job = a.files.startDownload(peerB, name, a.downloads.resolve(name), progress::add, s -> {}, null);
            waitForEnd(job, 60_000);
            assertEquals(DownloadJob.State.COMPLETED, job.getState(), name + ": " + job.getError());
            byte[] expected = name.endsWith(".pdf") ? large : small;
            assertEquals(FileHashUtil.sha256(expected), FileHashUtil.sha256(a.downloads.resolve(name)), name);
            assertEquals(1.0, progress.get(progress.size() - 1), 1e-9);
            assertFalse(Files.exists(Path.of(a.downloads.resolve(name) + ".part")));
        }
        // không ghi đè file đã có: tải lại cùng tên -> "(1)"
        DownloadJob again = a.files.startDownload(peerB, "ghi chú.txt", a.downloads.resolve("ghi chú.txt"),
                p -> {}, s -> {}, null);
        waitForEnd(again, 30_000);
        assertEquals(a.downloads.resolve("ghi chú (1).txt"), again.getSaveTo());

        // relay không giữ luồng nào sau khi tải xong (không lưu dữ liệu)
        waitUntil(() -> relay.activeStreamCount() == 0, 5000, "relay phải đóng hết luồng ảo");
    }

    // =============== 10. Pause / Resume ===============

    @Test
    void pauseAndResumeOverRelay() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        byte[] content = randomBytes(10 * 1024 * 1024, 2);
        Files.write(b.share.resolve("video.mp4"), content);
        // giới hạn băng thông tới relay để kịp tạm dừng giữa chừng
        FlakyNetworkProxy proxy = closeLater(new FlakyNetworkProxy(relayPort, 3L * 1024 * 1024));
        a.connectRelay(proxy.port());
        b.connectRelay(relayPort);
        waitUntil(() -> a.internet.isRelayOnline() && b.internet.isRelayOnline(), 10_000, "online");
        PeerInfo peerB = connect(a, b);

        List<Double> progress = new CopyOnWriteArrayList<>();
        DownloadJob[] holder = new DownloadJob[1];
        java.util.concurrent.atomic.AtomicBoolean pausedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        Path saveTo = a.downloads.resolve("video.mp4");
        holder[0] = a.files.startDownload(peerB, "video.mp4", saveTo, p -> {
            progress.add(p);
            // tạm dừng 1 lần ngay sau chunk đầu tiên
            if (p > 0 && p < 1 && holder[0] != null && pausedOnce.compareAndSet(false, true)) holder[0].pause();
        }, s -> {}, null);
        DownloadJob job = holder[0];
        waitUntil(() -> job.getState() == DownloadJob.State.PAUSED, 20_000, "phải tạm dừng được");
        Thread.sleep(1500); // worker đang tải dở dừng ở checkpoint
        int countWhenPaused = progress.size();
        double atPause = progress.get(progress.size() - 1);
        Thread.sleep(1000);
        assertEquals(countWhenPaused, progress.size(), "đang tạm dừng thì không được tải thêm");
        assertTrue(atPause < 1.0);
        assertTrue(Files.exists(Path.of(saveTo + ".part")));

        job.resume();
        waitForEnd(job, 60_000);
        assertEquals(DownloadJob.State.COMPLETED, job.getState(), String.valueOf(job.getError()));
        assertEquals(FileHashUtil.sha256(content), FileHashUtil.sha256(saveTo));
    }

    // =============== 11, 12: mất mạng đột ngột khi đang tải, tải tiếp sau khi có mạng ===============

    @Test
    void suddenNetworkLossIsDetectedAndDownloadResumesFromVerifiedChunks() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        byte[] content = randomBytes(16 * 1024 * 1024, 3);
        Files.write(b.share.resolve("đồ án.zip"), content);
        FlakyNetworkProxy wifiOfA = closeLater(new FlakyNetworkProxy(relayPort, 3L * 1024 * 1024));
        a.connectRelay(wifiOfA.port());
        b.connectRelay(relayPort);
        waitUntil(() -> a.internet.isRelayOnline() && b.internet.isRelayOnline(), 10_000, "online");
        PeerInfo peerB = connect(a, b);

        List<Double> progress = new CopyOnWriteArrayList<>();
        List<DownloadJob.State> states = new CopyOnWriteArrayList<>();
        Path saveTo = a.downloads.resolve("đồ án.zip");
        DownloadJob job = a.files.startDownload(peerB, "đồ án.zip", saveTo, progress::add, s -> {}, states::add);
        waitUntil(() -> !progress.isEmpty() && progress.get(progress.size() - 1) >= 0.25, 30_000, "tải được 25%");

        // tắt Wi-Fi của A: không có gói FIN, chỉ heartbeat mới phát hiện được
        wifiOfA.freeze();
        long lostAt = System.currentTimeMillis();
        waitUntil(() -> a.internet.getState() == RelayClient.State.RECONNECTING, 10_000,
                "A phải phát hiện mất kết nối relay");
        long detectMs = System.currentTimeMillis() - lostAt;
        assertTrue(detectMs < 8000, "phát hiện quá chậm: " + detectMs + "ms");
        waitUntil(() -> job.getState() == DownloadJob.State.WAITING_NETWORK, 30_000, "tác vụ phải chờ mạng");
        // phía B: relay vẫn online nhưng A offline -> không nhầm relay sống với peer sống
        waitUntil(() -> b.health.get(a.id()) == PeerHealthMonitor.Health.OFFLINE, 15_000, "B phải thấy A offline");
        assertEquals(RelayClient.State.ONLINE, b.internet.getState());
        assertFalse(Files.exists(saveTo), "chưa xong thì không được tạo file hoàn chỉnh");
        assertTrue(Files.exists(Path.of(saveTo + ".part")));
        assertTrue(Files.exists(Path.of(saveTo + ".bitmap")));
        double beforeLoss = progress.get(progress.size() - 1);
        int callbacksBeforeRestore = progress.size();

        // bật lại Wi-Fi
        wifiOfA.restore();
        waitForEnd(job, 90_000);
        assertEquals(DownloadJob.State.COMPLETED, job.getState(), String.valueOf(job.getError()));
        assertEquals(FileHashUtil.sha256(content), FileHashUtil.sha256(saveTo));
        // lượt tải sau khi có mạng bắt đầu từ các chunk đã xác minh, không tải lại từ đầu
        double resumedFrom = progress.get(callbacksBeforeRestore);
        assertTrue(resumedFrom >= beforeLoss - 0.2 && resumedFrom > 0.1,
                "tải lại từ đầu? trước khi mất=" + beforeLoss + " lúc tiếp tục=" + resumedFrom);
        assertTrue(states.contains(DownloadJob.State.WAITING_NETWORK));
        waitUntil(() -> b.health.get(a.id()) == PeerHealthMonitor.Health.ONLINE, 15_000, "B thấy A online lại");
    }

    // =============== 14. peer chia sẻ offline (relay vẫn sống), quay lại thì tải tiếp ===============

    @Test
    void sharerGoingOfflineIsReportedAndDownloadContinuesWhenItReturns() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        byte[] content = randomBytes(10 * 1024 * 1024, 4);
        Files.write(b.share.resolve("slide.pptx"), content);
        FlakyNetworkProxy slowLink = closeLater(new FlakyNetworkProxy(relayPort, 3L * 1024 * 1024));
        a.connectRelay(slowLink.port());
        b.connectRelay(relayPort);
        waitUntil(() -> a.internet.isRelayOnline() && b.internet.isRelayOnline(), 10_000, "online");
        PeerInfo peerB = connect(a, b);

        List<Double> progress = new CopyOnWriteArrayList<>();
        DownloadJob job = a.files.startDownload(peerB, "slide.pptx", a.downloads.resolve("slide.pptx"),
                progress::add, s -> {}, null);
        waitUntil(() -> !progress.isEmpty() && progress.get(progress.size() - 1) >= 0.2, 30_000, "tải được 20%");

        b.internet.disconnect(); // B tắt Internet Mode / mất relay
        waitUntil(() -> a.health.get(b.id()) == PeerHealthMonitor.Health.OFFLINE, 10_000, "A phải thấy B offline");
        assertEquals(RelayClient.State.ONLINE, a.internet.getState(), "relay của A vẫn sống");
        waitUntil(() -> job.getState() == DownloadJob.State.WAITING_NETWORK, 30_000, "tác vụ phải chờ B quay lại");

        b.connectRelay(relayPort); // cùng danh tính, quyền đã cấp vẫn còn
        waitForEnd(job, 90_000);
        assertEquals(DownloadJob.State.COMPLETED, job.getState(), String.valueOf(job.getError()));
        assertEquals(FileHashUtil.sha256(content), FileHashUtil.sha256(a.downloads.resolve("slide.pptx")));
    }

    @Test
    void connectingToOfflinePeerFailsWithClearMessage() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        online(a, b);
        String code = b.internet.createInvite().code();
        b.internet.disconnect();
        waitUntil(() -> relay.onlineCount() == 1, 5000, "B offline trên relay");

        RelayException e = assertThrows(RelayException.class, () -> a.internet.connectByCode(code));
        assertEquals(RelayProtocol.E_PEER_OFFLINE, e.getCode());
        assertEquals("Máy kia đang offline (chưa kết nối relay)", e.getMessage());
    }

    // =============== 13. relay khởi động lại giữa lúc tải ===============

    @Test
    void relayRestartRestoresLinksAndDownloadResumes() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        byte[] content = randomBytes(12 * 1024 * 1024, 5);
        Files.write(b.share.resolve("data.bin"), content);
        FlakyNetworkProxy slowLink = closeLater(new FlakyNetworkProxy(relayPort, 3L * 1024 * 1024));
        a.connectRelay(slowLink.port());
        b.connectRelay(relayPort);
        waitUntil(() -> a.internet.isRelayOnline() && b.internet.isRelayOnline(), 10_000, "online");
        PeerInfo peerB = connect(a, b);

        List<Double> progress = new CopyOnWriteArrayList<>();
        DownloadJob job = a.files.startDownload(peerB, "data.bin", a.downloads.resolve("data.bin"),
                progress::add, s -> {}, null);
        waitUntil(() -> !progress.isEmpty() && progress.get(progress.size() - 1) >= 0.2, 30_000, "tải được 20%");

        relay.close();
        waitUntil(() -> a.internet.getState() != RelayClient.State.ONLINE
                && b.internet.getState() != RelayClient.State.ONLINE, 10_000, "2 bên phải thấy mất relay");
        waitUntil(() -> job.getState() == DownloadJob.State.WAITING_NETWORK, 30_000, "tác vụ phải chờ mạng");

        relay = startRelay(relayPort, cfg -> {}); // cùng cổng, cùng RELAY_LINK_SECRET
        waitUntil(() -> a.internet.isRelayOnline() && b.internet.isRelayOnline(), 15_000, "tự kết nối lại relay");
        waitForEnd(job, 90_000);
        assertEquals(DownloadJob.State.COMPLETED, job.getState(), String.valueOf(job.getError()));
        assertEquals(FileHashUtil.sha256(content), FileHashUtil.sha256(a.downloads.resolve("data.bin")));
        // liên kết được khôi phục bằng link token, không phải nhập lại mã
        assertTrue(a.client.listFiles(peerB).stream().anyMatch(f -> f.name.equals("data.bin")));
    }

    // =============== 15. mã sai, hết hạn, đoán mò ===============

    @Test
    void wrongExpiredOwnAndBruteForcedInviteCodesAreRejected() throws Exception {
        relay.close();
        relay = startRelay(0, c -> {
            c.inviteTtlSeconds = 1;
            c.resolveMaxFailsPerSession = 4;
        });
        relayPort = relay.getPort();
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        online(a, b);
        RelayClient ra = a.internet.getRelayClient();

        assertEquals(RelayProtocol.E_INVITE_INVALID,
                assertThrows(RelayException.class, () -> ra.resolveInvite("abc")).getCode(), "sai định dạng");
        assertEquals(RelayProtocol.E_INVITE_INVALID,
                assertThrows(RelayException.class, () -> ra.resolveInvite("ZZZZ-ZZZZ")).getCode(), "mã không tồn tại");

        String expired = b.internet.createInvite().code();
        Thread.sleep(1500);
        assertEquals(RelayProtocol.E_INVITE_INVALID,
                assertThrows(RelayException.class, () -> ra.resolveInvite(expired)).getCode(), "mã hết hạn");

        String own = a.internet.createInvite().code();
        assertEquals(RelayProtocol.E_INVITE_INVALID,
                assertThrows(RelayException.class, () -> ra.resolveInvite(own)).getCode(), "mã của chính mình");

        assertEquals(RelayProtocol.E_INVITE_INVALID,
                assertThrows(RelayException.class, () -> ra.resolveInvite("YYYY-YYYY")).getCode());
        // đã sai 4 lần (sai định dạng không gửi lên relay): lần sau bị chặn kể cả mã đúng
        String valid = b.internet.createInvite().code();
        RelayException limited = assertThrows(RelayException.class, () -> ra.resolveInvite(valid));
        assertEquals(RelayProtocol.E_RATE_LIMITED, limited.getCode());
        assertFalse(ra.isLinked(b.id()));
    }

    // =============== 16. nhiều peer truy cập đồng thời ===============

    @Test
    void severalPeersDownloadFromSameSharerConcurrently() throws Exception {
        TestPeerApp b = app("Máy B");
        byte[] content = randomBytes(6 * 1024 * 1024 + 123, 6);
        Files.write(b.share.resolve("tai lieu.pdf"), content);
        List<TestPeerApp> downloaders = List.of(app("Máy A"), app("Máy C"), app("Máy D"));
        b.connectRelay(relayPort);
        for (TestPeerApp d : downloaders) d.connectRelay(relayPort);
        waitUntil(() -> relay.onlineCount() == 4, 10_000, "4 peer online");

        List<DownloadJob> jobs = new ArrayList<>();
        for (TestPeerApp d : downloaders) {
            PeerInfo peerB = connect(d, b);
            jobs.add(d.files.startDownload(peerB, "tai lieu.pdf", d.downloads.resolve("tai lieu.pdf"),
                    p -> {}, s -> {}, null));
        }
        for (int i = 0; i < jobs.size(); i++) {
            waitForEnd(jobs.get(i), 90_000);
            assertEquals(DownloadJob.State.COMPLETED, jobs.get(i).getState(), String.valueOf(jobs.get(i).getError()));
            assertEquals(FileHashUtil.sha256(content),
                    FileHashUtil.sha256(downloaders.get(i).downloads.resolve("tai lieu.pdf")));
        }
        assertEquals(3, b.server.getAcceptedPeers(List.of()).size());
    }

    // =============== 17. chặn đường dẫn ngoài thư mục chia sẻ ===============

    @Test
    void pathTraversalAbsolutePathAndSymlinkEscapeAreBlockedOverRelay() throws Exception {
        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        Path outside = Files.writeString(b.dir.resolve("mat-khau.txt"), "không được lộ");
        Files.writeString(b.share.resolve("ok.txt"), "ok");
        online(a, b);
        PeerInfo peerB = connect(a, b);

        for (String evil : List.of("../mat-khau.txt", "..\\mat-khau.txt", outside.toAbsolutePath().toString(),
                "./../mat-khau.txt", "ok.txt/../../mat-khau.txt")) {
            IOException e = assertThrows(IOException.class, () -> ChunkedFileClient.requestMetadata(
                    () -> a.router.open(peerB, Channel.FILE), a.id(), evil), evil);
            assertTrue(e.getMessage().contains("File not found"), evil + ": " + e.getMessage());
        }
        String summary = a.client.sendSummarizeRequest(peerB, "../mat-khau.txt");
        String text = new String(Base64.getDecoder().decode(summary.split("\\|", 3)[2].trim()), StandardCharsets.UTF_8);
        assertTrue(text.contains("không tồn tại"), text);

        Path link = b.share.resolve("link-ra-ngoai.txt");
        boolean symlinkSupported;
        try {
            Files.createSymbolicLink(link, outside);
            symlinkSupported = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            symlinkSupported = false; // Windows cần Developer Mode / quyền Admin để tạo symlink
        }
        assumeTrue(symlinkSupported, "Bỏ qua phần symlink: hệ điều hành không cho tạo symlink");
        assertTrue(a.client.listFiles(peerB).stream().noneMatch(f -> f.name.equals("link-ra-ngoai.txt")),
                "symlink trỏ ra ngoài không được liệt kê");
        IOException e = assertThrows(IOException.class, () -> ChunkedFileClient.requestMetadata(
                () -> a.router.open(peerB, Channel.FILE), a.id(), "link-ra-ngoai.txt"));
        assertTrue(e.getMessage().contains("File not found"), e.getMessage());
    }

    // =============== WSS/TLS: relay tự chạy TLS, client kiểm tra chứng chỉ ===============

    @Test
    void worksOverWssWithTls() throws Exception {
        Path keystore = tempRoot.resolve("relay.p12");
        String password = "test-" + Long.toHexString(new Random().nextLong());
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        assumeTrue(Files.isExecutable(keytool), "Không có keytool trong JDK");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "relay", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", password, "-keypass", password).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool lỗi");

        RelayServer tls = startRelay(0, c -> {
            c.tlsKeystorePath = keystore.toString();
            c.tlsKeystorePassword = password;
        });
        closeables.add(tls);
        SSLContext trustRelayCert = sslTrusting(keystore, password);

        TestPeerApp a = app("Máy A");
        TestPeerApp b = app("Máy B");
        Files.writeString(b.share.resolve("qua-tls.txt"), "dữ liệu qua wss");
        for (TestPeerApp x : List.of(a, b)) {
            RelaySettings s = RelaySettings.forUrl("wss://localhost:" + tls.getPort() + "/ws");
            s.sslContext = trustRelayCert;
            s.heartbeatSeconds = 1;
            s.timeoutSeconds = 3;
            x.internet.connect(s);
        }
        waitUntil(() -> a.internet.isRelayOnline() && b.internet.isRelayOnline(), 15_000, "online qua wss");
        PeerInfo peerB = connect(a, b);
        DownloadJob job = a.files.startDownload(peerB, "qua-tls.txt", a.downloads.resolve("qua-tls.txt"),
                x -> {}, x -> {}, null);
        waitForEnd(job, 30_000);
        assertEquals("dữ liệu qua wss", Files.readString(a.downloads.resolve("qua-tls.txt")));

        // client không tin chứng chỉ lạ (không có sslContext tuỳ chỉnh) -> không kết nối được
        TestPeerApp c = app("Máy C");
        RelaySettings untrusted = RelaySettings.forUrl("wss://localhost:" + tls.getPort() + "/ws");
        untrusted.maxReconnectAttempts = 1;
        untrusted.backoffInitialMs = 100;
        c.internet.connect(untrusted);
        Thread.sleep(3000);
        assertFalse(c.internet.isRelayOnline(), "chứng chỉ không tin cậy phải bị từ chối");
    }

    @Test
    void plainWsIsOnlyAllowedForLocalRelay() {
        assertThrows(IllegalArgumentException.class, () -> RelaySettings.validateUrl("ws://relay.example.com/ws"));
        assertThrows(IllegalArgumentException.class, () -> RelaySettings.validateUrl("http://relay.example.com"));
        assertThrows(IllegalArgumentException.class, () -> RelaySettings.validateUrl(""));
        assertEquals("/ws", RelaySettings.validateUrl("wss://relay.example.com").getPath());
        assertEquals("ws", RelaySettings.validateUrl("ws://127.0.0.1:8080/ws").getScheme());
        assertEquals("ws", RelaySettings.validateUrl("ws://192.168.1.10:8080/ws").getScheme());
    }

    @Test
    void reconnectBackoffGrowsExponentiallyAndIsCapped() {
        RelaySettings s = new RelaySettings();
        s.backoffInitialMs = 1000;
        s.backoffMaxMs = 60_000;
        assertEquals(1000, s.backoffDelayMs(1, 0.5));
        assertEquals(2000, s.backoffDelayMs(2, 0.5));
        assertEquals(16_000, s.backoffDelayMs(5, 0.5));
        assertEquals(60_000, s.backoffDelayMs(20, 0.5));
        assertTrue(s.backoffDelayMs(20, 1.0) <= 72_000, "jitter tối đa +20%");
        assertTrue(s.backoffDelayMs(1, 0.0) >= 800, "không thử lại dồn dập");
    }

    // ---------------- helpers ----------------

    private RelayServer startRelay(int port, Consumer<RelayConfig> tweak) throws IOException {
        RelayConfig c = new RelayConfig();
        c.bindAddress = "127.0.0.1";
        c.port = port;
        c.heartbeatSeconds = 1;
        c.idleTimeoutSeconds = 3;
        c.handshakeTimeoutSeconds = 5;
        c.openTimeoutSeconds = 5;
        c.stallTimeoutSeconds = 10;
        c.linkSecret = LINK_SECRET;
        tweak.accept(c);
        RelayServer r = new RelayServer(c);
        r.start();
        return r;
    }

    private TestPeerApp app(String name) throws IOException {
        return closeLater(new TestPeerApp(name, tempRoot));
    }

    private <T extends AutoCloseable> T closeLater(T c) {
        closeables.add(c);
        return c;
    }

    private void online(TestPeerApp... apps) throws InterruptedException {
        for (TestPeerApp x : apps) x.connectRelay(relayPort);
        for (TestPeerApp x : apps) waitUntil(x.internet::isRelayOnline, 10_000, x.name + " phải online");
    }

    /** x nhập mã của y, y chấp nhận -> trả về PeerInfo của y phía x */
    private PeerInfo connect(TestPeerApp x, TestPeerApp y) throws Exception {
        PeerInfo peer = x.internet.connectByCode(y.internet.createInvite().code());
        waitUntil(() -> y.connections.isConnected(x.id()), 5000, "kết nối 2 chiều");
        return peer;
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static void waitForEnd(DownloadJob job, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (job.isActive() || job.getState() == DownloadJob.State.NEW) {
            if (System.currentTimeMillis() > deadline) fail("Hết thời gian chờ: tải chưa kết thúc, trạng thái " + job.getState());
            Thread.sleep(25);
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("Hết thời gian chờ: " + message);
            Thread.sleep(25);
        }
    }

    private static SSLContext sslTrusting(Path keystore, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, password.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }
}
