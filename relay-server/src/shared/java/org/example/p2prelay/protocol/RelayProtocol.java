package org.example.p2prelay.protocol;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Giao thức giữa ứng dụng P2PFileShare và Relay Server (dùng chung cho cả 2 phía).
 *
 * Vận chuyển: WebSocket (wss:// khi qua Internet). Mỗi máy mở 1 kết nối ra ngoài tới relay,
 * nên không cần mở cổng router hay có IP công khai (đi qua được NAT / CGNAT).
 *
 * Text frame = lệnh điều khiển, các trường cách nhau 1 dấu cách:
 *   relay -> máy : CHALLENGE nonce version
 *   máy  -> relay: HELLO publicKey chữKý tên       (chữ ký Ed25519 trên AUTH_CONTEXT + nonce)
 *   relay -> máy : WELCOME peerId heartbeatGiây idleTimeoutGiây cửaSổLuồng
 *   máy  -> relay: CREATE_INVITE req              -> INVITE req mã hếtHạn
 *   máy  -> relay: RESOLVE req mã                 -> RESOLVED req peerId tên linkToken  (bên nhập mã)
 *                                                  LINKED peerId tên linkToken          (chủ mã)
 *   máy  -> relay: RESTORE peerId linkToken       -> RESTORED peerId ONLINE|OFFLINE | RESTORE_FAILED peerId
 *                                                  (khôi phục liên kết sau khi mất kết nối / relay khởi động lại)
 *   máy  -> relay: UNLINK peerId                  -> UNLINKED peerId (gửi cho bên kia)
 *   relay -> máy : PRESENCE peerId ONLINE|OFFLINE (chỉ gửi cho peer đã liên kết)
 *   máy  -> relay: OPEN req peerĐích kênh         -> (đích) INCOMING sid nguồn kênh -> ACCEPT sid | REJECT sid
 *                                                  -> (nguồn) OPENED req sid | OPEN_FAILED req mãLỗi
 *   2 chiều      : CREDIT sid sốByte, CLOSE sid, RESET sid lýDo, PING / PONG
 *   relay -> máy : ERROR mãLỗi [req]
 *
 * Binary frame = dữ liệu của 1 luồng ảo: [1 byte FRAME_DATA][4 byte sid][payload ≤ MAX_DATA_PAYLOAD].
 * Bên gửi chỉ được gửi tối đa số byte "credit" bên nhận đã cấp (cửa sổ ban đầu = cửa sổ luồng),
 * nên bộ đệm ở mỗi đầu luôn bị chặn trên, kể cả khi bên nhận đọc chậm.
 */
public final class RelayProtocol {

    private RelayProtocol() {}

    public static final int VERSION = 1;
    public static final String WS_PATH = "/ws";
    public static final String HEALTH_PATH = "/health";

    /** Dữ liệu tối đa trong 1 binary frame (không gồm header) */
    public static final int MAX_DATA_PAYLOAD = 64 * 1024;
    public static final int DATA_HEADER_SIZE = 5;
    public static final int MAX_BINARY_MESSAGE = MAX_DATA_PAYLOAD + DATA_HEADER_SIZE;
    public static final int MAX_TEXT_MESSAGE = 4096;
    public static final byte FRAME_DATA = 1;
    /** Cửa sổ (byte) mỗi luồng ảo được gửi trước khi phải chờ CREDIT */
    public static final int DEFAULT_STREAM_WINDOW = 1024 * 1024;

    public static final String AUTH_CONTEXT = "p2pfileshare-relay-auth-v1:";

    // ---- lệnh ----
    public static final String CHALLENGE = "CHALLENGE";
    public static final String HELLO = "HELLO";
    public static final String WELCOME = "WELCOME";
    public static final String ERROR = "ERROR";
    public static final String CREATE_INVITE = "CREATE_INVITE";
    public static final String INVITE = "INVITE";
    public static final String RESOLVE = "RESOLVE";
    public static final String RESOLVED = "RESOLVED";
    public static final String LINKED = "LINKED";
    public static final String RESTORE = "RESTORE";
    public static final String RESTORED = "RESTORED";
    public static final String RESTORE_FAILED = "RESTORE_FAILED";
    public static final String UNLINK = "UNLINK";
    public static final String UNLINKED = "UNLINKED";
    public static final String PRESENCE = "PRESENCE";
    public static final String OPEN = "OPEN";
    public static final String INCOMING = "INCOMING";
    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";
    public static final String OPENED = "OPENED";
    public static final String OPEN_FAILED = "OPEN_FAILED";
    public static final String CREDIT = "CREDIT";
    public static final String CLOSE = "CLOSE";
    public static final String RESET = "RESET";
    public static final String PING = "PING";
    public static final String PONG = "PONG";

    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";

    // ---- kênh của luồng ảo (ứng với cổng điều khiển / cổng file trong LAN) ----
    public static final String CH_CONTROL = "control";
    public static final String CH_FILE = "file";

    // ---- mã lỗi ----
    public static final String E_BAD_REQUEST = "BAD_REQUEST";
    public static final String E_AUTH_FAILED = "AUTH_FAILED";
    public static final String E_VERSION = "VERSION";
    public static final String E_SERVER_FULL = "SERVER_FULL";
    public static final String E_INVITE_INVALID = "INVITE_INVALID";
    public static final String E_RATE_LIMITED = "RATE_LIMITED";
    public static final String E_PEER_OFFLINE = "PEER_OFFLINE";
    public static final String E_NOT_LINKED = "NOT_LINKED";
    public static final String E_LIMIT = "LIMIT";
    public static final String E_REJECTED = "REJECTED";
    public static final String E_TIMEOUT = "TIMEOUT";
    public static final String E_PEER_GONE = "PEER_GONE";

    /** Bảng chữ cho mã mời: bỏ 0/O, 1/I/L để đọc qua điện thoại không nhầm */
    public static final String INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int INVITE_LENGTH = 8;

    public static String[] split(String message) {
        return message.split(" ", -1);
    }

    public static String join(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }

    /** Mã hoá chuỗi tuỳ ý (tên hiển thị) thành 1 trường không chứa dấu cách */
    public static String encodeText(String s) {
        if (s == null || s.isEmpty()) return "-";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String decodeText(String s) {
        if (s == null || s.equals("-")) return "";
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Danh tính của máy = 16 byte đầu SHA-256 của khoá công khai (X.509), dạng hex. Không tự khai báo được. */
    public static String peerIdFromPublicKey(byte[] encodedPublicKey) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(encodedPublicKey);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isValidPeerId(String s) {
        return s != null && s.matches("[0-9a-f]{32}");
    }

    public static byte[] authPayload(String nonce) {
        return (AUTH_CONTEXT + nonce).getBytes(StandardCharsets.US_ASCII);
    }

    public static boolean isValidChannel(String ch) {
        return CH_CONTROL.equals(ch) || CH_FILE.equals(ch);
    }

    /** Chuẩn hoá mã người dùng gõ ("abcd-efgh", " ABCD EFGH ") về 8 ký tự, null nếu sai định dạng */
    public static String normalizeInviteCode(String raw) {
        if (raw == null) return null;
        String s = raw.replace("-", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (s.length() != INVITE_LENGTH) return null;
        for (char c : s.toCharArray()) {
            if (INVITE_ALPHABET.indexOf(c) < 0) return null;
        }
        return s;
    }

    public static String formatInviteCode(String code) {
        if (code == null || code.length() != INVITE_LENGTH) return code;
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    public static ByteBuffer dataFrame(int sid, byte[] data, int off, int len) {
        ByteBuffer buf = ByteBuffer.allocate(DATA_HEADER_SIZE + len);
        buf.put(FRAME_DATA).putInt(sid).put(data, off, len);
        buf.flip();
        return buf;
    }

    /** Rút gọn id khi ghi log (không ghi đầy đủ danh tính) */
    public static String shortId(String peerId) {
        if (peerId == null) return "?";
        return peerId.length() <= 8 ? peerId : peerId.substring(0, 8);
    }
}
