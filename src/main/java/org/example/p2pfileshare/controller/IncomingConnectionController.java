package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.PeerService;

import java.io.IOException;
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
    @FXML private Button refreshButton;
    @FXML private Button disconnectButton;

    private PeerService peerService;
    private ControlServer controlServer;
    private Label globalStatusLabel;
    private String myUID;
    private final ObservableList<PeerInfo> incomingPeerList = FXCollections.observableArrayList();

    public void init(PeerService peerService, ControlServer controlServer, Label globalStatusLabel, String myUID) {
        this.myUID = myUID;
        this.peerService = peerService;
        this.controlServer = controlServer;
        this.globalStatusLabel = globalStatusLabel;

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
            showConfirmDialog("Cảnh báo", "Chưa chọn Peer", "Vui lòng chọn một dòng để ngắt kết nối.");
            return;
        }

        // Hiện Dialog Xác nhận
        boolean confirmed = showConfirmDialog(
                "🔌 Ngắt kết nối",
                "Ngắt kết nối với " + selected.getName() + "?",
                "Peer này sẽ không thể tải file của bạn nữa cho đến khi kết nối lại."
        );
        if (confirmed) {
            statusLabel.setText("Đang ngắt kết nối với " + selected.getName() + "...");
            // Gửi thông báo qua mạng ở thread nền để không treo giao diện khi peer đã tắt máy
            javafx.concurrent.Task<Boolean> task = new javafx.concurrent.Task<>() {
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
                    showSuccessDialog("Thành công", "Đã chặn kết nối từ " + selected.getName());
                } else {
                    statusLabel.setText("Đã chặn " + selected.getName() + " (peer không nhận được thông báo)");
                }
            });
            Thread t = new Thread(task, "kick-peer");
            t.setDaemon(true);
            t.start();
        }
    }

    private boolean showConfirmDialog(String title, String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (connectedPeerTable.getScene() != null) {
                dialogStage.initOwner(connectedPeerTable.getScene().getWindow());
            }
            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Thiết lập nội dung
            controller.setContent(title, header, content, "Đồng ý");
            controller.setStyleDanger(); // Màu đỏ cảnh báo

            dialogStage.showAndWait();
            return controller.isConfirmed();

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void showSuccessDialog(String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (connectedPeerTable.getScene() != null) {
                dialogStage.initOwner(connectedPeerTable.getScene().getWindow());
            }
            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            controller.setContent("Thông báo", header, content, "Đóng");
            controller.setStyleSuccess(); // Màu xanh thành công

            dialogStage.showAndWait();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}