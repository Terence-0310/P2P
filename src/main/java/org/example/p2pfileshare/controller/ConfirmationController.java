package org.example.p2pfileshare.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

public class ConfirmationController {

    @FXML private Label titleLabel;
    @FXML private Label headerLabel;
    @FXML private Label contentLabel;
    @FXML private Button confirmButton;
    @FXML private HBox headerBox;
    @FXML private Button cancelButton;

    private boolean confirmed = false;
    private Stage dialogStage;

    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    // Hàm thiết lập nội dung cho Dialog
    public void setContent(String title, String header, String content, String okButtonText) {
        titleLabel.setText(title);
        headerLabel.setText(header);
        contentLabel.setText(content);
        if (okButtonText != null) {
            confirmButton.setText(okButtonText);
        }
    }

    // Tùy chọn: Đổi màu dialog sang màu đỏ nếu là hành động nguy hiểm (như Xóa)
    public void setStyleDanger() {
        headerBox.setStyle("-fx-background-color: #e74c3c; -fx-padding: 10 15;");
        confirmButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-background-radius: 5; -fx-cursor: hand; -fx-font-weight: bold; -fx-padding: 8 20;");
    }

    public void setStyleSuccess() {
        // Đổi màu header và nút bấm sang xanh lá (Green)
        headerBox.setStyle("-fx-background-color: #2ecc71; -fx-padding: 10 15;");
        confirmButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white; -fx-background-radius: 5; -fx-cursor: hand; -fx-font-weight: bold; -fx-padding: 8 20;");

        // Ẩn nút Cancel đi (vì thông báo thành công chỉ cần nút Đóng)
        cancelButton.setVisible(false);
        cancelButton.setManaged(false); // setManaged(false) giúp nút Confirm tự động ra giữa

        confirmButton.setText("Đóng");
    }

    @FXML
    private void onConfirm() {
        confirmed = true;
        if (dialogStage != null) dialogStage.close();
    }

    @FXML
    private void onCancel() {
        confirmed = false;
        if (dialogStage != null) dialogStage.close();
    }
}