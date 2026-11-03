package org.example.p2pfileshare.network.relay;

import org.example.p2prelay.protocol.RelayProtocol;

import java.io.IOException;

/** Lỗi relay trả về (mã lỗi trong RelayProtocol), kèm thông báo tiếng Việt cho người dùng */
public class RelayException extends IOException {

    private final String code;

    public RelayException(String code) {
        super(describe(code));
        this.code = code;
    }

    public RelayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static String describe(String code) {
        if (code == null) return "Lỗi relay không xác định";
        return switch (code) {
            case RelayProtocol.E_INVITE_INVALID -> "Mã kết nối sai hoặc đã hết hạn";
            case RelayProtocol.E_RATE_LIMITED -> "Nhập sai quá nhiều lần, hãy thử lại sau ít phút";
            case RelayProtocol.E_PEER_OFFLINE -> "Máy kia đang offline (chưa kết nối relay)";
            case RelayProtocol.E_NOT_LINKED -> "Chưa liên kết với máy này, hãy nhập lại mã kết nối";
            case RelayProtocol.E_LIMIT -> "Quá nhiều kết nối đồng thời";
            case RelayProtocol.E_REJECTED -> "Máy kia từ chối luồng kết nối";
            case RelayProtocol.E_TIMEOUT -> "Hết thời gian chờ máy kia phản hồi";
            case RelayProtocol.E_AUTH_FAILED -> "Relay từ chối xác thực";
            case RelayProtocol.E_SERVER_FULL -> "Relay đang quá tải";
            case RelayProtocol.E_VERSION -> "Phiên bản relay không tương thích";
            case RelayProtocol.E_PEER_GONE -> "Máy kia mất kết nối relay";
            default -> "Lỗi relay: " + code;
        };
    }
}
