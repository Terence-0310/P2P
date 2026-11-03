package org.example.p2pfileshare.network.relay;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Cấu hình kết nối relay phía ứng dụng. Thời gian có thể chỉnh qua NetworkSettings
 * (system property / biến môi trường), xem docs/INTERNET_MODE.md.
 */
public final class RelaySettings {

    public URI uri;
    /** Chu kỳ gửi PING tới relay */
    public int heartbeatSeconds = 10;
    /** Không nhận được gì từ relay trong khoảng này -> coi như mất kết nối relay, chuyển sang Reconnecting */
    public int timeoutSeconds = 30;
    public int connectTimeoutSeconds = 10;
    /** Chờ relay / máy kia trả lời 1 yêu cầu (mở luồng, mã mời) */
    public int requestTimeoutSeconds = 20;
    /** Exponential backoff khi kết nối lại: 1s, 2s, 4s ... tối đa backoffMaxMs */
    public long backoffInitialMs = 1000;
    public long backoffMaxMs = 60_000;
    /** Số lần thử kết nối lại liên tiếp trước khi dừng hẳn (Offline, chờ người dùng bấm Kết nối) */
    public int maxReconnectAttempts = 30;
    /** Chờ tối đa khi ghi vào luồng mà máy kia chưa cấp thêm credit */
    public int writeTimeoutSeconds = 60;
    /** null = dùng kho chứng chỉ mặc định của Java (Let's Encrypt... đều được tin cậy) */
    public SSLContext sslContext;

    public static RelaySettings forUrl(String url) {
        RelaySettings s = new RelaySettings();
        s.uri = validateUrl(url);
        return s;
    }

    /**
     * Kiểm tra URL relay. Qua Internet bắt buộc wss:// (TLS); ws:// chỉ cho phép với relay chạy trên chính máy
     * hoặc trong mạng nội bộ (thử nghiệm).
     */
    public static URI validateUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Chưa nhập địa chỉ relay");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Địa chỉ relay không hợp lệ");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("wss") || scheme.equals("ws"))) {
            throw new IllegalArgumentException("Địa chỉ relay phải có dạng wss://tên-miền/ws");
        }
        if (scheme.equals("ws") && !isLocalHost(uri.getHost())) {
            throw new IllegalArgumentException("Kết nối relay qua Internet phải dùng wss:// (TLS), không dùng ws://");
        }
        if (uri.getPath() == null || uri.getPath().isEmpty()) {
            uri = URI.create(uri.toString() + "/ws");
        }
        return uri;
    }

    static boolean isLocalHost(String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Thời gian chờ trước lần thử kết nối lại thứ attempt (bắt đầu từ 1), có ±20% ngẫu nhiên để các máy không thử cùng lúc */
    public long backoffDelayMs(int attempt, double random01) {
        double base = backoffInitialMs * Math.pow(2, Math.max(0, attempt - 1));
        double capped = Math.min(base, backoffMaxMs);
        return (long) (capped * (0.8 + 0.4 * random01));
    }
}
