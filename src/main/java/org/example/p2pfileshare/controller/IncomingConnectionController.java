package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.PeerService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class IncomingConnectionController {

    @FXML private TableView<PeerInfo> connectedPeerTable;
    @FXML private TableColumn<PeerInfo, String> colPeerId;
    @FXML private TableColumn<PeerInfo, String> colDisplayName;
    @FXML private TableColumn<PeerInfo, String> colIp;
    @FXML private TableColumn<PeerInfo, String> colConnectTime;
    @FXML private Label statusLabel;

    private PeerService peerService;
    private ControlServer controlServer;
    private String myUID;
    private final ObservableList<PeerInfo> incomingPeerList = FXCollections.observableArrayList();

    public void init(PeerService peerService, ControlServer controlServer, String myUID) {
        this.myUID = myUID;
        this.peerService = peerService;
        this.controlServer = controlServer;

        setupTable();
        loadIncomingConnections();

        // Đăng ký listener để tự động reload khi có peer mới được chấp nhận
        controlServer.setOnPeerAccepted(() -> {
            System.out.println("[IncomingConnection] Peer accepted → reload table");
            Platform.runLater(this::loadIncomingConnections);
        });
    }

    private void setupTable() {
        colPeerId.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getPeerId()));

        colDisplayName.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getName()));

        colIp.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getIp()));

        // Thời điểm peer được chấp nhận kết nối
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
        colConnectTime.setCellValueFactory(data -> {
            LocalDateTime at = controlServer.getAcceptedAt(data.getValue().getPeerId());
            return new SimpleStringProperty(at == null ? "" : at.format(fmt));
        });

        connectedPeerTable.setItems(incomingPeerList);
    }

    private void loadIncomingConnections() {
        // ControlServer tự lưu thông tin peer khi chấp nhận kết nối, không cần quét LAN (không chặn giao diện)
        List<PeerInfo> acceptedPeers = controlServer.getAcceptedPeers(peerService.getDiscoveredPeers());

        // Cập nhật UI trên JavaFX thread để chắc chắn TableView được refresh
        Platform.runLater(() -> {
            incomingPeerList.setAll(acceptedPeers);
            connectedPeerTable.refresh();
            statusLabel.setText("Có " + acceptedPeers.size() + " peer đang kết nối đến");
        });
    }

    @FXML
    private void onRefresh() {
        statusLabel.setText("Đang làm mới...");
        loadIncomingConnections();
    }

    @FXML
    private void onDisconnect() {
        PeerInfo selected = connectedPeerTable.getSelectionModel().getSelectedItem();

        // Kiểm tra nếu chưa chọn
        if (selected == null) {
            Dialogs.error(connectedPeerTable, "Cảnh báo", "Chưa chọn Peer", "Vui lòng chọn một dòng để ngắt kết nối.");
            return;
        }

        boolean confirmed = Dialogs.confirm(connectedPeerTable,
                "🔌 Ngắt kết nối",
                "Ngắt kết nối với " + selected.getName() + "?",
                "Peer này sẽ không thể tải file của bạn nữa cho đến khi kết nối lại.",
                "Đồng ý");
        if (confirmed) {
            statusLabel.setText("Đang ngắt kết nối với " + selected.getName() + "...");
            // Gửi thông báo qua mạng ở thread nền để không treo giao diện khi peer đã tắt máy
            Task<Boolean> task = new Task<>() {
                @Override
                protected Boolean call() {
                    return controlServer.disconnectPeer(selected, myUID);
                }
            };
            task.setOnSucceeded(e -> {
                // Quyền truy cập đã bị thu hồi ngay cả khi không báo được cho peer
                incomingPeerList.remove(selected);
                if (Boolean.TRUE.equals(task.getValue())) {
                    statusLabel.setText("Đã ngắt kết nối với " + selected.getName());
                    Dialogs.info(connectedPeerTable, "Thông báo", "Thành công", "Đã chặn kết nối từ " + selected.getName());
                } else {
                    statusLabel.setText("Đã chặn " + selected.getName() + " (peer không nhận được thông báo)");
                }
            });
            Thread t = new Thread(task, "kick-peer");
            t.setDaemon(true);
            t.start();
        }
    }

}
