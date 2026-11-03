package org.example.p2pfileshare.network.relay;

import org.example.p2pfileshare.network.transport.Channel;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.example.p2prelay.protocol.RelayProtocol.*;

/**
 * Kết nối của ứng dụng tới Relay Server (Internet Mode).
 *
 * - Máy chủ động mở WebSocket ra ngoài (wss://), nên đi qua được NAT/CGNAT, không cần mở cổng router.
 * - Xác thực: relay gửi nonce, máy ký bằng khoá Ed25519 -> relay suy ra peerId từ khoá công khai.
 * - Heartbeat: gửi PING mỗi heartbeatSeconds; không nhận được gì trong timeoutSeconds thì coi như mất kết nối.
 * - Tự kết nối lại với exponential backoff (1s, 2s, 4s ... tối đa 60s), dừng sau maxReconnectAttempts lần.
 * - Trên 1 WebSocket chạy nhiều luồng ảo (RelayStream) cho kênh điều khiển và kênh file.
 */
public final class RelayClient implements AutoCloseable {

    public enum State {
        DISCONNECTED, CONNECTING, ONLINE, RECONNECTING, OFFLINE
    }

    public record Invite(String code, long expiresAtEpochSec) {
        public String displayCode() {
            return formatInviteCode(code);
        }
    }

    public record RemotePeer(String peerId, String displayName) {}

    /** Sự kiện được gọi tuần tự trên 1 luồng nền riêng (UI phải tự chuyển sang JavaFX thread) */
    public interface Listener {
        default void onStateChanged(State state, String detail) {}

        /** Trạng thái online của peer đã liên kết, theo thông báo của relay */
        default void onPresence(String peerId, boolean online) {}

        /** Có người vừa nhập đúng mã kết nối của mình */
        default void onLinked(String peerId, String displayName) {}

        default void onUnlinked(String peerId) {}
    }

    private static final int MAX_STREAMS = 64;

    private record PendingOpen(Connection connection, String peerId, Channel channel,
                               CompletableFuture<RelayStream> future) {}

    private record PendingRequest(Connection connection, CompletableFuture<String[]> future) {}

    private final RelaySettings settings;
    private final IdentityKey identity;
    private final Supplier<String> displayName;
    private final HttpClient http;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, RelayStream> streams = new ConcurrentHashMap<>();
    private final Map<Integer, PendingOpen> pendingOpens = new ConcurrentHashMap<>();
    private final Map<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    /** peerId -> link token relay cấp, gửi lại khi kết nối lại để khôi phục liên kết */
    private final Map<String, String> linkTokens;
    private final Map<String, Boolean> presence = new ConcurrentHashMap<>();
    private final AtomicInteger reqIds = new AtomicInteger();
    private final ExecutorService events;
    private final ExecutorService inbound;
    private final ScheduledExecutorService timer;
    private final Random jitter = new Random();
    private final Object wake = new Object();

    private volatile Consumer<RelayStream> inboundHandler;
    private volatile State state = State.DISCONNECTED;
    private volatile String stateDetail = "Chưa kết nối relay";
    private volatile Connection current;
    private volatile int streamWindow = DEFAULT_STREAM_WINDOW;
    private volatile boolean running;
    private Thread supervisor;

    public RelayClient(RelaySettings settings, IdentityKey identity, Supplier<String> displayName) {
        this(settings, identity, displayName, new ConcurrentHashMap<>());
    }

    /** @param linkTokenStore nơi giữ link token, dùng chung giữa các lần tạo client (đổi relay, bấm kết nối lại) */
    public RelayClient(RelaySettings settings, IdentityKey identity, Supplier<String> displayName,
                       Map<String, String> linkTokenStore) {
        this.linkTokens = linkTokenStore;
        this.settings = settings;
        this.identity = identity;
        this.displayName = displayName;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds));
        if (settings.sslContext != null) b.sslContext(settings.sslContext);
        this.http = b.build();
        this.events = Executors.newSingleThreadExecutor(daemon("relay-events"));
        this.inbound = Executors.newCachedThreadPool(daemon("relay-inbound"));
        this.timer = Executors.newSingleThreadScheduledExecutor(daemon("relay-heartbeat"));
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ===================== API =====================

    public void addListener(Listener l) {
        listeners.add(l);
    }

    /** Nhận luồng do peer khác mở tới mình (kênh control -> ControlServer, kênh file -> ChunkedFileServer) */
    public void setInboundHandler(Consumer<RelayStream> handler) {
        this.inboundHandler = handler;
    }

    public String myPeerId() {
        return identity.peerId();
    }

    public State getState() {
        return state;
    }

    public String getStateDetail() {
        return stateDetail;
    }

    public boolean isOnline() {
        return state == State.ONLINE && current != null;
    }

    /** true/false theo thông báo gần nhất của relay, null nếu chưa biết */
    public Boolean isPeerOnline(String peerId) {
        return presence.get(peerId);
    }

    public boolean isLinked(String peerId) {
        return linkTokens.containsKey(peerId);
    }

    /** Bắt đầu kết nối (và tự kết nối lại khi mất mạng) trên luồng nền */
    public synchronized void start() {
        if (running) return;
        running = true;
        supervisor = new Thread(this::superviseLoop, "relay-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    /** Ngắt relay, không tự kết nối lại */
    public void stop() {
        Thread sup;
        synchronized (this) {
            running = false;
            sup = supervisor;
        }
        synchronized (wake) {
            wake.notifyAll();
        }
        Connection c = current;
        if (c != null) c.fail("Đã ngắt relay");
        if (sup != null && sup != Thread.currentThread()) {
            try {
                sup.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
        timer.shutdownNow();
        inbound.shutdownNow();
        events.shutdown();
    }

    public Invite createInvite() throws IOException {
        String[] r = request(req -> join(CREATE_INVITE, req));
        if (r.length < 4 || !INVITE.equals(r[0])) throw new IOException("Relay trả lời sai giao thức");
        return new Invite(r[2], Long.parseLong(r[3]));
    }

    /** Tra mã mời của máy kia -> danh tính máy đó. Relay tạo liên kết giữa 2 máy để cho phép chuyển tiếp. */
    public RemotePeer resolveInvite(String rawCode) throws IOException {
        String code = normalizeInviteCode(rawCode);
        if (code == null) throw new RelayException(E_INVITE_INVALID, "Mã kết nối gồm 8 ký tự, ví dụ ABCD-EFGH");
        String[] r = request(req -> join(RESOLVE, req, code));
        if (r.length < 5 || !RESOLVED.equals(r[0]) || !isValidPeerId(r[2])) {
            throw new IOException("Relay trả lời sai giao thức");
        }
        return new RemotePeer(r[2], decodeText(r[3]));
    }

    /** Huỷ liên kết với peer (khi người dùng ngắt kết nối) */
    public void unlink(String peerId) {
        if (linkTokens.remove(peerId) == null) return;
        presence.remove(peerId);
        Connection c = current;
        if (c != null) c.sendText(join(UNLINK, peerId));
    }

    /** Mở luồng ảo tới peer đã liên kết (chặn tới khi máy kia nhận hoặc hết requestTimeout) */
    public RelayStream openStream(String peerId, Channel channel) throws IOException {
        Connection c = requireOnline();
        int req = nextReq();
        PendingOpen po = new PendingOpen(c, peerId, channel, new CompletableFuture<>());
        pendingOpens.put(req, po);
        c.sendText(join(OPEN, req, peerId, channel.wireName()));
        try {
            return po.future().get(settings.requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingOpens.remove(req);
            if (po.future().cancel(false)) throw new SocketTimeoutException("Hết thời gian chờ mở kết nối qua relay");
            return po.future().join();
        } catch (ExecutionException e) {
            throw asIOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingOpens.remove(req);
            throw new InterruptedIOException();
        }
    }

    // ===================== vòng kết nối / kết nối lại =====================

    private void superviseLoop() {
        int failures = 0;
        String reason = "";
        while (running) {
            setState(failures == 0 ? State.CONNECTING : State.RECONNECTING,
                    failures == 0 ? "Đang kết nối relay..." : "Đang kết nối lại relay (lần " + failures + ")...");
            Connection c = null;
            try {
                c = connectOnce();
                failures = 0;
                setState(State.ONLINE, "Đã kết nối relay");
                c.closed.join();
                reason = c.closeReason;
            } catch (IOException e) {
                reason = e.getMessage();
            } catch (RuntimeException e) {
                reason = String.valueOf(e.getMessage());
            } finally {
                if (c != null) teardown(c);
            }
            if (!running) break;
            failures++;
            if (settings.maxReconnectAttempts > 0 && failures > settings.maxReconnectAttempts) {
                running = false;
                setState(State.OFFLINE, "Không kết nối được relay sau " + settings.maxReconnectAttempts
                        + " lần thử (" + reason + "). Bấm Kết nối relay để thử lại.");
                return;
            }
            long delay = settings.backoffDelayMs(failures, jitter.nextDouble());
            setState(State.RECONNECTING, "Mất kết nối relay (" + reason + "). Thử lại sau "
                    + Math.max(1, delay / 1000) + " giây (lần " + failures + ")");
            synchronized (wake) {
                try {
                    if (running) wake.wait(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        setState(State.DISCONNECTED, "Đã ngắt relay");
    }

    private Connection connectOnce() throws IOException {
        Connection c = new Connection();
        try {
            c.ws = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds))
                    .buildAsync(settings.uri, c)
                    .get(settings.connectTimeoutSeconds + 5L, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Không kết nối được relay: " + rootMessage(e));
        } catch (TimeoutException e) {
            throw new IOException("Hết thời gian kết nối relay");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
        try {
            String[] ch = c.awaitHandshake(settings.requestTimeoutSeconds);
            if (!CHALLENGE.equals(ch[0]) || ch.length < 3) throw new IOException("Relay trả lời sai giao thức");
            if (!String.valueOf(VERSION).equals(ch[2])) throw new RelayException(E_VERSION);
            c.sendText(join(HELLO, identity.publicKeyBase64(), identity.signChallenge(ch[1]),
                    encodeText(displayName.get())));
            String[] w = c.awaitHandshake(settings.requestTimeoutSeconds);
            if (ERROR.equals(w[0])) throw new RelayException(w.length > 1 ? w[1] : null);
            if (!WELCOME.equals(w[0]) || w.length < 5 || !identity.peerId().equals(w[1])) {
                throw new IOException("Relay trả lời sai giao thức");
            }
            try {
                streamWindow = Integer.parseInt(w[4]);
            } catch (NumberFormatException ignored) {
            }
            current = c;
            // khôi phục liên kết (sau khi mất mạng hoặc relay khởi động lại)
            linkTokens.forEach((peer, token) -> c.sendText(join(RESTORE, peer, token)));
            c.heartbeat = timer.scheduleAtFixedRate(() -> c.heartbeatTick(), settings.heartbeatSeconds,
                    settings.heartbeatSeconds, TimeUnit.SECONDS);
            return c;
        } catch (IOException | RuntimeException e) {
            c.fail(e.getMessage());
            throw e;
        }
    }

    /** Kết nối đã đóng: huỷ mọi luồng và yêu cầu đang chờ trên kết nối đó */
    private void teardown(Connection c) {
        c.fail(c.closeReason.isEmpty() ? "đóng" : c.closeReason);
        if (c.heartbeat != null) c.heartbeat.cancel(false);
        if (current == c) current = null;
        IOException lost = new IOException("Mất kết nối relay");
        streams.values().removeIf(s -> {
            if (s.connection() != c) return false;
            s.onReset("Mất kết nối relay");
            return true;
        });
        pendingOpens.values().removeIf(p -> {
            if (p.connection() != c) return false;
            p.future().completeExceptionally(lost);
            return true;
        });
        pendingRequests.values().removeIf(p -> {
            if (p.connection() != c) return false;
            p.future().completeExceptionally(lost);
            return true;
        });
        // không còn biết trạng thái peer: UI hiển thị Reconnecting theo trạng thái relay
        presence.clear();
    }

    // ===================== xử lý message =====================

    private void onTextMessage(Connection c, String msg) {
        String[] t = split(msg);
        if (!c.authenticated) {
            if (WELCOME.equals(t[0])) c.authenticated = true;
            c.handshake.add(t);
            return;
        }
        switch (t[0]) {
            case PONG -> { }
            case INVITE, RESOLVED -> {
                if (RESOLVED.equals(t[0]) && t.length >= 5) linkTokens.put(t[2], t[4]);
                completeRequest(t, t.length > 1 ? t[1] : null);
            }
            case ERROR -> {
                if (t.length >= 3) failRequest(t[2], new RelayException(t[1]));
                else System.err.println("[Relay] Lỗi từ relay: " + (t.length > 1 ? t[1] : "?"));
            }
            case LINKED -> {
                if (t.length < 4 || !isValidPeerId(t[1])) return;
                linkTokens.put(t[1], t[3]);
                presence.put(t[1], true);
                String name = decodeText(t[2]);
                fire(l -> l.onLinked(t[1], name));
            }
            case RESTORED -> {
                if (t.length < 3) return;
                boolean on = ONLINE.equals(t[2]);
                presence.put(t[1], on);
                fire(l -> l.onPresence(t[1], on));
            }
            case RESTORE_FAILED, UNLINKED -> {
                if (t.length < 2) return;
                linkTokens.remove(t[1]);
                presence.remove(t[1]);
                fire(l -> l.onUnlinked(t[1]));
            }
            case PRESENCE -> {
                if (t.length < 3) return;
                boolean on = ONLINE.equals(t[2]);
                presence.put(t[1], on);
                fire(l -> l.onPresence(t[1], on));
            }
            case OPENED -> {
                if (t.length >= 3) handleOpened(c, parseInt(t[1]), parseInt(t[2]));
            }
            case OPEN_FAILED -> {
                if (t.length < 3) return;
                PendingOpen po = pendingOpens.remove(parseInt(t[1]));
                if (po != null) po.future().completeExceptionally(new RelayException(t[2]));
            }
            case INCOMING -> {
                if (t.length >= 4) handleIncoming(c, parseInt(t[1]), t[2], t[3]);
            }
            case CREDIT -> {
                if (t.length < 3) return;
                RelayStream s = streams.get(parseInt(t[1]));
                int n = parseInt(t[2]);
                if (s != null && s.connection() == c && n > 0) s.onCredit(n);
            }
            case CLOSE -> {
                if (t.length < 2) return;
                RelayStream s = streams.remove(parseInt(t[1]));
                if (s != null) s.onRemoteClose();
            }
            case RESET -> {
                if (t.length < 3) return;
                RelayStream s = streams.remove(parseInt(t[1]));
                if (s != null) s.onReset(RelayException.describe(t[2]));
            }
            default -> { }
        }
    }

    private void onBinaryMessage(Connection c, byte[] buf, int len) {
        if (len < DATA_HEADER_SIZE || buf[0] != FRAME_DATA) return;
        int sid = ByteBuffer.wrap(buf, 1, 4).getInt();
        RelayStream s = streams.get(sid);
        if (s == null || s.connection() != c) return;
        s.onData(buf, DATA_HEADER_SIZE, len - DATA_HEADER_SIZE);
    }

    private void handleOpened(Connection c, int req, int sid) {
        PendingOpen po = pendingOpens.remove(req);
        if (po == null || po.connection() != c || po.future().isDone()) {
            c.sendText(join(CLOSE, sid)); // người mở đã thôi chờ
            return;
        }
        RelayStream s = new RelayStream(c, sid, po.peerId(), po.channel(), streamWindow,
                settings.writeTimeoutSeconds * 1000L);
        // đăng ký trước khi trả về: dữ liệu của luồng có thể tới ngay sau OPENED
        streams.put(sid, s);
        if (!po.future().complete(s)) {
            streams.remove(sid);
            s.close();
        }
    }

    private void handleIncoming(Connection c, int sid, String from, String chName) {
        Channel ch = Channel.fromWire(chName);
        Consumer<RelayStream> handler = inboundHandler;
        if (ch == null || handler == null || !isValidPeerId(from) || streams.size() >= MAX_STREAMS) {
            c.sendText(join(REJECT, sid));
            return;
        }
        // "from" do relay điền theo phiên đã xác thực, không phải do peer kia tự khai
        RelayStream s = new RelayStream(c, sid, from, ch, streamWindow, settings.writeTimeoutSeconds * 1000L);
        streams.put(sid, s);
        c.sendText(join(ACCEPT, sid));
        try {
            inbound.execute(() -> {
                try {
                    handler.accept(s);
                } catch (RuntimeException e) {
                    s.close();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            s.close();
        }
    }

    // ===================== tiện ích =====================

    private String[] request(IntFunction<String> build) throws IOException {
        Connection c = requireOnline();
        int req = nextReq();
        CompletableFuture<String[]> f = new CompletableFuture<>();
        pendingRequests.put(req, new PendingRequest(c, f));
        try {
            c.sendText(build.apply(req));
            return f.get(settings.requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new SocketTimeoutException("Relay không trả lời");
        } catch (ExecutionException e) {
            throw asIOException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        } finally {
            pendingRequests.remove(req);
        }
    }

    private void completeRequest(String[] t, String reqStr) {
        PendingRequest p = reqStr == null ? null : pendingRequests.get(parseInt(reqStr));
        if (p != null) p.future().complete(t);
    }

    private void failRequest(String reqStr, IOException e) {
        int req = parseInt(reqStr);
        PendingRequest p = pendingRequests.get(req);
        if (p != null) p.future().completeExceptionally(e);
        PendingOpen po = pendingOpens.remove(req);
        if (po != null) po.future().completeExceptionally(e);
    }

    private Connection requireOnline() throws IOException {
        Connection c = current;
        if (c == null || state != State.ONLINE) {
            throw new IOException(state == State.RECONNECTING ? "Đang kết nối lại relay" : "Chưa kết nối relay");
        }
        return c;
    }

    private int nextReq() {
        return reqIds.incrementAndGet() & 0x3FFFFFFF;
    }

    private void setState(State s, String detail) {
        state = s;
        stateDetail = detail;
        fire(l -> l.onStateChanged(s, detail));
    }

    private void fire(Consumer<Listener> event) {
        try {
            events.execute(() -> {
                for (Listener l : listeners) {
                    try {
                        event.accept(l);
                    } catch (RuntimeException e) {
                        System.err.println("[Relay] Listener lỗi: " + e);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // client đã đóng
        }
    }

    private static IOException asIOException(Throwable t) {
        if (t instanceof IOException io) return io;
        return new IOException(t == null ? "Lỗi relay" : t.getMessage(), t);
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return m == null || m.isBlank() ? r.getClass().getSimpleName() : m;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 1 kết nối WebSocket tới relay (mỗi lần kết nối lại tạo đối tượng mới) */
    final class Connection implements WebSocket.Listener {
        volatile WebSocket ws;
        volatile boolean authenticated;
        volatile String closeReason = "";
        volatile long lastReceivedMs = System.currentTimeMillis();
        volatile ScheduledFuture<?> heartbeat;
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final LinkedBlockingQueue<String[]> handshake = new LinkedBlockingQueue<>();
        private final StringBuilder textBuf = new StringBuilder();
        private final byte[] binBuf = new byte[MAX_BINARY_MESSAGE];
        private int binLen;
        private CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);

        String[] awaitHandshake(int timeoutSeconds) throws IOException {
            try {
                String[] t = handshake.poll(timeoutSeconds, TimeUnit.SECONDS);
                if (t == null) {
                    throw new IOException(closed.isDone() ? "Relay đóng kết nối: " + closeReason
                            : "Relay không phản hồi khi xác thực");
                }
                return t;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        void heartbeatTick() {
            if (closed.isDone()) return;
            long silentMs = System.currentTimeMillis() - lastReceivedMs;
            if (silentMs > settings.timeoutSeconds * 1000L) {
                fail("không nhận phản hồi từ relay trong " + settings.timeoutSeconds + " giây");
                return;
            }
            sendText(PING);
        }

        CompletableFuture<Void> sendText(String s) {
            return enqueue(w -> w.sendText(s, true));
        }

        CompletableFuture<Void> sendBinary(ByteBuffer b) {
            return enqueue(w -> w.sendBinary(b, true));
        }

        /** WebSocket của JDK chỉ cho 1 lệnh gửi đang chờ: xếp các lệnh gửi nối tiếp nhau, không chặn luồng gọi */
        private synchronized CompletableFuture<Void> enqueue(Function<WebSocket, CompletableFuture<WebSocket>> op) {
            WebSocket w = ws;
            if (w == null || closed.isDone()) return CompletableFuture.failedFuture(new IOException("Mất kết nối relay"));
            CompletableFuture<Void> next = sendTail.handle((r, e) -> null)
                    .thenCompose(v -> op.apply(w))
                    .thenApply(x -> (Void) null);
            sendTail = next;
            return next;
        }

        void unregister(RelayStream s) {
            streams.remove(s.sid(), s);
        }

        void fail(String reason) {
            if (closeReason.isEmpty() && reason != null) closeReason = reason;
            WebSocket w = ws;
            if (w != null) w.abort();
            closed.complete(null);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastReceivedMs = System.currentTimeMillis();
            textBuf.append(data);
            if (textBuf.length() > MAX_TEXT_MESSAGE) {
                fail("relay gửi message quá lớn");
                return null;
            }
            if (last) {
                String msg = textBuf.toString();
                textBuf.setLength(0);
                try {
                    onTextMessage(this, msg);
                } catch (RuntimeException e) {
                    System.err.println("[Relay] Lỗi xử lý message: " + e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            lastReceivedMs = System.currentTimeMillis();
            int n = data.remaining();
            if (binLen + n > binBuf.length) {
                fail("relay gửi frame quá lớn");
                return null;
            }
            data.get(binBuf, binLen, n);
            binLen += n;
            if (last) {
                try {
                    onBinaryMessage(this, binBuf, binLen);
                } finally {
                    binLen = 0;
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            lastReceivedMs = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastReceivedMs = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            fail("relay đóng kết nối" + (reason == null || reason.isBlank() ? "" : ": " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(rootMessage(error));
        }
    }
}
