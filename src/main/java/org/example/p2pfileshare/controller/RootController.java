package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import javafx.stage.Stage;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlProtocol;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.network.relay.IdentityKey;
import org.example.p2pfileshare.network.transport.TransportRouter;
import org.example.p2pfileshare.service.ConnectionService;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.HistoryService;
import org.example.p2pfileshare.service.InternetModeService;
import org.example.p2pfileshare.service.PeerHealthMonitor;
import org.example.p2pfileshare.service.PeerService;
import org.example.p2pfileshare.service.SearchService;
import org.example.p2pfileshare.util.AppConfig;
import org.example.p2pfileshare.util.NetworkSettings;
import org.example.p2pfileshare.util.PortFinder;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RootController {

    // Include các tab con
    @FXML
    private PeerTabController peerTabController;
    @FXML
    private ShareTabController shareTabController;
    @FXML
    private SearchTabController searchTabController;
    @FXML
    private HistoryTabController historyTabController;
    @FXML
    private IncomingConnectionController incomingConnectionTabController;

    @FXML
    private TabPane mainTabPane;
    @FXML
    private Label globalStatusLabel;
    @FXML
    private Label userNameLabel; // hiển thị tên người dùng trên status bar

    // Services
    private PeerService peerService;
    private FileShareService fileShareService;
    private SearchService searchService;
    private HistoryService historyService;
    private volatile boolean shuttingDown = false;

    // Control channel
    private ControlServer controlServer;
    private ControlClient controlClient;
    private ConnectionService connectionService;

    // Tầng truyền thông (LAN TCP / Internet relay), heartbeat và Internet Mode
    private IdentityKey identity;
    private final TransportRouter transportRouter = new TransportRouter();
    private PeerHealthMonitor healthMonitor;
    private InternetModeService internetMode;

    // Peer info
    private String myPeerId;
    private String myName; // displayName
    // Cổng còn trống trong khoảng cố định (6000–6999 cho file, 7000–7999 cho điều khiển)
    private final int FILE_PORT = PortFinder.findFreePort(6000, 6999);
    private final int CONTROL_PORT = PortFinder.findFreePort(7000, 7999);
    private static final String KEY_PEER_NAME = "peer_display_name";

    @FXML
    public void initialize() {
        // 1) Hỏi tên peer
        myName = loadOrAskPeerName();
        // Khoá Ed25519 mới mỗi lần mở app; peerId = SHA-256(khoá công khai), relay xác thực được bằng chữ ký
        identity = IdentityKey.generate();
        myPeerId = identity.peerId();
        historyService = new HistoryService();

        // 2) Khởi tạo service
        peerService = new PeerService(myPeerId, myName, FILE_PORT, CONTROL_PORT);
        peerService.start(); // gọi hàm này để người khác tìm thấy mình

        fileShareService = new FileShareService(FILE_PORT, historyService);
        fileShareService.setMyDisplayName(myName); // Truyền tên hiển thị vào FileShareService
        fileShareService.setMyPeerId(myPeerId);    // gửi kèm khi tải file để peer kia kiểm tra quyền
        searchService = new SearchService(fileShareService);

        // 3) ControlClient để gửi request CONNECT (gửi peerId, displayName)
        controlClient = new ControlClient(myPeerId, myName);
        controlClient.setMyPorts(CONTROL_PORT, FILE_PORT);
        // peer LAN đi TCP trực tiếp, peer Internet đi qua relay (chọn theo PeerInfo.Route)
        controlClient.setTransport(transportRouter);
        fileShareService.setTransport(transportRouter);

        // 4) ControlServer để nhận CONNECT_REQUEST: luồng mạng chờ người dùng bấm Chấp nhận / Từ chối
        controlServer = new ControlServer(CONTROL_PORT, fromPeer -> {
            AtomicBoolean accepted = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(1);

            Platform.runLater(() -> {
                try {
                    // hộp thoại tự đóng khi hết giờ, khớp với thời gian bên gửi chờ
                    accepted.set(Dialogs.askWithTimeout(mainTabPane,
                            "🔗 Yêu cầu kết nối",
                            "Peer \"" + fromPeer + "\" muốn kết nối!",
                            "Hai máy sẽ xem và tải được file chia sẻ của nhau. Bạn có đồng ý kết nối không?",
                            "Chấp nhận",
                            ControlProtocol.CONNECT_DECISION_SECONDS));
                } finally {
                    latch.countDown();
                }
            });

            try {
                if (!latch.await(ControlProtocol.CONNECT_DECISION_SECONDS + 5, TimeUnit.SECONDS)) return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return accepted.get();
        });
        connectionService = new ConnectionService(controlClient, controlServer);

        // inject FileShareService để phục vụ LIST_FILES
        controlServer.setFileShareService(fileShareService);
        // Chỉ peer đã được chấp nhận kết nối (và kết nối đến đúng đường đã chấp nhận) mới tải được file
        fileShareService.setAccessChecker(controlServer::isAcceptedFrom);

        // Heartbeat tới các peer đang kết nối: phát hiện mất mạng, tự tải tiếp khi peer quay lại
        healthMonitor = new PeerHealthMonitor(connectionService, controlClient,
                NetworkSettings.peerHeartbeatSeconds() * 1000L, NetworkSettings.peerTimeoutSeconds() * 1000L);
        healthMonitor.setOnReachable(fileShareService::retryWaitingDownloads);
        healthMonitor.start();

        internetMode = new InternetModeService(identity, () -> myName, controlServer, fileShareService,
                connectionService, transportRouter, healthMonitor);

        // Thư mục chia sẻ thay đổi -> báo cho các peer đang xem kho file của mình tự tải lại danh sách
        fileShareService.addFilesChangedListener(() -> {
            for (PeerInfo p : controlServer.getAcceptedPeers(peerService.getDiscoveredPeers())) {
                controlClient.sendFilesChanged(p);
            }
        });

        // A. Khi có người hỏi mình (SEARCH_REQ) -> Tìm file local và trả lời.
        //    Người hỏi chắc chắn đã được mình chấp nhận nên đã có địa chỉ, không cần quét LAN.
        controlServer.setOnSearchRequestReceived((senderId, keyword) -> {
            PeerInfo senderInfo = controlServer.getAcceptedPeerInfo(senderId);
            if (senderInfo == null) {
                senderInfo = controlServer.getAcceptedPeers(peerService.getDiscoveredPeers()).stream()
                        .filter(p -> senderId.equals(p.getPeerId())).findFirst().orElse(null);
            }
            if (senderInfo == null) {
                System.out.println("[Root] Không tìm thấy peer " + senderId + " để trả kết quả tìm kiếm");
                return;
            }

            // Gộp mọi file tìm được vào 1 gói tin
            String payload = searchService.buildResponsePayload(keyword);
            if (!payload.isEmpty()) {
                controlClient.sendSearchResponse(senderInfo, payload);
            }
        });

        // B. Khi nhận được kết quả (SEARCH_RES) từ peer mình đang kết nối tới -> Đẩy vào SearchTab
        controlServer.setOnSearchResultReceived((senderId, data) -> {
            PeerInfo senderInfo = controlClient.getConnectedPeer(senderId);
            if (senderInfo != null && searchTabController != null) {
                searchTabController.onReceiveSearchResult(senderInfo, data);
            }
        });

        // Peer kia ngắt kết nối -> báo cho người dùng (cập nhật bảng/tab do PeerTabController tự lắng nghe)
        connectionService.addListener(new ConnectionService.Listener() {
            @Override
            public void onConnected(PeerInfo peer, boolean incoming) {
                if (incoming) Platform.runLater(() -> globalStatusLabel.setText("Đã kết nối với " + peer.getName()));
            }

            @Override
            public void onDisconnected(String peerId, boolean byRemote) {
                if (!byRemote || shuttingDown) return;
                // tên lấy từ bảng peer vì lúc này peer đã bị xoá khỏi danh sách kết nối
                Platform.runLater(() -> {
                    String name = peerTabController != null ? peerTabController.nameOf(peerId) : peerId;
                    globalStatusLabel.setText("Đã ngắt kết nối: " + name);
                    Dialogs.error(mainTabPane, "Thông báo", "Đã ngắt kết nối",
                            "Peer \"" + name + "\" đã ngắt kết nối với bạn.");
                });
            }
        });

        controlServer.start();
        System.out.println("[Root] ControlServer started at port " + CONTROL_PORT);

        // Inject service vào UI controllers
        if (peerTabController != null)
            peerTabController.init(peerService, fileShareService, controlClient, controlServer, connectionService,
                    internetMode, healthMonitor, globalStatusLabel);

        if (shareTabController != null)
            shareTabController.init(fileShareService, globalStatusLabel);

        if (searchTabController != null)
            searchTabController.init(fileShareService, controlClient);

        if (historyTabController != null)
            historyTabController.init(historyService, globalStatusLabel);

        if (incomingConnectionTabController != null)
            incomingConnectionTabController.init(peerService, controlServer, connectionService);

        globalStatusLabel.setText("Sẵn sàng");
        // Hiển thị tên người dùng lên status bar (nếu Label đã được inject)
        if (userNameLabel != null && myName != null) {
            userNameLabel.setText(myName);
        }
        this.fileShareService.startServer();
        Platform.runLater(() -> {
            Stage stage = (Stage) mainTabPane.getScene().getWindow();
            stage.setOnCloseRequest(event -> {
                System.out.println("[root] Window close requested");
                event.consume(); // chặn đóng ngay, shutdownGracefully sẽ thoát app sau khi báo cho các peer
                onExit();
            });
        });
    }

    // ================= MENU =================

    @FXML
    private void onChooseShareFolder() {
        globalStatusLabel.setText("Hãy vào tab 'Tài liệu chia sẻ' để chọn thư mục.");
        mainTabPane.getSelectionModel().select(1);
    }

    @FXML
    private void onExit() {
        System.out.println("[root] Exiting application...");
        shutdownGracefully();
    }

    @FXML
    private void onAbout() {
        Dialogs.info(mainTabPane,
                "Giới thiệu | About",
                "Ứng dụng truyền file P2P - JavaFX\nP2P File Sharing Application",
                "🇻🇳 Tiếng Việt:\n"
                        + "Ứng dụng Desktop chia sẻ file theo mô hình Peer-to-Peer (P2P).\n"
                        + "• Chế độ LAN: các máy cùng mạng tìm nhau bằng UDP và truyền file TCP trực tiếp.\n"
                        + "• Chế độ Internet: 2 máy ở 2 mạng khác nhau (Wi-Fi, 4G/5G, sau NAT/CGNAT) kết nối "
                        + "qua Relay Server bằng WebSocket bảo mật (wss). Đây là mô hình P2P có hỗ trợ chuyển tiếp: "
                        + "relay chỉ chuyển tiếp dữ liệu theo luồng, không lưu file. TLS bảo vệ đoạn máy ↔ relay, "
                        + "chưa có mã hoá đầu cuối.\n"
                        + "Truyền theo chunk 1 MiB, kiểm tra SHA-256, tạm dừng / tiếp tục / tải tiếp sau khi mất mạng.\n\n"
                        + "🇺🇸 English:\n"
                        + "Peer-to-Peer file sharing desktop application. LAN mode: UDP discovery and direct TCP transfer. "
                        + "Internet mode: peers on different networks (behind NAT/CGNAT) connect through a relay server "
                        + "over secure WebSocket (wss). This is relay-assisted P2P: the relay forwards streams and stores "
                        + "no files; TLS protects each peer-relay hop (no end-to-end encryption yet).\n\n"
                        + "Version: 3.0");
    }

    @FXML
    private void onChangeName() {
        // Dialog tự lưu tên vào AppConfig và báo tên mới cho các peer đang kết nối,
        // callback cập nhật tên ở mọi service của mình
        var result = ChangeNameController.showDialog(
                mainTabPane.getScene().getWindow(),
                myName,
                controlClient,
                controlServer,
                peerService,
                newName -> {
                    myName = newName;
                    userNameLabel.setText(myName);
                    peerService.setMyDisplayName(myName);
                    fileShareService.setMyDisplayName(myName);
                    controlClient.setMyDisplayName(myName);
                });
        if (result.isPresent()) {
            if (peerTabController != null) peerTabController.refresh();
            globalStatusLabel.setText("Đã đổi tên thành: " + myName);
        }
    }

    private String loadOrAskPeerName() {
        // 1) Load tên đã lưu
        String saved = AppConfig.load(KEY_PEER_NAME);
        if (saved != null && !saved.isBlank()) {
            return saved;
        }

        // 2) Chưa có → hiển thị dialog nhập tên
        return ChangeNameController.showForLogin(null);
    }

    private void shutdownGracefully() {
        if (shuttingDown)
            return;
        shuttingDown = true;

        if (globalStatusLabel != null)
            globalStatusLabel.setText("Đang ngắt kết nối...");

        // Mọi peer đang kết nối (2 chiều, không trùng)
        List<PeerInfo> connected = connectionService.getConnectedPeers();

        // Gửi thông báo ở thread nền để không treo giao diện khi peer đã tắt máy
        Thread t = new Thread(() -> {
            for (PeerInfo p : connected) {
                try {
                    connectionService.disconnect(p);
                } catch (Exception e) {
                    System.out.println("[Shutdown] Failed notify " + p.getPeerId() + ": " + e.getMessage());
                }
            }

            // Dừng các server; lỗi ở bước này không được chặn việc thoát app
            try {
                internetMode.close();
            } catch (Exception ignored) {
            }
            try {
                healthMonitor.close();
            } catch (Exception ignored) {
            }
            try {
                controlServer.stop();
            } catch (Exception ignored) {
            }
            try {
                fileShareService.stopServer();
            } catch (Exception ignored) {
            }
            try {
                peerService.stop();
            } catch (Exception ignored) {
            }

            Platform.runLater(() -> {
                if (mainTabPane.getScene() != null)
                    mainTabPane.getScene().getWindow().hide();
                Platform.exit(); // dừng JavaFX runtime
                System.exit(0);
            });
        }, "shutdown-thread");
        t.setDaemon(true);
        t.start();
    }
}
