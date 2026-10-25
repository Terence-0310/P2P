package org.example.p2pfileshare.network.control;

/**
 * Giao thức text đơn giản cho kênh điều khiển.
 *
 * Mỗi message là 1 dòng, format:
 *   COMMAND|fromPeer|toPeer|note(optional)
 *
 * Ví dụ:
 *   CONNECT_REQUEST|PeerA|PeerB
 *   CONNECT_ACCEPT|PeerB|PeerA|OK
 *   CONNECT_REJECT|PeerB|PeerA|Busy
 */
public class ControlProtocol {

    public static final String CONNECT_REQUEST = "CONNECT_REQUEST";
    public static final String CONNECT_ACCEPT  = "CONNECT_ACCEPT";
    public static final String CONNECT_REJECT  = "CONNECT_REJECT";
    public static final String DISCONNECT_REQUEST  = "DISCONNECT_REQUEST";
    public static final String DISCONNECT_NOTIFY   = "DISCONNECT_NOTIFY";
    public static final String UPDATE_NAMESERVER   = "UPDATE_NAME";
    public static final String UPDATE_CLIENT   = "UPDATE_NAME";
    // Mở rộng: lấy danh sách file chia sẻ từ peer đích
    public static final String LIST_FILES          = "LIST_FILES";
    public static final String LIST_FILES_RESPONSE = "LIST_FILES_RESPONSE"; // note: payload dạng key-value được encode
    // Lệnh hệ thống 1 chiều: báo cho peer đang xem kho file của mình rằng danh sách file đã thay đổi
    public static final String CMD_FILES_CHANGED = "CMD:FILES_CHANGED";

    // Cấu trúc sau khi parse
    public static class ParsedMessage {
        public String command;
        public String fromPeer;
        public String toPeer;
        public String note;
        public String[] parts; // toàn bộ các trường, dùng cho message có nhiều hơn 4 trường
    }

    /**
     * Parse từ một dòng raw (COMMAND|from|to|note)
     */
    public static ParsedMessage parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        String[] parts = raw.split("\\|", -1); // -1 để giữ cả chuỗi rỗng
        if (parts.length < 3) {
            return null;  // không đủ 3 phần
        }

        ParsedMessage m = new ParsedMessage();
        m.command  = parts[0];
        m.fromPeer = parts[1];
        m.toPeer   = parts[2];
        m.note     = (parts.length > 3) ? parts[3] : null;
        m.parts    = parts;

        return m;
    }

    /** Bỏ ký tự phân cách khỏi giá trị do người dùng nhập (vd. tên hiển thị) */
    public static String sanitize(String value) {
        if (value == null) return "";
        return value.replace("|", "/").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Build message không có note
     */
    public static String build(String cmd, String from, String to) {
        return cmd + "|" + from + "|" + to;
    }

    /**
     * Build message có note
     */
    public static String build(String cmd, String from, String to, String note) {
        if (note == null) note = "";
        return cmd + "|" + from + "|" + to + "|" + note;
    }
}
