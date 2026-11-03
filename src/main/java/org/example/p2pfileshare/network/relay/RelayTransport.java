package org.example.p2pfileshare.network.relay;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.transport.Channel;
import org.example.p2pfileshare.network.transport.PeerTransport;
import org.example.p2pfileshare.network.transport.StreamConnection;

import java.io.IOException;

/** Internet Mode: mỗi "kết nối" là 1 luồng ảo qua relay tới peerId đích */
public final class RelayTransport implements PeerTransport {

    private final RelayClient client;

    public RelayTransport(RelayClient client) {
        this.client = client;
    }

    @Override
    public StreamConnection open(PeerInfo peer, Channel channel) throws IOException {
        return client.openStream(peer.getPeerId(), channel);
    }
}
