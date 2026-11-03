package org.example.p2prelay;

import org.example.p2prelay.protocol.RelayProtocol;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Mã mời ngắn hạn: mỗi peer có tối đa 1 mã còn hiệu lực, mã hết hạn sau ttl và chỉ dùng được maxUses lần.
 * Chỉ lưu trong RAM, relay khởi động lại thì mã cũ mất hiệu lực.
 */
final class InviteRegistry {

    record Invite(String code, String ownerPeerId, long expiresAtMs, int usesLeft) {}

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Invite> byCode = new ConcurrentHashMap<>();
    private final Map<String, String> codeByOwner = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxUses;
    private final LongSupplier clock;

    InviteRegistry(long ttlMs, int maxUses, LongSupplier clock) {
        this.ttlMs = ttlMs;
        this.maxUses = Math.max(1, maxUses);
        this.clock = clock;
    }

    /** Tạo mã mới cho owner, mã cũ của owner (nếu có) bị huỷ */
    synchronized Invite create(String ownerPeerId) {
        revokeOwner(ownerPeerId);
        String code;
        do {
            StringBuilder sb = new StringBuilder(RelayProtocol.INVITE_LENGTH);
            for (int i = 0; i < RelayProtocol.INVITE_LENGTH; i++) {
                sb.append(RelayProtocol.INVITE_ALPHABET.charAt(random.nextInt(RelayProtocol.INVITE_ALPHABET.length())));
            }
            code = sb.toString();
        } while (byCode.containsKey(code));
        Invite inv = new Invite(code, ownerPeerId, clock.getAsLong() + ttlMs, maxUses);
        byCode.put(code, inv);
        codeByOwner.put(ownerPeerId, code);
        return inv;
    }

    /** Mã còn hiệu lực (chưa hết hạn, còn lượt) hoặc null. Không trừ lượt. */
    synchronized Invite peek(String code) {
        Invite inv = code == null ? null : byCode.get(code);
        if (inv == null) return null;
        if (inv.expiresAtMs() <= clock.getAsLong()) {
            remove(inv);
            return null;
        }
        return inv;
    }

    /** Trừ 1 lượt dùng, hết lượt thì xoá mã */
    synchronized void consume(Invite inv) {
        Invite current = byCode.get(inv.code());
        if (current == null) return;
        if (current.usesLeft() <= 1) {
            remove(current);
        } else {
            byCode.put(current.code(), new Invite(current.code(), current.ownerPeerId(), current.expiresAtMs(),
                    current.usesLeft() - 1));
        }
    }

    synchronized void revokeOwner(String ownerPeerId) {
        String old = codeByOwner.remove(ownerPeerId);
        if (old != null) byCode.remove(old);
    }

    synchronized void cleanupExpired() {
        long now = clock.getAsLong();
        byCode.values().removeIf(inv -> {
            if (inv.expiresAtMs() > now) return false;
            codeByOwner.remove(inv.ownerPeerId(), inv.code());
            return true;
        });
    }

    int size() {
        return byCode.size();
    }

    private void remove(Invite inv) {
        byCode.remove(inv.code());
        codeByOwner.remove(inv.ownerPeerId(), inv.code());
    }
}
