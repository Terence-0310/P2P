module org.example.p2pfileshare {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires java.prefs;
    requires com.google.gson;
    requires org.apache.pdfbox;
    requires org.apache.poi.ooxml;

    exports org.example.p2pfileshare;
    exports org.example.p2pfileshare.controller;

    opens org.example.p2pfileshare to javafx.fxml;
    opens org.example.p2pfileshare.controller to javafx.fxml;
    opens org.example.p2pfileshare.model to javafx.base, com.google.gson;
}
