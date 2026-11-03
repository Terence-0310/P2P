package org.example.p2pfileshare.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Kiểm tra đường dẫn do peer khác gửi tới (dữ liệu không tin cậy) trước khi đọc file.
 * Chặn "../", đường dẫn tuyệt đối, ký tự lạ, và symlink / junction trỏ ra ngoài thư mục chia sẻ.
 */
public final class SafePaths {

    /** File tạm của quá trình tải (resume) không được chia sẻ */
    public static final List<String> TEMP_SUFFIXES = List.of(".part", ".bitmap", ".meta.properties");

    private SafePaths() {}

    /**
     * @return đường dẫn thật (đã giải symlink) của file thường nằm trong root, hoặc null nếu không hợp lệ
     */
    public static Path resolveSharedFile(Path root, String relativePath) {
        if (root == null || relativePath == null || relativePath.isEmpty()) return null;
        if (relativePath.indexOf('\0') >= 0 || relativePath.length() > 4096) return null;
        try {
            Path rootReal = root.toRealPath();
            Path candidate = rootReal.resolve(relativePath).normalize();
            if (!candidate.startsWith(rootReal)) return null;
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return null;
            // toRealPath giải mọi symlink trên đường đi: file thật phải vẫn nằm trong thư mục chia sẻ
            Path real = candidate.toRealPath();
            if (!real.startsWith(rootReal) || !Files.isRegularFile(real)) return null;
            if (isHiddenOrTemp(rootReal, real)) return null;
            return real;
        } catch (IOException | InvalidPathException | SecurityException e) {
            return null;
        }
    }

    /** File ẩn, nằm trong thư mục ẩn (.git...), hoặc file tạm khi đang tải: không chia sẻ */
    public static boolean isHiddenOrTemp(Path root, Path file) {
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

    /** File (đi theo symlink) có thực sự nằm trong root không. Dùng khi liệt kê thư mục chia sẻ. */
    public static boolean isInside(Path root, Path file) {
        try {
            return file.toRealPath().startsWith(root.toRealPath());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Thư mục quá rộng để chia sẻ: gốc ổ đĩa (C:\, /) hoặc nguyên thư mục người dùng.
     * Tránh vô tình công khai toàn bộ dữ liệu cá nhân cho máy khác.
     */
    public static boolean isTooBroadToShare(Path folder) {
        if (folder == null) return false;
        try {
            Path real = folder.toRealPath();
            for (Path r : FileSystems.getDefault().getRootDirectories()) {
                if (real.equals(r.toAbsolutePath())) return true;
            }
            if (real.getParent() == null) return true;
            String home = System.getProperty("user.home");
            return home != null && real.equals(Path.of(home).toRealPath());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
