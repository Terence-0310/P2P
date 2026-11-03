package org.example.p2pfileshare.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.relay.RelayClient;
import org.example.p2pfileshare.network.relay.RelaySettings;
import org.example.p2pfileshare.service.ConnectionService;
import org.example.p2pfileshare.service.InternetModeService;
import org.example.p2pfileshare.service.PeerHealthMonitor;
import org.example.p2pfileshare.util.AppConfig;
import org.example.p2pfileshare.util.NetworkSettings;
import org.example.p2prelay.protocol.RelayProtocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Khu vực Internet Mode trong tab kết nối: trạng thái relay, mã kết nối của mình, nhập mã máy kia,
 * danh sách máy kết nối qua Internet. Mọi thao tác mạng chạy ở luồng nền.
 */
public class InternetPanelController {

    @FXML private Label relayStateLabel;
    @FXML private TextField relayUrlField;
    @FXML private Button connectRelayButton;
    @FXML private Button disconnectRelayButton;
    @FXML private Label inviteCodeLabel;
    @FXML private Label inviteExpiryLabel;
    @FXML private Button copyCodeButton;
    @FXML private Button newCodeButton;
    @FXML private TextField remoteCodeField;
    @FXML private Button connectPeerButton;
    @FXML private Label messageLabel;
    @FXML private TableView<RemoteRow> remotePeerTable;
    @FXML private TableColumn<RemoteRow, String> colRemoteName;
    @FXML private TableColumn<RemoteRow, String> colRemoteId;
    @FXML private TableColumn<RemoteRow, String> colRemoteHealth;
    @FXML private TableColumn<RemoteRow, String> colRemoteConnection;

    private InternetModeService internet;
    private ConnectionService connections;
    private PeerHealthMonitor monitor;
    private Consumer<PeerInfo> openPeerTab;
    private Consumer<PeerInfo> confirmDisconnect;
    private Label globalStatusLabel;

    private final ObservableList<RemoteRow> rows = FXCollections.observableArrayList();
    /** peer chưa kết nối xong: đang chờ bên kia chấp nhận, hoặc vừa nhập mã của mình (id -> tên) */
    private final Map<String, String> pending = new LinkedHashMap<>();
    private RelayClient.Invite invite;
    private boolean creatingInvite;
    private boolean connectingPeer;

    public void init(InternetModeService internet, ConnectionService connections, PeerHealthMonitor monitor,
                     Consumer<PeerInfo> openPeerTab, Consumer<PeerInfo> confirmDisconnect, Label globalStatusLabel) {
        this.internet = internet;
        this.connections = connections;
        this.monitor = monitor;
        this.openPeerTab = openPeerTab;
        this.confirmDisconnect = confirmDisconnect;
        this.globalStatusLabel = globalStatusLabel;

        relayUrlField.setText(NetworkSettings.defaultRelayUrl());
        setupTable();

        internet.addRelayListener(new RelayClient.Listener() {
            @Override
            public void onStateChanged(RelayClient.State state, String detail) {
                // đọc trạng thái của client hiện tại: sự kiện trễ của client cũ (trước khi bấm kết nối lại) bị bỏ qua
                Platform.runLater(() -> onRelayState(internet.getState(), internet.getStateDetail()));
            }

            @Override
            public void onPresence(String peerId, boolean online) {
                Platform.runLater(InternetPanelController.this::refreshRows);
            }

            @Override
            public void onLinked(String peerId, String displayName) {
                Platform.runLater(() -> {
                    if (!connections.isConnected(peerId)) pending.put(peerId, displayName);
                    info("\"" + displayName + "\" vừa nhập mã của bạn, đang chờ yêu cầu kết nối...");
                    refreshRows();
                    // mã đã dùng (chỉ dùng 1 lần): tạo mã mới cho lần sau
                    invite = null;
                    requestNewInvite();
                });
            }

            @Override
            public void onUnlinked(String peerId) {
                Platform.runLater(() -> {
                    pending.remove(peerId);
                    refreshRows();
                });
            }
        });
        connections.addListener(new ConnectionService.Listener() {
            @Override
            public void onConnected(PeerInfo peer, boolean incoming) {
                if (!peer.isRelay()) return;
                Platform.runLater(() -> {
                    pending.remove(peer.getPeerId());
                    refreshRows();
                });
            }

            @Override
            public void onDisconnected(String peerId, boolean byRemote) {
                Platform.runLater(InternetPanelController.this::refreshRows);
            }
        });
        monitor.addListener((peerId, health) -> Platform.runLater(this::refreshRows));

        // đếm ngược thời hạn mã kết nối
        Timeline countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateInviteExpiry()));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();

        remoteCodeField.setOnAction(e -> onConnectByCode());
        onRelayState(internet.getState(), internet.getStateDetail());
        // lần trước đang dùng Internet Mode -> tự kết nối lại relay
        if (NetworkSettings.relayAutoConnect() && !relayUrlField.getText().isBlank()) onConnectRelay();
    }

    private void setupTable() {
        colRemoteName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colRemoteId.setCellValueFactory(new PropertyValueFactory<>("shortId"));
        colRemoteHealth.setCellValueFactory(new PropertyValueFactory<>("health"));
        colRemoteConnection.setCellValueFactory(new PropertyValueFactory<>("connection"));
        colRemoteHealth.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (empty || item == null) {
                    setStyle("");
                } else if (item.contains("Online")) {
                    setStyle("-fx-text-fill: #4caf50; -fx-font-weight: bold;");
                } else if (item.contains("Offline")) {
                    setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                } else {
                    setStyle("-fx-text-fill: #ff9800; -fx-font-weight: bold;");
                }
            }
        });
        remotePeerTable.setItems(rows);
        remotePeerTable.setRowFactory(tv -> {
            TableRow<RemoteRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) onOpenPeer();
            });
            return row;
        });
    }

    // ================= relay =================

    @FXML
    private void onConnectRelay() {
        String url = relayUrlField.getText() == null ? "" : relayUrlField.getText().trim();
        RelaySettings settings;
        try {
            settings = NetworkSettings.relaySettings(url);
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        AppConfig.save(NetworkSettings.KEY_RELAY_URL, url);
        AppConfig.save(NetworkSettings.KEY_RELAY_AUTOCONNECT, "true");
        connectRelayButton.setDisable(true);
        info("Đang kết nối relay...");
        runInBackground("relay-connect", () -> internet.connect(settings));
    }

    @FXML
    private void onDisconnectRelay() {
        disconnectRelayButton.setDisable(true);
        AppConfig.save(NetworkSettings.KEY_RELAY_AUTOCONNECT, "false");
        runInBackground("relay-disconnect", () -> internet.disconnect());
        invite = null;
        showInvite();
    }

    private void onRelayState(RelayClient.State state, String detail) {
        switch (state) {
            case ONLINE -> setRelayLabel("🟢 Relay: Online", "#4caf50");
            case CONNECTING -> setRelayLabel("🟡 Relay: Connecting...", "#ff9800");
            case RECONNECTING -> setRelayLabel("🟠 Relay: Reconnecting", "#ff9800");
            case OFFLINE -> setRelayLabel("🔴 Relay: Offline", "#f44336");
            case DISCONNECTED -> setRelayLabel("⚪ Relay: chưa kết nối", "#7f8c8d");
        }
        relayStateLabel.setTooltip(new Tooltip(detail));
        boolean online = state == RelayClient.State.ONLINE;
        boolean active = state != RelayClient.State.DISCONNECTED && state != RelayClient.State.OFFLINE;
        connectRelayButton.setDisable(active);
        relayUrlField.setDisable(active);
        disconnectRelayButton.setDisable(!active);
        newCodeButton.setDisable(!online);
        connectPeerButton.setDisable(!online || connectingPeer);
        copyCodeButton.setDisable(!online || invite == null);

        if (online) {
            info("Đã kết nối relay. Đọc mã của bạn cho máy kia, hoặc nhập mã của máy kia.");
            if (invite == null || isExpired(invite)) requestNewInvite();
        } else if (state == RelayClient.State.RECONNECTING || state == RelayClient.State.OFFLINE) {
            // mã cũ có thể đã mất hiệu lực nếu relay khởi động lại
            invite = null;
            showInvite();
            if (state == RelayClient.State.OFFLINE) error(detail);
            else info(detail);
        } else if (state == RelayClient.State.DISCONNECTED) {
            invite = null;
            showInvite();
        }
        if (globalStatusLabel != null && state != RelayClient.State.CONNECTING) {
            globalStatusLabel.setText(relayStateLabel.getText());
        }
        refreshRows();
    }

    private void setRelayLabel(String text, String color) {
        relayStateLabel.setText(text);
        relayStateLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + ";");
    }

    // ================= mã kết nối =================

    @FXML
    private void onNewCode() {
        requestNewInvite();
    }

    private void requestNewInvite() {
        if (creatingInvite || !internet.isRelayOnline()) return;
        creatingInvite = true;
        Task<RelayClient.Invite> task = new Task<>() {
            @Override
            protected RelayClient.Invite call() throws Exception {
                return internet.createInvite();
            }
        };
        task.setOnSucceeded(e -> {
            creatingInvite = false;
            invite = task.getValue();
            showInvite();
        });
        task.setOnFailed(e -> {
            creatingInvite = false;
            error("Không tạo được mã kết nối: " + task.getException().getMessage());
        });
        startDaemon(task, "relay-invite");
    }

    private void showInvite() {
        inviteCodeLabel.setText(invite == null ? "—" : invite.displayCode());
        copyCodeButton.setDisable(invite == null);
        updateInviteExpiry();
    }

    private void updateInviteExpiry() {
        if (invite == null) {
            inviteExpiryLabel.setText("");
            return;
        }
        long left = invite.expiresAtEpochSec() - System.currentTimeMillis() / 1000;
        if (left <= 0) {
            inviteExpiryLabel.setText("(đã hết hạn)");
            inviteCodeLabel.setText("—");
            invite = null;
            copyCodeButton.setDisable(true);
            requestNewInvite();
        } else {
            inviteExpiryLabel.setText(String.format("(hết hạn sau %d:%02d, dùng 1 lần)", left / 60, left % 60));
        }
    }

    private static boolean isExpired(RelayClient.Invite inv) {
        return inv.expiresAtEpochSec() * 1000 <= System.currentTimeMillis();
    }

    @FXML
    private void onCopyCode() {
        if (invite == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(invite.displayCode());
        Clipboard.getSystemClipboard().setContent(content);
        info("Đã sao chép mã " + invite.displayCode());
    }

    // ================= kết nối máy kia =================

    @FXML
    private void onConnectByCode() {
        if (connectingPeer) return;
        String raw = remoteCodeField.getText();
        if (RelayProtocol.normalizeInviteCode(raw) == null) {
            error("Mã kết nối gồm 8 ký tự chữ và số, ví dụ K7QM-4XZ9");
            return;
        }
        if (!internet.isRelayOnline()) {
            error("Chưa kết nối relay");
            return;
        }
        connectingPeer = true;
        connectPeerButton.setDisable(true);
        info("Đang tìm máy có mã " + RelayProtocol.formatInviteCode(RelayProtocol.normalizeInviteCode(raw))
                + " và gửi yêu cầu, chờ bên kia bấm Chấp nhận...");

        Task<PeerInfo> task = new Task<>() {
            @Override
            protected PeerInfo call() throws Exception {
                return internet.connectByCode(raw);
            }
        };
        task.setOnSucceeded(e -> {
            connectingPeer = false;
            connectPeerButton.setDisable(!internet.isRelayOnline());
            remoteCodeField.clear();
            PeerInfo peer = task.getValue();
            info("Đã kết nối với " + peer.getName() + " qua Internet");
            if (globalStatusLabel != null) globalStatusLabel.setText("Đã kết nối với " + peer.getName() + " (Internet)");
            refreshRows();
        });
        task.setOnFailed(e -> {
            connectingPeer = false;
            connectPeerButton.setDisable(!internet.isRelayOnline());
            Throwable ex = task.getException();
            error("Không kết nối được: " + (ex == null ? "lỗi không xác định" : ex.getMessage()));
            refreshRows();
        });
        startDaemon(task, "relay-connect-peer");
    }

    @FXML
    private void onOpenPeer() {
        RemoteRow row = remotePeerTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            error("Hãy chọn 1 máy trong bảng");
            return;
        }
        PeerInfo p = connections.getConnectedPeer(row.peerId);
        if (p == null) {
            error("Máy này chưa kết nối xong");
            return;
        }
        openPeerTab.accept(p);
    }

    @FXML
    private void onDisconnectPeer() {
        RemoteRow row = remotePeerTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            error("Hãy chọn 1 máy trong bảng");
            return;
        }
        PeerInfo p = connections.getConnectedPeer(row.peerId);
        if (p == null) {
            pending.remove(row.peerId);
            refreshRows();
            return;
        }
        confirmDisconnect.accept(p);
    }

    // ================= bảng =================

    private void refreshRows() {
        RemoteRow selected = remotePeerTable.getSelectionModel().getSelectedItem();
        Map<String, RemoteRow> next = new LinkedHashMap<>();
        for (PeerInfo p : connections.getConnectedPeers()) {
            if (!p.isRelay()) continue;
            next.put(p.getPeerId(), new RemoteRow(p.getPeerId(), p.getName(), healthText(p.getPeerId()), "✅ Đã kết nối"));
        }
        for (Map.Entry<String, String> e : pending.entrySet()) {
            next.putIfAbsent(e.getKey(), new RemoteRow(e.getKey(), e.getValue(), "🟡 Connecting", "⏳ Chờ yêu cầu kết nối"));
        }
        rows.setAll(next.values());
        if (selected != null) {
            rows.stream().filter(r -> r.peerId.equals(selected.peerId)).findFirst()
                    .ifPresent(r -> remotePeerTable.getSelectionModel().select(r));
        }
    }

    /** Không nhầm "relay còn sống" với "máy kia còn online": trạng thái máy kia lấy từ heartbeat + presence */
    private String healthText(String peerId) {
        RelayClient.State relay = internet.getState();
        if (relay == RelayClient.State.RECONNECTING || relay == RelayClient.State.CONNECTING) return "🟠 Reconnecting";
        if (relay != RelayClient.State.ONLINE) return "🔴 Offline (mất relay)";
        PeerHealthMonitor.Health h = monitor.getHealth(peerId);
        if (h == null) return "🟡 Connecting";
        return switch (h) {
            case ONLINE -> "🟢 Online";
            case OFFLINE -> "🔴 Offline";
            case RECONNECTING -> "🟠 Reconnecting";
            case CONNECTING -> "🟡 Connecting";
        };
    }

    private void info(String msg) {
        messageLabel.setText(msg);
        messageLabel.setStyle("-fx-text-fill: #2980b9; -fx-font-style: italic;");
    }

    private void error(String msg) {
        messageLabel.setText("⚠ " + msg);
        messageLabel.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
    }

    private static void runInBackground(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void startDaemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    /** 1 dòng trong bảng máy kết nối qua Internet */
    public static class RemoteRow {
        private final String peerId;
        private final String name;
        private final String health;
        private final String connection;

        RemoteRow(String peerId, String name, String health, String connection) {
            this.peerId = peerId;
            this.name = name;
            this.health = health;
            this.connection = connection;
        }

        public String getName() {
            return name;
        }

        public String getShortId() {
            return RelayProtocol.shortId(peerId);
        }

        public String getHealth() {
            return health;
        }

        public String getConnection() {
            return connection;
        }
    }
}
