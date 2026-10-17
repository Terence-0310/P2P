package org.example.p2pfileshare.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class SummaryResultController {

    @FXML
    private Label fileNameLabel;
    @FXML
    private TextArea summaryArea;
    @FXML
    private Button copyButton;
    @FXML
    private VBox rootBox;
    @FXML
    private HBox headerBox;

    private Stage dialogStage;

    private double xOffset = 0;
    private double yOffset = 0;

    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;

        if (headerBox != null) {
            headerBox.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });

            headerBox.setOnMouseDragged(event -> {
                dialogStage.setX(event.getScreenX() - xOffset);
                dialogStage.setY(event.getScreenY() - yOffset);
            });
        }
    }

    public void setContent(String fileName, String summaryContent) {
        if (fileNameLabel != null)
            fileNameLabel.setText(fileName);
        if (summaryArea != null) {
            summaryArea.setText(summaryContent);
            // Scroll to top
            summaryArea.setScrollTop(0);
        }
    }

    @FXML
    private void onCopy() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(summaryArea.getText());
        clipboard.setContent(content);

        copyButton.setText("Đã sao chép! ✓");
        copyButton.getStyleClass().remove("outline");
        copyButton.getStyleClass().add("success");
        copyButton.setDisable(true);

        // Tự động reset lại nút sau 2 giây
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        pause.setOnFinished(e -> {
            copyButton.setText("📋 Sao chép nội dung");

            copyButton.getStyleClass().remove("success");
            copyButton.getStyleClass().add("outline");

            copyButton.setDisable(false);
        });
        pause.play();
    }

    @FXML
    private void onClose() {
        if (dialogStage != null) {
            dialogStage.close();
        }
    }
}
