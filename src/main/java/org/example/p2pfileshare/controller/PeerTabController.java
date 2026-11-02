package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.AnchorPane;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlProtocol;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.ConnectionService;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.PeerService;
import org.example.p2pfileshare.util.AppConfig;
import org.example.p2pfileshare.util.WindowsFirewall;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PeerTabController {

    private PeerService peerService;
    private FileShareService fileShareService;
    private ControlClient controlClient;
    private ConnectionService connectionService;
    private Label globalStatusLabel;

    // peer đang chờ bên kia bấm Chấp nhận (để quét LAN giữa chừng không làm mất trạng thái "Đang kết nối...")
    private final Set<String> pendingPeerIds = new HashSet<>();

    // tab kết nối đang mở và controller của nó, key = peerId (để đổi tên, chuyển tin nhắn hoặc đóng tab)
    private final Map<String, ConnectedPeerController> connectedControllers = new HashMap<>();
    private final Map<String, Tab> connectedTabs = new HashMap<>();

    @FXML private TableView<PeerInfo> peerTable;
    @FXML private TableColumn<PeerInfo, String> colPeerName;
    @FXML private TableColumn<PeerInfo, String> colPeerIp;
    @FXML private TableColumn<PeerInfo, Number> colPeerPort;
    @FXML private TableColumn<PeerInfo, PeerInfo.ConnectionState> colPeerStatus;
    @FXML private Label peerStatusLabel;
    @FXML private Button firewallButton;

    // người dùng đã chọn "không" ở hộp thoại tường lửa -> không hỏi lại mỗi lần mở app
    private static final String FIREWALL_SKIP_KEY = "firewall.skipPrompt";

    private final ObservableList<PeerInfo> peerList = FXCollections.observableArrayList();

    public void init(PeerService peerService,
                     FileShareService fileShareService,
                     ControlClient controlClient,
                     ControlServer controlServer,
                     ConnectionService connectionService,
                     Label globalStatusLabel) {
        // nhận các service bên ngoài truyền vào
        this.peerService = peerService;
        this.fileShareService = fileShareService;
        this.controlClient = controlClient;
        this.connectionService = connectionService;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        onScanPeers();
        checkFirewallOnStartup();

        // Mọi thay đổi kết nối (mình bấm hay peer kia bấm, ở tab nào) đều đi qua đây -> bảng và tab luôn đúng
        connectionService.addListener(new ConnectionService.Listener() {
            @Override
            public void onConnected(PeerInfo peer, boolean incoming) {
                Platform.runLater(() -> handleConnected(peer, incoming));
            }

            @Override
            public void onDisconnected(String peerId, boolean byRemote) {
                Platform.runLater(() -> handleDisconnected(peerId, byRemote));
            }
        });

        // peer đổi tên -> quét lại để cập nhật bảng
        controlServer.setpeerUpdateName(() -> Platform.runLater(this::onScanPeers));
        // tin nhắn hệ thống (vd. kho file thay đổi) -> chuyển cho tab kết nối tương ứng
        controlServer.setOnSystemMessageReceived((senderId, msg) ->
                Platform.runLater(() -> routeSystemMessage(senderId, msg)));
        controlServer.setOnRenameTab(this::renameConnectedTab);
    }

    private void setupTable() {
        colPeerName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colPeerIp.setCellValueFactory(new PropertyValueFactory<>("ip"));
        colPeerPort.setCellValueFactory(new PropertyValueFactory<>("fileServerPort"));

        // Hiển thị trạng thái kết nối thực tế thay vì "Online"
        colPeerStatus.setCellValueFactory(new PropertyValueFactory<>("connectionState"));
        colPeerStatus.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(PeerInfo.ConnectionState state, boolean empty) {
                super.updateItem(state, empty);
                if (empty || state == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                switch (state) {
                    case NOT_CONNECTED -> {
                        setText("Chưa kết nối");
                        setStyle("-fx-text-fill: #666666;");
                    }
                    case PENDING -> {
                        setText("Đang kết nối...");
                        setStyle("-fx-text-fill: #ff9800; -fx-font-weight: bold;");
                    }
                    case CONNECTED -> {
                        setText("Đã kết nối");
                        setStyle("-fx-text-fill: #4caf50; -fx-font-weight: bold;");
                    }
                    case REJECTED -> {
                        setText("Bị từ chối");
                        setStyle("-fx-text-fill: #f44336;");
                    }
                }
            }
        });

        peerTable.setItems(peerList);
    }

    // QUÉT PEER
    @FXML
    private void onScanPeers() {
        peerStatusLabel.setText("Đang quét...");
        peerTable.setDisable(true);

        Task<List<PeerInfo>> task = new Task<>() {
            @Override
            protected List<PeerInfo> call() {
                return peerService.scanPeers();
            }
        };

        task.setOnSucceeded(e -> {
            List<PeerInfo> scanned = new ArrayList<>(task.getValue());
            // peer đang kết nối nhưng lần quét này không thấy (mạng chặn multicast...) vẫn giữ trong bảng
            for (PeerInfo c : connectionService.getConnectedPeers()) {
                if (scanned.stream().noneMatch(p -> p.getPeerId().equals(c.getPeerId()))) scanned.add(c);
            }

            peerList.setAll(scanned); // đổ data mới vào table
            refreshConnectionStates();
            peerStatusLabel.setText("Đã tìm thấy " + scanned.size() + " peer");
            if (globalStatusLabel != null) globalStatusLabel.setText("Quét LAN xong");
            peerTable.setDisable(false);
        });

        task.setOnFailed(e -> {
            peerStatusLabel.setText("Lỗi khi quét");
            peerTable.setDisable(false);
        });

        startDaemon(task, "scan-peers");
    }

    // THÊM PEER THEO IP (khi Wi-Fi/router chặn quét tự động)
    @FXML
    private void onAddPeerByIp() {
        TextInputDialog dialog = new TextInputDialog();
        if (peerTable.getScene() != null) dialog.initOwner(peerTable.getScene().getWindow());
        dialog.setTitle("Thêm peer theo IP");
        dialog.setHeaderText("Nhập địa chỉ IP của máy bên kia (xem bằng lệnh ipconfig)");
        dialog.setContentText("IP:");
        String host = dialog.showAndWait().map(String::trim).orElse("");
        if (host.isEmpty()) return;

        peerStatusLabel.setText("Đang hỏi " + host + "...");
        Task<PeerInfo> task = new Task<>() {
            @Override
            protected PeerInfo call() throws Exception {
                return peerService.addPeerByIp(host);
            }
        };
        task.setOnSucceeded(e -> {
            PeerInfo peer = task.getValue();
            if (peer == null) {
                peerStatusLabel.setText("Không có phản hồi từ " + host);
                Dialogs.error(peerTable, "Không tìm thấy peer", "Không có phản hồi từ " + host,
                        "Kiểm tra máy kia đã mở app, hai máy ping được nhau, và đã bấm 🛡 Tường lửa trên cả 2 máy.");
                return;
            }
            PeerInfo row = upsertPeer(peer);
            refreshConnectionStates();
            peerTable.getSelectionModel().select(row);
            peerStatusLabel.setText("Đã thêm " + peer.getName() + " (" + peer.getIp() + ")");
        });
        task.setOnFailed(e -> {
            peerStatusLabel.setText("Địa chỉ không hợp lệ: " + host);
            Dialogs.error(peerTable, "Lỗi", "Địa chỉ không hợp lệ", host);
        });
        startDaemon(task, "add-peer-ip");
    }

    // TƯỜNG LỬA
    private void checkFirewallOnStartup() {
        if (!WindowsFirewall.isWindows()) {
            firewallButton.setVisible(false);
            firewallButton.setManaged(false);
            return;
        }
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return WindowsFirewall.isConfigured();
            }
        };
        task.setOnSucceeded(e -> {
            updateFirewallButton(task.getValue());
            if (task.getValue() || "true".equals(AppConfig.load(FIREWALL_SKIP_KEY))) return;
            boolean ok = Dialogs.ask(peerTable,
                    "🛡 Tường lửa Windows",
                    "Cho phép P2PFileShare qua tường lửa?",
                    "Nếu không, máy khác trong LAN sẽ không quét thấy và không kết nối được tới máy này. "
                            + "Windows sẽ hỏi quyền Admin (chỉ cần làm 1 lần).",
                    "Cho phép");
            if (ok) {
                onConfigureFirewall();
            } else {
                AppConfig.save(FIREWALL_SKIP_KEY, "true");
                peerStatusLabel.setText("Chưa mở tường lửa, bấm 🛡 Tường lửa khi cần");
            }
        });
        startDaemon(task, "firewall-check");
    }

    @FXML
    private void onConfigureFirewall() {
        firewallButton.setDisable(true);
        peerStatusLabel.setText("Đang cấu hình tường lửa (chấp nhận hộp thoại quyền Admin)...");
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return WindowsFirewall.configure();
            }
        };
        task.setOnSucceeded(e -> {
            firewallButton.setDisable(false);
            updateFirewallButton(task.getValue());
            if (task.getValue()) {
                AppConfig.remove(FIREWALL_SKIP_KEY);
                peerStatusLabel.setText("Đã cho phép P2PFileShare qua tường lửa");
                onScanPeers();
            } else {
                peerStatusLabel.setText("Chưa cấu hình được tường lửa");
                Dialogs.error(peerTable, "Tường lửa", "Chưa cấu hình được tường lửa",
                        "Có thể bạn đã bấm No ở hộp thoại quyền Admin. Hãy thử lại, "
                                + "hoặc mở Windows Security → Firewall và cho phép Java.");
            }
        });
        startDaemon(task, "firewall-config");
    }

    private void updateFirewallButton(boolean configured) {
        firewallButton.setText(configured ? "🛡 Tường lửa ✓" : "🛡 Tường lửa ⚠");
        firewallButton.setTooltip(new Tooltip(configured
                ? "Đã cho phép P2PFileShare qua Windows Firewall"
                : "Tường lửa có thể đang chặn, bấm để cho phép"));
    }

    // sử dụng để refresh từ bên ngoài
    public void refresh() {
        onScanPeers();
    }


    // KẾT NỐI PEER
    @FXML
    private void onConnectPeer() {
        PeerInfo peer = peerTable.getSelectionModel().getSelectedItem();
        if (peer == null) {
            Dialogs.error(peerTable, "Cảnh báo", "Hãy chọn peer trước!", "Vui lòng chọn peer để kết nối.");
            return;
        }
        String peerId = peer.getPeerId();

        // Đã kết nối (do mình hoặc peer kia gửi yêu cầu) -> mở / chuyển sang tab của peer đó
        if (connectionService.isConnected(peerId)) {
            PeerInfo info = connectionService.getConnectedPeer(peerId);
            openConnectedTab(info != null ? info : peer, true);
            peerStatusLabel.setText("Đã kết nối với " + peer.getName());
            return;
        }
        if (pendingPeerIds.contains(peerId)) {
            peerStatusLabel.setText("Đang chờ " + peer.getName() + " chấp nhận...");
            return;
        }

        pendingPeerIds.add(peerId);
        refreshConnectionStates();
        peerStatusLabel.setText("Đang gửi yêu cầu kết nối, chờ " + peer.getName() + " chấp nhận...");

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                // thành công -> listener mở tab và cập nhật bảng
                return connectionService.connect(peer);
            }
        };

        task.setOnSucceeded(e -> {
            pendingPeerIds.remove(peerId);
            if (task.getValue()) {
                peerStatusLabel.setText("Kết nối thành công với " + peer.getName());
                refreshConnectionStates();
            } else {
                markRejected(peerId);
                peerStatusLabel.setText("Peer từ chối hoặc không phản hồi");
                Dialogs.error(peerTable, "Kết nối thất bại", "Peer từ chối hoặc không phản hồi",
                        "Peer đã bấm Từ chối, không trả lời trong " + ControlProtocol.CONNECT_DECISION_SECONDS
                                + " giây, hoặc đã tắt ứng dụng. Hãy thử kết nối lại.");
            }
        });
        task.setOnFailed(e -> {
            pendingPeerIds.remove(peerId);
            markRejected(peerId);
            peerStatusLabel.setText("Lỗi khi kết nối");
        });

        startDaemon(task, "connect-peer");
    }

    // NGẮT KẾT NỐI
    @FXML
    private void onDisconnectPeer() {
        PeerInfo peer = peerTable.getSelectionModel().getSelectedItem();
        if (peer == null) {
            Dialogs.error(peerTable, "Cảnh báo", "Hãy chọn peer trước!", "Vui lòng chọn peer để ngắt kết nối.");
            return;
        }
        // trạng thái thật lấy từ ConnectionService (kể cả khi peer kia là bên gửi yêu cầu)
        if (!connectionService.isConnected(peer.getPeerId())) {
            refreshConnectionStates();
            peerStatusLabel.setText("Chưa kết nối đến peer này");
            return;
        }
        confirmAndDisconnect(peer);
    }

    /** Hỏi xác nhận rồi ngắt cả 2 chiều (dùng chung cho tab Mạng LAN và tab kết nối) */
    void confirmAndDisconnect(PeerInfo peer) {
        boolean confirmed = Dialogs.confirm(peerTable,
                "🔌 Ngắt kết nối",
                "Ngắt kết nối với " + peer.getName() + "?",
                "Cả hai máy sẽ không xem và tải file của nhau được nữa. Mọi tải xuống đang chạy sẽ dừng.",
                "Ngắt kết nối");
        if (!confirmed) return;

        PeerInfo target = connectionService.getConnectedPeer(peer.getPeerId());
        PeerInfo contact = target != null ? target : peer;
        peerStatusLabel.setText("Đang ngắt kết nối với " + peer.getName() + "...");

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                // listener cập nhật bảng + tab ngay khi ngắt xong
                return connectionService.disconnect(contact);
            }
        };
        task.setOnSucceeded(e -> peerStatusLabel.setText(Boolean.TRUE.equals(task.getValue())
                ? "Đã ngắt kết nối với " + peer.getName()
                : "Đã ngắt kết nối (peer không phản hồi)"));
        task.setOnFailed(e -> peerStatusLabel.setText("Đã ngắt kết nối (lỗi khi báo cho peer)"));
        startDaemon(task, "disconnect-peer");
    }

    // ---------------- cập nhật UI theo ConnectionService (JavaFX thread) ----------------

    private void handleConnected(PeerInfo peer, boolean incoming) {
        pendingPeerIds.remove(peer.getPeerId());
        upsertPeer(peer);
        refreshConnectionStates();
        if (incoming) {
            // peer kia gửi yêu cầu, mình đã chấp nhận -> mình cũng có tab xem file của peer đó
            openConnectedTab(peer, false);
            peerStatusLabel.setText(peer.getName() + " đã kết nối với bạn");
        } else {
            openConnectedTab(peer, true);
        }
    }

    private void handleDisconnected(String peerId, boolean byRemote) {
        ConnectedPeerController ctrl = connectedControllers.get(peerId);
        if (ctrl != null) ctrl.onPeerDisconnected();
        refreshConnectionStates();
        if (byRemote) peerStatusLabel.setText("Peer " + nameOf(peerId) + " đã ngắt kết nối");
    }

    /** Trạng thái mỗi dòng trong bảng lấy từ nguồn chuẩn (ConnectionService), không giữ giá trị cũ */
    private void refreshConnectionStates() {
        for (PeerInfo p : peerList) {
            if (pendingPeerIds.contains(p.getPeerId())) {
                p.setConnectionState(PeerInfo.ConnectionState.PENDING);
            } else if (connectionService.isConnected(p.getPeerId())) {
                p.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
            } else if (p.getConnectionState() != PeerInfo.ConnectionState.REJECTED) {
                p.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
            }
        }
        peerTable.refresh();
    }

    private void markRejected(String peerId) {
        for (PeerInfo p : peerList) {
            if (p.getPeerId().equals(peerId) && !connectionService.isConnected(peerId)) {
                p.setConnectionState(PeerInfo.ConnectionState.REJECTED);
            }
        }
        peerTable.refresh();
    }

    /** Thêm peer vào bảng nếu chưa có (vd. peer kết nối tới mình mà mình chưa quét thấy), trả về dòng trong bảng */
    private PeerInfo upsertPeer(PeerInfo peer) {
        for (int i = 0; i < peerList.size(); i++) {
            if (peerList.get(i).getPeerId().equals(peer.getPeerId())) {
                peerList.set(i, peer);
                return peer;
            }
        }
        peerList.add(peer);
        return peer;
    }

    /** Tên hiển thị của peer (bảng / tab đang mở), peerId nếu không biết */
    public String nameOf(String peerId) {
        for (PeerInfo p : peerList) {
            if (p.getPeerId().equals(peerId)) return p.getName();
        }
        Tab tab = connectedTabs.get(peerId);
        return tab != null ? tab.getText().replaceFirst("^Kết nối: ", "") : peerId;
    }

    private TabPane findTabPane() {
        Node n = peerTable;
        while (n != null && !(n instanceof TabPane)) {
            n = n.getParent();
        }
        return (TabPane) n;
    }

    /**
     * Mở tab xem file của peer. Tab cũ còn kết nối thì giữ nguyên (không làm hỏng tải xuống đang chạy),
     * tab cũ đã ngắt thì thay bằng tab mới.
     */
    private void openConnectedTab(PeerInfo peer, boolean select) {
        TabPane tabPane = findTabPane();
        if (tabPane == null) {
            Dialogs.error(peerTable, "Lỗi", null, "Không tìm thấy TabPane để mở tab mới.");
            return;
        }

        Tab oldTab = connectedTabs.get(peer.getPeerId());
        ConnectedPeerController oldCtrl = connectedControllers.get(peer.getPeerId());
        if (oldTab != null && tabPane.getTabs().contains(oldTab) && oldCtrl != null && !oldCtrl.isDisconnected()) {
            if (select) tabPane.getSelectionModel().select(oldTab);
            return;
        }
        // tab của lần kết nối trước (đã ngắt) -> đóng để không bị trùng
        connectedTabs.remove(peer.getPeerId());
        connectedControllers.remove(peer.getPeerId());
        if (oldTab != null) {
            if (oldCtrl != null) oldCtrl.dispose();
            tabPane.getTabs().remove(oldTab);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConnectedPeerTab.fxml"));
            AnchorPane content = loader.load();

            // lấy controller của tab con để cài đặt
            ConnectedPeerController controller = loader.getController();
            controller.init(peer, controlClient, fileShareService, this::confirmAndDisconnect);

            Tab tab = new Tab("Kết nối: " + peer.getName());
            tab.setContent(content);
            tab.setUserData(controller);
            tab.setClosable(true);
            connectedTabs.put(peer.getPeerId(), tab);
            connectedControllers.put(peer.getPeerId(), controller);

            // khi tab đóng thì xoá mapping (chỉ khi map vẫn trỏ tới đúng tab này). Đóng tab không ngắt kết nối.
            tab.setOnClosed(ev -> {
                connectedControllers.remove(peer.getPeerId(), controller);
                connectedTabs.remove(peer.getPeerId(), tab);
                controller.dispose();
            });

            tabPane.getTabs().add(tab);
            if (select) tabPane.getSelectionModel().select(tab);

        } catch (IOException ex) {
            Dialogs.error(peerTable, "Lỗi", "Lỗi tải UI tab kết nối:", ex.getMessage());
        }
    }

    private void routeSystemMessage(String senderPeerId, String message) {
        ConnectedPeerController targetController = connectedControllers.get(senderPeerId);
        if (targetController != null) {
            targetController.receivedMessage(message);
        } else {
            System.out.println("Nhận tin từ " + senderPeerId + " nhưng không tìm thấy tab nào đang mở.");
        }
    }

    // đổi tên tab và update trên UI con
    public void renameConnectedTab(String peerId, String newName) {
        Platform.runLater(() -> {
            PeerInfo connected = connectionService.getConnectedPeer(peerId);
            if (connected != null) connected.setDisplayName(newName);

            Tab tab = connectedTabs.get(peerId);
            if (tab != null) tab.setText("Kết nối: " + newName);

            ConnectedPeerController ctrl = connectedControllers.get(peerId);
            if (ctrl != null) ctrl.updatePeerDisplayName(newName);
            peerTable.refresh();
        });
    }

    private static void startDaemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}