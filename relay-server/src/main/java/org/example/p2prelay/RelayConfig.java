package org.example.p2prelay;

import java.util.Map;

/**
 * Cấu hình relay, đọc từ biến môi trường (xem deploy/relay.env.example).
 * Không có giá trị bí mật mặc định: RELAY_LINK_SECRET và mật khẩu keystore phải do người triển khai đặt.
 */
public final class RelayConfig {

    public String bindAddress = "0.0.0.0";
    public int port = 8080;

    /** Keystore PKCS12 để relay tự chạy TLS (wss://). Bỏ trống nếu TLS do reverse proxy (Caddy/Nginx) đảm nhận. */
    public String tlsKeystorePath;
    public String tlsKeystorePassword;

    /** Khoá HMAC ký link token, để liên kết giữa 2 peer còn hiệu lực sau khi relay khởi động lại */
    public String linkSecret;
    public long linkTtlSeconds = 7L * 24 * 3600;

    public long inviteTtlSeconds = 600;
    public int inviteMaxUses = 1;

    public int heartbeatSeconds = 15;
    public int idleTimeoutSeconds = 45;
    public int handshakeTimeoutSeconds = 10;
    public int openTimeoutSeconds = 15;
    /** Nếu hàng đợi gửi tới 1 peer đầy quá lâu (peer không đọc) thì đóng phiên peer đó */
    public int stallTimeoutSeconds = 20;

    public int maxSessions = 500;
    public int maxConnectionsPerIp = 20;
    public int maxStreamsPerSession = 64;
    /** Byte tối đa đang chờ gửi tới 1 peer: chặn trên bộ nhớ relay dùng cho mỗi phiên */
    public int sessionQueueBytes = 4 * 1024 * 1024;

    /** Số lần nhập sai mã mời tối đa trong cửa sổ thời gian, theo phiên và theo IP */
    public int resolveMaxFailsPerSession = 5;
    public int resolveSessionWindowSeconds = 60;
    public int resolveMaxFailsPerIp = 20;
    public int resolveIpWindowSeconds = 600;

    /** Chỉ bật khi relay nằm sau reverse proxy tin cậy: lấy IP thật từ X-Forwarded-For */
    public boolean trustProxyHeaders = false;

    public static RelayConfig fromEnvironment() {
        return fromMap(System.getenv());
    }

    static RelayConfig fromMap(Map<String, String> env) {
        RelayConfig c = new RelayConfig();
        c.bindAddress = str(env, "RELAY_BIND", c.bindAddress);
        c.port = num(env, "RELAY_PORT", c.port);
        c.tlsKeystorePath = str(env, "RELAY_TLS_KEYSTORE", null);
        c.tlsKeystorePassword = str(env, "RELAY_TLS_KEYSTORE_PASSWORD", null);
        c.linkSecret = str(env, "RELAY_LINK_SECRET", null);
        c.linkTtlSeconds = num(env, "RELAY_LINK_TTL_SECONDS", (int) c.linkTtlSeconds);
        c.inviteTtlSeconds = num(env, "RELAY_INVITE_TTL_SECONDS", (int) c.inviteTtlSeconds);
        c.inviteMaxUses = num(env, "RELAY_INVITE_MAX_USES", c.inviteMaxUses);
        c.heartbeatSeconds = num(env, "RELAY_HEARTBEAT_SECONDS", c.heartbeatSeconds);
        c.idleTimeoutSeconds = num(env, "RELAY_IDLE_TIMEOUT_SECONDS", c.idleTimeoutSeconds);
        c.stallTimeoutSeconds = num(env, "RELAY_STALL_TIMEOUT_SECONDS", c.stallTimeoutSeconds);
        c.maxSessions = num(env, "RELAY_MAX_SESSIONS", c.maxSessions);
        c.maxConnectionsPerIp = num(env, "RELAY_MAX_CONN_PER_IP", c.maxConnectionsPerIp);
        c.maxStreamsPerSession = num(env, "RELAY_MAX_STREAMS_PER_SESSION", c.maxStreamsPerSession);
        c.sessionQueueBytes = num(env, "RELAY_SESSION_QUEUE_BYTES", c.sessionQueueBytes);
        c.resolveMaxFailsPerSession = num(env, "RELAY_RESOLVE_MAX_FAILS", c.resolveMaxFailsPerSession);
        c.resolveMaxFailsPerIp = num(env, "RELAY_RESOLVE_MAX_FAILS_PER_IP", c.resolveMaxFailsPerIp);
        c.trustProxyHeaders = Boolean.parseBoolean(str(env, "RELAY_TRUST_PROXY", "false"));
        return c;
    }

    void validate() {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("RELAY_PORT không hợp lệ");
        if (heartbeatSeconds <= 0 || idleTimeoutSeconds <= heartbeatSeconds) {
            throw new IllegalArgumentException("Cần RELAY_IDLE_TIMEOUT_SECONDS > RELAY_HEARTBEAT_SECONDS > 0");
        }
        if (sessionQueueBytes < 2 * org.example.p2prelay.protocol.RelayProtocol.MAX_BINARY_MESSAGE) {
            throw new IllegalArgumentException("RELAY_SESSION_QUEUE_BYTES quá nhỏ");
        }
        if (tlsKeystorePath != null && tlsKeystorePassword == null) {
            throw new IllegalArgumentException("Thiếu RELAY_TLS_KEYSTORE_PASSWORD");
        }
        if (linkSecret != null && linkSecret.length() < 32) {
            throw new IllegalArgumentException("RELAY_LINK_SECRET phải dài ít nhất 32 ký tự");
        }
    }

    private static String str(Map<String, String> env, String key, String def) {
        String v = env.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int num(Map<String, String> env, String key, int def) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " phải là số nguyên");
        }
    }
}
