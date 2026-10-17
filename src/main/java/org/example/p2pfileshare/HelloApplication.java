package org.example.p2pfileshare;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class HelloApplication extends Application {

    private static HostServices hostServices;

    /** Dùng để mở file / thư mục bằng ứng dụng mặc định của hệ điều hành */
    public static HostServices hostServices() {
        return hostServices;
    }

    @Override
    public void start(Stage stage) {
        hostServices = getHostServices();
        try {
            FXMLLoader loader = new FXMLLoader(
                    HelloApplication.class.getResource("MainView.fxml")
            );

            Scene scene = new Scene(loader.load(), 1000, 650);

            stage.setTitle("P2P File Sharing - Learning Assistant");
            stage.setScene(scene);
            stage.setMinWidth(900);
            stage.setMinHeight(600);
            stage.show();

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("❌ Không thể load file MainView.fxml");
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
