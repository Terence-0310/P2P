package org.example.p2pfileshare.internet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TCP proxy giữa 1 máy và relay để giả lập mạng thật trong test:
 * - giới hạn băng thông (để kịp "rút mạng" khi đang tải),
 * - freeze(): ngừng chuyển dữ liệu nhưng KHÔNG đóng socket, giống tắt Wi-Fi đột ngột (không có gói FIN),
 *   2 đầu chỉ phát hiện được nhờ heartbeat / timeout,
 * - restore(): bỏ các kết nối cũ (chết hẳn) và cho phép kết nối mới.
 */
final class FlakyNetworkProxy implements AutoCloseable {

    private final ServerSocket server;
    private final int targetPort;
    private final long bytesPerSecond;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean frozen;
    private volatile boolean closed;

    FlakyNetworkProxy(int targetPort, long bytesPerSecond) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.targetPort = targetPort;
        this.bytesPerSecond = bytesPerSecond;
        Thread t = new Thread(this::acceptLoop, "proxy-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Mất mạng đột ngột: dữ liệu ngừng chảy, socket vẫn "mở" */
    void freeze() {
        frozen = true;
    }

    /** Có mạng trở lại: kết nối cũ đã chết, máy phải tự kết nối lại */
    void restore() {
        for (Socket s : sockets) closeQuietly(s);
        sockets.clear();
        frozen = false;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                if (frozen) {
                    // đang mất mạng: kết nối mới cũng không đi được, giữ treo
                    sockets.add(client);
                    continue;
                }
                Socket upstream = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                sockets.add(client);
                sockets.add(upstream);
                pump(client, upstream);
                pump(upstream, client);
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16 * 1024];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    while (frozen && !from.isClosed()) Thread.sleep(20);
                    if (from.isClosed()) return;
                    out.write(buf, 0, n);
                    out.flush();
                    if (bytesPerSecond > 0) Thread.sleep(Math.max(1, n * 1000L / bytesPerSecond));
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                if (!frozen) {
                    closeQuietly(from);
                    closeQuietly(to);
                }
            }
        }, "proxy-pump");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        restore();
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }
}
