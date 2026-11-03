package org.example.p2pfileshare.network.transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 1 kết nối 2 chiều dạng luồng byte giữa 2 peer, không phụ thuộc cách truyền:
 * socket TCP trong LAN, hoặc luồng ảo đi qua Relay Server.
 *
 * Tầng nghiệp vụ (ControlClient/Server, ChunkedFileClient/Server) chỉ làm việc với interface này,
 * nên toàn bộ giao thức kết nối, liệt kê file, tìm kiếm, tải theo chunk được dùng lại cho cả 2 chế độ.
 */
public interface StreamConnection extends Closeable {

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    /** Giống Socket.setSoTimeout: mỗi lần read chờ tối đa ms (0 = chờ mãi), quá hạn ném SocketTimeoutException */
    void setReadTimeout(int ms) throws IOException;

    /** Mô tả đầu bên kia để ghi log (IP hoặc "relay:peerId") */
    String describeRemote();
}
