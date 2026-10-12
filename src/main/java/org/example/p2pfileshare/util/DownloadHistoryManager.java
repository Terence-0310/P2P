package org.example.p2pfileshare.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.example.p2pfileshare.model.DownloadHistory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DownloadHistoryManager {

    private static final String HISTORY_FILE = "download_history.json";
    // Lưu trong thư mục người dùng: không phụ thuộc thư mục đang chạy app, không lẫn vào mã nguồn
    private static final Path DEFAULT_PATH = Paths.get(System.getProperty("user.home"), ".p2pfileshare", HISTORY_FILE);

    private final Path historyPath;
    private final Gson gson;

    // listeners to notify when history changes
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public DownloadHistoryManager() {
        this(DEFAULT_PATH);
        migrateLegacyFile();
    }

    public DownloadHistoryManager(Path historyPath) {
        this.historyPath = historyPath;

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .create();
    }

    // Load lịch sử từ file
    public synchronized List<DownloadHistory> loadHistory() {
        try {
            if (!Files.exists(historyPath)) {
                return new ArrayList<>();
            }

            try (Reader reader = Files.newBufferedReader(historyPath, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<DownloadHistory>>() {}.getType();
                List<DownloadHistory> histories = gson.fromJson(reader, listType);
                return histories != null ? histories : new ArrayList<>();
            }

        } catch (IOException | JsonParseException e) {
            // file hỏng -> coi như lịch sử trống, không làm sập app
            System.err.println("[History] Không đọc được lịch sử: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // Lưu lịch sử xuống file
    private synchronized void saveHistory(List<DownloadHistory> histories) {
        try {
            Path parent = historyPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(historyPath, StandardCharsets.UTF_8)) {
                gson.toJson(histories, writer);
            }
        } catch (IOException e) {
            System.err.println("[History] Không ghi được lịch sử: " + e.getMessage());
        }
    }

    // Bản cũ lưu download_history.json ở thư mục đang chạy app -> chuyển sang vị trí mới 1 lần
    private void migrateLegacyFile() {
        Path legacy = Paths.get(HISTORY_FILE).toAbsolutePath();
        if (Files.exists(historyPath) || !Files.exists(legacy) || legacy.equals(historyPath.toAbsolutePath())) return;
        try {
            Files.createDirectories(historyPath.toAbsolutePath().getParent());
            Files.copy(legacy, historyPath, StandardCopyOption.COPY_ATTRIBUTES);
            System.out.println("[History] Đã chuyển lịch sử cũ sang " + historyPath);
        } catch (IOException e) {
            System.err.println("[History] Không chuyển được lịch sử cũ: " + e.getMessage());
        }
    }

    public Path getHistoryPath() {
        return historyPath;
    }

    // Xóa 1 mục lịch sử
    public synchronized void removeHistory(DownloadHistory target) {
        List<DownloadHistory> histories = loadHistory();
        boolean removed = histories.removeIf(h ->
                java.util.Objects.equals(h.getSavedPath(), target.getSavedPath())
                        && java.util.Objects.equals(h.getDownloadDate(), target.getDownloadDate()));
        if (removed) {
            saveHistory(histories);
            notifyListeners();
        }
    }

    // Thêm 1 lịch sử mới
    public synchronized void addHistory(DownloadHistory history) {
        List<DownloadHistory> histories = loadHistory();
        histories.add(history);
        saveHistory(histories);
        notifyListeners();
    }

    // Xóa toàn bộ lịch sử (ghi file rỗng)
    public synchronized void clearHistory() {
        // Write empty list to the history file (keeps file present but empty)
        saveHistory(new ArrayList<>());
        notifyListeners();
    }

    // Listener API
    public void addChangeListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        if (listener != null) listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
