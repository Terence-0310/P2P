package org.example.p2pfileshare.util;

import org.example.p2pfileshare.network.relay.RelaySettings;

/**
 * Thông số mạng cấu hình được, đọc theo thứ tự: system property (-Dp2p...) -> biến môi trường (P2P_...) -> mặc định.
 * Ví dụ: set P2P_PEER_TIMEOUT_SECONDS=30 trước khi chạy run.bat.
 */
public final class NetworkSettings {

    public static final String KEY_RELAY_URL = "relay.url";
    /** true nếu lần trước người dùng đang bật relay -> mở app thì tự kết nối lại */
    public static final String KEY_RELAY_AUTOCONNECT = "relay.autoconnect";
    /** chế độ hiển thị lần trước: "lan" hoặc "internet" */
    public static final String KEY_CONNECTION_MODE = "connection.mode";

    private NetworkSettings() {}

    /** Chu kỳ gửi PING tới các peer đang kết nối */
    public static int peerHeartbeatSeconds() {
        return intSetting("p2p.peer.heartbeatSeconds", "P2P_PEER_HEARTBEAT_SECONDS", 5, 1);
    }

    /** Không liên lạc được peer trong khoảng này -> hiển thị Offline */
    public static int peerTimeoutSeconds() {
        return intSetting("p2p.peer.timeoutSeconds", "P2P_PEER_TIMEOUT_SECONDS", 15, 2);
    }

    public static int relayHeartbeatSeconds() {
        return intSetting("p2p.relay.heartbeatSeconds", "P2P_RELAY_HEARTBEAT_SECONDS", 10, 1);
    }

    /** Không nhận được gì từ relay trong khoảng này -> Reconnecting */
    public static int relayTimeoutSeconds() {
        return intSetting("p2p.relay.timeoutSeconds", "P2P_RELAY_TIMEOUT_SECONDS", 30, 3);
    }

    public static int relayMaxReconnectAttempts() {
        return intSetting("p2p.relay.maxReconnectAttempts", "P2P_RELAY_MAX_RECONNECT_ATTEMPTS", 30, 0);
    }

    /** Tải xuống mất kết nối thì chờ kết nối lại tối đa bao lâu trước khi báo thất bại */
    public static int downloadRetryMaxWaitSeconds() {
        return intSetting("p2p.download.retryMaxWaitSeconds", "P2P_DOWNLOAD_RETRY_MAX_WAIT_SECONDS", 600, 5);
    }

    /** URL relay: -Dp2p.relay.url -> lần dùng gần nhất (lưu trong máy) -> biến môi trường P2P_RELAY_URL -> rỗng */
    public static String defaultRelayUrl() {
        String v = System.getProperty("p2p.relay.url");
        if (v == null || v.isBlank()) v = AppConfig.load(KEY_RELAY_URL);
        if (v == null || v.isBlank()) v = System.getenv("P2P_RELAY_URL");
        return v == null ? "" : v.trim();
    }

    public static boolean relayAutoConnect() {
        String v = System.getProperty("p2p.relay.autoconnect");
        if (v == null) v = AppConfig.load(KEY_RELAY_AUTOCONNECT);
        return Boolean.parseBoolean(v);
    }

    /** true nếu mở app ở chế độ Internet (-Dp2p.mode=internet hoặc lần trước đang ở Internet) */
    public static boolean startInInternetMode() {
        String v = System.getProperty("p2p.mode");
        if (v == null) v = AppConfig.load(KEY_CONNECTION_MODE);
        return "internet".equalsIgnoreCase(v);
    }

    /** Cấu hình kết nối relay theo các thông số trên. Ném IllegalArgumentException nếu URL không hợp lệ. */
    public static RelaySettings relaySettings(String url) {
        RelaySettings s = RelaySettings.forUrl(url);
        s.heartbeatSeconds = relayHeartbeatSeconds();
        s.timeoutSeconds = Math.max(relayTimeoutSeconds(), s.heartbeatSeconds * 2);
        s.maxReconnectAttempts = relayMaxReconnectAttempts();
        return s;
    }

    private static int intSetting(String prop, String env, int def, int min) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        if (v == null || v.isBlank()) return def;
        try {
            return Math.max(min, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            System.err.println("[NetworkSettings] " + env + " không phải số, dùng mặc định " + def);
            return def;
        }
    }
}
