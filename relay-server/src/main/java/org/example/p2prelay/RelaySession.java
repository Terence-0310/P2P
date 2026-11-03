package org.example.p2prelay;

import org.example.p2prelay.protocol.RelayProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1 kết nối WebSocket của 1 máy tới relay.
 *
 * - Luồng reader: đọc frame, giao cho RelayServer xử lý. Khi chuyển tiếp dữ liệu sang phiên khác mà hàng đợi
 *   bên đó đầy, reader bị chặn lại -> ngừng đọc socket -> TCP tự báo bên gửi chậm lại (backpressure).
 * - Luồng writer: lấy frame trong hàng đợi và ghi ra socket.
 * Hàng đợi gửi bị giới hạn theo tổng số byte (sessionQueueBytes), nên RAM relay dùng cho mỗi phiên có chặn trên.
 */
final class RelaySession {

    private record Out(int opcode, byte[] data, int cost) {}

    private static final Out POISON = new Out(-1, new byte[0], 0);

    final long id;
    final String ip;
    final long createdMs = System.currentTimeMillis();
    private volatile long lastPingMs = createdMs;
    private final RelayServer server;
    private final WebSocketConnection ws;
    private final Semaphore queueBytes;
    private final LinkedBlockingQueue<Out> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final long stallTimeoutMs;

    /** Luồng ảo (sid) mà phiên này tham gia */
    final Set<Integer> streams = ConcurrentHashMap.newKeySet();

    volatile String peerId;
    volatile String displayName = "";
    volatile boolean authenticated;
    volatile String nonce;
    volatile long lastSeenMs = System.currentTimeMillis();
    /** true nếu phiên bị thay bằng phiên mới cùng peerId (không báo OFFLINE) */
    volatile boolean replaced;

    RelaySession(long id, String ip, RelayServer server, WebSocketConnection ws, int queueLimitBytes, long stallTimeoutMs) {
        this.id = id;
        this.ip = ip;
        this.server = server;
        this.ws = ws;
        this.queueBytes = new Semaphore(queueLimitBytes);
        this.stallTimeoutMs = stallTimeoutMs;
    }

    void startWriter() {
        Thread w = new Thread(this::writeLoop, "relay-writer-" + id);
        w.setDaemon(true);
        w.start();
    }

    /** Vòng đọc, chạy trên luồng của phiên tới khi kết nối đóng */
    void readLoop() {
        try {
            while (!closed.get()) {
                WebSocketConnection.Message m = ws.readMessage();
                lastSeenMs = System.currentTimeMillis();
                switch (m.opcode()) {
                    case WebSocketConnection.OP_TEXT -> server.handleText(this, m.text());
                    case WebSocketConnection.OP_BINARY -> server.handleBinary(this, m.data());
                    case WebSocketConnection.OP_PING -> enqueue(WebSocketConnection.OP_PONG, m.data(), false);
                    case WebSocketConnection.OP_PONG -> { }
                    case WebSocketConnection.OP_CLOSE -> {
                        close("client đóng kết nối");
                        return;
                    }
                    default -> { }
                }
            }
        } catch (WebSocketConnection.ProtocolException e) {
            RelayLog.warn("Phiên " + id + " vi phạm giao thức: " + e.getMessage());
            enqueue(WebSocketConnection.OP_CLOSE, WebSocketConnection.closePayload(e.closeCode, "protocol error"), false);
            closeSoon();
        } catch (EOFException | SocketException e) {
            close("mất kết nối");
        } catch (IOException e) {
            close("lỗi đọc: " + e.getMessage());
        } catch (RuntimeException e) {
            RelayLog.warn("Phiên " + id + " lỗi xử lý: " + e);
            close("lỗi xử lý");
        } finally {
            close("kết thúc");
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                Out o = queue.take();
                if (o == POISON) break;
                try {
                    ws.writeFrame(o.opcode(), o.data());
                } finally {
                    queueBytes.release(o.cost());
                }
                if (o.opcode() == WebSocketConnection.OP_CLOSE) break;
            }
        } catch (IOException e) {
            // socket đã đóng
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close("writer dừng");
        }
    }

    boolean sendText(String msg) {
        return enqueue(WebSocketConnection.OP_TEXT, msg.getBytes(StandardCharsets.UTF_8), true);
    }

    /** Gửi không chờ: hàng đợi đầy thì bỏ (dùng cho luồng định kỳ, không được bị chặn) */
    boolean trySendText(String msg) {
        return enqueue(WebSocketConnection.OP_TEXT, msg.getBytes(StandardCharsets.UTF_8), false);
    }

    /** Gửi ping nếu đã quá chu kỳ heartbeat kể từ lần ping trước */
    void pingIfDue(long now, long intervalMs) {
        if (now - lastPingMs < intervalMs) return;
        lastPingMs = now;
        enqueue(WebSocketConnection.OP_PING, new byte[0], false);
    }

    /**
     * Thông báo nhỏ quan trọng (đóng luồng, OFFLINE...) được gửi kể cả khi hàng đợi đang đầy,
     * để bên kia không phải chờ timeout mới biết. Mỗi sự kiện chỉ sinh 1 thông báo nên không làm tràn RAM.
     */
    void forceSendText(String msg) {
        if (closed.get()) return;
        queue.add(new Out(WebSocketConnection.OP_TEXT, msg.getBytes(StandardCharsets.UTF_8), 0));
    }

    /** Chuyển tiếp dữ liệu nhị phân: chờ tới khi hàng đợi còn chỗ (backpressure lên phiên nguồn) */
    boolean sendBinary(byte[] frame) {
        return enqueue(WebSocketConnection.OP_BINARY, frame, true);
    }

    private boolean enqueue(int opcode, byte[] data, boolean block) {
        if (closed.get()) return false;
        int cost = data.length + 16;
        try {
            boolean ok = block
                    ? queueBytes.tryAcquire(cost, stallTimeoutMs, TimeUnit.MILLISECONDS)
                    : queueBytes.tryAcquire(cost);
            if (!ok) {
                if (block) {
                    RelayLog.warn("Phiên " + id + " (" + RelayProtocol.shortId(peerId)
                            + ") không nhận dữ liệu trong " + stallTimeoutMs / 1000 + "s, đóng phiên");
                    close("hàng đợi gửi bị kẹt");
                }
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (closed.get()) {
            queueBytes.release(cost);
            return false;
        }
        queue.add(new Out(opcode, data, cost));
        return true;
    }

    boolean isClosed() {
        return closed.get();
    }

    /** Gửi close frame rồi đóng (cho writer kịp ghi) */
    void closeWithError(String code) {
        enqueue(WebSocketConnection.OP_TEXT, RelayProtocol.join(RelayProtocol.ERROR, code).getBytes(StandardCharsets.UTF_8), false);
        enqueue(WebSocketConnection.OP_CLOSE, WebSocketConnection.closePayload(1008, code), false);
        closeSoon();
    }

    private void closeSoon() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            close("đóng sau khi báo lỗi");
        }, "relay-close-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** Đóng ngay (idempotent), sau đó RelayServer dọn luồng ảo và báo OFFLINE */
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        queue.clear();
        queue.add(POISON);
        // đánh thức các phiên khác đang chờ chỗ trong hàng đợi của phiên này (chúng sẽ thấy closed và bỏ qua)
        queueBytes.release(Integer.MAX_VALUE / 2);
        ws.close();
        server.onSessionClosed(this, reason);
    }
}
