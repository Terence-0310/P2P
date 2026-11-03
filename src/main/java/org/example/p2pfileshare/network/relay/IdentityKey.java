package org.example.p2pfileshare.network.relay;

import org.example.p2prelay.protocol.RelayProtocol;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/**
 * Cặp khoá Ed25519 của máy, sinh mới mỗi lần mở ứng dụng (giống peerId UUID trước đây).
 * peerId = SHA-256(khoá công khai) nên relay kiểm tra được: muốn dùng id này phải ký được bằng khoá bí mật.
 * Khoá bí mật không bao giờ rời khỏi máy (chỉ gửi chữ ký).
 */
public final class IdentityKey {

    private final KeyPair keyPair;
    private final String peerId;

    private IdentityKey(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.peerId = RelayProtocol.peerIdFromPublicKey(keyPair.getPublic().getEncoded());
    }

    public static IdentityKey generate() {
        try {
            return new IdentityKey(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM không hỗ trợ Ed25519 (cần Java 15+)", e);
        }
    }

    public String peerId() {
        return peerId;
    }

    public String publicKeyBase64() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** Ký nonce relay gửi trong CHALLENGE */
    public String signChallenge(String nonce) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(keyPair.getPrivate());
            s.update(RelayProtocol.authPayload(nonce));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
