package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlProtocol;
import org.example.p2pfileshare.service.DownloadJob;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.util.AppConfig;
import org.example.p2pfileshare.util.TransferStats;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

public class ConnectedPeerController {

    static final String KEY_LAST_DOWNLOAD_DIR = "last_download_dir";

    @FXML
    private Label peerNameLabel;
    @FXML
    private TableView<Row> fileTable;
    @FXML
    private TableColumn<Row, String> colName;
    @FXML
    private TableColumn<Row, String> colRelative;
    @FXML
    private TableColumn<Row, Long> colSize;

    @FXML
    private ProgressBar progress;
    @FXML
    private Label statusLabel;

    @FXML
    private Button btnDownload;
    @FXML
    private Button btnPause;
    @FXML
    private Button btnResume;
    @FXML
    private Button btnCancel;

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private DownloadJob currentJob;
    private PeerInfo peer;
    private ControlClient controlClient;
    private FileShareService fileShareService;

    // PeerTabController đăng ký để biết khi tab này đã ngắt kết nối (gọi trên JavaFX thread)
    private Runnable onDisconnectedCallback;

    public void init(PeerInfo peer, ControlClient controlClient, FileShareService fileShareService) {
        this.peer = peer;
        this.controlClient = controlClient;
        this.fileShareService = fileShareService;

        peerNameLabel.setText(peer.getName() + " (" + peer.getIp() + ")");

        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colRelative.setCellValueFactory(new PropertyValueFactory<>("relativePath"));
        colSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        // Format hiển thị kích thước: KB/MB/GB
        colSize.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : TransferStats.formatSize(item));
            }
        });
        fileTable.setItems(rows);

        setupContextMenu();
        reload();
    }

    @FXML
    private void onPauseDownload() {
        if (currentJob == null) {
            statusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        currentJob.pause();
        statusLabel.setText("Đã tạm dừng");
        btnPause.setDisable(true);
        btnResume.setDisable(false);
    }

    @FXML
    private void onResumeDownload() {
        if (currentJob == null) {
            statusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        currentJob.resume();
        statusLabel.setText("Đang tiếp tục tải...");
        btnPause.setDisable(false);
        btnResume.setDisable(true);
    }

    @FXML
    private void onCancelDownload() {
        if (currentJob == null) {
            statusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        // cancel có hiệu lực ngay ở checkpoint gần nhất, kể cả khi đang tạm dừng
        currentJob.cancel();
        statusLabel.setText("Đang hủy...");
    }

    private void setupContextMenu() {
        MenuItem downloadItem = new MenuItem("⬇ Tải xuống");
        downloadItem.getStyleClass().add("menu-item-action");
        downloadItem.setOnAction(e -> {
            Row selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) downloadFile(selected);
        });

        MenuItem summarizeItem = new MenuItem("✨ Tóm tắt file");
        summarizeItem.getStyleClass().add("menu-item-action");
        summarizeItem.setOnAction(e -> {
            Row selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) requestRemoteSummary(selected);
        });

        ContextMenu contextMenu = new ContextMenu(downloadItem, summarizeItem);

        // Chỉ hiện menu khi chuột phải vào dòng có dữ liệu
        fileTable.setRowFactory(tv -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    fileTable.getSelectionModel().select(row.getItem());
                    contextMenu.show(row, event.getScreenX(), event.getScreenY());
                }
            });
            return row;
        });
    }

    private void requestRemoteSummary(Row row) {
        statusLabel.setText("⏳ Đang yêu cầu Peer " + peer.getName() + " tóm tắt file: " + row.getName() + "...");

        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return controlClient.sendSummarizeRequest(peer, row.getRelativePath());
            }
        };

        task.setOnSucceeded(e -> {
            // Format: SUMMARIZE_RES|path|base64(nội dung)
            String rawResponse = task.getValue();
            String[] parts = rawResponse == null ? new String[0] : rawResponse.split("\\|", 3);
            if (parts.length < 3 || !"SUMMARIZE_RES".equals(parts[0])) {
                statusLabel.setText("❌ Không nhận được phản hồi hoặc lỗi.");
                Dialogs.error(fileTable, "Lỗi", "Không nhận được tóm tắt", "Peer không phản hồi hoặc timeout.");
                return;
            }

            String fileName = parts[1];
            String rawContent = parts[2].trim();
            try {
                String content = new String(Base64.getDecoder().decode(rawContent), StandardCharsets.UTF_8);
                statusLabel.setText("✅ Đã nhận được tóm tắt!");
                Dialogs.summary(fileTable, fileName, content);
            } catch (IllegalArgumentException ex) {
                // Peer bản cũ gửi văn bản thường (xuống dòng thay bằng <br>)
                statusLabel.setText("⚠️ Nội dung chưa được mã hóa");
                Dialogs.summary(fileTable, fileName,
                        rawContent.replace("<br>", "\n") + "\n\n(Lưu ý: Peer dùng phiên bản cũ)");
            }
        });

        task.setOnFailed(e -> {
            statusLabel.setText("❌ Lỗi kết nối.");
            task.getException().printStackTrace();
        });

        startDaemon(task, "remote-summary");
    }

    /** Lệnh hệ thống từ peer này (đã chuyển về JavaFX thread) */
    public void receivedMessage(String message) {
        if (message != null && message.startsWith(ControlProtocol.CMD_FILES_CHANGED)) {
            // peer vừa thêm/xóa/sửa file trong thư mục chia sẻ -> tự tải lại danh sách
            reload();
        }
    }

    @FXML
    public void onReload() {
        reload();
    }

    public void reload() {
        statusLabel.setText("Đang tải danh sách...");
        Task<List<ControlClient.RemoteFile>> task = new Task<>() {
            @Override
            protected List<ControlClient.RemoteFile> call() {
                return controlClient.listFiles(peer);
            }
        };

        task.setOnSucceeded(e -> {
            rows.clear();
            for (var rf : task.getValue()) {
                rows.add(new Row(rf.name, rf.relativePath, rf.size));
            }
            statusLabel.setText("Đã nạp " + rows.size() + " file");
        });
        task.setOnFailed(e -> statusLabel.setText("Lỗi tải danh sách"));

        startDaemon(task, "reload-remote-files");
    }

    /** Gọi trên JavaFX thread khi kết nối với peer này đã bị ngắt (do mình hoặc do peer) */
    public void onPeerDisconnected() {
        statusLabel.setText("Đã ngắt kết nối với peer. Bấm 'Kết nối' ở tab Mạng LAN để kết nối lại.");
        fileTable.setDisable(true);
        btnDownload.setDisable(true);
        // tải dở (nếu có) sẽ bị peer từ chối và báo thất bại, file .part được giữ lại
        // nên lần sau kết nối lại và tải cùng file vào cùng thư mục sẽ tải tiếp phần còn thiếu
    }

    /** Gọi khi tab bị đóng: hủy tải dở để không còn thread chạy ngầm */
    public void dispose() {
        if (currentJob != null && currentJob.isActive()) {
            currentJob.cancel();
        }
        currentJob = null;
    }

    @FXML
    private void onDownloadSelected() {
        Row sel = fileTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            Dialogs.info(fileTable, "Thông báo", "Chưa chọn file", "Hãy chọn 1 file để tải.");
            return;
        }
        downloadFile(sel);
    }

    private void downloadFile(Row fileRow) {
        // Mỗi tab chỉ tải 1 file một lúc
        if (currentJob != null && currentJob.isActive()) {
            Dialogs.info(fileTable, "Thông báo", "Đang có file đang tải", "Hãy tạm dừng hoặc hủy trước khi tải file khác.");
            return;
        }

        File selectedDir = chooseDownloadDir(fileTable);
        if (selectedDir == null) {
            statusLabel.setText("Đã hủy tải xuống");
            return;
        }

        Path saveTo = new File(selectedDir, fileRow.name).toPath();
        progress.setProgress(0);
        statusLabel.setText("Đang chuẩn bị tải: " + fileRow.name);
        TransferStats stats = new TransferStats(fileRow.size);

        // service tạo job + chạy nền + trả về handle
        currentJob = fileShareService.startDownload(
                peer,
                fileRow.relativePath,
                saveTo,
                // progressCallback: 0..1
                p -> {
                    String info = stats.update(p);
                    Platform.runLater(() -> {
                        progress.setProgress(p);
                        statusLabel.setText("Đang tải " + fileRow.name + ": " + info);
                    });
                },
                // statusCallback: text trạng thái
                s -> Platform.runLater(() -> statusLabel.setText(s)),
                // stateCallback: cập nhật nút bấm theo trạng thái
                state -> Platform.runLater(() -> onDownloadStateChanged(state)));

        btnDownload.setDisable(true);
        btnPause.setDisable(false);
        btnResume.setDisable(true);
        btnCancel.setDisable(false);
    }

    /** Hỏi thư mục lưu file (nhớ thư mục lần trước), null nếu người dùng hủy */
    static File chooseDownloadDir(Control anchor) {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục lưu file");
        String last = AppConfig.load(KEY_LAST_DOWNLOAD_DIR);
        if (last != null && new File(last).isDirectory()) {
            dirChooser.setInitialDirectory(new File(last));
        }
        File dir = dirChooser.showDialog(anchor.getScene().getWindow());
        if (dir != null) AppConfig.save(KEY_LAST_DOWNLOAD_DIR, dir.getAbsolutePath());
        return dir;
    }

    private void onDownloadStateChanged(DownloadJob.State state) {
        switch (state) {
            case COMPLETED -> {
                progress.setProgress(1.0);
                resetButtons();
            }
            case CANCELLED -> {
                progress.setProgress(0);
                resetButtons();
            }
            case FAILED -> resetButtons();
            default -> { }
        }
        if (fileTable.isDisabled()) {
            btnDownload.setDisable(true); // đã ngắt kết nối thì không cho tải tiếp
        }
    }

    // Trả trạng thái nút về ban đầu
    private void resetButtons() {
        btnDownload.setDisable(false);
        btnPause.setDisable(true);
        btnResume.setDisable(true);
        btnCancel.setDisable(true);
    }

    public void setOnDisconnected(Runnable callback) {
        this.onDisconnectedCallback = callback;
    }

    @FXML
    private void onDisconnect() {
        boolean confirmed = Dialogs.confirm(fileTable,
                "🔌 Ngắt kết nối",
                "Bạn có chắc muốn ngắt kết nối với " + peer.getName() + "?",
                "Hành động này sẽ dừng mọi tiến trình tải file đang chạy.",
                "Ngắt kết nối");
        if (!confirmed) return;

        if (currentJob != null) {
            currentJob.cancel();
        }

        // Nếu đã ngắt rồi thì chỉ cập nhật giao diện
        if (!controlClient.isConnectedTo(peer.getPeerId())) {
            onPeerDisconnected();
            return;
        }

        statusLabel.setText("Đang ngắt kết nối...");
        peer.setConnectionState(PeerInfo.ConnectionState.PENDING);
        progress.setProgress(-1);
        fileTable.setDisable(true);

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return controlClient.sendDisconnectRequest(peer);
            }
        };

        task.setOnSucceeded(e -> {
            // Phía mình luôn coi như đã ngắt, kể cả khi peer kia không phản hồi
            finishDisconnect();
            statusLabel.setText(Boolean.TRUE.equals(task.getValue())
                    ? "Đã ngắt kết nối"
                    : "Đã ngắt kết nối (peer không phản hồi)");
        });
        task.setOnFailed(e -> finishDisconnect());

        startDaemon(task, "disconnect-from-connected-tab");
    }

    private void finishDisconnect() {
        peer.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
        progress.setProgress(0);
        onPeerDisconnected();
        if (onDisconnectedCallback != null) onDisconnectedCallback.run();
    }

    public void updatePeerDisplayName(String newName) {
        if (newName == null) return;
        Platform.runLater(() -> peerNameLabel.setText(newName + " (" + peer.getIp() + ")"));
    }

    private static void startDaemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    // Row model cho TableView
    public static class Row {
        private final String name;
        private final String relativePath;
        private final long size;

        public Row(String name, String relativePath, long size) {
            this.name = name;
            this.relativePath = relativePath;
            this.size = size;
        }

        public String getName() {
            return name;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public long getSize() {
            return size;
        }
    }
}
