package org.example.p2pfileshare.network.control;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.service.DocumentSummaryService;
import org.example.p2pfileshare.service.FileShareService;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Consumer;

import static java.lang.System.out;

public class ControlServer {

    private final int port;
    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;

    // Đã chấp nhận kết nối từ peerId nào
    private final Set<String> acceptedPeers = ConcurrentHashMap.newKeySet();
    // Thông tin liên lạc của peer đã chấp nhận (IP lấy từ socket, cổng do peer gửi kèm CONNECT_REQUEST)
    // -> không phụ thuộc việc mình đã quét LAN thấy peer đó hay chưa
    private final Map<String, PeerInfo> acceptedPeerInfo = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> acceptedAt = new ConcurrentHashMap<>();
    private final Function<String, Boolean> onIncomingConnect;

    // Để trả danh sách file local
    private FileShareService fileShareService;
    private Runnable onPeerAccepted;
    private Runnable onUpdatePeerName;
    private Consumer<ControlProtocol.ParsedMessage> onDisconnectNotify;
    // Callback nhận tin nhắn hệ thống
    private java.util.function.BiConsumer<String, String> onSystemMessageCallback;
    // lưu PeerInfo với peerID, với các peer đã connect
    private java.util.function.BiConsumer<String, String> onRenameTab;

    // CALLBACK TÌM KIẾM
    private BiConsumer<String, String> onSearchRequestReceived; // (SenderID, Keyword) -> Xử lý tìm và trả lời
    private BiConsumer<String, String> onSearchResultReceived;  // (SenderID, Data)    -> Cập nhật UI

    private final DocumentSummaryService summaryService = new DocumentSummaryService();

    public ControlServer(int port, Function<String, Boolean> onIncomingConnect) {
        this.port = port;
        this.onIncomingConnect = onIncomingConnect;
    }

    // Cho phép inject FileShareService để phục vụ LIST_FILES
    public void setFileShareService(FileShareService fileShareService) {
        this.fileShareService = fileShareService;
    }

    public void setOnSystemMessageReceived(java.util.function.BiConsumer<String, String> callback) {
        this.onSystemMessageCallback = callback;
    }

    // SETTER CHO SEARCH
    public void setOnSearchRequestReceived(BiConsumer<String, String> callback) {
        this.onSearchRequestReceived = callback;
    }
    public void setOnSearchResultReceived(BiConsumer<String, String> callback) {
        this.onSearchResultReceived = callback;
    }

    public void start() {
        if (running) return;
        running = true;

        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                serverSocket = ss;
                out.println("[ControlServer] Listening on port " + port);
                while (running) {
                    Socket client = ss.accept();
                    handleClient(client);
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
                else out.println("[ControlServer] Stopped");
            }
        }, "control-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        // đóng ServerSocket để accept() thoát ngay
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Peer có peerId này đã được mình chấp nhận kết nối chưa */
    public boolean isAccepted(String peerId) {
        return peerId != null && acceptedPeers.contains(peerId);
    }

    private void handleClient(Socket socket) {
        Thread t = new Thread(() -> {
            try (Socket s = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

                // client luôn gửi request ngay sau khi kết nối, không để thread treo vô hạn
                s.setSoTimeout(15_000);
                String remoteIp = s.getInetAddress().getHostAddress();

                String raw = reader.readLine();
                if (raw == null || raw.isEmpty()) return;

                if (raw.startsWith("SEARCH_REQ|") || raw.startsWith("SEARCH_RES|")) {
                    out.println("[ControlServer] Received Search CMD: " + raw);
                    handleSearchCommand(raw);
                    return;
                }

                if(raw.startsWith("SUMMARIZE_REQ|")) {
                    out.println("[ControlServer] Received AI Summary REQ: " + raw);

                    // Format: SUMMARIZE_REQ | SenderID | Path
                    String[] parts = raw.split("\\|", 3);
                    if (parts.length >= 3) {
                        String senderId = parts[1];
                        String path = parts[2];

                        File fileToSummarize = resolveSharedFile(path);
                        String result;

                        if (!acceptedPeers.contains(senderId)) {
                            result = "Bạn chưa được peer này chấp nhận kết nối.";
                        } else if (fileToSummarize != null) {
                            try {
                                result = summaryService.summarize(fileToSummarize);
                            } catch (Exception e) {
                                result = "Lỗi khi đọc file: " + e.getMessage();
                            }
                        } else {
                            result = "File không tồn tại bên phía Peer.";
                        }
                        String encodeResult = Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8));
                        String response = "SUMMARIZE_RES|" + path + "|" + encodeResult;
                        writer.println(response);
                        out.println("[ControlServer] Sent AI Result (" + result.length() + " chars)");
                    }
                    return;
                }

                if (raw.startsWith("CMD:")) {
                    out.println("[ControlServer] Received System CMD: " + raw);

                    String[] parts = raw.split("\\|");
                    // parts[0] = CMD:REMOVE_FILE
                    // parts[1] = SenderID (quan trọng để định tuyến)
                    // parts[2] = FileName

                    if (parts.length >= 3) {
                        String senderId = parts[1];

                        // Tái tạo lại tin nhắn để gửi cho Controller xử lý
                        // Controller mong đợi: "CMD:REMOVE_FILE|FileName"
                        String originalCmd = parts[0] + "|" + parts[2];

                        if (onSystemMessageCallback != null) {
                            // Gọi về PeerTabController
                            onSystemMessageCallback.accept(senderId, originalCmd);
                        }
                    }
                    return; // Xử lý xong thì thoát, không parse tiếp
                }

                ControlProtocol.ParsedMessage msg = ControlProtocol.parse(raw);
                if (msg == null) return;

                out.println("[ControlServer] Received: " + raw);

                if (ControlProtocol.CONNECT_REQUEST.equals(msg.command)) {
                    // Format: CONNECT_REQUEST|fromId|toId|displayName|controlPort|filePort
                    String fromPeer = msg.fromPeer; // người yêu cầu
                    String toPeer   = msg.toPeer;   // mình
                    String displayName = (msg.note == null || msg.note.isBlank()) ? fromPeer : msg.note;
                    int ctrlPort = msg.parts.length > 4 ? tryParseInt(msg.parts[4], -1) : -1;
                    int filePort = msg.parts.length > 5 ? tryParseInt(msg.parts[5], -1) : -1;

                    boolean accept;
                    if (acceptedPeers.contains(fromPeer)) {
                        // đã chấp nhận trước đó (vd. bên kia đóng tab rồi kết nối lại) -> không hỏi lại
                        accept = true;
                    } else if (onIncomingConnect != null) {
                        try {
                            accept = Boolean.TRUE.equals(onIncomingConnect.apply(displayName));
                        } catch (Exception ex) {
                            accept = false;
                        }
                    } else {
                        accept = true;
                    }

                    if (accept) {
                        registerAcceptedPeer(fromPeer, displayName, remoteIp, ctrlPort, filePort);
                        // load lại danh sách incoming connection để cập nhật danh sách
                        if (onPeerAccepted != null) {
                            onPeerAccepted.run();
                        }
                    }

                    String respCmd  = accept ? ControlProtocol.CONNECT_ACCEPT : ControlProtocol.CONNECT_REJECT;
                    String respNote = accept ? "Accepted" : "Rejected";

                    // Lưu ý: từ phía server, fromPeer = "mình", toPeer = "người gửi request"
                    String resp = ControlProtocol.build(
                            respCmd,
                            toPeer,     // from = mình
                            fromPeer,   // to   = người yêu cầu
                            respNote
                    );

                    writer.println(resp);
                    out.println("[ControlServer] Sent: " + resp);
                }
                else if (ControlProtocol.LIST_FILES.equals(msg.command)) {
                    // Trả về danh sách file chia sẻ (tên/relative path)
                    String toPeer   = msg.toPeer;   // mình
                    String fromPeer = msg.fromPeer; // client
                    // CHẶN NẾU CHƯA ĐƯỢC ACCEPT
                    if (!acceptedPeers.contains(fromPeer)) {
                        String denyResp = ControlProtocol.build(
                                ControlProtocol.LIST_FILES_RESPONSE,
                                msg.toPeer,
                                fromPeer,
                                ""   // payload rỗng
                        );
                        writer.println(denyResp);
                        return;
                    }
                    // Build payload TSV: fileName\trelativePath\tsize
                    String payload = buildFileListPayload();
                    String resp = ControlProtocol.build(
                            ControlProtocol.LIST_FILES_RESPONSE,
                            toPeer,     // from = mình
                            fromPeer,   // to   = requester
                            payload
                    );

                    writer.println(resp);
                    out.println("[ControlServer] Sent LIST_FILES_RESPONSE (" + payload.length() + " bytes)");
                }
                else if (ControlProtocol.DISCONNECT_REQUEST.equals(msg.command)) {
                    String fromPeer = msg.fromPeer; // người yêu cầu ngắt (mình) hoặc client
                    String toPeer   = msg.toPeer;   // người bị ngắt
                    removeAcceptedPeer(fromPeer);
                    // gọi cập nhật lại trạng thái của peertab
                    if (onUpdatePeerName != null) {
                        onUpdatePeerName.run();
                    }
                    // gọi cập nhật lại trạng thái của incoming connection, peer client gọi đến peer server
                    if (onPeerAccepted != null) {
                        onPeerAccepted.run();
                    }
//                // Gửi phản hồi DISCONNECT_NOTIFY cho client bt là ok đã ngắt
                    writer.println(ControlProtocol.build(ControlProtocol.DISCONNECT_NOTIFY,
                            msg.toPeer, msg.fromPeer, "Disconnected"));
                    out.println("[ControlServer] Disconnected: " + fromPeer);
                }
                // xử lý thông báo DISCONNECT_NOTIFY nhận từ peer sever
                else if (ControlProtocol.DISCONNECT_NOTIFY.equals(msg.command)) {
                    out.println("[ControlServer] Received DISCONNECT_NOTIFY from " + msg.fromPeer
                            + " note=" + msg.note);
                    // Gọi callback nếu đã đăng ký để UI xử lý hiển alert thông báo ở root
                    if (onDisconnectNotify != null) {
                        try {
                            onDisconnectNotify.accept(msg);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    // không cần gửi phản hồi
                }
                //server gửi gói tin và server ở client nhận hàm này để cập nhật tên peer
                else if (ControlProtocol.UPDATE_NAMESERVER.equals(msg.command)) {
                    // to peer ở đây t co cai là name mới
                    out.println("[ControlServer] Received UPDATE_NAME from " + msg.fromPeer +"va name mới"+ msg.toPeer);

                    PeerInfo info = acceptedPeerInfo.get(msg.fromPeer);
                    if (info != null) {
                        info.setDisplayName(msg.toPeer);
                    }
                    if (onPeerAccepted != null) {
                        onPeerAccepted.run();
                    }
                    if (onUpdatePeerName != null) {
                        onUpdatePeerName.run();
                    }
                    if (onRenameTab != null) {
                        onRenameTab.accept(msg.fromPeer, msg.toPeer);
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "control-handler");

        t.setDaemon(true);
        t.start();
    }

    // XỬ LÝ LỆNH TÌM KIẾM
    private void handleSearchCommand(String raw) {
        // Format: SEARCH_REQ|SenderID|Keyword
        // Format: SEARCH_RES|SenderID|FileData
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) return;

        String cmd = parts[0];
        String senderId = parts[1];
        String content = parts[2];

        if ("SEARCH_REQ".equals(cmd)) {
            // Chỉ trả lời peer đã được chấp nhận kết nối
            if (!acceptedPeers.contains(senderId)) {
                out.println("[ControlServer] Ignored SEARCH_REQ from unaccepted peer " + senderId);
                return;
            }
            // Có người hỏi -> Gọi callback để Root xử lý (tìm file & gửi trả)
            if (onSearchRequestReceived != null) {
                onSearchRequestReceived.accept(senderId, content);
            }
        } else if ("SEARCH_RES".equals(cmd)) {
            // Có người trả lời -> Gọi callback để cập nhật bảng kết quả
            if (onSearchResultReceived != null) {
                onSearchResultReceived.accept(senderId, content);
            }
        }
    }

    // build payload danh sách file được chia sẻ theo dạng 2A.docx	2A.docx	27084<NL>AI4life (2).docx	AI4life (2).docx	933162<NL>
    private String buildFileListPayload() {
        if (fileShareService == null) return "";
        // liệt kê danh sách file được chia sẻ
        List<org.example.p2pfileshare.model.SharedFileLocal> list = fileShareService.listSharedFiles();
        StringBuilder sb = new StringBuilder();

        for (var f : list) {
            // Encode tab-safe by replacing tabs/newlines
            String name = safe(f.getFileName());
            String rel  = safe(f.getRelativePath());
            long size   = f.getSize();
            if (sb.length() > 0) sb.append("<NL>");
            sb.append(name).append('\t').append(rel).append('\t').append(size);
        }
        return sb.toString();
    }

    // File nằm trong thư mục chia sẻ (chặn "../" thoát ra ngoài), null nếu không hợp lệ
    private File resolveSharedFile(String relativePath) {
        if (fileShareService == null || fileShareService.getShareFolder() == null || relativePath == null) {
            return null;
        }
        Path root = fileShareService.getShareFolder().toPath().toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !target.toFile().isFile()) return null;
        return target.toFile();
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("\t", " ").replace("\n", " ");
    }
    private void registerAcceptedPeer(String peerId, String displayName, String ip, int ctrlPort, int filePort) {
        acceptedPeers.add(peerId);
        acceptedAt.putIfAbsent(peerId, LocalDateTime.now());
        if (ctrlPort > 0) {
            acceptedPeerInfo.put(peerId, new PeerInfo(peerId, displayName, ip, filePort, ctrlPort, "Online"));
        }
    }

    private void removeAcceptedPeer(String peerId) {
        acceptedPeers.remove(peerId);
        acceptedPeerInfo.remove(peerId);
        acceptedAt.remove(peerId);
    }

    /**
     * Danh sách peer đã được mình chấp nhận kết nối.
     * Ưu tiên thông tin nhận từ CONNECT_REQUEST, nếu không có (peer bản cũ) thì lấy từ danh sách quét LAN.
     */
    public List<PeerInfo> getAcceptedPeers(List<PeerInfo> discoveredPeers) {
        List<PeerInfo> result = new ArrayList<>();
        for (String id : acceptedPeers) {
            PeerInfo info = acceptedPeerInfo.get(id);
            if (info == null && discoveredPeers != null) {
                info = discoveredPeers.stream().filter(p -> id.equals(p.getPeerId())).findFirst().orElse(null);
            }
            if (info != null) result.add(info);
        }
        result.sort(Comparator.comparing(p -> acceptedAt.getOrDefault(p.getPeerId(), LocalDateTime.MIN)));
        return result;
    }

    /** Thời điểm peer được chấp nhận kết nối (null nếu không có) */
    public LocalDateTime getAcceptedAt(String peerId) {
        return peerId == null ? null : acceptedAt.get(peerId);
    }

    public void setOnPeerAccepted(Runnable callback) {
        this.onPeerAccepted = callback;
    }
    public void setpeerUpdateName(Runnable callback) {
        this.onUpdatePeerName = callback;
    }
    public void setOnRenameTab(java.util.function.BiConsumer<String, String> cb) {
        this.onRenameTab = cb;
    }
    // setter để UI đăng ký listener khi nhận DISCONNECT_NOTIFY
    public void setOnDisconnectNotify(Consumer<ControlProtocol.ParsedMessage> callback) {
        this.onDisconnectNotify = callback;
    }

    public boolean disconnectPeer(PeerInfo peer, String myUID) {
        if (peer == null) return false;

        // 1) Remove the peer from the accepted set to block further file access
        removeAcceptedPeer(peer.getPeerId());

        // 2) Notify the peer about the disconnection
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(peer.getIp(), peer.getControlPort()), 3000);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);

            String msg = ControlProtocol.build(
                    ControlProtocol.DISCONNECT_NOTIFY,
                    myUID,                 // from (server)
                    peer.getPeerId(),         // to (disconnected peer)
                    "Bạn đã bị peer '" + fileShareService.getMyDisplayName() + "' ngắt kết nối"
            );

            w.println(msg);
            out.println("[ControlServer] Sent DISCONNECT_NOTIFY to " + peer.getIp());
            return true;
        } catch (IOException e) {
            // Even if the notification fails, the peer is still removed from the accepted list
            out.println("[ControlServer] Notify failed: " + e.getMessage());
            return false;
        }
    }

    private int tryParseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    public List<String> getConnectedPeers() {
        return new ArrayList<>(acceptedPeers);
    }
}
