package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.util.DownloadHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileShareServiceTest {

    @TempDir Path tempRoot;
    private Path share;
    private FileShareService service;

    @BeforeEach
    void setUp() throws IOException {
        share = Files.createDirectory(tempRoot.resolve("share"));
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        service = new FileShareService(port,
                new HistoryService(new DownloadHistoryManager(tempRoot.resolve("history.json"))));
    }

    @AfterEach
    void tearDown() {
        service.stopServer();
    }

    @Test
    void listsSubfoldersAndSkipsTempAndHiddenFiles() throws IOException {
        Files.write(share.resolve("a.pdf"), new byte[1]);
        Path sub = Files.createDirectories(share.resolve("Lap trinh mang"));
        Files.write(sub.resolve("chuong1.pdf"), new byte[2]);
        Files.write(share.resolve("dang-tai.zip.part"), new byte[3]);
        Files.write(share.resolve("dang-tai.zip.bitmap"), new byte[3]);
        Path git = Files.createDirectories(share.resolve(".git"));
        Files.write(git.resolve("config"), new byte[3]);

        service.setShareFolder(share.toFile());
        List<SharedFileLocal> files = service.listSharedFiles();

        assertEquals(List.of("a.pdf", "Lap trinh mang/chuong1.pdf"),
                files.stream().map(SharedFileLocal::getRelativePath).toList());
        // môn học đoán được nhờ tên thư mục chứa file
        assertEquals("Lập trình mạng", files.get(1).getSubject());
    }

    @Test
    void resolveSharedFileBlocksTraversal() throws IOException {
        service.setShareFolder(share.toFile());
        assertNull(service.resolveSharedFile("../history.json"));
        assertEquals(share.resolve("x/y.txt").toAbsolutePath().normalize().toFile(),
                service.resolveSharedFile("x/y.txt"));
    }

    @Test
    void savePathGetsSuffixWhenFileExists() throws IOException {
        Path target = tempRoot.resolve("bai tap.docx");
        assertEquals(target, FileShareService.resolveSavePath(target));

        Files.write(target, new byte[1]);
        assertEquals(tempRoot.resolve("bai tap (1).docx"), FileShareService.resolveSavePath(target));

        Files.write(tempRoot.resolve("bai tap (1).docx"), new byte[1]);
        assertEquals(tempRoot.resolve("bai tap (2).docx"), FileShareService.resolveSavePath(target));
    }

    @Test
    void savePathKeptWhenPartialDownloadExists() throws IOException {
        Path target = tempRoot.resolve("video.mp4");
        Files.write(target, new byte[1]);
        Files.write(tempRoot.resolve("video.mp4.part"), new byte[1]);
        assertEquals(target, FileShareService.resolveSavePath(target));
    }

    @Test
    void notifiesListenersWhenShareFolderChanges() throws Exception {
        service.setShareFolder(share.toFile());

        CountDownLatch latch = new CountDownLatch(1);
        service.addFilesChangedListener(latch::countDown);

        Files.write(share.resolve("moi.txt"), new byte[1]);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "FolderWatcher không báo thay đổi");
    }
}
