package org.example.p2pfileshare.network.control;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.network.transport.InboundOrigin;
import org.example.p2pfileshare.network.transport.SocketStreamConnection;
import org.example.p2pfileshare.network.transport.StreamConnection;
import org.example.p2pfileshare.service.DocumentSummaryService;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.util.SafePaths;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Consumer;

import static java.lang.System.out;

public class ControlServer {

    // Số kết nối điều khiển được xử lý cùng lúc. Mỗi lệnh là 1 kết nối ngắn, riêng CONNECT_REQUEST
    // giữ luồng tới khi người dùng bấm Chấp nhận/Từ chối; kết nối vượt quá sẽ xếp hàng chờ.
    private static final int HANDLER_THREADS = 32;

    private final int port;
    private volatile ExecutorService handlers;
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
    private Consumer<PeerInfo> onPeerConnected;
    private Consumer<String> onPeerDisconnected;
    // Callback nhận tin nhắn hệ thống
    private BiConsumer<String, String> onSystemMessageCallback;
    // (peerId, tên mới) khi peer đang kết nối đổi tên
    private BiConsumer<String, String> onRenameTab;

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

    public void setOnSystemMessageReceived(BiConsumer<String, String> callback) {
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
        handlers = Executors.newFixedThreadPool(HANDLER_THREADS, r -> {
            Thread h = new Thread(r, "control-handler");
            h.setDaemon(true);
            return h;
        });

        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                serverSocket = ss;
                out.println("[ControlServer] Listening on port " + port);
                while (running) {
                    Socket client = ss.accept();
                    try {
                        handlers.execute(() -> handleConnection(new SocketStreamConnection(client),
                                InboundOrigin.lan(client.getInetAddress().getHostAddress())));
                    } catch (RejectedExecutionException e) {
                        client.close(); // đang dừng server
                    }
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
        ExecutorService pool = handlers;
        if (pool != null) pool.shutdownNow();
    }

    /** Peer có peerId này đã được mình chấp nhận kết nối chưa */
    public boolean isAccepted(String peerId) {
        return peerId != null && acceptedPeers.contains(peerId);
    }

    /**
     * Đã chấp nhận peer VÀ kết nối đến đúng đường đã chấp nhận: peer được chấp nhận qua relay (danh tính đã xác thực)
     * thì không được dùng lại id đó từ 1 kết nối LAN chưa xác thực, và ngược lại.
     */
    public boolean isAcceptedFrom(String peerId, InboundOrigin origin) {
        if (!isAccepted(peerId) || !origin.permitsClaimedId(peerId)) return false;
        PeerInfo info = acceptedPeerInfo.get(peerId);
        PeerInfo.Route acceptedRoute = info == null ? PeerInfo.Route.LAN : info.getRoute();
        return acceptedRoute == origin.route();
    }

    /** Nhận 1 kết nối không qua ServerSocket (luồng ảo từ relay), xử lý trên pool của server */
    public void acceptExternal(StreamConnection conn, InboundOrigin origin) {
        ExecutorService pool = handlers;
        try {
            if (!running || pool == null) throw new RejectedExecutionException();
            pool.execute(() -> handleConnection(conn, origin));
        } catch (RejectedExecutionException e) {
            try {
                conn.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleConnection(StreamConnection conn, InboundOrigin origin) {
        try (StreamConnection s = conn;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // client luôn gửi request ngay sau khi kết nối, không để thread treo vô hạn
            s.setReadTimeout(15_000);
            String remoteIp = origin.remoteAddress();

            String raw = reader.readLine();
            if (raw == null || raw.isEmpty()) return;

            // Mọi lệnh đều có dạng LỆNH|idNgườiGửi|...: qua relay thì id người gửi phải đúng danh tính relay đã xác thực
            String[] head = raw.split("\\|", 3);
            if (head.length < 2 || !origin.permitsClaimedId(head[1])) {
                out.println("[ControlServer] Bỏ lệnh mạo danh từ " + s.describeRemote());
                return;
            }

            if (raw.startsWith(ControlProtocol.PING + "|")) {
                // Heartbeat: PING|from|to -> PONG|to|from|ACCEPTED (hoặc NOT_ACCEPTED nếu mình đã quên peer này)
                ControlProtocol.ParsedMessage ping = ControlProtocol.parse(raw);
                if (ping != null) {
                    writer.println(ControlProtocol.build(ControlProtocol.PONG, ping.toPeer, ping.fromPeer,
                            isAcceptedFrom(ping.fromPeer, origin) ? ControlProtocol.PONG_ACCEPTED
                                    : ControlProtocol.PONG_NOT_ACCEPTED));
                }
                return;
            }

            if (raw.startsWith("SEARCH_REQ|") || raw.startsWith("SEARCH_RES|")) {
                out.println("[ControlServer] Received Search CMD: " + raw);
                handleSearchCommand(raw, origin);
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

                    if (!isAcceptedFrom(senderId, origin)) {
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

                // Format: CMD:TÊN_LỆNH|SenderID|nội dung (vd. CMD:FILES_CHANGED|peerId|*)
                String[] parts = raw.split("\\|", 3);
                if (parts.length >= 3 && onSystemMessageCallback != null) {
                    // bỏ SenderID (dùng để chọn tab), chuyển "CMD:TÊN_LỆNH|nội dung" cho PeerTabController
                    onSystemMessageCallback.accept(parts[1], parts[0] + "|" + parts[2]);
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
                if (isAcceptedFrom(fromPeer, origin)) {
                    // đã chấp nhận trước đó (vd. bên kia đóng tab rồi kết nối lại) -> không hỏi lại
                    accept = true;
                } else if (onIncomingConnect != null) {
                    long askedAt = System.currentTimeMillis();
                    String shownName = origin.route() == PeerInfo.Route.RELAY ? displayName + " (qua Internet)" : displayName;
                    try {
                        accept = Boolean.TRUE.equals(onIncomingConnect.apply(shownName));
                    } catch (Exception ex) {
                        accept = false;
                    }
                    // trả lời quá hạn thì bên gửi đã thôi chờ -> coi như từ chối để 2 bên không lệch trạng thái
                    if (System.currentTimeMillis() - askedAt > (ControlProtocol.CONNECT_DECISION_SECONDS + 5) * 1000L) {
                        out.println("[ControlServer] Decision for " + fromPeer + " came too late, rejected");
                        accept = false;
                    }
                } else {
                    accept = true;
                }

                // đăng ký trước khi trả lời: bên kia nhận ACCEPT xong sẽ gửi LIST_FILES ngay
                if (accept) {
                    registerAcceptedPeer(fromPeer, displayName, origin, remoteIp, ctrlPort, filePort);
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
                if (writer.checkError()) {
                    // bên kia đã đóng kết nối (tắt app, hết giờ chờ) -> huỷ chấp nhận, không báo "đã kết nối"
                    out.println("[ControlServer] Cannot reply to " + fromPeer + ", connection cancelled");
                    if (accept) removeAcceptedPeer(fromPeer);
                    return;
                }
                out.println("[ControlServer] Sent: " + resp);

                if (accept) {
                    PeerInfo info = acceptedPeerInfo.get(fromPeer);
                    if (info != null && onPeerConnected != null) onPeerConnected.accept(info);
                    if (onPeerAccepted != null) onPeerAccepted.run();
                }
            }
            else if (ControlProtocol.LIST_FILES.equals(msg.command)) {
                // Trả về danh sách file chia sẻ (tên/relative path)
                String toPeer   = msg.toPeer;   // mình
                String fromPeer = msg.fromPeer; // client
                // CHẶN NẾU CHƯA ĐƯỢC ACCEPT
                if (!isAcceptedFrom(fromPeer, origin)) {
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
            else if (ControlProtocol.DISCONNECT_REQUEST.equals(msg.command)
                    || ControlProtocol.DISCONNECT_NOTIFY.equals(msg.command)) {
                // Peer kia ngắt kết nối (DISCONNECT_NOTIFY: peer bản cũ chặn mình) -> ngắt cả 2 chiều
                String fromPeer = msg.fromPeer;
                if (isAccepted(fromPeer) && !isAcceptedFrom(fromPeer, origin)) {
                    out.println("[ControlServer] Bỏ DISCONNECT từ đường khác với đường đã chấp nhận: " + fromPeer);
                    return;
                }
                removeAcceptedPeer(fromPeer);
                if (ControlProtocol.DISCONNECT_REQUEST.equals(msg.command)) {
                    // xác nhận đã ngắt
                    writer.println(ControlProtocol.build(ControlProtocol.DISCONNECT_NOTIFY,
                            msg.toPeer, fromPeer, "Disconnected"));
                }
                out.println("[ControlServer] Disconnected by " + fromPeer);
                if (onPeerDisconnected != null) onPeerDisconnected.accept(fromPeer);
                if (onPeerAccepted != null) onPeerAccepted.run();
            }
            //server gửi gói tin và server ở client nhận hàm này để cập nhật tên peer
            else if (ControlProtocol.UPDATE_NAMESERVER.equals(msg.command)) {
                // Format: UPDATE_NAME|fromId|tênMới (trường thứ 3 là tên mới, không phải peer đích)
                out.println("[ControlServer] Received UPDATE_NAME from " + msg.fromPeer + " new name: " + msg.toPeer);

                if (!isAcceptedFrom(msg.fromPeer, origin)) return;
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
    }

    // XỬ LÝ LỆNH TÌM KIẾM
    private void handleSearchCommand(String raw, InboundOrigin origin) {
        // Format: SEARCH_REQ|SenderID|Keyword
        // Format: SEARCH_RES|SenderID|FileData
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) return;

        String cmd = parts[0];
        String senderId = parts[1];
        String content = parts[2];

        if ("SEARCH_REQ".equals(cmd)) {
            // Chỉ trả lời peer đã được chấp nhận kết nối
            if (!isAcceptedFrom(senderId, origin)) {
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
        List<SharedFileLocal> list = fileShareService.listSharedFiles();
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

    // File nằm trong thư mục chia sẻ (chặn "../", đường dẫn tuyệt đối, symlink ra ngoài), null nếu không hợp lệ
    private File resolveSharedFile(String relativePath) {
        if (fileShareService == null || fileShareService.getShareFolder() == null) return null;
        Path p = SafePaths.resolveSharedFile(fileShareService.getShareFolder().toPath(), relativePath);
        return p == null ? null : p.toFile();
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("\t", " ").replace("\n", " ");
    }
    private void registerAcceptedPeer(String peerId, String displayName, InboundOrigin origin, String ip,
                                      int ctrlPort, int filePort) {
        acceptedPeers.add(peerId);
        acceptedAt.putIfAbsent(peerId, LocalDateTime.now());
        if (origin.route() == PeerInfo.Route.RELAY) {
            // peer ở mạng khác: liên lạc lại qua relay theo danh tính đã xác thực, không cần IP/cổng
            acceptedPeerInfo.put(peerId, PeerInfo.viaRelay(peerId, displayName));
        } else if (ctrlPort > 0) {
            acceptedPeerInfo.put(peerId, new PeerInfo(peerId, displayName, ip, filePort, ctrlPort, "Online"));
        }
    }

    private void removeAcceptedPeer(String peerId) {
        if (peerId == null) return;
        acceptedPeers.remove(peerId);
        acceptedPeerInfo.remove(peerId);
        acceptedAt.remove(peerId);
    }

    /**
     * Mình kết nối tới peer này và được chấp nhận -> cho peer đó xem file của mình luôn (kết nối 2 chiều).
     * Dùng chính đối tượng PeerInfo của ControlClient để đổi tên cập nhật cả 2 nơi.
     */
    public void allowPeer(PeerInfo peer) {
        if (peer == null || peer.getPeerId() == null) return;
        acceptedPeers.add(peer.getPeerId());
        acceptedAt.putIfAbsent(peer.getPeerId(), LocalDateTime.now());
        acceptedPeerInfo.put(peer.getPeerId(), peer);
        if (onPeerAccepted != null) onPeerAccepted.run();
    }

    /** Thu hồi quyền xem/tải file của peer (khi mình ngắt kết nối) */
    public void revokePeer(String peerId) {
        removeAcceptedPeer(peerId);
        if (onPeerAccepted != null) onPeerAccepted.run();
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

    /** Thông tin liên lạc của peer đã được chấp nhận (gửi kèm CONNECT_REQUEST), null nếu không có */
    public PeerInfo getAcceptedPeerInfo(String peerId) {
        return peerId == null ? null : acceptedPeerInfo.get(peerId);
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
    public void setOnRenameTab(BiConsumer<String, String> cb) {
        this.onRenameTab = cb;
    }
    /** Đã trả lời CHẤP NHẬN cho peer (thông tin liên lạc lấy từ CONNECT_REQUEST). Gọi trên luồng mạng. */
    public void setOnPeerConnected(Consumer<PeerInfo> callback) {
        this.onPeerConnected = callback;
    }

    /** Peer kia đã ngắt kết nối với mình (tham số = peerId). Gọi trên luồng mạng. */
    public void setOnPeerDisconnected(Consumer<String> callback) {
        this.onPeerDisconnected = callback;
    }

    private int tryParseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }
}
