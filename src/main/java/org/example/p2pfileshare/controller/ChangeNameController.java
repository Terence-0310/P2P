package org.example.p2pfileshare.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.PeerService;
import org.example.p2pfileshare.util.AppConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class ChangeNameController {

    @FXML
    private TextField nameField;

    private Stage stage;
    private String result;

    private ControlClient controlClient;
    private ControlServer controlServer;
    private PeerService peerService;
    private Consumer<String> onUpdatePeerName;

    public static final String KEY_PEER_NAME = "peer_display_name";

    @FXML
    private Button cancelButton;
    @FXML
    private Button saveButton;

    private boolean isLoginMode = false;

    public void init(Stage stage,
            ControlClient controlClient,
            ControlServer controlServer,
            PeerService peerService,
            Consumer<String> callback) {
        this.stage = stage;
        this.controlClient = controlClient;
        this.controlServer = controlServer;
        this.peerService = peerService;
        this.onUpdatePeerName = callback;
    }

    public void setLoginMode(boolean loginMode) {
        this.isLoginMode = loginMode;
        if (loginMode) {
            if (cancelButton != null) {
                cancelButton.setVisible(false); // Ẩn nút Hủy
                cancelButton.setManaged(false); // Không chiếm chỗ
            }
            if (saveButton != null) {
                saveButton.setText("Bắt đầu tham gia");
            }
        }
    }

    @FXML
    private void onSave() {
        String v = nameField.getText();
        if (v == null)
            v = "";
        v = v.trim();

        if (v.isEmpty()) {
            nameField.setStyle("-fx-border-color: red;");
            return;
        }

        result = v;

        // lưu cấu hình
        AppConfig.save(KEY_PEER_NAME, result);

        // NẾU KHÔNG PHẢI LOGIN MODE THÌ:
        if (!isLoginMode) {
            // callback về Root
            if (onUpdatePeerName != null) {
                try {
                    onUpdatePeerName.accept(v);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            // broadcast cho mọi peer đang kết nối (cả 2 chiều), chạy nền để không treo giao diện
            if (controlClient != null && controlServer != null && peerService != null) {
                final String newName = result;
                List<PeerInfo> targets = new ArrayList<>(controlServer.getAcceptedPeers(peerService.getDiscoveredPeers()));
                for (PeerInfo p : controlClient.getConnectedPeers()) {
                    boolean exists = targets.stream().anyMatch(t -> t.getPeerId().equals(p.getPeerId()));
                    if (!exists) targets.add(p);
                }
                Thread t = new Thread(() -> controlClient.broadcastUpdateName(targets, newName), "broadcast-rename");
                t.setDaemon(true);
                t.start();
            }
        }

        if (stage != null)
            stage.close();
    }

    @FXML
    private void onCancel() {
        result = null;
        if (stage != null)
            stage.close();
    }

    public void setInitialName(String name) {
        nameField.setText(name != null ? name : "");
        nameField.requestFocus(); // Focus vào ô nhập
        nameField.selectAll(); // Bôi đen để user gõ đè luôn nếu muốn
    }

    public static Optional<String> showDialog(
            Window owner,
            String initialName,
            ControlClient controlClient,
            ControlServer controlServer,
            PeerService peerService,
            Consumer<String> onUpdatePeerName) {
        return showInternal(owner, initialName, controlClient, controlServer, peerService, onUpdatePeerName, false);
    }

    // Static method chuyên cho Login (vào app)
    public static String showForLogin(Window owner) {
        // Load tên đã lưu trước đó nếu có
        String savedObj = AppConfig.load(KEY_PEER_NAME);
        String initialName = (savedObj != null && !savedObj.isBlank()) ? savedObj
                : "Peer_" + System.currentTimeMillis();

        Optional<String> res = showInternal(owner, initialName, null, null, null, null, true);
        return res.orElse("Peer_Generic");
    }

    private static Optional<String> showInternal(
            Window owner,
            String initialName,
            ControlClient client,
            ControlServer server,
            PeerService peerService,
            Consumer<String> callback,
            boolean isLogin) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    ChangeNameController.class.getResource("/org/example/p2pfileshare/ChangeNameDialog.fxml"));

            Scene scene = new Scene(loader.load());
            // Transparent scene nếu muốn bo góc đẹp
            scene.setFill(Color.TRANSPARENT);

            ChangeNameController controller = loader.getController();

            Stage stage = new Stage();
            stage.initOwner(owner);
            // Nếu là login màn hình đầu tiên (owner=null), ta dùng APPLICATION_MODAL hoặc
            // không set owner cũng được
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initStyle(StageStyle.TRANSPARENT); // Để bo góc đẹp

            stage.setScene(scene);

            controller.init(stage, client, server, peerService, callback);
            controller.setLoginMode(isLogin);
            controller.setInitialName(initialName);

            stage.showAndWait();
            return Optional.ofNullable(controller.result);

        } catch (IOException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }
}
