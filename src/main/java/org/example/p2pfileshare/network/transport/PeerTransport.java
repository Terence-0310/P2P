package org.example.p2pfileshare.network.transport;

import org.example.p2pfileshare.model.PeerInfo;

import java.io.IOException;

/** Tầng truyền thông: mở 1 kết nối tới peer trên kênh điều khiển hoặc kênh file */
public interface PeerTransport {

    StreamConnection open(PeerInfo peer, Channel channel) throws IOException;
}
