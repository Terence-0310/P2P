package org.example.p2pfileshare.network.transport;

import java.io.IOException;

/** Mở 1 kết nối mới tới cùng 1 đích (ChunkedFileClient gọi nhiều lần để tải song song / thử lại) */
@FunctionalInterface
public interface ConnectionOpener {

    StreamConnection open() throws IOException;
}
