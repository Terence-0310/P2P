package org.example.p2pfileshare.network.control;

import org.example.p2pfileshare.model.PeerInfo;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ControlClient {

    // Thời gian chờ mở kết nối: peer đã tắt máy thì báo lỗi nhanh thay vì treo ~20s
    private static final int CONNECT_TIMEOUT_MS = 3000;
    // Chờ người bên kia bấm Chấp nhận / Từ chối
    private static final int CONNECT_DECISION_TIMEOUT_MS = 60_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;
    // Tóm tắt file lớn có thể mất thời gian
    private static final int SUMMARIZE_TIMEOUT_MS = 60_000;

    private final String myPeerId;
    private volatile String myDisplayName;
    private volatile int myControlPort;
    private volatile int myFilePort;

    // Các peer đã chấp nhận kết nối từ mình (mình là client), key = peerId.
    // Giữ cả PeerInfo để gửi lệnh tiếp theo mà không phải quét LAN lại.
    private final Map<String, PeerInfo> connectedPeers = new ConcurrentHashMap<>();

    public ControlClient(String myPeerId, String myDisplayName) {
        this.myPeerId = myPeerId;
        this.myDisplayName = myDisplayName;
    }

    public void setMyDisplayName(String myDisplayName) {
        this.myDisplayName = myDisplayName;
    }

    /** Cổng của mình, gửi kèm CONNECT_REQUEST để peer kia liên lạc lại được dù chưa quét LAN thấy mình */
    public void setMyPorts(int controlPort, int filePort) {
        this.myControlPort = controlPort;
        this.myFilePort = filePort;
    }

    // Format: CONNECT_REQUEST|myId|toId|displayName|controlPort|filePort
    public boolean sendConnectRequest(PeerInfo peer) {
        String host = peer.getIp();
        int controlPort = peer.getControlPort();
        String toPeer = peer.getPeerId();
        System.out.println("[ControlClient] Connecting to " + host + ":" + controlPort +
                " to request connect → " + toPeer);

        try (Socket socket = openSocket(host, controlPort, CONNECT_DECISION_TIMEOUT_MS);
             BufferedReader reader = readerOf(socket);
             PrintWriter writer = writerOf(socket)) {

            // 1) Gửi CONNECT_REQUEST (gửi peerId làm from)
            String msg = ControlProtocol.build(
                    ControlProtocol.CONNECT_REQUEST,
                    myPeerId,
                    toPeer,
                    ControlProtocol.sanitize(myDisplayName)
            ) + "|" + myControlPort + "|" + myFilePort;

            writer.println(msg);

            // 2) Đọc 1 dòng response
            String respRaw = reader.readLine();
            if (respRaw == null || respRaw.isEmpty()) {
                return false;
            }

            ControlProtocol.ParsedMessage parsed = ControlProtocol.parse(respRaw);
            if (parsed == null || !myPeerId.equals(parsed.toPeer)) {
                return false;
            }

            if (ControlProtocol.CONNECT_ACCEPT.equals(parsed.command)) {
                connectedPeers.put(parsed.fromPeer, peer);
                return true;
            }
            return false;

        } catch (IOException e) {
            System.err.println("[ControlClient] Connect failed: " + e.getMessage());
            return false;
        }
    }

    public List<RemoteFile> listFiles(PeerInfo peer) {
        String toPeer = peer.getPeerId();
        System.out.println("[ControlClient] Request LIST_FILES → " + toPeer);
        List<RemoteFile> files = new ArrayList<>();

        try (Socket socket = openSocket(peer.getIp(), peer.getControlPort(), DEFAULT_READ_TIMEOUT_MS);
             BufferedReader reader = readerOf(socket);
             PrintWriter writer = writerOf(socket)) {

            writer.println(ControlProtocol.build(ControlProtocol.LIST_FILES, myPeerId, toPeer));

            String respRaw = reader.readLine();
            if (respRaw == null) return files;

            ControlProtocol.ParsedMessage parsed = ControlProtocol.parse(respRaw);
            if (parsed == null || !myPeerId.equals(parsed.toPeer)) return files;

            if (ControlProtocol.LIST_FILES_RESPONSE.equals(parsed.command)) {
                String payload = parsed.note != null ? parsed.note : "";
                // Parse TSV lines: name\trelative\tsize
                for (String line : payload.split("<NL>")) {
                    if (line.isBlank()) continue;
                    String[] cols = line.split("\t");
                    String name = cols.length > 0 ? cols[0] : "";
                    String rel  = cols.length > 1 ? cols[1] : name;
                    long size   = 0L;
                    try {
                        size = cols.length > 2 ? Long.parseLong(cols[2]) : 0L;
                    } catch (NumberFormatException ignored) {}
                    files.add(new RemoteFile(name, rel, size));
                }
            }
        } catch (IOException e) {
            System.err.println("[ControlClient] LIST_FILES failed: " + e.getMessage());
        }

        return files;
    }

    /**
     * Gửi DISCONNECT_REQUEST. Phía mình luôn coi như đã ngắt (kể cả khi peer đã tắt máy),
     * giá trị trả về chỉ cho biết peer kia có xác nhận hay không.
     */
    public boolean sendDisconnectRequest(PeerInfo peer) {
        if (peer == null) return false;
        String toPeer = peer.getPeerId();
        System.out.println("[ControlClient] Sending DISCONNECT_REQUEST to " + peer.getIp() + ":"
                + peer.getControlPort() + " -> " + toPeer);
        connectedPeers.remove(toPeer);

        try (Socket socket = openSocket(peer.getIp(), peer.getControlPort(), DEFAULT_READ_TIMEOUT_MS);
             BufferedReader reader = readerOf(socket);
             PrintWriter writer = writerOf(socket)) {

            writer.println(ControlProtocol.build(
                    ControlProtocol.DISCONNECT_REQUEST,
                    myPeerId,   // from = mình
                    toPeer,     // to = peer đích
                    "Request disconnect"
            ));

            // Đọc phản hồi 1 dòng (ControlServer gửi DISCONNECT_NOTIFY)
            String respRaw = reader.readLine();
            if (respRaw == null || respRaw.isBlank()) {
                return false;
            }

            ControlProtocol.ParsedMessage parsed = ControlProtocol.parse(respRaw);
            return parsed != null
                    && ControlProtocol.DISCONNECT_NOTIFY.equals(parsed.command)
                    && myPeerId.equals(parsed.toPeer);
        } catch (IOException e) {
            System.err.println("[ControlClient] DISCONNECT_REQUEST failed: " + e.getMessage());
            return false;
        }
    }

    public String sendSummarizeRequest(PeerInfo peer, String relativePath) {
        if (peer == null) return null;
        System.out.println("[ControlClient] Gửi yêu cầu AI Summary tới " + peer.getName());
        try (Socket socket = openSocket(peer.getIp(), peer.getControlPort(), SUMMARIZE_TIMEOUT_MS);
             BufferedReader reader = readerOf(socket);
             PrintWriter writer = writerOf(socket)) {

            // Format: SUMMARIZE_REQ | ID_Của_Tôi | Đường_dẫn_file
            writer.println("SUMMARIZE_REQ|" + myPeerId + "|" + relativePath);

            // Chờ server đọc file + tóm tắt rồi trả lời trên cùng kết nối
            String response = reader.readLine();
            System.out.println("[ControlClient] Nhận phản hồi tóm tắt: "
                    + (response == null ? "null" : response.length() + " ký tự"));
            return response;
        } catch (IOException e) {
            System.err.println("Lỗi khi yêu cầu tóm tắt: " + e.getMessage());
            return null;
        }
    }

    // gửi yêu cầu tìm kiếm file
    public void sendSearchRequest(PeerInfo peer, String keyword) {
        if (peer == null) return;
        // sendSystemCommand sẽ tự nối thêm myPeerId vào: SEARCH_REQ|myId|keyword
        sendSystemCommand(peer, "SEARCH_REQ|" + ControlProtocol.sanitize(keyword));
    }

    // gửi phản hồi tìm kiếm file
    public void sendSearchResponse(PeerInfo peer, String fileData) {
        if (peer == null) return;
        sendSystemCommand(peer, "SEARCH_RES|" + fileData);
    }

    /** Báo cho peer (đang xem kho file của mình) rằng danh sách file vừa thay đổi */
    public void sendFilesChanged(PeerInfo peer) {
        sendSystemCommand(peer, ControlProtocol.CMD_FILES_CHANGED + "|*");
    }

    private boolean sendOneWay(String host, int port, String msg) {
        try (Socket socket = openSocket(host, port, DEFAULT_READ_TIMEOUT_MS);
             PrintWriter writer = writerOf(socket)) {

            writer.println(msg);
            System.out.println("[ControlClient] One-way send: " + msg);
            return true;

        } catch (IOException e) {
            System.err.println("[ControlClient] One-way send to " + host + ":" + port + " failed: " + e.getMessage());
            return false;
        }
    }

    // rawCommand dạng "PREFIX|nội dung" -> gửi "PREFIX|myPeerId|nội dung"
    public boolean sendSystemCommand(PeerInfo peer, String rawCommand) {
        if (peer == null) return false;

        String[] parts = rawCommand.split("\\|", 2);
        if (parts.length < 2) return false;

        String finalMsg = parts[0] + "|" + myPeerId + "|" + parts[1];

        System.out.println("[ControlClient] Sending System Cmd to " + peer.getName() + ": " + finalMsg);
        return sendOneWay(peer.getIp(), peer.getControlPort(), finalMsg);
    }

    public void broadcastUpdateName(List<PeerInfo> peers, String newName) {
        if (peers == null || peers.isEmpty()) {
            return;
        }

        newName = ControlProtocol.sanitize(newName).trim();
        if (newName.isEmpty()) {
            System.out.println("[ControlClient] broadcastUpdateName: newName is empty");
            return;
        }

        // Format: UPDATE_NAME|myId|newName
        String msg = ControlProtocol.build(ControlProtocol.UPDATE_NAMESERVER, myPeerId, newName);

        System.out.println("[ControlClient] broadcastUpdateName -> peers=" + peers.size() + " msg=" + msg);

        for (PeerInfo p : peers) {
            // tránh gửi cho chính mình nếu list có chứa mình
            if (p == null || myPeerId.equals(p.getPeerId())) continue;
            sendOneWay(p.getIp(), p.getControlPort(), msg);
        }
    }

    /** Danh sách peer mà mình đang kết nối tới (bản sao) */
    public List<PeerInfo> getConnectedPeers() {
        return new ArrayList<>(connectedPeers.values());
    }

    /** Thông tin peer mình đang kết nối tới, null nếu chưa kết nối */
    public PeerInfo getConnectedPeer(String peerId) {
        return peerId == null ? null : connectedPeers.get(peerId);
    }

    public boolean isConnectedTo(String peerId) {
        return peerId != null && connectedPeers.containsKey(peerId);
    }

    /** Gọi khi peer kia chủ động ngắt kết nối với mình */
    public void removeConnectedPeer(String peerId) {
        if (peerId != null) connectedPeers.remove(peerId);
    }

    // ---------------- helpers ----------------

    private static Socket openSocket(String host, int port, int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(readTimeoutMs);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static BufferedReader readerOf(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static PrintWriter writerOf(Socket socket) throws IOException {
        return new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    // DTO đơn giản cho UI
    public static class RemoteFile {
        public final String name;
        public final String relativePath;
        public final long size;
        public RemoteFile(String name, String relativePath, long size) {
            this.name = name;
            this.relativePath = relativePath;
            this.size = size;
        }
    }

}
