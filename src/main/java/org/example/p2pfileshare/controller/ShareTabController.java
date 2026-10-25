package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.service.DocumentSummaryService;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.util.AppConfig;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.service.PeerService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.IOException;

public class ShareTabController {

    private FileShareService fileShareService;
    private Label globalStatusLabel;

    private static final String KEY_SHARE_DIR = "shared_folder";

    @FXML
    private TextField shareFolderField;

    // TableView dùng SharedFileLocal
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

    private PeerTabController peerTabController;
    private ControlClient controlClient;
    private PeerService peerService;
    private final DocumentSummaryService documentSummaryService = new DocumentSummaryService();

    public void init(FileShareService fileShareService, Label globalStatusLabel, ControlClient controlClient,
            PeerTabController peerTabController) {
        this.fileShareService = fileShareService;
        this.globalStatusLabel = globalStatusLabel;
        this.controlClient = controlClient;
        this.peerTabController = peerTabController;

        setupTable();
        // thư mục chia sẻ thay đổi (thêm/xóa/sửa file, kể cả từ Explorer) -> tự làm mới bảng
        fileShareService.addFilesChangedListener(() -> Platform.runLater(this::refreshSharedFiles));
        loadLastSharedFolder();
    }

    // ánh xạ data từ file sharefilelocal lên bảng
    private void setupTable() {
        // hiển thị đường dẫn tương đối để phân biệt file trong thư mục con
        colSharedName.setCellValueFactory(new PropertyValueFactory<>("relativePath"));
        colSharedType.setCellValueFactory(new PropertyValueFactory<>("extension"));
        colSharedSize.setCellValueFactory(new PropertyValueFactory<>("size"));

        // Format hiển thị kích thước file
        colSharedSize.setCellFactory(col -> new TableCell<SharedFileLocal, Long>() { // chặn data trước để format
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(formatSize(item));
                }
            }
        });

        colSharedSubject.setCellValueFactory(new PropertyValueFactory<>("subject"));
        if (colSharedTags != null) {
            colSharedTags.setCellValueFactory(new PropertyValueFactory<>("tag"));
        }
        colSharedVisibility.setCellValueFactory(new PropertyValueFactory<>("visible"));
        sharedFileTable.setItems(sharedFiles);

        sharedFileTable.setRowFactory(tv -> {
            TableRow<SharedFileLocal> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();

            MenuItem summarizeItem = new MenuItem("✨ Tóm tắt nội dung (AI)");
            summarizeItem.setStyle("-fx-font-weight: bold;");

            summarizeItem.setOnAction(event -> {
                SharedFileLocal selected = row.getItem();
                if (selected != null) {
                    onSummarizeFile(selected);
                }
            });

            MenuItem deleteItem = new MenuItem("🗑 Xóa file");
            deleteItem.setOnAction(event -> {
                sharedFileTable.getSelectionModel().select(row.getItem());
                onRemoveSharedFile();
            });

            contextMenu.getItems().addAll(summarizeItem, new SeparatorMenuItem(), deleteItem);

            // Chỉ hiện menu khi dòng không rỗng
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(contextMenu));
            return row;
        });
    }

    private void onSummarizeFile(SharedFileLocal fileMeta) {
        File readFile = fileShareService.resolveSharedFile(fileMeta.getRelativePath());

        if (readFile == null || !readFile.exists()) {
            showSuccessDialog("Lỗi", "File không tồn tại trên ổ cứng.");
            return;
        }

        // Hiện thông báo chờ
        if (globalStatusLabel != null)
            globalStatusLabel.setText("🤖 AI đang đọc và tóm tắt file...");

        // chay ngam
        javafx.concurrent.Task<String> task = new javafx.concurrent.Task<>() {
            @Override
            protected String call() throws Exception {
                return documentSummaryService.summarize(readFile);
            }
        };

        task.setOnSucceeded(e -> {
            if (globalStatusLabel != null)
                globalStatusLabel.setText("🤖 AI đã hoàn thành tóm tắt file.");
            String summary = task.getValue();

            showSummaryResultDialog(fileMeta.getFileName(), summary);
        });

        task.setOnFailed(e -> {
            if (globalStatusLabel != null)
                globalStatusLabel.setText("🤖 AI không thể tóm tắt file.");
            showSuccessDialog("Lỗi", "AI không thể tóm tắt file do lỗi xảy ra.");
            e.getSource().getException().printStackTrace();
        });
        Thread t = new Thread(task, "summarize-local");
        t.setDaemon(true);
        t.start();
    }

    // Load thư mục chia sẻ đã lưu trong AppConfig
    private void loadLastSharedFolder() {
        String last = AppConfig.load(KEY_SHARE_DIR);
        if (last != null) {
            File dir = new File(last);
            if (dir.isDirectory()) {
                applyShareFolder(dir);
            }
        }
    }

    private void applyShareFolder(File dir) {
        shareFolderField.setText(dir.getAbsolutePath());
        fileShareService.setShareFolder(dir);

        if (globalStatusLabel != null) {
            globalStatusLabel.setText("Thư mục chia sẻ: " + dir.getName());
        }
    }

    // Chọn thư mục chia sẻ
    @FXML
    private void onChooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục chia sẻ");

        String lastDir = AppConfig.load(KEY_SHARE_DIR);
        if (lastDir != null) {
            File prev = new File(lastDir);
            if (prev.isDirectory())
                chooser.setInitialDirectory(prev);
        }

        Stage stage = (Stage) shareFolderField.getScene().getWindow();
        File dir = chooser.showDialog(stage); // show dialog

        if (dir != null) {
            shareFolderField.setText(dir.getAbsolutePath());
            AppConfig.save(KEY_SHARE_DIR, dir.getAbsolutePath()); // lưu cấu hình
            fileShareService.setShareFolder(dir); // áp dụng thư mục chia sẻ (listener sẽ tự làm mới bảng)

            if (globalStatusLabel != null) {
                globalStatusLabel.setText("Thư mục chia sẻ: " + dir.getName());
            }
        }

    }

    // Refresh lại bảng file
    @FXML
    private void onRefreshSharedFiles() {
        refreshSharedFiles();

        if (globalStatusLabel != null) {
            globalStatusLabel.setText("Đã quét lại thư mục và cập nhật Tag.");
        }
    }

    private void refreshSharedFiles() {
        // đọc thư mục ở thread nền (thư mục lớn có thể mất thời gian), rồi cập nhật bảng trên JavaFX thread
        Task<List<SharedFileLocal>> task = new Task<>() {
            @Override
            protected List<SharedFileLocal> call() {
                return fileShareService.listSharedFiles();
            }
        };
        task.setOnSucceeded(e -> {
            sharedFiles.setAll(task.getValue());
            sharedFileTable.refresh();
        });
        Thread t = new Thread(task, "list-shared-files");
        t.setDaemon(true);
        t.start();
    }

    // Chép file người dùng chọn vào thư mục chia sẻ
    @FXML
    private void onAddSharedFile() {
        File folder = fileShareService.getShareFolder();
        if (folder == null) {
            showSuccessDialog("Thông báo", "Hãy chọn thư mục chia sẻ trước.");
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
            if (globalStatusLabel != null) {
                globalStatusLabel.setText("Đã thêm " + task.getValue() + " tài liệu vào thư mục chia sẻ");
            }
        });
        task.setOnFailed(e -> showSuccessDialog("Lỗi", "Không chép được file: " + task.getException().getMessage()));

        if (globalStatusLabel != null) globalStatusLabel.setText("Đang chép " + files.size() + " file...");
        Thread t = new Thread(task, "add-shared-files");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onRemoveSharedFile() {
        SharedFileLocal selected = sharedFileTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showSuccessDialog("Thông báo", "Vui lòng chọn file để xóa.");
            return;
        }

        boolean confirmed = showConfirmDialog(
                "🗑 Xác nhận xóa",
                "Xóa file: " + selected.getFileName() + "?",
                "Hành động này sẽ xóa file khỏi ổ cứng vĩnh viễn.");

        if (confirmed) {
            // Lấy đường dẫn file thật
            File fileToDelete = fileShareService.resolveSharedFile(selected.getRelativePath());
            // Thực hiện xóa
            if (fileToDelete != null && fileToDelete.exists() && fileToDelete.delete()) {
                // Xóa thành công -> Cập nhật lại giao diện.
                // Peer đang xem kho file sẽ được báo tự động nhờ FolderWatcher
                sharedFiles.remove(selected); // Xóa khỏi bảng
                refreshSharedFiles();
                showSuccessDialog("Thành công", "Đã xóa file thành công!");
            } else {
                showConfirmDialog("Lỗi", "Không thể xóa file", "File đang được mở hoặc bạn không có quyền xóa.");
            }
        }
    }

    private boolean showConfirmDialog(String title, String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (shareFolderField.getScene() != null) {
                dialogStage.initOwner(shareFolderField.getScene().getWindow());
            }

            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);
            controller.setContent(title, header, content, "Đồng ý"); // Nút mặc định hoặc set text tùy ý

            if (title.contains("xóa") || title.contains("Lỗi")) {
                controller.setStyleDanger();
            }

            dialogStage.showAndWait();
            return controller.isConfirmed();

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void showSuccessDialog(String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (sharedFileTable.getScene() != null) {
                dialogStage.initOwner(sharedFileTable.getScene().getWindow());
            }

            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Set nội dung
            controller.setContent("Thông báo", header, content, "Đóng");

            // GỌI HÀM MỚI ĐỂ CHUYỂN GIAO DIỆN SANG MÀU XANH & ẨN NÚT HỦY
            controller.setStyleSuccess();

            dialogStage.showAndWait();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showSummaryResultDialog(String fileName, String summaryContent) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/SummaryResultDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (sharedFileTable.getScene() != null) {
                dialogStage.initOwner(sharedFileTable.getScene().getWindow());
            }

            dialogStage.setScene(new Scene(page));

            SummaryResultController controller = loader.getController();
            controller.setDialogStage(dialogStage);
            controller.setContent(fileName, summaryContent);

            dialogStage.showAndWait();

        } catch (IOException e) {
            e.printStackTrace();
            // Fallback nếu không load được FXML
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Kết quả Tóm tắt AI");
            alert.setHeaderText("✨ Tóm tắt nội dung file: " + fileName);
            TextArea textArea = new TextArea(summaryContent);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);
            GridPane.setVgrow(textArea, Priority.ALWAYS);
            GridPane.setHgrow(textArea, Priority.ALWAYS);
            GridPane expContent = new GridPane();
            expContent.setMaxWidth(Double.MAX_VALUE);
            expContent.add(textArea, 0, 1);
            alert.getDialogPane().setContent(expContent);
            alert.showAndWait();
        }
    }

    // hàm format size của file
    private static String formatSize(long bytes) {
        final double KB = 1024.0;
        final double MB = KB * 1024.0;
        final double GB = MB * 1024.0;
        if (bytes >= GB) {
            return String.format("%.2f GB", bytes / GB);
        } else if (bytes >= MB) {
            return String.format("%.2f MB", bytes / MB);
        } else {
            return String.format("%.2f KB", bytes / KB);
        }
    }
}
