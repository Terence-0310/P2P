package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.example.p2pfileshare.HelloApplication;
import org.example.p2pfileshare.model.DownloadHistory;
import org.example.p2pfileshare.service.HistoryService;

import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class HistoryTabController {

    private HistoryService historyService;
    private Label globalStatusLabel;

    @FXML private TableView<DownloadHistory> historyTable;

    // data table
    private final ObservableList<DownloadHistory> histories = FXCollections.observableArrayList();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // callback lắng nghe thay đổi
    private Runnable historyChangeListener;

    public void init(HistoryService historyService, Label globalStatusLabel) {
        this.historyService = historyService;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        // initial load
        refreshHistory();

        // Đăng ký lắng nghe để tự động cập nhật UI khi file lịch sử thay đổi
        if (this.historyService != null) {
            historyChangeListener = () -> Platform.runLater(this::refreshHistory);
            this.historyService.addHistoryChangeListener(historyChangeListener);
            System.out.println("Đã đăng ký callback theo dõi file lịch sử.");
        }
    }

    public void setupTable() {
        if (historyTable == null) return;

        historyTable.setItems(histories);
        historyTable.getColumns().clear();

        // tên file
        TableColumn<DownloadHistory, String> colName = new TableColumn<>("Tên File");
        colName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colName.setPrefWidth(200);

        // đường dẫn lưu
        TableColumn<DownloadHistory, String> colPath = new TableColumn<>("Đường dẫn lưu");
        colPath.setCellValueFactory(new PropertyValueFactory<>("savedPath"));
        colPath.setPrefWidth(350);

        // Nguồn Peer
        TableColumn<DownloadHistory, String> colPeer = new TableColumn<>("Peer");
        colPeer.setCellValueFactory(cell -> {
            DownloadHistory d = cell.getValue();
            String v = (d.getPeerName() == null ? "" : d.getPeerName()) +
                    (d.getPeerIp() == null || d.getPeerIp().isBlank() ? "" : " (" + d.getPeerIp() + ")");
            return new ReadOnlyStringWrapper(v);

        });
        colPeer.setPrefWidth(200);

        // ngày tải
        TableColumn<DownloadHistory, String> colDate = new TableColumn<>("Downloaded At");
        colDate.setCellValueFactory(cell -> {
            DownloadHistory d = cell.getValue();
            String s = d.getDownloadDate() != null ? d.getDownloadDate().format(dateFormatter) : "";
            return new ReadOnlyStringWrapper(s);
        });
        colDate.setPrefWidth(160);

        // file còn trên máy hay đã bị xóa/di chuyển
        TableColumn<DownloadHistory, String> colExists = new TableColumn<>("Trạng thái");
        colExists.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                fileExists(cell.getValue()) ? "✔ Còn" : "✖ Không còn"));
        colExists.setPrefWidth(100);

        historyTable.getColumns().addAll(colName, colPath, colPeer, colDate, colExists);
        historyTable.setPlaceholder(new Label("Chưa có lịch sử tải xuống"));

        // nhấp đúp để mở file, chuột phải để có thêm lựa chọn
        historyTable.setRowFactory(tv -> {
            TableRow<DownloadHistory> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) openFile(row.getItem());
            });

            MenuItem openItem = new MenuItem("📄 Mở file");
            openItem.setOnAction(ev -> openFile(row.getItem()));
            MenuItem folderItem = new MenuItem("📂 Mở thư mục chứa");
            folderItem.setOnAction(ev -> openFolder(row.getItem()));
            MenuItem removeItem = new MenuItem("🗑 Xóa khỏi lịch sử");
            removeItem.setOnAction(ev -> historyService.removeHistory(row.getItem()));
            ContextMenu menu = new ContextMenu(openItem, folderItem, new SeparatorMenuItem(), removeItem);

            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(menu));
            return row;
        });
    }

    private static boolean fileExists(DownloadHistory h) {
        return h.getSavedPath() != null && new File(h.getSavedPath()).isFile();
    }

    private void openFile(DownloadHistory h) {
        if (h == null) return;
        if (!fileExists(h)) {
            showSuccessDialog("Không tìm thấy file", "File đã bị xóa hoặc di chuyển:\n" + h.getSavedPath());
            return;
        }
        showDocument(new File(h.getSavedPath()));
    }

    private void openFolder(DownloadHistory h) {
        if (h == null || h.getSavedPath() == null) return;
        File folder = new File(h.getSavedPath()).getParentFile();
        if (folder == null || !folder.isDirectory()) {
            showSuccessDialog("Không tìm thấy thư mục", "Thư mục không còn tồn tại.");
            return;
        }
        showDocument(folder);
    }

    // mở bằng ứng dụng mặc định của hệ điều hành (thư mục -> trình quản lý file)
    private void showDocument(File f) {
        if (HelloApplication.hostServices() != null) {
            HelloApplication.hostServices().showDocument(f.toURI().toString());
            if (globalStatusLabel != null) globalStatusLabel.setText("Đang mở: " + f.getName());
        }
    }

    @FXML
    private void onRefreshHistory() {
        refreshHistory();
        if (globalStatusLabel != null) {
            globalStatusLabel.setText("Lịch sử đã nạp: " + histories.size() + " mục");
        }
    }

    private void refreshHistory() {
        if (historyService == null) return;
        List<DownloadHistory> list = new java.util.ArrayList<>(historyService.listHistories());
        // mới nhất lên đầu
        list.sort(java.util.Comparator.comparing(DownloadHistory::getDownloadDate,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        histories.setAll(list);
    }

    @FXML
    private void onClearHistory() {
        if (historyService == null) return;

        if (histories.isEmpty()) {
            // Nếu trống thì không cần xóa, chỉ báo nhẹ
            if (globalStatusLabel != null) globalStatusLabel.setText("Lịch sử đang trống.");
            return;
        }

        try {
            // 1. Load Dialog
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            // 2. Tạo Stage không viền
            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (historyTable.getScene() != null) {
                dialogStage.initOwner(historyTable.getScene().getWindow());
            }

            dialogStage.setScene(new Scene(page));

            // 3. Cấu hình Controller
            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Thiết lập nội dung hiển thị dialog
            controller.setContent(
                    "🗑 Xóa lịch sử",
                    "Bạn có muốn xóa toàn bộ " + histories.size() + " mục lịch sử không?",
                    "Hành động này sẽ xóa danh sách nhật ký tải xuống vĩnh viễn.",
                    "Xóa ngay"
            );

            // Chuyển sang màu đỏ cảnh báo
            controller.setStyleDanger();

            // 4. Hiển thị
            dialogStage.showAndWait();

            // 5. Xử lý kết quả
            if (controller.isConfirmed()) {
                historyService.clearHistory();
                refreshHistory();
                if (globalStatusLabel != null) {
                    globalStatusLabel.setText("Đã xóa toàn bộ lịch sử tải xuống.");
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            // Fallback nếu lỗi load FXML
            new Alert(Alert.AlertType.ERROR, "Lỗi hiển thị hộp thoại: " + e.getMessage()).showAndWait();
        }
    }

    private void showSuccessDialog(String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            if (historyTable.getScene() != null) {
                dialogStage.initOwner(historyTable.getScene().getWindow());
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

    // optional: call this when controller is disposed to avoid leaks
    public void dispose() {
        if (historyService != null && historyChangeListener != null) {
            historyService.removeHistoryChangeListener(historyChangeListener);
        }
    }
}
