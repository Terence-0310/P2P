package org.example.p2prelay;

import org.example.p2prelay.protocol.RelayProtocol;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Mã mời, giới hạn số lần thử, link token, handshake WebSocket */
class RelayRegistriesTest {

    private static final String A = "0123456789abcdef0123456789abcdef";
    private static final String B = "fedcba9876543210fedcba9876543210";
    private static final String C = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void inviteExpiresAfterTtlAndIsSingleUse() {
        AtomicLong now = new AtomicLong(1_000_000);
        InviteRegistry reg = new InviteRegistry(600_000, 1, now::get);
        InviteRegistry.Invite inv = reg.create(A);

        assertEquals(RelayProtocol.INVITE_LENGTH, inv.code().length());
        assertEquals(inv.code(), RelayProtocol.normalizeInviteCode(RelayProtocol.formatInviteCode(inv.code()).toLowerCase()));
        assertNotNull(reg.peek(inv.code()));

        now.addAndGet(600_001);
        assertNull(reg.peek(inv.code()), "hết hạn sau ttl");

        InviteRegistry.Invite again = reg.create(A);
        reg.consume(again);
        assertNull(reg.peek(again.code()), "chỉ dùng 1 lần");
    }

    @Test
    void newInviteRevokesPreviousOneOfSameOwner() {
        InviteRegistry reg = new InviteRegistry(60_000, 3, System::currentTimeMillis);
        InviteRegistry.Invite first = reg.create(A);
        InviteRegistry.Invite second = reg.create(A);
        assertNull(reg.peek(first.code()));
        assertNotNull(reg.peek(second.code()));
        reg.consume(second);
        reg.consume(second);
        assertNotNull(reg.peek(second.code()), "còn 1 lượt");
        reg.consume(second);
        assertNull(reg.peek(second.code()));
    }

    @Test
    void inviteCodesUseUnambiguousAlphabetAndAreRandom() {
        InviteRegistry reg = new InviteRegistry(60_000, 1, System::currentTimeMillis);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = reg.create("owner" + i).code();
            assertTrue(code.chars().allMatch(ch -> RelayProtocol.INVITE_ALPHABET.indexOf(ch) >= 0), code);
            assertFalse(code.matches(".*[01OIL].*"), code);
            codes.add(code);
        }
        assertEquals(200, codes.size());
        assertNull(RelayProtocol.normalizeInviteCode("ABCD-EFG0"), "ký tự 0 không có trong bảng chữ");
        assertNull(RelayProtocol.normalizeInviteCode("ABC"));
    }

    @Test
    void rateLimiterBlocksAfterMaxFailuresInWindowThenRecovers() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter rl = new RateLimiter(3, 60_000, now::get);
        for (int i = 0; i < 3; i++) {
            assertFalse(rl.isLimited("s:x"));
            rl.record("s:x");
        }
        assertTrue(rl.isLimited("s:x"));
        assertFalse(rl.isLimited("s:y"), "khoá khác không bị ảnh hưởng");
        now.addAndGet(60_001);
        assertFalse(rl.isLimited("s:x"), "hết cửa sổ thì được thử lại");
        rl.cleanup();
    }

    @Test
    void linkTokenIsBoundToBothPeersSecretAndExpiry() {
        AtomicLong now = new AtomicLong(10_000_000);
        LinkRegistry reg = new LinkRegistry("s".repeat(40), 3_600_000, now::get);
        String token = reg.issueToken(A, B);

        assertEquals(B, reg.verifyToken(token, A));
        assertEquals(A, reg.verifyToken(token, B));
        assertNull(reg.verifyToken(token, C), "peer thứ 3 không dùng được token của người khác");

        String tampered = token.replace(A, C);
        assertNull(reg.verifyToken(tampered, C), "sửa nội dung thì sai chữ ký HMAC");

        LinkRegistry otherSecret = new LinkRegistry("x".repeat(40), 3_600_000, now::get);
        assertNull(otherSecret.verifyToken(token, A), "relay khác khoá không chấp nhận");

        now.addAndGet(3_600_001);
        assertNull(reg.verifyToken(token, A), "hết hạn");
        assertNull(reg.verifyToken("rác", A));
        assertNull(reg.verifyToken(null, A));
    }

    @Test
    void linksAreSymmetricAndRemovable() {
        LinkRegistry reg = new LinkRegistry(null, 60_000, System::currentTimeMillis);
        reg.link(A, B);
        assertTrue(reg.isLinked(A, B));
        assertTrue(reg.isLinked(B, A));
        assertFalse(reg.isLinked(A, C));
        reg.unlink(B, A);
        assertFalse(reg.isLinked(A, B));
        reg.cleanup(id -> false);
        assertTrue(reg.linkedPeers(A).isEmpty());
    }

    @Test
    void webSocketAcceptKeyMatchesRfc6455Example() {
        // RFC 6455 mục 1.3
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketConnection.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void configValidationRejectsUnsafeValues() {
        RelayConfig c = new RelayConfig();
        c.linkSecret = "ngắn";
        assertThrows(IllegalArgumentException.class, c::validate);
        RelayConfig d = new RelayConfig();
        d.idleTimeoutSeconds = d.heartbeatSeconds;
        assertThrows(IllegalArgumentException.class, d::validate);
        RelayConfig e = new RelayConfig();
        e.tlsKeystorePath = "relay.p12";
        assertThrows(IllegalArgumentException.class, e::validate, "có keystore mà thiếu mật khẩu");
        assertDoesNotThrow(new RelayConfig()::validate);
    }

    @Test
    void peerIdIsDerivedFromPublicKeyNotChosenByClient() {
        String id = RelayProtocol.peerIdFromPublicKey(new byte[]{1, 2, 3});
        assertTrue(RelayProtocol.isValidPeerId(id));
        assertEquals(id, RelayProtocol.peerIdFromPublicKey(new byte[]{1, 2, 3}));
        assertNotEquals(id, RelayProtocol.peerIdFromPublicKey(new byte[]{1, 2, 4}));
        assertEquals("Máy A + B", RelayProtocol.decodeText(RelayProtocol.encodeText("Máy A + B")));
        assertFalse(RelayProtocol.encodeText("có dấu cách").contains(" "));
    }
}
