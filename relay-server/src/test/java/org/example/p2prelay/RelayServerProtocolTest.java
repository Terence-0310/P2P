package org.example.p2prelay;

import org.example.p2prelay.protocol.RelayProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm tra relay ở mức giao thức bằng WebSocket client chuẩn của JDK (không dùng code client của ứng dụng):
 * xác thực, chặn lệnh trước khi xác thực, chặn mở luồng khi chưa liên kết, chuyển tiếp dữ liệu nhị phân.
 */
@Timeout(30)
class RelayServerProtocolTest {

    private RelayServer relay;
    private URI uri;

    @BeforeEach
    void start() throws Exception {
        RelayConfig c = new RelayConfig();
        c.bindAddress = "127.0.0.1";
        c.port = 0;
        c.heartbeatSeconds = 1;
        c.idleTimeoutSeconds = 3;
        c.handshakeTimeoutSeconds = 3;
        relay = new RelayServer(c);
        relay.start();
        uri = URI.create("ws://127.0.0.1:" + relay.getPort() + RelayProtocol.WS_PATH);
    }

    @AfterEach
    void stop() {
        relay.close();
    }

    /** Client WebSocket tối giản, nhận message vào hàng đợi */
    static final class RawClient implements WebSocket.Listener {
        final LinkedBlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private final java.io.ByteArrayOutputStream bin = new java.io.ByteArrayOutputStream();
        WebSocket ws;

        static RawClient connect(URI uri) throws Exception {
            RawClient c = new RawClient();
            c.ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, c).get(5, TimeUnit.SECONDS);
            return c;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                inbox.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            bin.write(b, 0, b.length);
            if (last) {
                inbox.add(bin.toByteArray());
                bin.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(-1);
        }

        void send(String msg) throws Exception {
            ws.sendText(msg, true).get(5, TimeUnit.SECONDS);
        }

        /** Message text tiếp theo (bỏ qua PRESENCE nếu skipPresence) */
        String nextText() throws Exception {
            while (true) {
                Object o = inbox.poll(5, TimeUnit.SECONDS);
                assertNotNull(o, "không nhận được message");
                if (o instanceof String s && !s.startsWith(RelayProtocol.PRESENCE)) return s;
            }
        }

        byte[] nextBinary() throws Exception {
            while (true) {
                Object o = inbox.poll(5, TimeUnit.SECONDS);
                assertNotNull(o, "không nhận được dữ liệu");
                if (o instanceof byte[] b) return b;
            }
        }

        /** Đăng nhập bằng khoá mới, trả về peerId relay cấp */
        String login(String name) throws Exception {
            String[] ch = RelayProtocol.split(nextText());
            assertEquals(RelayProtocol.CHALLENGE, ch[0]);
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(kp.getPrivate());
            s.update(RelayProtocol.authPayload(ch[1]));
            send(RelayProtocol.join(RelayProtocol.HELLO, b64(kp.getPublic().getEncoded()), b64(s.sign()),
                    RelayProtocol.encodeText(name)));
            String[] w = RelayProtocol.split(nextText());
            assertEquals(RelayProtocol.WELCOME, w[0]);
            assertEquals(RelayProtocol.peerIdFromPublicKey(kp.getPublic().getEncoded()), w[1]);
            return w[1];
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        RawClient c = RawClient.connect(uri);
        String[] ch = RelayProtocol.split(c.nextText());
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(RelayProtocol.authPayload(ch[1] + "khác")); // ký sai nonce
        c.send(RelayProtocol.join(RelayProtocol.HELLO, b64(kp.getPublic().getEncoded()), b64(s.sign()), "x"));
        assertEquals("ERROR " + RelayProtocol.E_AUTH_FAILED, c.nextText());
        c.closed.get(5, TimeUnit.SECONDS);
        assertEquals(0, relay.onlineCount());
    }

    @Test
    void commandsBeforeAuthenticationAreRejected() throws Exception {
        RawClient c = RawClient.connect(uri);
        c.nextText(); // CHALLENGE
        c.send(RelayProtocol.join(RelayProtocol.OPEN, 1, "0".repeat(32), "file"));
        assertEquals("ERROR " + RelayProtocol.E_AUTH_FAILED, c.nextText());
        c.closed.get(5, TimeUnit.SECONDS);
    }

    @Test
    void unauthenticatedConnectionIsDroppedAfterHandshakeTimeout() throws Exception {
        RawClient c = RawClient.connect(uri);
        c.nextText(); // CHALLENGE, không trả lời
        c.closed.get(10, TimeUnit.SECONDS);
    }

    @Test
    void streamsOnlyBetweenLinkedPeersAndDataIsForwardedUnchanged() throws Exception {
        RawClient a = RawClient.connect(uri);
        RawClient b = RawClient.connect(uri);
        String idA = a.login("A");
        String idB = b.login("B");

        a.send(RelayProtocol.join(RelayProtocol.OPEN, 1, idB, RelayProtocol.CH_FILE));
        assertEquals("OPEN_FAILED 1 " + RelayProtocol.E_NOT_LINKED, a.nextText(), "chưa liên kết thì không mở được");

        b.send(RelayProtocol.join(RelayProtocol.CREATE_INVITE, 7));
        String code = RelayProtocol.split(b.nextText())[2];
        a.send(RelayProtocol.join(RelayProtocol.RESOLVE, 2, code));
        String[] resolved = RelayProtocol.split(a.nextText());
        assertEquals(RelayProtocol.RESOLVED, resolved[0]);
        assertEquals(idB, resolved[2]);
        assertEquals("B", RelayProtocol.decodeText(resolved[3]));
        String[] linked = RelayProtocol.split(b.nextText());
        assertEquals(RelayProtocol.LINKED, linked[0]);
        assertEquals(idA, linked[1], "relay báo đúng danh tính đã xác thực của A");

        a.send(RelayProtocol.join(RelayProtocol.OPEN, 3, idB, RelayProtocol.CH_FILE));
        String[] incoming = RelayProtocol.split(b.nextText());
        assertEquals(RelayProtocol.INCOMING, incoming[0]);
        assertEquals(idA, incoming[2]);
        int sid = Integer.parseInt(incoming[1]);
        b.send(RelayProtocol.join(RelayProtocol.ACCEPT, sid));
        assertEquals("OPENED 3 " + sid, a.nextText());

        byte[] payload = "dữ liệu chunk".getBytes(StandardCharsets.UTF_8);
        a.ws.sendBinary(RelayProtocol.dataFrame(sid, payload, 0, payload.length), true).get(5, TimeUnit.SECONDS);
        byte[] got = b.nextBinary();
        assertEquals(RelayProtocol.FRAME_DATA, got[0]);
        assertEquals(sid, ByteBuffer.wrap(got, 1, 4).getInt());
        assertEquals("dữ liệu chunk", new String(got, 5, got.length - 5, StandardCharsets.UTF_8));

        // bên thứ 3 không chen được vào luồng của người khác
        RawClient c = RawClient.connect(uri);
        c.login("C");
        c.ws.sendBinary(RelayProtocol.dataFrame(sid, payload, 0, payload.length), true).get(5, TimeUnit.SECONDS);
        c.send(RelayProtocol.join(RelayProtocol.CLOSE, sid));
        a.send(RelayProtocol.PING);
        assertEquals(RelayProtocol.PONG, a.nextText());
        assertEquals(1, relay.activeStreamCount(), "C không đóng được luồng của A-B");

        a.send(RelayProtocol.join(RelayProtocol.CLOSE, sid));
        assertEquals("CLOSE " + sid, b.nextText());
        assertEquals(0, relay.activeStreamCount());

        // B mất kết nối -> A nhận PRESENCE OFFLINE
        b.ws.abort();
        Object o;
        do {
            o = a.inbox.poll(5, TimeUnit.SECONDS);
            assertNotNull(o, "A phải được báo B offline");
        } while (!(o instanceof String s && s.equals("PRESENCE " + idB + " OFFLINE")));
    }

    @Test
    void healthEndpointAndUnknownPaths() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + relay.getPort() + "/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().startsWith("ok"));
        HttpResponse<String> nf = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + relay.getPort() + "/files")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, nf.statusCode());
    }

    @Test
    void unmaskedFrameFromClientIsAProtocolError() throws Exception {
        try (Socket s = new Socket("127.0.0.1", relay.getPort())) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            String head = readHeader(in);
            assertTrue(head.startsWith("HTTP/1.1 101"), head);
            assertTrue(head.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
            // frame text không mask (client bắt buộc phải mask)
            out.write(new byte[]{(byte) 0x81, 0x02, 'h', 'i'});
            out.flush();
            s.setSoTimeout(5000);
            // đọc tới khi relay đóng kết nối
            while (in.read() >= 0) { /* bỏ qua CHALLENGE / close frame */ }
        }
    }

    private static String readHeader(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) break;
            sb.append((char) b);
        }
        return sb.toString();
    }
}
