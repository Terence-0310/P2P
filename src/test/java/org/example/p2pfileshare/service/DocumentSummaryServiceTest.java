package org.example.p2pfileshare.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DocumentSummaryServiceTest {

    private final DocumentSummaryService service = new DocumentSummaryService();

    @Test
    void longTextIsReducedToThreeSentences() {
        String text = "Mạng máy tính cho phép các máy trao đổi dữ liệu với nhau. "
                + "Giao thức TCP đảm bảo dữ liệu đến đúng thứ tự và không bị mất. "
                + "Giao thức UDP nhanh hơn nhưng không đảm bảo dữ liệu đến nơi. "
                + "Hôm nay trời đẹp. "
                + "Mô hình P2P giúp các máy trao đổi dữ liệu trực tiếp không qua máy chủ. "
                + "Multicast gửi một gói tin tới nhiều máy trong cùng nhóm.";

        String summary = service.extracKeySentences(text, 3);

        assertNotEquals(text, summary);
        assertEquals(3, summary.split(" \\(\\.\\.\\.\\) ").length);
        assertFalse(summary.contains("Hôm nay trời đẹp"));
    }

    @Test
    void shortTextIsReturnedAsIs() {
        String summary = service.extracKeySentences("Câu một ở đây. Câu hai ở đây.", 3);
        assertTrue(summary.contains("Câu một ở đây."));
        assertTrue(summary.contains("Câu hai ở đây."));
    }

    @Test
    void summarizesTxtFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bai-giang.txt");
        Files.writeString(file, "Socket là điểm cuối của kết nối mạng. Socket TCP dùng ServerSocket để lắng nghe. "
                + "Socket UDP dùng DatagramSocket để gửi gói tin. Cuối cùng là phần bài tập về socket.", StandardCharsets.UTF_8);

        String summary = service.summarize(file.toFile());
        assertFalse(summary.isBlank());
        assertTrue(summary.toLowerCase().contains("socket"));
    }

    @Test
    void unsupportedExtension() throws Exception {
        assertTrue(service.summarize(new File("abc.exe")).startsWith("Chỉ hỗ trợ"));
    }
}
