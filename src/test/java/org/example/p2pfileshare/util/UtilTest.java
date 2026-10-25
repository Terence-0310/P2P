package org.example.p2pfileshare.util;

import org.example.p2pfileshare.model.DownloadHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    @Test
    void historyRoundTripKeepsVietnameseAndSupportsRemove(@TempDir Path dir) {
        DownloadHistoryManager manager = new DownloadHistoryManager(dir.resolve("sub").resolve("history.json"));
        DownloadHistory a = new DownloadHistory("Bài tập.docx", "E:\\tải về\\Bài tập.docx", "cường ngô", "192.168.1.2",
                LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        DownloadHistory b = new DownloadHistory("b.pdf", "E:\\b.pdf", "vương", "192.168.1.3",
                LocalDateTime.of(2026, 1, 3, 3, 4, 5));
        manager.addHistory(a);
        manager.addHistory(b);

        List<DownloadHistory> loaded = new DownloadHistoryManager(manager.getHistoryPath()).loadHistory();
        assertEquals(2, loaded.size());
        assertEquals("cường ngô", loaded.get(0).getPeerName());
        assertEquals("Bài tập.docx", loaded.get(0).getFileName());

        manager.removeHistory(a);
        assertEquals(List.of("b.pdf"), manager.loadHistory().stream().map(DownloadHistory::getFileName).toList());
    }

    @Test
    void corruptHistoryFileGivesEmptyList(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("history.json");
        Files.writeString(file, "{ không phải json", StandardCharsets.UTF_8);
        assertTrue(new DownloadHistoryManager(file).loadHistory().isEmpty());
    }

    @Test
    void transferStatsShowsSpeedAndRemainingTime() {
        TransferStats stats = new TransferStats(10L * 1024 * 1024); // 10 MB
        long t0 = 1_000_000_000L;

        assertEquals("0.0%", stats.update(0.0, t0));
        // 1 MB trong 1 giây -> 1 MB/s, còn 9 MB -> 9 giây
        assertEquals("10.0% • 1.00 MB/s • còn 9 giây", stats.update(0.1, t0 + 1_000_000_000L));
        assertEquals("100.0%", stats.update(1.0, t0 + 10_000_000_000L));
    }

    @Test
    void formatsSizesAndDurations() {
        assertEquals("512 B", TransferStats.formatSize(512));
        assertEquals("1.50 KB", TransferStats.formatSize(1536));
        assertEquals("2.00 GB", TransferStats.formatSize(2L * 1024 * 1024 * 1024));
        assertEquals("2 phút 5 giây", TransferStats.formatDuration(125));
        assertEquals("1 giờ 1 phút", TransferStats.formatDuration(3660));
    }

    @Test
    void folderWatcherSeesChangesInNewSubfolders(@TempDir Path dir) throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(2);
        try (FolderWatcher ignored = new FolderWatcher(dir, 200, () -> {
            first.countDown();
            second.countDown();
        })) {
            Path sub = Files.createDirectory(dir.resolve("moi"));
            assertTrue(first.await(10, TimeUnit.SECONDS));

            Files.write(sub.resolve("file.txt"), new byte[1]);
            assertTrue(second.await(10, TimeUnit.SECONDS), "Không thấy thay đổi trong thư mục con mới tạo");
        }
    }
}
