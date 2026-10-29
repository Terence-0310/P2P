package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.service.DocumentSummaryService;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.util.AppConfig;
import org.example.p2pfileshare.util.TransferStats;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ShareTabController {

    private static final String KEY_SHARE_DIR = "shared_folder";

    private FileShareService fileShareService;
    private Label globalStatusLabel;

    @FXML
    private TextField shareFolderField;

    @FXML
    private TableView<SharedFileLocal> sharedFileTable;
    @FXML
    private TableColumn<SharedFileLocal, String> colSharedName;
    @FXML
    private TableColumn<SharedFileLocal, String> colSharedType;
    @FXML
    private TableColumn<SharedFileLocal, Long> colSharedSize;
    @FXML
    private TableColumn<SharedFileLocal, String> colSharedSubject;
    @FXML
    private TableColumn<SharedFileLocal, String> colSharedTags;
    @FXML
    private TableColumn<SharedFileLocal, Boolean> colSharedVisibility;

    private final ObservableList<SharedFileLocal> sharedFiles = FXCollections.observableArrayList();
    private final DocumentSummaryService documentSummaryService = new DocumentSummaryService();

    public void init(FileShareService fileShareService, Label globalStatusLabel) {
        this.fileShareService = fileShareService;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        // thư mục chia sẻ thay đổi (thêm/xóa/sửa file, kể cả từ Explorer) -> tự làm mới bảng
        fileShareService.addFilesChangedListener(() -> Platform.runLater(this::refreshSharedFiles));
        loadLastSharedFolder();
    }

    // ánh xạ data từ SharedFileLocal lên bảng
    private void setupTable() {
        // hiển thị đường dẫn tương đối để phân biệt file trong thư mục con
        colSharedName.setCellValueFactory(new PropertyValueFactory<>("relativePath"));
        colSharedType.setCellValueFactory(new PropertyValueFactory<>("extension"));
        colSharedSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        colSharedSize.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : TransferStats.formatSize(item));
            }
        });

        colSharedSubject.setCellValueFactory(new PropertyValueFactory<>("subject"));
        colSharedTags.setCellValueFactory(new PropertyValueFactory<>("tag"));
        colSharedVisibility.setCellValueFactory(new PropertyValueFactory<>("visible"));
        sharedFileTable.setItems(sharedFiles);

        sharedFileTable.setRowFactory(tv -> {
            TableRow<SharedFileLocal> row = new TableRow<>();

            MenuItem summarizeItem = new MenuItem("✨ Tóm tắt nội dung (AI)");
            summarizeItem.setStyle("-fx-font-weight: bold;");
            summarizeItem.setOnAction(event -> {
                if (row.getItem() != null) onSummarizeFile(row.getItem());
            });

            MenuItem deleteItem = new MenuItem("🗑 Xóa file");
            deleteItem.setOnAction(event -> {
                sharedFileTable.getSelectionModel().select(row.getItem());
                onRemoveSharedFile();
            });

            ContextMenu contextMenu = new ContextMenu(summarizeItem, new SeparatorMenuItem(), deleteItem);

            // Chỉ hiện menu khi dòng không rỗng
            row.contextMenuProperty().bind(
                    Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(contextMenu));
            return row;
        });
    }

    private void onSummarizeFile(SharedFileLocal fileMeta) {
        File readFile = fileShareService.resolveSharedFile(fileMeta.getRelativePath());
        if (readFile == null || !readFile.exists()) {
            Dialogs.error(sharedFileTable, "Lỗi", "Không tìm thấy file", "File không tồn tại trên ổ cứng.");
            return;
        }

        setStatus("🤖 AI đang đọc và tóm tắt file...");

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return documentSummaryService.summarize(readFile);
            }
        };

        task.setOnSucceeded(e -> {
            setStatus("🤖 AI đã hoàn thành tóm tắt file.");
            Dialogs.summary(sharedFileTable, fileMeta.getFileName(), task.getValue());
        });

        task.setOnFailed(e -> {
            setStatus("🤖 AI không thể tóm tắt file.");
            Dialogs.error(sharedFileTable, "Lỗi", "Không tóm tắt được file", "AI không thể tóm tắt file do lỗi xảy ra.");
            task.getException().printStackTrace();
        });
        startDaemon(task, "summarize-local");
    }

    // Load thư mục chia sẻ đã lưu trong AppConfig
    private void loadLastSharedFolder() {
        String last = AppConfig.load(KEY_SHARE_DIR);
        if (last != null && new File(last).isDirectory()) {
            applyShareFolder(new File(last));
        }
    }

    // áp dụng thư mục chia sẻ (listener sẽ tự làm mới bảng)
    private void applyShareFolder(File dir) {
        shareFolderField.setText(dir.getAbsolutePath());
        fileShareService.setShareFolder(dir);
        setStatus("Thư mục chia sẻ: " + dir.getName());
    }

    @FXML
    private void onChooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục chia sẻ");

        String lastDir = AppConfig.load(KEY_SHARE_DIR);
        if (lastDir != null && new File(lastDir).isDirectory()) {
            chooser.setInitialDirectory(new File(lastDir));
        }

        File dir = chooser.showDialog(shareFolderField.getScene().getWindow());
        if (dir != null) {
            AppConfig.save(KEY_SHARE_DIR, dir.getAbsolutePath());
            applyShareFolder(dir);
        }
    }

    @FXML
    private void onRefreshSharedFiles() {
        refreshSharedFiles();
        setStatus("Đã quét lại thư mục và cập nhật Tag.");
    }

    private void refreshSharedFiles() {
        // đọc thư mục ở thread nền (thư mục lớn có thể mất thời gian), rồi cập nhật bảng trên JavaFX thread
        Task<List<SharedFileLocal>> task = new Task<>() {
            @Override
            protected List<SharedFileLocal> call() {
                return fileShareService.listSharedFiles();
            }
        };
        task.setOnSucceeded(e -> sharedFiles.setAll(task.getValue()));
        startDaemon(task, "list-shared-files");
    }

    // Chép file người dùng chọn vào thư mục chia sẻ
    @FXML
    private void onAddSharedFile() {
        File folder = fileShareService.getShareFolder();
        if (folder == null) {
            Dialogs.info(sharedFileTable, "Thông báo", "Chưa chọn thư mục", "Hãy chọn thư mục chia sẻ trước.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tài liệu để chia sẻ");
        List<File> files = chooser.showOpenMultipleDialog(sharedFileTable.getScene().getWindow());
        if (files == null || files.isEmpty()) return;

        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                int copied = 0;
                for (File f : files) {
                    Path target = FileShareService.resolveSavePath(folder.toPath().resolve(f.getName()));
                    if (f.toPath().toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) continue;
                    Files.copy(f.toPath(), target);
                    copied++;
                }
                return copied;
            }
        };
        task.setOnSucceeded(e -> {
            refreshSharedFiles();
            setStatus("Đã thêm " + task.getValue() + " tài liệu vào thư mục chia sẻ");
        });
        task.setOnFailed(e -> Dialogs.error(sharedFileTable, "Lỗi", "Không chép được file",
                task.getException().getMessage()));

        setStatus("Đang chép " + files.size() + " file...");
        startDaemon(task, "add-shared-files");
    }

    @FXML
    private void onRemoveSharedFile() {
        SharedFileLocal selected = sharedFileTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Dialogs.info(sharedFileTable, "Thông báo", "Chưa chọn file", "Vui lòng chọn file để xóa.");
            return;
        }

        boolean confirmed = Dialogs.confirm(sharedFileTable,
                "🗑 Xác nhận xóa",
                "Xóa file: " + selected.getFileName() + "?",
                "Hành động này sẽ xóa file khỏi ổ cứng vĩnh viễn.",
                "Đồng ý");
        if (!confirmed) return;

        File fileToDelete = fileShareService.resolveSharedFile(selected.getRelativePath());
        if (fileToDelete != null && fileToDelete.exists() && fileToDelete.delete()) {
            // Peer đang xem kho file sẽ được báo tự động nhờ FolderWatcher
            sharedFiles.remove(selected);
            refreshSharedFiles();
            Dialogs.info(sharedFileTable, "Thông báo", "Thành công", "Đã xóa file thành công!");
        } else {
            Dialogs.error(sharedFileTable, "Lỗi", "Không thể xóa file", "File đang được mở hoặc bạn không có quyền xóa.");
        }
    }

    private void setStatus(String text) {
        if (globalStatusLabel != null) globalStatusLabel.setText(text);
    }

    private static void startDaemon(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
