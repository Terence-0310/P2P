package org.example.p2prelay;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Liên kết giữa 2 peer (tạo khi 1 bên nhập đúng mã mời của bên kia).
 * Relay chỉ chuyển tiếp luồng giữa 2 peer đã liên kết, nên người không có mã mời không làm phiền được ai.
 *
 * Mỗi liên kết kèm 1 link token ký HMAC-SHA256: "v1.peerA.peerB.hếtHạn.mac". Relay khởi động lại thì
 * mất bảng liên kết trong RAM, client gửi lại token (RESTORE) để khôi phục mà không phải nhập lại mã.
 */
final class LinkRegistry {

    private final Map<String, Set<String>> links = new ConcurrentHashMap<>();
    private final byte[] secret;
    private final long ttlMs;
    private final LongSupplier clock;

    LinkRegistry(String secretOrNull, long ttlMs, LongSupplier clock) {
        if (secretOrNull != null) {
            this.secret = secretOrNull.getBytes(StandardCharsets.UTF_8);
        } else {
            // Không cấu hình: khoá ngẫu nhiên theo tiến trình -> token cũ mất hiệu lực khi relay khởi động lại
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
        }
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    void link(String a, String b) {
        links.computeIfAbsent(a, k -> ConcurrentHashMap.newKeySet()).add(b);
        links.computeIfAbsent(b, k -> ConcurrentHashMap.newKeySet()).add(a);
    }

    void unlink(String a, String b) {
        Set<String> sa = links.get(a);
        if (sa != null) sa.remove(b);
        Set<String> sb = links.get(b);
        if (sb != null) sb.remove(a);
    }

    boolean isLinked(String a, String b) {
        Set<String> s = links.get(a);
        return s != null && s.contains(b);
    }

    Set<String> linkedPeers(String peerId) {
        Set<String> s = links.get(peerId);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    /** Bỏ các peer không còn liên kết nào (giữ bảng nhỏ) */
    void cleanup(java.util.function.Predicate<String> isOnline) {
        links.entrySet().removeIf(e -> e.getValue().isEmpty() && !isOnline.test(e.getKey()));
    }

    String issueToken(String a, String b) {
        String lo = a.compareTo(b) <= 0 ? a : b;
        String hi = lo.equals(a) ? b : a;
        long exp = (clock.getAsLong() + ttlMs) / 1000;
        String body = "v1." + lo + "." + hi + "." + exp;
        return body + "." + mac(body);
    }

    /**
     * Kiểm tra token do peer "presenter" gửi lên.
     * @return peerId bên kia của liên kết, hoặc null nếu token sai / hết hạn / không thuộc presenter
     */
    String verifyToken(String token, String presenter) {
        if (token == null) return null;
        String[] p = token.split("\\.");
        if (p.length != 5 || !"v1".equals(p[0])) return null;
        String body = p[0] + "." + p[1] + "." + p[2] + "." + p[3];
        byte[] expected = mac(body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, p[4].getBytes(StandardCharsets.US_ASCII))) return null;
        long exp;
        try {
            exp = Long.parseLong(p[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (exp * 1000 <= clock.getAsLong()) return null;
        if (presenter.equals(p[1])) return p[2];
        if (presenter.equals(p[2])) return p[1];
        return null;
    }

    private String mac(String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
