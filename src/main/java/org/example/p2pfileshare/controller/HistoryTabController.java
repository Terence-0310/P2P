package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import org.example.p2pfileshare.HelloApplication;
import org.example.p2pfileshare.model.DownloadHistory;
import org.example.p2pfileshare.service.HistoryService;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HistoryTabController {

    private HistoryService historyService;
    private Label globalStatusLabel;

    @FXML private TableView<DownloadHistory> historyTable;

    // data table
    private final ObservableList<DownloadHistory> histories = FXCollections.observableArrayList();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void init(HistoryService historyService, Label globalStatusLabel) {
        this.historyService = historyService;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        // initial load
        refreshHistory();

        // tự động cập nhật bảng khi có lượt tải mới / xóa lịch sử
        historyService.addHistoryChangeListener(() -> Platform.runLater(this::refreshHistory));
    }

    private void setupTable() {
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
                    Bindings.when(row.emptyProperty())
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
            Dialogs.error(historyTable, "Thông báo", "Không tìm thấy file", "File đã bị xóa hoặc di chuyển:\n" + h.getSavedPath());
            return;
        }
        showDocument(new File(h.getSavedPath()));
    }

    private void openFolder(DownloadHistory h) {
        if (h == null || h.getSavedPath() == null) return;
        File folder = new File(h.getSavedPath()).getParentFile();
        if (folder == null || !folder.isDirectory()) {
            Dialogs.error(historyTable, "Thông báo", "Không tìm thấy thư mục", "Thư mục không còn tồn tại.");
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
        List<DownloadHistory> list = new ArrayList<>(historyService.listHistories());
        // mới nhất lên đầu
        list.sort(Comparator.comparing(DownloadHistory::getDownloadDate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        histories.setAll(list);
    }

    @FXML
    private void onClearHistory() {
        if (histories.isEmpty()) {
            if (globalStatusLabel != null) globalStatusLabel.setText("Lịch sử đang trống.");
            return;
        }

        boolean confirmed = Dialogs.confirm(historyTable,
                "🗑 Xóa lịch sử",
                "Bạn có muốn xóa toàn bộ " + histories.size() + " mục lịch sử không?",
                "Hành động này sẽ xóa danh sách nhật ký tải xuống vĩnh viễn.",
                "Xóa ngay");
        if (confirmed) {
            historyService.clearHistory();
            if (globalStatusLabel != null) globalStatusLabel.setText("Đã xóa toàn bộ lịch sử tải xuống.");
        }
    }
}
