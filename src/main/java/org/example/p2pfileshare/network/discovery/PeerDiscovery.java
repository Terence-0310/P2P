package org.example.p2pfileshare.network.discovery;

import org.example.p2pfileshare.model.PeerInfo;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PeerDiscovery {

    // Các port có thể dùng cho discovery (UDP)
//    private static final int[] DISCOVERY_PORTS = {
//            50000, 50001, 50002, 50003, 50004
//    };

    // cấu hình multicast
    private static final String MULTICAST_GROUP = "230.0.0.1";
    private static final int MULTICAST_PORT = 8888;

    private static final String DISCOVER_MSG    = "P2P_DISCOVER_REQUEST";
    private static final String RESPONSE_PREFIX = "P2P_DISCOVER_RESPONSE";

    private static volatile boolean responderRunning = false;
    private static volatile MulticastSocket responderSocket = null;
    // các card mạng đã tham gia nhóm multicast (để leaveGroup khi dừng)
    private static final List<NetworkInterface> joinedInterfaces = new ArrayList<>();
    private static InetAddress groupAddress = null;
    private static Thread responderThread = null;

    // chế độ lắng nghe, sử dụng thread
    public static synchronized void startResponder(
            String myPeerId,
            java.util.function.Supplier<String> displayNameSupplier,
            int filePort,
            int controlPort
    ) {
        if (responderRunning) {
            System.out.println("[Discovery] Responder already running");
            return;
        }

        responderRunning = true;

        responderThread = new Thread(() -> {
            try {
                // 1. Tạo Multicast Socket
                responderSocket = new MulticastSocket(MULTICAST_PORT);
                groupAddress = InetAddress.getByName(MULTICAST_GROUP);

                // 2. Tham gia nhóm trên mọi card mạng (Wi-Fi, Ethernet, card ảo VMware/VirtualBox...)
                //    để không phụ thuộc vào card mặc định của hệ điều hành
                InetSocketAddress group = new InetSocketAddress(groupAddress, MULTICAST_PORT);
                synchronized (joinedInterfaces) {
                    joinedInterfaces.clear();
                    for (NetworkInterface ni : multicastInterfaces()) {
                        try {
                            responderSocket.joinGroup(group, ni);
                            joinedInterfaces.add(ni);
                        } catch (IOException e) {
                            System.err.println("[Discovery] Cannot join on " + ni.getDisplayName() + ": " + e.getMessage());
                        }
                    }
                    if (joinedInterfaces.isEmpty()) {
                        responderSocket.joinGroup(group, null);
                    }
                }
                System.out.println("[Discovery] Responder joined Multicast Group: " + MULTICAST_GROUP + ":" + MULTICAST_PORT
                        + " on " + joinedInterfaces.size() + " interface(s)");

                byte[] buf = new byte[1024];

                while (responderRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        responderSocket.receive(packet);

                        String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
//                        if (!DISCOVER_MSG.equals(msg)) continue;

                        if (DISCOVER_MSG.equals(msg)) {
                            String response = buildResponse(myPeerId, displayNameSupplier.get(), filePort, controlPort);

                            byte[] respData = response.getBytes(StandardCharsets.UTF_8);

                            DatagramPacket resp = new DatagramPacket(
                                    respData,
                                    respData.length,
                                    packet.getAddress(),
                                    packet.getPort()
                            );
                            try {
                                responderSocket.send(resp);
                            } catch (IOException sendError) {
                                // lỗi gửi cho 1 peer (vd. NoRouteToHostException) không được làm dừng responder
                                if (responderSocket.isClosed()) throw sendError;
                                System.err.println("[Discovery] Cannot reply to " + packet.getAddress()
                                        + ":" + packet.getPort() + ": " + sendError.getMessage());
                            }
                        }

                    } catch (SocketException se) {
                        // xảy ra khi socket.close() lúc stop
                        if (responderRunning) {
                            System.err.println("[Discovery] Socket closed: " + se.getMessage());
                        }
                        break;
                    }
                }

            } catch (IOException e) {
                if (responderRunning) {
                    e.printStackTrace();
                }
            } finally {
                if (responderRunning) stopResponder();
            }
        }, "discovery-responder");

        responderThread.setDaemon(true);
        responderThread.start();
    }

    public static synchronized void stopResponder() {
        if (!responderRunning && responderSocket == null) return;
        responderRunning = false;
        if (responderSocket != null && !responderSocket.isClosed()) {
            try {
                if (groupAddress != null) {
                    InetSocketAddress group = new InetSocketAddress(groupAddress, MULTICAST_PORT);
                    synchronized (joinedInterfaces) {
                        for (NetworkInterface ni : joinedInterfaces) {
                            try {
                                responderSocket.leaveGroup(group, ni);
                            } catch (IOException ignored) {
                            }
                        }
                        joinedInterfaces.clear();
                    }
                }
            } finally {
                responderSocket.close();
            }
        }
        responderSocket = null;
        System.out.println("[Discovery] Responder stopped");
    }

    // Quét tìm peer trong LAN
    public static List<PeerInfo> discoverPeers(String myPeerId, int timeoutMillis) {
        // key = peerId: 1 peer có thể trả lời qua nhiều card mạng, chỉ giữ lần đầu
        Map<String, PeerInfo> result = new LinkedHashMap<>();

        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setSoTimeout(timeoutMillis); // timeout chỉ chờ trong 3s, quá thì thôi

            byte[] data = DISCOVER_MSG.getBytes(StandardCharsets.UTF_8);

            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    group,
                    MULTICAST_PORT
            );
            // Gửi yêu cầu ra từng card mạng, nếu không gửi được qua card nào thì dùng card mặc định
            int sent = 0;
            for (NetworkInterface ni : multicastInterfaces()) {
                try {
                    socket.setNetworkInterface(ni);
                    socket.send(packet);
                    sent++;
                } catch (IOException e) {
                    System.err.println("[Discovery] Cannot send on " + ni.getDisplayName() + ": " + e.getMessage());
                }
            }
            if (sent == 0) {
                socket.send(packet);
            }
            System.out.println("[Discovery] Sent Multicast Request to " + MULTICAST_GROUP + " via " + Math.max(sent, 1) + " interface(s)");


            byte[] buf = new byte[1024];
            long start = System.currentTimeMillis();

            while (true) {
                // chỉ chờ phần thời gian còn lại, để cả lần quét không vượt quá timeoutMillis
                long remaining = timeoutMillis - (System.currentTimeMillis() - start);
                if (remaining <= 0) break;
                try {
                    socket.setSoTimeout((int) remaining);
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    socket.receive(resp);

                    String msg = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8).trim();
                    PeerInfo peer = parseResponse(msg, resp.getAddress().getHostAddress());
                    if (peer == null) {
                        if (msg.startsWith(RESPONSE_PREFIX)) {
                            System.err.println("[Discovery] Ignored malformed response from "
                                    + resp.getAddress().getHostAddress());
                        }
                        continue;
                    }

                    // Bỏ qua chính mình dựa trên peerId, và peer đã nhận phản hồi qua card khác
                    if (peer.getPeerId().equals(myPeerId) || result.containsKey(peer.getPeerId())) {
                        continue;
                    }
                    result.put(peer.getPeerId(), peer);

                    System.out.println("[Discovery] Found: " + peer.getDisplayName() +
                            " ip: " + peer.getIp() +
                            " filePort:" + peer.getFileServerPort() +
                            " ctrlPort:" + peer.getControlPort() +
                            " id:" + peer.getPeerId());
                } catch (SocketTimeoutException e) {
                    // hết thời gian đợi -> thoát vòng while
                    break;
                } catch (IOException ex) {
                    ex.printStackTrace();
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new ArrayList<>(result.values());
    }

    // Format: P2P_DISCOVER_RESPONSE|peerId|displayName|filePort|controlPort
    // '|' là ký tự phân cách nên không được xuất hiện trong tên hiển thị
    static String buildResponse(String peerId, String displayName, int filePort, int controlPort) {
        String safeName = displayName == null ? "" : displayName.replace('|', '/');
        return RESPONSE_PREFIX + "|" + peerId + "|" + safeName + "|" + filePort + "|" + controlPort;
    }

    /**
     * Đọc gói phản hồi nhận từ mạng. Dữ liệu không tin cậy nên mọi lỗi định dạng đều trả về null
     * thay vì ném exception làm hỏng cả lần quét.
     * Hỗ trợ cả format cũ: P2P_DISCOVER_RESPONSE|name|filePort|controlPort
     */
    static PeerInfo parseResponse(String msg, String ip) {
        if (msg == null || !msg.startsWith(RESPONSE_PREFIX + "|")) return null;
        String[] parts = msg.split("\\|", -1);
        try {
            if (parts.length == 5) {
                String peerId = parts[1];
                int filePort = parsePort(parts[3]);
                int controlPort = parsePort(parts[4]);
                if (peerId.isBlank() || filePort < 0 || controlPort < 0) return null;
                return new PeerInfo(peerId, parts[2], ip, filePort, controlPort, "Online");
            }
            if (parts.length == 4) {
                String peerName = parts[1];
                int filePort = parsePort(parts[2]);
                int controlPort = parsePort(parts[3]);
                if (peerName.isBlank() || filePort < 0 || controlPort < 0) return null;
                return new PeerInfo(peerName, ip, filePort, controlPort, "Online");
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    // -1 nếu port nằm ngoài khoảng hợp lệ 1..65535
    private static int parsePort(String s) {
        int port = Integer.parseInt(s.trim());
        return port >= 1 && port <= 65535 ? port : -1;
    }

    // Các card mạng đang bật, hỗ trợ multicast và có địa chỉ IPv4
    private static List<NetworkInterface> multicastInterfaces() {
        List<NetworkInterface> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                boolean hasIpv4 = ni.getInterfaceAddresses().stream()
                        .anyMatch(a -> a.getAddress() instanceof Inet4Address);
                if (hasIpv4) list.add(ni);
            }
        } catch (SocketException e) {
            System.err.println("[Discovery] Cannot list network interfaces: " + e.getMessage());
        }
        return list;
    }
}