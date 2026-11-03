package org.example.p2prelay;

import org.example.p2prelay.protocol.RelayProtocol;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.example.p2prelay.protocol.RelayProtocol.*;

/**
 * Relay server: định tuyến lệnh và chuyển tiếp luồng dữ liệu giữa 2 máy đã liên kết.
 *
 * Relay không lưu file: mỗi frame dữ liệu chỉ nằm trong hàng đợi gửi (có giới hạn byte) cho tới khi được ghi
 * sang máy đích. Relay cũng không quyết định quyền xem file: người nhận vẫn phải bấm Chấp nhận trong ứng dụng.
 */
public final class RelayServer implements Closeable {

    /** Luồng ảo giữa 2 phiên */
    static final class StreamEntry {
        final int sid;
        final RelaySession opener;
        final RelaySession target;
        final String reqId;
        final long createdMs;
        volatile boolean established;

        StreamEntry(int sid, RelaySession opener, RelaySession target, String reqId, long createdMs) {
            this.sid = sid;
            this.opener = opener;
            this.target = target;
            this.reqId = reqId;
            this.createdMs = createdMs;
        }

        RelaySession other(RelaySession s) {
            if (s == opener) return target;
            if (s == target) return opener;
            return null;
        }
    }

    private final RelayConfig cfg;
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong sessionIds = new AtomicLong();
    private final AtomicInteger nextSid = new AtomicInteger(1);
    private final Map<Long, RelaySession> allSessions = new ConcurrentHashMap<>();
    private final Map<String, RelaySession> online = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private final Map<Integer, StreamEntry> streams = new ConcurrentHashMap<>();
    private final InviteRegistry invites;
    private final LinkRegistry links;
    private final RateLimiter sessionFails;
    private final RateLimiter ipFails;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public RelayServer(RelayConfig cfg) {
        cfg.validate();
        this.cfg = cfg;
        this.invites = new InviteRegistry(cfg.inviteTtlSeconds * 1000, cfg.inviteMaxUses, System::currentTimeMillis);
        this.links = new LinkRegistry(cfg.linkSecret, cfg.linkTtlSeconds * 1000, System::currentTimeMillis);
        this.sessionFails = new RateLimiter(cfg.resolveMaxFailsPerSession, cfg.resolveSessionWindowSeconds * 1000L,
                System::currentTimeMillis);
        this.ipFails = new RateLimiter(cfg.resolveMaxFailsPerIp, cfg.resolveIpWindowSeconds * 1000L,
                System::currentTimeMillis);
    }

    // ===================== vòng đời =====================

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = cfg.tlsKeystorePath != null ? createTlsServerSocket() : new ServerSocket();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(cfg.bindAddress, cfg.port), 128);
        serverSocket = ss;
        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "relay-maintenance");
            t.setDaemon(true);
            return t;
        });
        long tickMs = Math.max(200, Math.min(cfg.heartbeatSeconds * 1000L, 5000) / 2);
        scheduler.scheduleAtFixedRate(this::safeTick, tickMs, tickMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::cleanup, 60, 60, TimeUnit.SECONDS);

        Thread acceptor = new Thread(this::acceptLoop, "relay-accept");
        acceptor.setDaemon(false);
        acceptor.start();

        RelayLog.info("Relay đang lắng nghe " + cfg.bindAddress + ":" + getPort()
                + (cfg.tlsKeystorePath != null ? " (TLS/wss)" : " (ws, TLS do reverse proxy đảm nhận)"));
        if (cfg.linkSecret == null) {
            RelayLog.warn("Chưa đặt RELAY_LINK_SECRET: liên kết giữa các peer sẽ mất khi relay khởi động lại");
        }
    }

    public int getPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    public int onlineCount() {
        return online.size();
    }

    public int activeStreamCount() {
        return streams.size();
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        if (scheduler != null) scheduler.shutdownNow();
        for (RelaySession s : allSessions.values()) s.close("relay dừng");
        RelayLog.info("Relay đã dừng");
    }

    private ServerSocket createTlsServerSocket() throws IOException {
        try (InputStream in = new FileInputStream(cfg.tlsKeystorePath)) {
            char[] pass = cfg.tlsKeystorePassword.toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, pass);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
            ss.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            return ss;
        } catch (GeneralSecurityException e) {
            throw new IOException("Không nạp được keystore TLS: " + e.getMessage(), e);
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = serverSocket.accept();
            } catch (IOException e) {
                if (running) RelayLog.warn("accept lỗi: " + e.getMessage());
                continue;
            }
            Thread t = new Thread(() -> handleConnection(s), "relay-session");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handleConnection(Socket socket) {
        WebSocketConnection ws = null;
        String ip = socket.getInetAddress().getHostAddress();
        try {
            socket.setSoTimeout(cfg.handshakeTimeoutSeconds * 1000);
            socket.setTcpNoDelay(true);
            ws = new WebSocketConnection(socket, MAX_TEXT_MESSAGE, MAX_BINARY_MESSAGE);
            WebSocketConnection.HttpRequest req = ws.readHttpRequest();
            ip = clientIp(socket, req);

            if (HEALTH_PATH.equals(req.path()) && "GET".equals(req.method())) {
                ws.writeHttp(200, "OK", "", "ok online=" + online.size() + "\n");
                ws.close();
                return;
            }
            if (!WS_PATH.equals(req.path()) || !WebSocketConnection.isWebSocketUpgrade(req)) {
                ws.writeHttp(404, "Not Found", "", "P2PFileShare relay: dùng WebSocket tại " + WS_PATH + "\n");
                ws.close();
                return;
            }
            if (allSessions.size() >= cfg.maxSessions) {
                ws.writeHttp(503, "Service Unavailable", "", "relay đầy\n");
                ws.close();
                return;
            }
            AtomicInteger ipCount = perIp.computeIfAbsent(ip, k -> new AtomicInteger());
            if (ipCount.incrementAndGet() > cfg.maxConnectionsPerIp) {
                ipCount.decrementAndGet();
                ws.writeHttp(429, "Too Many Requests", "", "quá nhiều kết nối từ 1 IP\n");
                ws.close();
                return;
            }
            RelaySession session;
            try {
                ws.acceptUpgrade(req);
                session = new RelaySession(sessionIds.incrementAndGet(), ip, this, ws, cfg.sessionQueueBytes,
                        cfg.stallTimeoutSeconds * 1000L);
            } catch (IOException e) {
                ipCount.decrementAndGet();
                throw e;
            }
            allSessions.put(session.id, session);
            // từ đây idle do bộ dọn dẹp định kỳ kiểm soát; timeout socket chỉ là lưới an toàn
            socket.setSoTimeout(cfg.idleTimeoutSeconds * 2000);
            session.startWriter();

            byte[] n = new byte[24];
            random.nextBytes(n);
            session.nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(n);
            session.sendText(join(CHALLENGE, session.nonce, VERSION));
            session.readLoop();
        } catch (IOException e) {
            if (ws != null) ws.close();
        }
    }

    private String clientIp(Socket socket, WebSocketConnection.HttpRequest req) {
        String ip = socket.getInetAddress().getHostAddress();
        if (!cfg.trustProxyHeaders) return ip;
        String xff = req.header("x-forwarded-for");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String real = req.header("x-real-ip");
        return real != null && !real.isBlank() ? real.trim() : ip;
    }

    // ===================== xử lý lệnh =====================

    void handleText(RelaySession s, String text) {
        String[] t = split(text);
        String cmd = t[0];
        if (!s.authenticated) {
            if (HELLO.equals(cmd) && t.length == 4) handleHello(s, t);
            else s.closeWithError(E_AUTH_FAILED);
            return;
        }
        switch (cmd) {
            case PING -> s.sendText(PONG);
            case CREATE_INVITE -> {
                if (arity(s, t, 2)) handleCreateInvite(s, t[1]);
            }
            case RESOLVE -> {
                if (arity(s, t, 3)) handleResolve(s, t[1], t[2]);
            }
            case RESTORE -> {
                if (arity(s, t, 3)) handleRestore(s, t[1], t[2]);
            }
            case UNLINK -> {
                if (arity(s, t, 2)) handleUnlink(s, t[1]);
            }
            case OPEN -> {
                if (arity(s, t, 4)) handleOpen(s, t[1], t[2], t[3]);
            }
            case ACCEPT -> {
                if (arity(s, t, 2)) handleAccept(s, parseInt(t[1]));
            }
            case REJECT -> {
                if (arity(s, t, 2)) handleReject(s, parseInt(t[1]));
            }
            case CREDIT -> {
                if (arity(s, t, 3)) handleCredit(s, parseInt(t[1]), parseInt(t[2]));
            }
            case CLOSE -> {
                if (arity(s, t, 2)) handleClose(s, parseInt(t[1]), null);
            }
            case RESET -> {
                if (arity(s, t, 3)) handleClose(s, parseInt(t[1]), t[2].matches("[A-Z_]{1,32}") ? t[2] : E_PEER_GONE);
            }
            default -> s.sendText(join(ERROR, E_BAD_REQUEST));
        }
    }

    private boolean arity(RelaySession s, String[] t, int n) {
        if (t.length == n) return true;
        s.sendText(join(ERROR, E_BAD_REQUEST));
        return false;
    }

    private void handleHello(RelaySession s, String[] t) {
        String peerId;
        try {
            byte[] pub = Base64.getUrlDecoder().decode(t[1]);
            byte[] sig = Base64.getUrlDecoder().decode(t[2]);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(pub));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(authPayload(s.nonce));
            if (!verifier.verify(sig)) throw new GeneralSecurityException("chữ ký sai");
            // id suy ra từ khoá công khai: chỉ máy giữ khoá bí mật mới đăng nhập được với id này
            peerId = peerIdFromPublicKey(key.getEncoded());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            RelayLog.warn("Phiên " + s.id + " từ " + s.ip + " xác thực thất bại");
            s.closeWithError(E_AUTH_FAILED);
            return;
        }
        String name = decodeText(t[3]).strip();
        if (name.length() > 64) name = name.substring(0, 64);

        s.peerId = peerId;
        s.displayName = name;
        s.nonce = null;
        s.authenticated = true;
        RelaySession old = online.put(peerId, s);
        if (old != null && old != s) {
            // cùng máy kết nối lại (phiên cũ có thể đã chết mà chưa hết timeout)
            old.replaced = true;
            old.close("thay bằng phiên mới");
        }
        s.sendText(join(WELCOME, peerId, cfg.heartbeatSeconds, cfg.idleTimeoutSeconds, DEFAULT_STREAM_WINDOW));
        RelayLog.info("Peer " + shortId(peerId) + " online (phiên " + s.id + ", ip " + s.ip + ")");

        // Báo cho các peer đã liên kết (relay chưa khởi động lại) và gửi lại trạng thái của họ
        for (String other : links.linkedPeers(peerId)) {
            RelaySession o = online.get(other);
            s.sendText(join(PRESENCE, other, o != null ? ONLINE : OFFLINE));
            if (o != null) o.forceSendText(join(PRESENCE, peerId, ONLINE));
        }
    }

    private void handleCreateInvite(RelaySession s, String req) {
        if (!validReq(req)) {
            s.sendText(join(ERROR, E_BAD_REQUEST));
            return;
        }
        InviteRegistry.Invite inv = invites.create(s.peerId);
        s.sendText(join(INVITE, req, inv.code(), inv.expiresAtMs() / 1000));
        RelayLog.info("Peer " + shortId(s.peerId) + " tạo mã mời mới");
    }

    private void handleResolve(RelaySession s, String req, String rawCode) {
        if (!validReq(req)) {
            s.sendText(join(ERROR, E_BAD_REQUEST));
            return;
        }
        String sKey = "s:" + s.peerId;
        String ipKey = "ip:" + s.ip;
        if (sessionFails.isLimited(sKey) || ipFails.isLimited(ipKey)) {
            RelayLog.warn("Peer " + shortId(s.peerId) + " (ip " + s.ip + ") nhập sai mã quá nhiều lần, tạm chặn");
            s.sendText(join(ERROR, E_RATE_LIMITED, req));
            return;
        }
        String code = normalizeInviteCode(rawCode);
        InviteRegistry.Invite inv = code == null ? null : invites.peek(code);
        if (inv == null || inv.ownerPeerId().equals(s.peerId)) {
            sessionFails.record(sKey);
            ipFails.record(ipKey);
            RelayLog.info("Peer " + shortId(s.peerId) + " nhập mã mời không hợp lệ hoặc đã hết hạn");
            s.sendText(join(ERROR, E_INVITE_INVALID, req));
            return;
        }
        RelaySession owner = online.get(inv.ownerPeerId());
        if (owner == null) {
            s.sendText(join(ERROR, E_PEER_OFFLINE, req));
            return;
        }
        invites.consume(inv);
        links.link(s.peerId, owner.peerId);
        String token = links.issueToken(s.peerId, owner.peerId);
        s.sendText(join(RESOLVED, req, owner.peerId, encodeText(owner.displayName), token));
        owner.sendText(join(LINKED, s.peerId, encodeText(s.displayName), token));
        s.sendText(join(PRESENCE, owner.peerId, ONLINE));
        owner.sendText(join(PRESENCE, s.peerId, ONLINE));
        RelayLog.info("Liên kết " + shortId(s.peerId) + " <-> " + shortId(owner.peerId) + " qua mã mời");
    }

    private void handleRestore(RelaySession s, String otherId, String token) {
        String other = links.verifyToken(token, s.peerId);
        if (other == null || !other.equals(otherId)) {
            sessionFails.record("s:" + s.peerId);
            s.sendText(join(RESTORE_FAILED, isValidPeerId(otherId) ? otherId : "-"));
            return;
        }
        links.link(s.peerId, other);
        RelaySession o = online.get(other);
        s.sendText(join(RESTORED, other, o != null ? ONLINE : OFFLINE));
        if (o != null) o.forceSendText(join(PRESENCE, s.peerId, ONLINE));
    }

    private void handleUnlink(RelaySession s, String other) {
        if (!isValidPeerId(other)) return;
        if (!links.isLinked(s.peerId, other)) return;
        links.unlink(s.peerId, other);
        RelaySession o = online.get(other);
        if (o != null) o.forceSendText(join(UNLINKED, s.peerId));
        RelayLog.info("Huỷ liên kết " + shortId(s.peerId) + " <-> " + shortId(other));
    }

    private void handleOpen(RelaySession s, String req, String to, String ch) {
        if (!validReq(req) || !isValidChannel(ch) || !isValidPeerId(to)) {
            s.sendText(join(OPEN_FAILED, req, E_BAD_REQUEST));
            return;
        }
        RelaySession target = online.get(to);
        if (target == null) {
            s.sendText(join(OPEN_FAILED, req, E_PEER_OFFLINE));
            return;
        }
        // chỉ chuyển tiếp giữa 2 peer đã liên kết qua mã mời
        if (!links.isLinked(s.peerId, to)) {
            s.sendText(join(OPEN_FAILED, req, E_NOT_LINKED));
            return;
        }
        if (s.streams.size() >= cfg.maxStreamsPerSession || target.streams.size() >= cfg.maxStreamsPerSession) {
            s.sendText(join(OPEN_FAILED, req, E_LIMIT));
            return;
        }
        int sid = nextSid.getAndUpdate(v -> v >= Integer.MAX_VALUE - 1 ? 1 : v + 1);
        StreamEntry e = new StreamEntry(sid, s, target, req, System.currentTimeMillis());
        streams.put(sid, e);
        s.streams.add(sid);
        target.streams.add(sid);
        if (!target.sendText(join(INCOMING, sid, s.peerId, ch))) {
            removeStream(e);
            s.sendText(join(OPEN_FAILED, req, E_PEER_OFFLINE));
        }
    }

    private void handleAccept(RelaySession s, int sid) {
        StreamEntry e = streams.get(sid);
        if (e == null || e.target != s || e.established) return;
        e.established = true;
        e.opener.sendText(join(OPENED, e.reqId, sid));
    }

    private void handleReject(RelaySession s, int sid) {
        StreamEntry e = streams.get(sid);
        if (e == null || e.target != s || e.established) return;
        removeStream(e);
        e.opener.sendText(join(OPEN_FAILED, e.reqId, E_REJECTED));
    }

    private void handleCredit(RelaySession s, int sid, int n) {
        StreamEntry e = streams.get(sid);
        if (e == null || !e.established || n <= 0) return;
        RelaySession other = e.other(s);
        if (other != null) other.sendText(join(CREDIT, sid, n));
    }

    private void handleClose(RelaySession s, int sid, String resetReason) {
        StreamEntry e = streams.get(sid);
        if (e == null) return;
        RelaySession other = e.other(s);
        if (other == null) return;
        removeStream(e);
        if (!e.established && s == e.target) {
            other.sendText(join(OPEN_FAILED, e.reqId, E_REJECTED));
        } else if (resetReason != null) {
            other.sendText(join(RESET, sid, resetReason));
        } else {
            other.sendText(join(CLOSE, sid));
        }
    }

    /** Dữ liệu của luồng ảo: chuyển nguyên frame sang phía bên kia, không giải mã, không lưu lại */
    void handleBinary(RelaySession s, byte[] frame) {
        if (!s.authenticated || frame.length < DATA_HEADER_SIZE || frame[0] != FRAME_DATA) {
            s.closeWithError(E_BAD_REQUEST);
            return;
        }
        int sid = ByteBuffer.wrap(frame, 1, 4).getInt();
        StreamEntry e = streams.get(sid);
        if (e == null || !e.established) return; // luồng vừa đóng: bỏ frame
        RelaySession other = e.other(s);
        if (other == null) return;
        other.sendBinary(frame);
    }

    private void removeStream(StreamEntry e) {
        streams.remove(e.sid, e);
        e.opener.streams.remove(e.sid);
        e.target.streams.remove(e.sid);
    }

    // ===================== đóng phiên, dọn dẹp =====================

    void onSessionClosed(RelaySession s, String reason) {
        allSessions.remove(s.id);
        if (s.ip != null) {
            AtomicInteger c = perIp.get(s.ip);
            if (c != null && c.decrementAndGet() <= 0) perIp.remove(s.ip, c);
        }
        for (Integer sid : s.streams.toArray(new Integer[0])) {
            StreamEntry e = streams.get(sid);
            if (e == null) continue;
            removeStream(e);
            RelaySession other = e.other(s);
            if (other == null) continue;
            if (!e.established && other == e.opener) {
                other.forceSendText(join(OPEN_FAILED, e.reqId, E_PEER_OFFLINE));
            } else {
                other.forceSendText(join(RESET, sid, E_PEER_GONE));
            }
        }
        if (s.peerId != null && online.remove(s.peerId, s) && !s.replaced) {
            for (String other : links.linkedPeers(s.peerId)) {
                RelaySession o = online.get(other);
                if (o != null) o.forceSendText(join(PRESENCE, s.peerId, OFFLINE));
            }
            RelayLog.info("Peer " + shortId(s.peerId) + " offline (" + reason + ")");
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            RelayLog.warn("Lỗi bảo trì: " + e);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long idleMs = cfg.idleTimeoutSeconds * 1000L;
        long heartbeatMs = cfg.heartbeatSeconds * 1000L;
        for (RelaySession s : allSessions.values()) {
            if (!s.authenticated && now - s.createdMs > cfg.handshakeTimeoutSeconds * 1000L) {
                s.close("không xác thực kịp");
            } else if (now - s.lastSeenMs > idleMs) {
                // không nhận được gì (kể cả pong) trong idleTimeout: coi như máy đã mất mạng
                s.close("quá " + cfg.idleTimeoutSeconds + "s không phản hồi");
            } else {
                s.pingIfDue(now, heartbeatMs);
            }
        }
        long openTimeoutMs = cfg.openTimeoutSeconds * 1000L;
        for (StreamEntry e : streams.values()) {
            if (!e.established && now - e.createdMs > openTimeoutMs) {
                removeStream(e);
                e.opener.forceSendText(join(OPEN_FAILED, e.reqId, E_TIMEOUT));
                e.target.forceSendText(join(CLOSE, e.sid));
            }
        }
    }

    private void cleanup() {
        try {
            invites.cleanupExpired();
            sessionFails.cleanup();
            ipFails.cleanup();
            links.cleanup(online::containsKey);
        } catch (RuntimeException e) {
            RelayLog.warn("Lỗi dọn dẹp: " + e);
        }
    }

    private static boolean validReq(String req) {
        return req != null && req.matches("\\d{1,10}");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
