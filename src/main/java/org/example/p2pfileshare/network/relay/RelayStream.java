package org.example.p2pfileshare.network.relay;

import org.example.p2pfileshare.network.transport.Channel;
import org.example.p2pfileshare.network.transport.StreamConnection;
import org.example.p2prelay.protocol.RelayProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Luồng ảo qua relay, dùng như 1 socket TCP (StreamConnection).
 *
 * Flow control kiểu credit (giống cửa sổ TCP / HTTP/2): mỗi bên chỉ được gửi tối đa "window" byte chưa được
 * bên kia đọc. Bên nhận đọc xong thì gửi CREDIT để cấp thêm. Nhờ vậy bộ đệm nhận của mỗi luồng không bao giờ
 * vượt quá window, và 1 luồng đọc chậm (vd. đang tạm dừng tải) không làm nghẽn các luồng khác trên cùng WebSocket.
 */
public final class RelayStream implements StreamConnection {

    private final RelayClient.Connection connection;
    private final int sid;
    private final String remotePeerId;
    private final Channel channel;
    private final int window;
    private final long writeTimeoutMs;

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> inbound = new ArrayDeque<>();
    private int headOffset;
    private int buffered;
    private int consumedSinceCredit;
    private long sendCredit;
    private boolean remoteClosed;
    private boolean localClosed;
    private String resetReason;
    private volatile int readTimeoutMs;

    private final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return doRead(b, off, len);
        }

        @Override
        public int available() {
            synchronized (lock) {
                return buffered;
            }
        }

        @Override
        public void close() {
            RelayStream.this.close();
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            doWrite(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            doWrite(b, off, len);
        }

        @Override
        public void close() {
            RelayStream.this.close();
        }
    };

    RelayStream(RelayClient.Connection connection, int sid, String remotePeerId, Channel channel, int window,
                long writeTimeoutMs) {
        this.connection = connection;
        this.sid = sid;
        this.remotePeerId = remotePeerId;
        this.channel = channel;
        this.window = window;
        this.sendCredit = window;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    public String remotePeerId() {
        return remotePeerId;
    }

    public Channel channel() {
        return channel;
    }

    int sid() {
        return sid;
    }

    RelayClient.Connection connection() {
        return connection;
    }

    // ---------------- sự kiện từ RelayClient (luồng nhận WebSocket, không được chặn) ----------------

    void onData(byte[] data, int off, int len) {
        synchronized (lock) {
            if (localClosed || resetReason != null || len == 0) return;
            if (buffered + len > window) {
                // bên kia gửi vượt credit đã cấp: vi phạm giao thức, huỷ luồng để không phải đệm vô hạn
                resetReason = "Peer gửi vượt cửa sổ luồng";
                inbound.clear();
                buffered = 0;
                lock.notifyAll();
                connection.sendText(RelayProtocol.join(RelayProtocol.RESET, sid, RelayProtocol.E_LIMIT));
                return;
            }
            inbound.add(Arrays.copyOfRange(data, off, off + len));
            buffered += len;
            lock.notifyAll();
        }
    }

    void onCredit(int n) {
        synchronized (lock) {
            sendCredit += n;
            lock.notifyAll();
        }
    }

    void onRemoteClose() {
        synchronized (lock) {
            remoteClosed = true;
            lock.notifyAll();
        }
    }

    void onReset(String reason) {
        synchronized (lock) {
            if (resetReason == null) resetReason = reason;
            lock.notifyAll();
        }
    }

    // ---------------- đọc ----------------

    private int doRead(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        int n = 0;
        int credit = 0;
        synchronized (lock) {
            int timeout = readTimeoutMs;
            long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0;
            while (inbound.isEmpty()) {
                if (resetReason != null) throw new SocketException(resetReason);
                if (localClosed) throw new SocketException("Luồng đã đóng");
                if (remoteClosed) return -1;
                try {
                    if (deadline == 0) {
                        lock.wait();
                    } else {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) throw new SocketTimeoutException("Read timed out (relay)");
                        lock.wait(left);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            while (n < len && !inbound.isEmpty()) {
                byte[] head = inbound.peekFirst();
                int take = Math.min(len - n, head.length - headOffset);
                System.arraycopy(head, headOffset, b, off + n, take);
                n += take;
                headOffset += take;
                if (headOffset == head.length) {
                    inbound.pollFirst();
                    headOffset = 0;
                }
            }
            buffered -= n;
            consumedSinceCredit += n;
            // cấp lại credit theo lô để không gửi quá nhiều message nhỏ
            if (consumedSinceCredit >= window / 4 || (inbound.isEmpty() && consumedSinceCredit >= RelayProtocol.MAX_DATA_PAYLOAD)) {
                credit = consumedSinceCredit;
                consumedSinceCredit = 0;
            }
            if (remoteClosed) credit = 0;
        }
        if (credit > 0) connection.sendText(RelayProtocol.join(RelayProtocol.CREDIT, sid, credit));
        return n;
    }

    // ---------------- ghi ----------------

    private void doWrite(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n;
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + writeTimeoutMs;
                while (true) {
                    if (resetReason != null) throw new SocketException(resetReason);
                    if (localClosed) throw new SocketException("Luồng đã đóng");
                    if (remoteClosed) throw new SocketException("Peer đã đóng luồng");
                    if (sendCredit > 0) break;
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new SocketTimeoutException("Peer không nhận dữ liệu (hết credit)");
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                }
                n = (int) Math.min(Math.min(len, sendCredit), RelayProtocol.MAX_DATA_PAYLOAD);
                sendCredit -= n;
            }
            try {
                // chờ WebSocket nhận frame: ghi nhanh hơn mạng thì luồng ghi bị chặn ở đây (backpressure)
                connection.sendBinary(RelayProtocol.dataFrame(sid, b, off, n)).get(writeTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw new SocketException("Mất kết nối relay");
            } catch (TimeoutException e) {
                throw new SocketTimeoutException("Relay không nhận dữ liệu");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            off += n;
            len -= n;
        }
    }

    // ---------------- StreamConnection ----------------

    @Override
    public InputStream getInputStream() {
        return in;
    }

    @Override
    public OutputStream getOutputStream() {
        return out;
    }

    @Override
    public void setReadTimeout(int ms) {
        readTimeoutMs = Math.max(0, ms);
    }

    @Override
    public String describeRemote() {
        return "relay:" + RelayProtocol.shortId(remotePeerId);
    }

    @Override
    public void close() {
        boolean notify;
        synchronized (lock) {
            if (localClosed) return;
            localClosed = true;
            notify = resetReason == null && !remoteClosed;
            inbound.clear();
            buffered = 0;
            lock.notifyAll();
        }
        connection.unregister(this);
        if (notify) connection.sendText(RelayProtocol.join(RelayProtocol.CLOSE, sid));
    }
}
