package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.DownloadHistory;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.network.transfer.ChunkedFileServer;
import org.example.p2pfileshare.util.FolderWatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileShareService {

    // File tạm của quá trình tải (resume) không được chia sẻ
    private static final List<String> TEMP_SUFFIXES = List.of(".part", ".bitmap", ".meta.properties");
    private static final long WATCH_DEBOUNCE_MS = 800;

    private final int fileServerPort;
    private File shareFolder;
    private ChunkedFileServer fileServer; // dùng ChunkedFileServer để chia sẻ file
    private final HistoryService historyService;
    private String myDisplayName;
    private String myPeerId;
    // Kiểm tra peer (theo peerId) đã được chấp nhận kết nối chưa trước khi cho tải file
    private Predicate<String> accessChecker;
    private final SimpleAIService aiService = new SimpleAIService();

    // Theo dõi thư mục chia sẻ để tự cập nhật danh sách file
    private FolderWatcher folderWatcher;
    private final List<Runnable> filesChangedListeners = new CopyOnWriteArrayList<>();

    public FileShareService(int fileServerPort, HistoryService historyService) {
        this.fileServerPort = fileServerPort;
        this.historyService = historyService;
    }

    // khởi tạo server từ người share
    public synchronized void startServer() {
        if (fileServer == null && shareFolder != null) {
            fileServer = new ChunkedFileServer(fileServerPort, shareFolder.toPath(), accessChecker);
            fileServer.start();
        }
    }

    // dừng server
    public synchronized void stopServer() {
        stopWatcher();
        if (fileServer != null) {
            fileServer.stopServer();
            fileServer = null;
        }
    }

    // đổi folder chia sẻ
    public synchronized void setShareFolder(File folder) {
        stopWatcher();

        // folder null → không share
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            this.shareFolder = null;
            if (fileServer != null) {
                fileServer.changeFolder(null);
            }
            notifyFilesChanged();
            return;
        }

        // set thư mục mới
        this.shareFolder = folder;

        // nếu server chưa chạy → khởi động
        if (fileServer == null) {
            fileServer = new ChunkedFileServer(fileServerPort, folder.toPath(), accessChecker);
            fileServer.start();
        }
        // nếu server đang chạy → đổi folder ngay lập tức không cần restart
        else {
            fileServer.changeFolder(folder.toPath());
        }

        try {
            folderWatcher = new FolderWatcher(folder.toPath(), WATCH_DEBOUNCE_MS, this::notifyFilesChanged);
        } catch (IOException e) {
            System.err.println("[FileShareService] Không theo dõi được thư mục: " + e.getMessage());
        }
        notifyFilesChanged();
    }

    private void stopWatcher() {
        if (folderWatcher != null) {
            folderWatcher.close();
            folderWatcher = null;
        }
    }

    /** Đăng ký nhận thông báo khi file trong thư mục chia sẻ thay đổi (chạy trên thread nền) */
    public void addFilesChangedListener(Runnable listener) {
        if (listener != null) filesChangedListeners.add(listener);
    }

    private void notifyFilesChanged() {
        for (Runnable r : filesChangedListeners) {
            try {
                r.run();
            } catch (Exception e) {
                System.err.println("[FileShareService] Listener error: " + e.getMessage());
            }
        }
    }

    public File getShareFolder() {
        return shareFolder;
    }

    /** File thật trong thư mục chia sẻ theo đường dẫn tương đối, null nếu không hợp lệ (chặn "../") */
    public File resolveSharedFile(String relativePath) {
        if (shareFolder == null || relativePath == null) return null;
        Path root = shareFolder.toPath().toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) return null;
        return target.toFile();
    }

    // liệt kê file trong thư mục chia sẻ (kể cả thư mục con)
    public List<SharedFileLocal> listSharedFiles() {
        List<SharedFileLocal> result = new ArrayList<>();

        File folder = shareFolder;
        if (folder == null) return result;
        Path root = folder.toPath();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> !isHiddenOrTemp(root, p))
                 .sorted(Comparator.comparing(p -> root.relativize(p).toString().toLowerCase()))
                 .forEach(p -> result.add(buildMetadata(root, p.toFile())));
        } catch (IOException | java.io.UncheckedIOException e) {
            System.err.println("[FileShareService] Lỗi đọc thư mục chia sẻ: " + e.getMessage());
        }

        return result;
    }

    // bỏ file ẩn, file trong thư mục ẩn (.git...) và file tạm khi đang tải
    private static boolean isHiddenOrTemp(Path root, Path file) {
        String name = file.getFileName().toString();
        if (TEMP_SUFFIXES.stream().anyMatch(name::endsWith)) return true;
        for (Path part : root.relativize(file)) {
            if (part.toString().startsWith(".")) return true;
        }
        try {
            return Files.isHidden(file);
        } catch (IOException e) {
            return false;
        }
    }

    public List<SharedFileLocal> searchLocalFiles(String keyword) {
        // 1. Lấy tất cả file đang chia sẻ
        List<SharedFileLocal> allFiles = listSharedFiles();

        // 2. Từ khóa trống hoặc "*" -> trả hết
        if (keyword == null || keyword.trim().isEmpty() || keyword.equals("*")) {
            return new ArrayList<>(allFiles);
        }

        String searchKey = keyword.toLowerCase().trim();

        // 3. Lọc file khớp tên
        return allFiles.stream()
                .filter(f -> f.getFileName().toLowerCase().contains(searchKey))
                .collect(Collectors.toList());
    }

    // tạo metadata cho file
    private SharedFileLocal buildMetadata(Path root, File f) {
        String fileName = f.getName();
        long size = f.length();

        // đường dẫn tương đối để bên client tải về đúng, luôn dùng '/' để peer khác hệ điều hành vẫn hiểu
        String relativePath = root.relativize(f.toPath()).toString().replace('\\', '/');

        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            extension = fileName.substring(dot + 1).toLowerCase();
        }

        // đoán môn học theo cả tên thư mục chứa file (vd. "Lap trinh mang/Chuong1.pdf")
        String subject = aiService.predictSubject(relativePath);
        String tags = aiService.predictTag(fileName);

        return new SharedFileLocal(
                fileName,
                relativePath,
                extension,
                size,
                subject,
                tags,
                true
        );
    }

    public void setMyDisplayName(String myDisplayName) {
        this.myDisplayName = myDisplayName;
    }

    public String getMyDisplayName() {
        return myDisplayName;
    }

    public void setMyPeerId(String myPeerId) {
        this.myPeerId = myPeerId;
    }

    // Phải gọi trước khi server được khởi động
    public synchronized void setAccessChecker(Predicate<String> accessChecker) {
        this.accessChecker = accessChecker;
    }

    /**
     * Nếu đã có file trùng tên ở nơi lưu thì đổi thành "ten (1).ext", "ten (2).ext"...
     * Riêng trường hợp còn file .part (đang tải dở) thì giữ nguyên tên để tải tiếp.
     */
    public static Path resolveSavePath(Path saveTo) {
        if (!Files.exists(saveTo) || Files.exists(Path.of(saveTo + ".part"))) {
            return saveTo;
        }
        String name = saveTo.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";

        for (int i = 1; ; i++) {
            Path candidate = saveTo.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate) || Files.exists(Path.of(candidate + ".part"))) {
                return candidate;
            }
        }
    }

    public DownloadJob startDownload(PeerInfo peer, String relativePath, Path saveTo,
                                     Consumer<Double> progressCallback,
                                     Consumer<String> statusCallback) {
        return startDownload(peer, relativePath, saveTo, progressCallback, statusCallback, null);
    }

    /**
     * @param stateCallback nhận trạng thái job (RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED) để UI cập nhật nút bấm
     */
    public DownloadJob startDownload(PeerInfo peer, String relativePath, Path requestedSaveTo,
                                     Consumer<Double> progressCallback,
                                     Consumer<String> statusCallback,
                                     Consumer<DownloadJob.State> stateCallback) {

        Path saveTo = resolveSavePath(requestedSaveTo);

        // Tạo Job: service chỉ tạo & trả về, UI giữ job để pause/resume/cancel
        DownloadJob job = new DownloadJob(
                peer.getIp(),
                peer.getFileServerPort(),
                myPeerId,
                relativePath,
                saveTo,
                p -> {
                    if (progressCallback != null) progressCallback.accept(p);
                },
                state -> {
                    // Map state -> status text
                    if (statusCallback != null) {
                        switch (state) {
                            case NEW -> statusCallback.accept("Đang chuẩn bị...");
                            case RUNNING -> statusCallback.accept("Đang tải...");
                            case PAUSED -> statusCallback.accept("Đã tạm dừng");
                            case CANCELLED -> statusCallback.accept("Đã hủy tải");
                            case COMPLETED -> statusCallback.accept("Tải xong: " + saveTo.getFileName());
                            case FAILED -> statusCallback.accept("Tải thất bại");
                        }
                    }

                    // Nếu COMPLETED thì lưu history ngay tại service
                    if (state == DownloadJob.State.COMPLETED) {
                        try {
                            historyService.addHistory(new DownloadHistory(
                                    saveTo.getFileName().toString(),
                                    saveTo.toAbsolutePath().toString(),
                                    peer.getName(),
                                    peer.getIp(),
                                    LocalDateTime.now()
                            ));
                        } catch (Exception e) {
                            System.err.println("✗ Lỗi khi lưu lịch sử tải xuống: " + e.getMessage());
                        }
                    }

                    if (stateCallback != null) stateCallback.accept(state);
                },
                err -> {
                    // lỗi kỹ thuật (IO, timeout, bị từ chối...) -> hiện lý do cụ thể
                    if (statusCallback != null) {
                        statusCallback.accept("Tải thất bại: " + err.getMessage());
                    }
                }
        );

        // Service KHÔNG block: chỉ start thread và trả job về cho UI
        job.start();
        return job;
    }

}
