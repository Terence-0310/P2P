package org.example.p2prelay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WebSocket phía server theo RFC 6455, chỉ dùng thư viện chuẩn của Java.
 * Đọc frame trên 1 luồng (reader), ghi frame trên 1 luồng khác (writer của RelaySession).
 */
final class WebSocketConnection implements Closeable {

    static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HTTP_HEADER = 8192;

    static final int OP_CONTINUATION = 0x0;
    static final int OP_TEXT = 0x1;
    static final int OP_BINARY = 0x2;
    static final int OP_CLOSE = 0x8;
    static final int OP_PING = 0x9;
    static final int OP_PONG = 0xA;

    /** 1 message hoàn chỉnh (đã ghép các frame continuation) */
    record Message(int opcode, byte[] data) {
        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    /** Yêu cầu HTTP đầu tiên (trước khi nâng cấp lên WebSocket) */
    record HttpRequest(String method, String path, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    static final class ProtocolException extends IOException {
        final int closeCode;

        ProtocolException(int closeCode, String msg) {
            super(msg);
            this.closeCode = closeCode;
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final int maxTextMessage;
    private final int maxBinaryMessage;

    WebSocketConnection(Socket socket, int maxTextMessage, int maxBinaryMessage) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        this.maxTextMessage = maxTextMessage;
        this.maxBinaryMessage = maxBinaryMessage;
    }

    Socket socket() {
        return socket;
    }

    // ---------------- HTTP handshake ----------------

    HttpRequest readHttpRequest() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0; // đếm chuỗi \r\n\r\n
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new EOFException("Kết nối đóng trong lúc handshake");
            buf.write(b);
            if (buf.size() > MAX_HTTP_HEADER) throw new IOException("HTTP header quá lớn");
            state = switch (state) {
                case 0, 2 -> b == '\r' ? state + 1 : 0;
                case 1, 3 -> b == '\n' ? state + 1 : (b == '\r' ? 1 : 0);
                default -> 0;
            };
        }
        String[] lines = buf.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 3) throw new IOException("Request line không hợp lệ");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        String path = requestLine[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        return new HttpRequest(requestLine[0], path, headers);
    }

    static boolean isWebSocketUpgrade(HttpRequest req) {
        String upgrade = req.header("upgrade");
        String connection = req.header("connection");
        return "GET".equals(req.method())
                && upgrade != null && upgrade.equalsIgnoreCase("websocket")
                && connection != null && connection.toLowerCase(Locale.ROOT).contains("upgrade")
                && req.header("sec-websocket-key") != null;
    }

    void acceptUpgrade(HttpRequest req) throws IOException {
        if (!"13".equals(req.header("sec-websocket-version"))) {
            writeHttp(426, "Upgrade Required", "Sec-WebSocket-Version: 13\r\n", "");
            throw new IOException("Phiên bản WebSocket không hỗ trợ");
        }
        String accept = acceptKey(req.header("sec-websocket-key"));
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        synchronized (out) {
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
    }

    void writeHttp(int status, String reason, String extraHeaders, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String resp = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n"
                + extraHeaders + "\r\n";
        synchronized (out) {
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.write(b);
            out.flush();
        }
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key.trim() + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- đọc frame ----------------

    /** Đọc 1 message (text/binary đã ghép đủ, hoặc frame điều khiển ping/pong/close) */
    Message readMessage() throws IOException {
        ByteArrayOutputStream fragments = null;
        int fragmentedOpcode = -1;
        while (true) {
            int b0 = readByte();
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            if ((b0 & 0x70) != 0) throw new ProtocolException(1002, "RSV bit khác 0");
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            if (!masked) throw new ProtocolException(1002, "Frame từ client phải có mask");
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                if (len < 0) throw new ProtocolException(1002, "Độ dài âm");
            }
            boolean control = opcode >= 0x8;
            if (control && (len > 125 || !fin)) throw new ProtocolException(1002, "Frame điều khiển sai");

            int limit = opcode == OP_TEXT || fragmentedOpcode == OP_TEXT ? maxTextMessage : maxBinaryMessage;
            long already = fragments == null ? 0 : fragments.size();
            if (!control && already + len > limit) throw new ProtocolException(1009, "Message quá lớn");

            byte[] mask = readFully(4);
            byte[] payload = readFully((int) len);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            if (control) {
                if (opcode != OP_CLOSE && opcode != OP_PING && opcode != OP_PONG) {
                    throw new ProtocolException(1002, "Opcode không hỗ trợ " + opcode);
                }
                return new Message(opcode, payload);
            }
            if (opcode == OP_CONTINUATION) {
                if (fragments == null) throw new ProtocolException(1002, "Continuation không có frame đầu");
                fragments.write(payload);
                if (fin) return new Message(fragmentedOpcode, fragments.toByteArray());
                continue;
            }
            if (opcode != OP_TEXT && opcode != OP_BINARY) throw new ProtocolException(1002, "Opcode không hỗ trợ " + opcode);
            if (fragments != null) throw new ProtocolException(1002, "Frame mới khi chưa hết message cũ");
            if (fin) return new Message(opcode, payload);
            fragments = new ByteArrayOutputStream();
            fragments.write(payload);
            fragmentedOpcode = opcode;
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return buf;
    }

    // ---------------- ghi frame (server không mask) ----------------

    void writeFrame(int opcode, byte[] data) throws IOException {
        int len = data.length;
        synchronized (out) {
            out.write(0x80 | opcode);
            if (len <= 125) {
                out.write(len);
            } else if (len <= 0xFFFF) {
                out.write(126);
                out.write(len >>> 8);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
            out.write(data);
            out.flush();
        }
    }

    static byte[] closePayload(int code, String reason) {
        byte[] r = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(r.length, 120);
        byte[] p = new byte[2 + n];
        p[0] = (byte) (code >>> 8);
        p[1] = (byte) code;
        System.arraycopy(r, 0, p, 2, n);
        return p;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
