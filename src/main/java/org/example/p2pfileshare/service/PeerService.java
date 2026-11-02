package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.discovery.PeerDiscovery;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public class PeerService {

    private final String myPeerId;
    private String myDisplayName; // cho phép thay đổi runtime
    private final int fileServerPort;
    private final int controlPort;

    // Danh sách peer tìm thấy trong LAN
    private final List<PeerInfo> discoveredPeers = Collections.synchronizedList(new ArrayList<>());
    // IP nhập tay, được hỏi trực tiếp ở mỗi lần quét
    private final Set<String> manualHosts = new LinkedHashSet<>();

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

    // quét tìm Peer (kèm các IP người dùng đã nhập tay)
    public List<PeerInfo> scanPeers() {
        List<String> hosts;
        synchronized (manualHosts) {
            hosts = new ArrayList<>(manualHosts);
        }
        List<PeerInfo> found = PeerDiscovery.discoverPeers(myPeerId, 3000, hosts);

        // Cập nhật danh sách local
        synchronized (discoveredPeers) {
            discoveredPeers.clear();
            discoveredPeers.addAll(found);
        }
        return found;
    }

    /**
     * Hỏi thẳng một IP (khi mạng chặn multicast/broadcast). Nếu có peer trả lời thì nhớ IP đó
     * để các lần quét sau vẫn thấy. Trả về null nếu không có phản hồi.
     */
    public PeerInfo addPeerByIp(String host) throws UnknownHostException {
        String ip = InetAddress.getByName(host).getHostAddress();
        List<PeerInfo> found = PeerDiscovery.discoverPeers(myPeerId, 2000, List.of(ip));
        for (PeerInfo p : found) {
            if (p.getIp().equals(ip)) {
                synchronized (manualHosts) {
                    manualHosts.add(ip);
                }
                return p;
            }
        }
        return null;
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
