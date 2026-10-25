package org.example.p2pfileshare.service;

import org.example.p2pfileshare.util.DownloadHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {

    private FileShareService fileShareService;

    @AfterEach
    void tearDown() {
        if (fileShareService != null) fileShareService.stopServer();
    }

    @Test
    void payloadRoundTripKeepsSpecialCharacters(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("Bài tập - Socket (v2), TCP.docx"), new byte[123]);
        Files.write(dir.resolve("khac.txt"), new byte[5]);

        fileShareService = new FileShareService(0,
                new HistoryService(new DownloadHistoryManager(dir.resolve(".history.json"))));
        fileShareService.setShareFolder(dir.toFile());
        SearchService search = new SearchService(fileShareService);

        String payload = search.buildResponsePayload("socket");
        List<SearchService.RemoteEntry> entries = SearchService.parseResponsePayload(payload);

        assertEquals(1, entries.size());
        SearchService.RemoteEntry e = entries.get(0);
        assertEquals("Bài tập - Socket (v2), TCP.docx", e.fileName());
        assertEquals("Bài tập - Socket (v2), TCP.docx", e.relativePath());
        assertEquals(123, e.size());
        assertEquals("Lập trình mạng", e.subject());
    }

    @Test
    void parsesNameContainingColon() {
        // Định dạng cũ "Name:Size:Subject" làm hỏng tên có dấu ':' (vd. file trên Linux/macOS)
        List<SearchService.RemoteEntry> entries =
                SearchService.parseResponsePayload("a:b.txt\ta:b.txt\t10\tKhác<NL>c.pdf\tc.pdf\t20\t");

        assertEquals(2, entries.size());
        assertEquals("a:b.txt", entries.get(0).fileName());
        assertEquals(10, entries.get(0).size());
        assertEquals("Khác", entries.get(1).subject());
    }

    @Test
    void emptyPayloadGivesNoResults() {
        assertTrue(SearchService.parseResponsePayload("").isEmpty());
        assertTrue(SearchService.parseResponsePayload(null).isEmpty());
    }
}
