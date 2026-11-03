package org.example.p2pfileshare.util;

import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.HistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Chặn truy cập ngoài thư mục chia sẻ với đường dẫn do peer khác gửi tới */
class SafePathsTest {

    @Test
    void resolvesOnlyRegularFilesInsideRoot(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("share"));
        Files.createDirectories(root.resolve("Chương 1"));
        Files.writeString(root.resolve("Chương 1").resolve("bai1.pdf"), "x");
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "bí mật");

        assertNotNull(SafePaths.resolveSharedFile(root, "Chương 1/bai1.pdf"));
        assertNotNull(SafePaths.resolveSharedFile(root, "Chương 1/./bai1.pdf"));

        assertNull(SafePaths.resolveSharedFile(root, "../secret.txt"));
        assertNull(SafePaths.resolveSharedFile(root, "Chương 1/../../secret.txt"));
        assertNull(SafePaths.resolveSharedFile(root, secret.toAbsolutePath().toString()), "đường dẫn tuyệt đối");
        assertNull(SafePaths.resolveSharedFile(root, "Chương 1"), "thư mục không phải file");
        assertNull(SafePaths.resolveSharedFile(root, "khong-co.txt"));
        assertNull(SafePaths.resolveSharedFile(root, "bai\0.pdf"), "ký tự NUL");
        assertNull(SafePaths.resolveSharedFile(root, ""));
        assertNull(SafePaths.resolveSharedFile(root, null));
        assertNull(SafePaths.resolveSharedFile(null, "a.txt"));
    }

    @Test
    void hiddenAndTemporaryDownloadFilesAreNotServed(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("phim.mp4.part"), "dở");
        Files.writeString(root.resolve("phim.mp4.bitmap"), "dở");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "token=...");
        Files.writeString(root.resolve(".env"), "SECRET=1");

        assertNull(SafePaths.resolveSharedFile(root, "phim.mp4.part"));
        assertNull(SafePaths.resolveSharedFile(root, "phim.mp4.bitmap"));
        assertNull(SafePaths.resolveSharedFile(root, ".git/config"));
        assertNull(SafePaths.resolveSharedFile(root, ".env"));
    }

    @Test
    void symlinkPointingOutsideIsBlocked(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectory(tmp.resolve("share"));
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "ngoài");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Hệ điều hành không cho tạo symlink (Windows cần Developer Mode)");
        }
        assertNull(SafePaths.resolveSharedFile(root, "link.txt"));
        assertFalse(SafePaths.isInside(root, link));
    }

    @Test
    void refusesToShareDriveRootOrWholeHomeFolder(@TempDir Path tmp) {
        Path driveRoot = FileSystems.getDefault().getRootDirectories().iterator().next();
        assertTrue(SafePaths.isTooBroadToShare(driveRoot));
        assertTrue(SafePaths.isTooBroadToShare(Path.of(System.getProperty("user.home"))));
        assertFalse(SafePaths.isTooBroadToShare(tmp));

        FileShareService service = new FileShareService(0, new HistoryService(
                new DownloadHistoryManager(tmp.resolve("h.json"))));
        assertThrows(IllegalArgumentException.class, () -> service.setShareFolder(driveRoot.toFile()));
        assertNull(service.getShareFolder());
    }
}
