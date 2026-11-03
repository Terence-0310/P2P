package org.example.p2pfileshare.network.transport;

import org.example.p2pfileshare.model.PeerInfo;

import java.io.IOException;

/** LAN Mode: kết nối TCP trực tiếp tới IP và cổng mà peer đã quảng bá */
public final class LanTcpTransport implements PeerTransport {

    // peer đã tắt máy thì báo lỗi nhanh thay vì treo ~20s
    public static final int CONTROL_CONNECT_TIMEOUT_MS = 3000;
    public static final int FILE_CONNECT_TIMEOUT_MS = 5000;

    @Override
    public StreamConnection open(PeerInfo peer, Channel channel) throws IOException {
        int port = channel == Channel.CONTROL ? peer.getControlPort() : peer.getFileServerPort();
        int timeout = channel == Channel.CONTROL ? CONTROL_CONNECT_TIMEOUT_MS : FILE_CONNECT_TIMEOUT_MS;
        return SocketStreamConnection.connect(peer.getIp(), port, timeout);
    }
}
