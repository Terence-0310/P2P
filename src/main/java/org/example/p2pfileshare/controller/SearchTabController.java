package org.example.p2pfileshare.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.util.Duration;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.model.SearchResult;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.service.DownloadJob;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.SearchService;
import org.example.p2pfileshare.util.TransferStats;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class SearchTabController {

    private FileShareService fileShareService;
    private ControlClient controlClient;

    @FXML private TextField searchField;
    @FXML private TextField filterSubjectField;
    @FXML private TextField filterPeerField;

    @FXML private TableView<SearchResult> searchResultTable;
    @FXML private TableColumn<SearchResult, String> colName;
    @FXML private TableColumn<SearchResult, String> colSubject;
    @FXML private TableColumn<SearchResult, Long> colSize;
    @FXML private TableColumn<SearchResult, String> colOwner;

    @FXML private ProgressBar downloadProgress;
    @FXML private Label downloadStatusLabel;

    @FXML private Button btnDownload;
    @FXML private Button btnPause;
    @FXML private Button btnResume;
    @FXML private Button btnCancel;

    private final ObservableList<SearchResult> searchResults = FXCollections.observableArrayList();

    // job tải hiện tại (giống ConnectedPeerController)
    private DownloadJob currentJob;

    public void init(FileShareService fileShareService, ControlClient controlClient) {
        this.fileShareService = fileShareService;
        this.controlClient = controlClient;

        setupTable();
        // Bấm Enter ở ô nào cũng tìm kiếm (TextField có onAction thì không chuyển Enter cho nút mặc định nữa)
        searchField.setOnAction(e -> onSearch());
        filterSubjectField.setOnAction(e -> onSearch());
        filterPeerField.setOnAction(e -> onSearch());

        resetButtons();
        downloadProgress.setProgress(0);
        downloadStatusLabel.setText("Sẵn sàng");
    }

    private void setupTable() {
        colName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colSubject.setCellValueFactory(new PropertyValueFactory<>("subject"));
        colOwner.setCellValueFactory(new PropertyValueFactory<>("ownerName"));

        colSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        colSize.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : TransferStats.formatSize(item));
            }
        });

        searchResultTable.setItems(searchResults);
        searchResultTable.setPlaceholder(new Label("Nhập điều kiện và nhấn Tìm kiếm"));
    }

    @FXML
    private void onSearch() {
        String keyword = text(searchField);
        String subject = text(filterSubjectField);
        String peerName = text(filterPeerField);

        if (keyword.isEmpty() && subject.isEmpty() && peerName.isEmpty()) {
            Dialogs.error(searchResultTable, "Cảnh báo", "Thiếu thông tin",
                    "Vui lòng nhập ít nhất một điều kiện tìm kiếm (Tên file, Môn hoặc Peer).");
            return;
        }

        searchResults.clear();
        downloadStatusLabel.setText("Đang gửi yêu cầu tìm kiếm...");
        searchResultTable.setPlaceholder(new Label("⏳ Đang tìm kiếm..."));

        // chỉ hỏi các peer mình đang kết nối tới (đã có địa chỉ, không cần quét LAN)
        List<PeerInfo> connectedPeers = controlClient.getConnectedPeers();
        if (connectedPeers.isEmpty()) {
            downloadStatusLabel.setText("Không có peer nào kết nối.");
            searchResultTable.setPlaceholder(new Label("Không có kết nối nào."));
            return;
        }

        // gửi ở thread nền: peer đã tắt máy có thể mất tới vài giây mới báo lỗi kết nối.
        // Chỉ gửi từ khóa, lọc môn / peer làm ở phía mình khi nhận kết quả
        Thread sender = new Thread(() -> {
            for (PeerInfo p : connectedPeers) {
                controlClient.sendSearchRequest(p, keyword);
            }
        }, "send-search");
        sender.setDaemon(true);
        sender.start();
        downloadStatusLabel.setText("Đã gửi yêu cầu đến " + connectedPeers.size() + " peer.");

        PauseTransition delay = new PauseTransition(Duration.seconds(3));
        delay.setOnFinished(e -> {
            if (searchResults.isEmpty()) {
                searchResultTable.setPlaceholder(new Label("❌ Không tìm thấy kết quả nào phù hợp."));
                downloadStatusLabel.setText("Hoàn tất tìm kiếm. Không có kết quả.");
            }
        });
        delay.play();
    }

    // Được gọi từ ControlServer khi nhận kết quả search (SEARCH_RES) trả về
    public void onReceiveSearchResult(PeerInfo sender, String payload) {
        List<SearchService.RemoteEntry> entries = SearchService.parseResponsePayload(payload);
        Platform.runLater(() -> {
            // lọc theo điều kiện hiện tại
            String curKey = text(searchField).toLowerCase();
            String curSub = text(filterSubjectField).toLowerCase();
            String curPeer = text(filterPeerField).toLowerCase();

            for (SearchService.RemoteEntry e : entries) {
                if (!curKey.isEmpty() && !e.fileName().toLowerCase().contains(curKey)) continue;
                if (!curSub.isEmpty() && !e.subject().toLowerCase().contains(curSub)) continue;
                if (!curPeer.isEmpty() && !sender.getName().toLowerCase().contains(curPeer)) continue;

                searchResults.add(new SearchResult(e.fileName(), e.relativePath(), e.size(), e.subject(), sender));
            }
            if (!searchResults.isEmpty()) {
                downloadStatusLabel.setText("Tìm thấy " + searchResults.size() + " kết quả.");
            }
        });
    }

    @FXML
    private void onViewFileDetails() {
        SearchResult selected = searchResultTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        String content = "Kích thước: " + TransferStats.formatSize(selected.getSize()) + "\n" +
                "Môn học: " + selected.getSubject() + "\n" +
                "Chủ sở hữu: " + selected.getOwner().getName() + "\n" +
                "IP: " + selected.getOwner().getIp();

        Dialogs.info(searchResultTable, "Chi tiết file", selected.getFileName(), content);
    }

    // =========================
    // DOWNLOAD / PAUSE / RESUME / CANCEL
    // =========================

    @FXML
    private void onDownloadSelected() {
        SearchResult sel = searchResultTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            Dialogs.error(searchResultTable, "Thông báo", "Chưa chọn file", "Hãy chọn 1 file để tải.");
            return;
        }

        // tránh tải chồng
        if (currentJob != null && currentJob.isActive()) {
            Dialogs.info(searchResultTable, "Thông báo", "Đang có file đang tải", "Hãy tạm dừng hoặc hủy trước khi tải file khác.");
            return;
        }

        File selectedDir = ConnectedPeerController.chooseDownloadDir(searchResultTable);
        if (selectedDir == null) {
            downloadStatusLabel.setText("Đã hủy tải xuống");
            return;
        }

        Path saveTo = new File(selectedDir, sel.getFileName()).toPath();
        downloadProgress.setProgress(0);
        downloadStatusLabel.setText("Đang chuẩn bị tải: " + sel.getFileName());
        TransferStats stats = new TransferStats(sel.getSize());

        currentJob = fileShareService.startDownload(
                sel.getOwner(),
                sel.getRelativePath(), // đường dẫn tương đối bên peer sở hữu file
                saveTo,
                p -> {
                    String info = stats.update(p);
                    Platform.runLater(() -> {
                        downloadProgress.setProgress(p);
                        downloadStatusLabel.setText("Đang tải " + sel.getFileName() + ": " + info);
                    });
                },
                s -> Platform.runLater(() -> downloadStatusLabel.setText(s)),
                // trạng thái kết thúc -> trả nút bấm về ban đầu
                state -> Platform.runLater(() -> {
                    switch (state) {
                        case COMPLETED -> {
                            downloadProgress.setProgress(1.0);
                            resetButtons();
                        }
                        case CANCELLED -> {
                            downloadProgress.setProgress(0);
                            resetButtons();
                        }
                        case FAILED -> resetButtons();
                        default -> { }
                    }
                })
        );

        btnDownload.setDisable(true);
        btnPause.setDisable(false);
        btnResume.setDisable(true);
        btnCancel.setDisable(false);
    }

    @FXML
    private void onPauseDownload() {
        if (currentJob == null) {
            downloadStatusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        currentJob.pause();
        downloadStatusLabel.setText("Đã tạm dừng");
        btnPause.setDisable(true);
        btnResume.setDisable(false);
    }

    @FXML
    private void onResumeDownload() {
        if (currentJob == null) {
            downloadStatusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        currentJob.resume();
        downloadStatusLabel.setText("Đang tiếp tục tải...");
        btnPause.setDisable(false);
        btnResume.setDisable(true);
    }

    @FXML
    private void onCancelDownload() {
        if (currentJob == null) {
            downloadStatusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        // cancel có hiệu lực ngay ở checkpoint gần nhất, kể cả khi đang tạm dừng
        currentJob.cancel();
        downloadStatusLabel.setText("Đang hủy...");
    }

    private void resetButtons() {
        btnDownload.setDisable(false);
        btnPause.setDisable(true);
        btnResume.setDisable(true);
        btnCancel.setDisable(true);
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }
}
