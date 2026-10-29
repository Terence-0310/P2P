package org.example.p2pfileshare.controller;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.io.IOException;

/**
 * Hộp thoại dùng chung cho mọi tab (ConfirmationDialog.fxml / SummaryResultDialog.fxml).
 * Phải gọi trên JavaFX thread. {@code anchor} là node bất kỳ trong cửa sổ chính, dùng để dialog hiện giữa app.
 */
final class Dialogs {

    private static final String CONFIRM_FXML = "/org/example/p2pfileshare/ConfirmationDialog.fxml";
    private static final String SUMMARY_FXML = "/org/example/p2pfileshare/SummaryResultDialog.fxml";

    private enum Style { DEFAULT, DANGER, SUCCESS }

    private Dialogs() {}

    /** Hỏi Đồng ý / Hủy (màu mặc định), trả về true nếu người dùng bấm nút đồng ý */
    static boolean ask(Node anchor, String title, String header, String content, String okText) {
        return show(anchor, title, header, content, okText, Style.DEFAULT);
    }

    /** Như {@link #ask} nhưng màu đỏ, dùng cho hành động nguy hiểm (xóa, ngắt kết nối...) */
    static boolean confirm(Node anchor, String title, String header, String content, String okText) {
        return show(anchor, title, header, content, okText, Style.DANGER);
    }

    /** Thông báo màu xanh, chỉ có nút Đóng */
    static void info(Node anchor, String title, String header, String content) {
        show(anchor, title, header, content, "Đóng", Style.SUCCESS);
    }

    /** Thông báo lỗi / cảnh báo màu đỏ */
    static void error(Node anchor, String title, String header, String content) {
        show(anchor, title, header, content, "Đóng", Style.DANGER);
    }

    private static boolean show(Node anchor, String title, String header, String content, String okText, Style style) {
        try {
            FXMLLoader loader = new FXMLLoader(Dialogs.class.getResource(CONFIRM_FXML));
            Stage stage = newStage(anchor, loader.load(), StageStyle.UNDECORATED);
            ConfirmationController controller = loader.getController();
            controller.setDialogStage(stage);
            controller.setContent(title, header, content, okText);
            if (style == Style.DANGER) controller.setStyleDanger();
            if (style == Style.SUCCESS) controller.setStyleSuccess();
            stage.showAndWait();
            return controller.isConfirmed();
        } catch (IOException e) {
            e.printStackTrace();
            Alert alert = new Alert(style == Style.SUCCESS ? Alert.AlertType.INFORMATION : Alert.AlertType.CONFIRMATION, content);
            alert.setTitle(title);
            alert.setHeaderText(header);
            return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        }
    }

    /** Kết quả tóm tắt tài liệu, có nút sao chép */
    static void summary(Node anchor, String fileName, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(Dialogs.class.getResource(SUMMARY_FXML));
            Stage stage = newStage(anchor, loader.load(), StageStyle.TRANSPARENT);
            stage.getScene().setFill(Color.TRANSPARENT); // để bo góc trong FXML hiển thị đúng
            SummaryResultController controller = loader.getController();
            controller.setDialogStage(stage);
            controller.setContent(fileName, content);
            stage.showAndWait();
        } catch (IOException e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Kết quả Tóm tắt AI");
            alert.setHeaderText("Tóm tắt file: " + fileName);
            TextArea textArea = new TextArea(content);
            textArea.setEditable(false);
            textArea.setWrapText(true);
            alert.getDialogPane().setContent(textArea);
            alert.showAndWait();
        }
    }

    private static Stage newStage(Node anchor, Parent page, StageStyle style) {
        Stage stage = new Stage();
        stage.initStyle(style);
        stage.initModality(Modality.APPLICATION_MODAL);
        Window owner = anchor != null && anchor.getScene() != null ? anchor.getScene().getWindow() : null;
        if (owner != null) stage.initOwner(owner);
        stage.setScene(new Scene(page));
        return stage;
    }
}
