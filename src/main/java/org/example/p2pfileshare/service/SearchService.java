package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.SharedFileLocal;

import java.util.ArrayList;
import java.util.List;

/**
 * Tìm kiếm file và đóng gói / giải mã kết quả gửi qua kênh điều khiển.
 *
 * Payload của SEARCH_RES (1 dòng, gộp mọi file tìm thấy):
 *   name \t relativePath \t size \t subject <NL> name \t relativePath \t size \t subject ...
 * Dùng tab làm dấu phân cách nên tên file chứa ':' hay '|' vẫn parse đúng.
 */
public class SearchService {

    private static final String FIELD_SEP = "\t";
    private static final String ENTRY_SEP = "<NL>";

    private final FileShareService fileShareService;

    public SearchService(FileShareService fileShareService) {
        this.fileShareService = fileShareService;
    }

    /** Tìm trong thư mục chia sẻ của mình và đóng gói kết quả, trả về "" nếu không có file nào. */
    public String buildResponsePayload(String keyword) {
        List<SharedFileLocal> found = fileShareService.searchLocalFiles(keyword);
        StringBuilder sb = new StringBuilder();
        for (SharedFileLocal f : found) {
            if (sb.length() > 0) sb.append(ENTRY_SEP);
            sb.append(safe(f.getFileName())).append(FIELD_SEP)
              .append(safe(f.getRelativePath())).append(FIELD_SEP)
              .append(f.getSize()).append(FIELD_SEP)
              .append(safe(f.getSubject()));
        }
        return sb.toString();
    }

    /** Giải mã payload SEARCH_RES nhận từ peer khác. */
    public static List<RemoteEntry> parseResponsePayload(String payload) {
        List<RemoteEntry> result = new ArrayList<>();
        if (payload == null || payload.isBlank()) return result;

        for (String line : payload.split(ENTRY_SEP)) {
            if (line.isBlank()) continue;
            String[] cols = line.split(FIELD_SEP, -1);
            String name = cols[0];
            String rel = cols.length > 1 && !cols[1].isEmpty() ? cols[1] : name;
            long size = 0;
            try {
                if (cols.length > 2) size = Long.parseLong(cols[2].trim());
            } catch (NumberFormatException ignored) {}
            String subject = cols.length > 3 && !cols[3].isEmpty() ? cols[3] : "Khác";
            result.add(new RemoteEntry(name, rel, size, subject));
        }
        return result;
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\t", " ").replace("\n", " ").replace("\r", " ").replace(ENTRY_SEP, " ");
    }

    public record RemoteEntry(String fileName, String relativePath, long size, String subject) {}
}
