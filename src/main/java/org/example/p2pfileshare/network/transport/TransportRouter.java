package org.example.p2pfileshare.network.transport;

import org.example.p2pfileshare.model.PeerInfo;

import java.io.IOException;

/**
 * Chọn tầng truyền thông theo peer: peer trong LAN đi TCP trực tiếp, peer Internet đi qua relay.
 * Tầng nghiệp vụ chỉ gọi open(peer, kênh), không cần biết peer ở đâu.
 */
public final class TransportRouter implements PeerTransport {

    private final PeerTransport lan;
    private volatile PeerTransport relay;

    public TransportRouter() {
        this(new LanTcpTransport());
    }

    public TransportRouter(PeerTransport lan) {
        this.lan = lan;
    }

    /** Gắn transport relay khi bật Internet Mode (null = tắt) */
    public void setRelayTransport(PeerTransport relay) {
        this.relay = relay;
    }

    @Override
    public StreamConnection open(PeerInfo peer, Channel channel) throws IOException {
        if (peer.isRelay()) {
            PeerTransport r = relay;
            if (r == null) throw new IOException("Chưa bật chế độ Internet (chưa kết nối relay)");
            return r.open(peer, channel);
        }
        return lan.open(peer, channel);
    }
}
