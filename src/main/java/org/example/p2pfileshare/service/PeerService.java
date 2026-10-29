package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.discovery.PeerDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class PeerService {

    private final String myPeerId;
    private String myDisplayName; // cho phép thay đổi runtime
    private final int fileServerPort;
    private final int controlPort;

    // Danh sách peer tìm thấy trong LAN
    private final List<PeerInfo> discoveredPeers = Collections.synchronizedList(new ArrayList<>());

    public PeerService(String myPeerId, String myDisplayName, int fileServerPort, int controlPort) {
        this.myPeerId = myPeerId;
        this.myDisplayName = myDisplayName;
        this.fileServerPort = fileServerPort;
        this.controlPort = controlPort;
    }

    // BẮT ĐẦU LẮNG NGHE
    // Hàm này sẽ gọi PeerDiscovery để tham gia nhóm Multicast và lắng nghe ai đó tìm mình
    public void start() {
        PeerDiscovery.startResponder(
                myPeerId,
                () -> myDisplayName, // Lambda để lấy tên mới nhất nếu có thay đổi
                fileServerPort,
                controlPort
        );
        System.out.println("[PeerService] Đã khởi động Responder (Multicast)");
    }

    // DỪNG LẮNG NGHE
    // Gọi hàm này khi tắt ứng dụng để rời nhóm Multicast
    public void stop() {
        PeerDiscovery.stopResponder();
        System.out.println("[PeerService] Đã dừng Responder");
    }

    // quét tìm Peer
    public List<PeerInfo> scanPeers() {
        List<PeerInfo> found = PeerDiscovery.discoverPeers(myPeerId, 3000);

        // Cập nhật danh sách local
        synchronized (discoveredPeers) {
            discoveredPeers.clear();
            discoveredPeers.addAll(found);
        }
        return found;
    }

    // lấy danh sách peer tìm thấy ở lần quét gần nhất
    public List<PeerInfo> getDiscoveredPeers() {
        return discoveredPeers;
    }

    public String getMyPeerId() {
        return myPeerId;
    }

    public String getMyDisplayName() {
        return myDisplayName;
    }

    public void setMyDisplayName(String myDisplayName) {
        this.myDisplayName = myDisplayName;
    }

    public int getFileServerPort() {
        return fileServerPort;
    }

    public int getControlPort() {
        return controlPort;
    }
}
