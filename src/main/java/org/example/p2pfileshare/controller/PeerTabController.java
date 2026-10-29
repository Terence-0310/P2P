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
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.PeerService;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PeerTabController {

    private PeerService peerService;
    private FileShareService fileShareService;
    private ControlClient controlClient;
    private Label globalStatusLabel;

    // tab kết nối đang mở và controller của nó, key = peerId (để đổi tên, chuyển tin nhắn hoặc đóng tab)
    private final Map<String, ConnectedPeerController> connectedControllers = new HashMap<>();
    private final Map<String, Tab> connectedTabs = new HashMap<>();

    @FXML private TableView<PeerInfo> peerTable;
    @FXML private TableColumn<PeerInfo, String> colPeerName;
    @FXML private TableColumn<PeerInfo, String> colPeerIp;
    @FXML private TableColumn<PeerInfo, Number> colPeerPort;
    @FXML private TableColumn<PeerInfo, PeerInfo.ConnectionState> colPeerStatus;
    @FXML private Label peerStatusLabel;

    private final ObservableList<PeerInfo> peerList = FXCollections.observableArrayList();

    public void init(PeerService peerService,
                     FileShareService fileShareService,
                     ControlClient controlClient,
                     ControlServer controlServer,
                     Label globalStatusLabel) {
        // nhận các service bên ngoài truyền vào
        this.peerService = peerService;
        this.fileShareService = fileShareService;
        this.controlClient = controlClient;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        onScanPeers();

        // peer đổi tên / ngắt kết nối với mình -> quét lại để cập nhật bảng
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
            List<PeerInfo> scanned = task.getValue();
            // trạng thái lấy từ ControlClient (nguồn chuẩn), không phụ thuộc lần quét trước
            for (PeerInfo p : scanned) {
                p.setConnectionState(controlClient.isConnectedTo(p.getPeerId())
                        ? PeerInfo.ConnectionState.CONNECTED
                        : PeerInfo.ConnectionState.NOT_CONNECTED);
            }

            peerList.setAll(scanned); // đổ data mới vào table
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

        // Đã kết nối và tab còn mở -> chỉ cần chuyển sang tab đó
        if (controlClient.isConnectedTo(peer.getPeerId()) && selectExistingTab(peer.getPeerId())) {
            peerStatusLabel.setText("Đã kết nối với " + peer.getName());
            return;
        }

        peerStatusLabel.setText("Đang gửi yêu cầu kết nối, chờ " + peer.getName() + " chấp nhận...");
        peer.setConnectionState(PeerInfo.ConnectionState.PENDING);
        peerTable.refresh();

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return controlClient.sendConnectRequest(peer);
            }
        };

        task.setOnSucceeded(e -> {
            if (task.getValue()) {
                peer.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
                peerStatusLabel.setText("Kết nối thành công!");
                openConnectedTab(peer);
            } else {
                peer.setConnectionState(PeerInfo.ConnectionState.REJECTED);
                peerStatusLabel.setText("Peer từ chối hoặc không phản hồi");
                Dialogs.error(peerTable, "Kết nối thất bại", "Peer từ chối hoặc không phản hồi",
                        "Peer đã bấm Từ chối, không trả lời trong 60 giây, hoặc đã tắt ứng dụng. Hãy quét LAN lại rồi thử lại.");
            }
            peerTable.refresh();
        });

        startDaemon(task, "connect-peer");
    }

    private TabPane findTabPane() {
        Node n = peerTable;
        while (n != null && !(n instanceof TabPane)) {
            n = n.getParent();
        }
        return (TabPane) n;
    }

    private boolean selectExistingTab(String peerId) {
        Tab tab = connectedTabs.get(peerId);
        TabPane tabPane = findTabPane();
        if (tab == null || tabPane == null || !tabPane.getTabs().contains(tab)) return false;
        tabPane.getSelectionModel().select(tab);
        return true;
    }

    // mở tab riêng
    private void openConnectedTab(PeerInfo peer) {
        TabPane tabPane = findTabPane();
        if (tabPane == null) {
            Dialogs.error(peerTable, "Lỗi", null, "Không tìm thấy TabPane để mở tab mới.");
            return;
        }

        // tab cũ của lần kết nối trước (đã ngắt) -> đóng để không bị trùng
        Tab oldTab = connectedTabs.remove(peer.getPeerId());
        connectedControllers.remove(peer.getPeerId());
        if (oldTab != null) {
            if (oldTab.getUserData() instanceof ConnectedPeerController oldCtrl) oldCtrl.dispose();
            tabPane.getTabs().remove(oldTab);
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConnectedPeerTab.fxml"));
            AnchorPane content = loader.load();

            // lấy controller của tab con để cài đặt
            ConnectedPeerController controller = loader.getController();
            controller.init(peer, controlClient, fileShareService);

            // ngắt kết nối từ trong tab -> cập nhật trạng thái peer trong bảng (callback đã chạy trên JavaFX thread)
            controller.setOnDisconnected(() -> {
                for (PeerInfo p : peerList) {
                    if (peer.getPeerId().equals(p.getPeerId())) {
                        p.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
                        break;
                    }
                }
                peerTable.refresh();
                peerStatusLabel.setText("Đã ngắt kết nối: " + peer.getName());
            });

            Tab tab = new Tab("Kết nối: " + peer.getName());
            tab.setContent(content);
            tab.setUserData(controller);
            tab.setClosable(true);
            connectedTabs.put(peer.getPeerId(), tab);
            connectedControllers.put(peer.getPeerId(), controller);

            // khi tab đóng thì xoá mapping (chỉ khi map vẫn trỏ tới đúng tab này)
            tab.setOnClosed(ev -> {
                connectedControllers.remove(peer.getPeerId(), controller);
                connectedTabs.remove(peer.getPeerId(), tab);
                controller.dispose();
            });

            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);

        } catch (IOException ex) {
            Dialogs.error(peerTable, "Lỗi", "Lỗi tải UI tab kết nối:", ex.getMessage());
        }
    }

    // NGẮT KẾT NỐI
    @FXML
    private void onDisconnectPeer() {
        PeerInfo peer = peerTable.getSelectionModel().getSelectedItem();
        if (peer == null) {
            Dialogs.error(peerTable, "Cảnh báo", "Hãy chọn peer trước!", "Vui lòng chọn peer để ngắt kết nối.");
            return;
        }

        // Nếu chưa kết nối thì chỉ cập nhật UI
        if (peer.getConnectionState() != PeerInfo.ConnectionState.CONNECTED) {
            peer.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
            peerTable.refresh();
            peerStatusLabel.setText("Chưa kết nối đến peer này");
            return;
        }

        boolean confirmed = Dialogs.confirm(peerTable,
                "🔌 Ngắt kết nối",
                "Ngắt kết nối với " + peer.getName() + "?",
                "Hành động này sẽ đóng tab chia sẻ file và dừng mọi tải xuống.",
                "Đồng ý");
        if (!confirmed) return;

        peerStatusLabel.setText("Đang ngắt kết nối...");
        peer.setConnectionState(PeerInfo.ConnectionState.PENDING);
        peerTable.refresh();

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return controlClient.sendDisconnectRequest(peer);
            }
        };

        task.setOnSucceeded(e -> {
            // Phía mình luôn coi như đã ngắt, kể cả khi peer kia không phản hồi (đã tắt máy...)
            peer.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
            peerStatusLabel.setText(Boolean.TRUE.equals(task.getValue())
                    ? "Đã ngắt kết nối thành công"
                    : "Đã ngắt kết nối (peer không phản hồi)");

            // Nếu có tab kết nối đang mở, báo để cập nhật UI tab
            ConnectedPeerController ctrl = connectedControllers.get(peer.getPeerId());
            if (ctrl != null) ctrl.onPeerDisconnected();
            peerTable.refresh();
        });

        task.setOnFailed(e -> {
            peer.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
            peerStatusLabel.setText("Đã ngắt kết nối (lỗi khi báo cho peer)");
            peerTable.refresh();
        });

        startDaemon(task, "disconnect-peer");
    }

    private void routeSystemMessage(String senderPeerId, String message) {
        ConnectedPeerController targetController = connectedControllers.get(senderPeerId);
        if (targetController != null) {
            targetController.receivedMessage(message);
        } else {
            System.out.println("Nhận tin từ " + senderPeerId + " nhưng không tìm thấy tab nào đang mở.");
        }
    }

    // gọi khi remote peer bị ngắt (hoặc khi muốn đặt trạng thái peer về "chưa kết nối")
    public void onRemotePeerDisconnected(String peerId) {
        if (peerId == null || peerId.isBlank()) return;

        // Cập nhật trên JavaFX thread để tránh lỗi đa luồng
        Platform.runLater(() -> {
            boolean updated = false;

            // 1) cập nhật trạng thái trong peer list
            for (PeerInfo p : peerList) {
                if (peerId.equals(p.getPeerId())) {
                    p.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
                    updated = true;
                    peerStatusLabel.setText("Peer " + p.getName() + " đã bị ngắt kết nối");
                    break;
                }
            }

            // 2) cập nhật UI tab kết nối nếu đang mở
            ConnectedPeerController ctrl = connectedControllers.get(peerId);
            if (ctrl != null) ctrl.onPeerDisconnected();

            // peer chưa có trong bảng -> quét lại
            if (!updated) refresh();

            peerTable.refresh();
        });
    }

    // đổi tên tab và update trên UI con
    public void renameConnectedTab(String peerId, String newName) {
        Platform.runLater(() -> {
            PeerInfo connected = controlClient.getConnectedPeer(peerId);
            if (connected != null) connected.setDisplayName(newName);

            Tab tab = connectedTabs.get(peerId);
            if (tab != null) tab.setText("Kết nối: " + newName);

            ConnectedPeerController ctrl = connectedControllers.get(peerId);
            if (ctrl != null) ctrl.updatePeerDisplayName(newName);
        });
    }

    private static void startDaemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
