package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kết nối giữa 2 peer là 2 chiều: A gửi yêu cầu, B chấp nhận -> cả A và B đều "đã kết nối",
 * xem và tải được file của nhau. Ngắt ở bất kỳ phía nào (tab Mạng LAN, tab kết nối, tab Kết nối đến)
 * thì cả 2 phía cùng ngắt.
 *
 * Mọi nơi trong UI kết nối / ngắt kết nối qua lớp này để trạng thái ControlClient (mình xem file của peer)
 * và ControlServer (peer xem file của mình) luôn đi cùng nhau.
 */
public class ConnectionService {

    /** Được gọi trên luồng mạng / luồng nền, UI phải tự chuyển sang JavaFX thread */
    public interface Listener {
        /** @param incoming true nếu peer kia gửi yêu cầu và mình đã chấp nhận */
        default void onConnected(PeerInfo peer, boolean incoming) {}

        /** @param byRemote true nếu peer kia ngắt kết nối với mình */
        default void onDisconnected(String peerId, boolean byRemote) {}
    }

    private final ControlClient client;
    private final ControlServer server;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public ConnectionService(ControlClient client, ControlServer server) {
        this.client = client;
        this.server = server;

        server.setOnPeerConnected(peer -> {
            client.addConnectedPeer(peer);
            for (Listener l : listeners) l.onConnected(peer, true);
        });
        server.setOnPeerDisconnected(peerId -> {
            // peer không kết nối với mình (vd. tắt app) thì không báo gì
            if (!client.isConnectedTo(peerId)) return;
            client.removeConnectedPeer(peerId);
            for (Listener l : listeners) l.onDisconnected(peerId, true);
        });
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Gửi yêu cầu và chờ peer kia quyết định (chặn luồng, tối đa ~1 phút). true nếu được chấp nhận */
    public boolean connect(PeerInfo peer) {
        if (!client.sendConnectRequest(peer)) return false;
        server.allowPeer(peer);
        for (Listener l : listeners) l.onConnected(peer, false);
        return true;
    }

    /**
     * Ngắt cả 2 chiều. Phía mình luôn coi như đã ngắt (kể cả khi peer kia đã tắt máy),
     * giá trị trả về chỉ cho biết peer kia có xác nhận hay không.
     */
    public boolean disconnect(PeerInfo peer) {
        if (peer == null) return false;
        server.revokePeer(peer.getPeerId());
        boolean acknowledged = client.sendDisconnectRequest(peer);
        for (Listener l : listeners) l.onDisconnected(peer.getPeerId(), false);
        return acknowledged;
    }

    /**
     * Heartbeat phát hiện peer kia không còn chấp nhận mình (vd. đã tắt và mở lại ứng dụng nên quên kết nối cũ):
     * gỡ kết nối ở phía mình và báo như khi peer kia chủ động ngắt.
     */
    public void handleRemoteGone(String peerId) {
        if (!isConnected(peerId)) return;
        server.revokePeer(peerId);
        client.removeConnectedPeer(peerId);
        for (Listener l : listeners) l.onDisconnected(peerId, true);
    }

    public boolean isConnected(String peerId) {
        return client.isConnectedTo(peerId) || server.isAccepted(peerId);
    }

    /** Thông tin liên lạc của peer đang kết nối, null nếu không kết nối */
    public PeerInfo getConnectedPeer(String peerId) {
        PeerInfo p = client.getConnectedPeer(peerId);
        return p != null ? p : server.getAcceptedPeerInfo(peerId);
    }

    /** Mọi peer đang kết nối (không trùng) */
    public List<PeerInfo> getConnectedPeers() {
        Map<String, PeerInfo> all = new LinkedHashMap<>();
        for (PeerInfo p : client.getConnectedPeers()) all.put(p.getPeerId(), p);
        for (PeerInfo p : server.getAcceptedPeers(List.of())) all.putIfAbsent(p.getPeerId(), p);
        return new ArrayList<>(all.values());
    }
}
