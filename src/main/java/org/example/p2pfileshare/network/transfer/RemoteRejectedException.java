package org.example.p2pfileshare.network.transfer;

import java.io.IOException;

/**
 * Peer chia sẻ trả lời ERROR (không có quyền, file không tồn tại, chưa chọn thư mục chia sẻ...).
 * Khác lỗi mạng: thử lại cũng không được, nên tác vụ tải chuyển sang thất bại thay vì chờ kết nối lại.
 */
public class RemoteRejectedException extends IOException {
    public RemoteRejectedException(String reason) {
        super("Server error: " + reason);
    }
}
