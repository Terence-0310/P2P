package org.example.p2pfileshare.network.discovery;

import org.example.p2pfileshare.model.PeerInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Đọc/ghi gói phản hồi discovery: dữ liệu từ mạng không tin cậy. */
class PeerDiscoveryTest {

    private static final String IP = "192.168.1.20";

    @Test
    void parsesValidResponse() {
        PeerInfo peer = PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|id-1|Máy Tài|5000|6000", IP);

        assertNotNull(peer);
        assertEquals("id-1", peer.getPeerId());
        assertEquals("Máy Tài", peer.getDisplayName());
        assertEquals(IP, peer.getIp());
        assertEquals(5000, peer.getFileServerPort());
        assertEquals(6000, peer.getControlPort());
    }

    @Test
    void parsesLegacyFourFieldResponse() {
        PeerInfo peer = PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|old-peer|5000|6000", IP);

        assertNotNull(peer);
        assertEquals("old-peer", peer.getPeerId());
        assertEquals(6000, peer.getControlPort());
    }

    @Test
    void ignoresNonNumericPort() {
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|id-1|name|abc|6000", IP));
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|old|5000|", IP));
    }

    @Test
    void ignoresPortOutOfRange() {
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|id-1|name|0|6000", IP));
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|id-1|name|5000|70000", IP));
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|id-1|name|-1|6000", IP));
    }

    @Test
    void ignoresWrongFieldCountBlankIdAndOtherMessages() {
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE|a|b|c|5000|6000", IP));
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSE| |name|5000|6000", IP));
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_REQUEST", IP));
        assertNull(PeerDiscovery.parseResponse("P2P_DISCOVER_RESPONSEX|id|name|5000|6000", IP));
        assertNull(PeerDiscovery.parseResponse(null, IP));
    }

    @Test
    void displayNameWithSeparatorDoesNotBreakResponse() {
        String msg = PeerDiscovery.buildResponse("id-1", "Nhóm|12", 5000, 6000);
        PeerInfo peer = PeerDiscovery.parseResponse(msg, IP);

        assertNotNull(peer);
        assertEquals("id-1", peer.getPeerId());
        assertEquals("Nhóm/12", peer.getDisplayName());
        assertEquals(5000, peer.getFileServerPort());
        assertEquals(6000, peer.getControlPort());
    }

    @Test
    void nullOrMultilineDisplayNameIsSanitized() {
        assertEquals("", PeerDiscovery.parseResponse(
                PeerDiscovery.buildResponse("id-1", null, 5000, 6000), IP).getDisplayName());
        assertEquals("Máy A", PeerDiscovery.parseResponse(
                PeerDiscovery.buildResponse("id-1", "Máy\nA", 5000, 6000), IP).getDisplayName());
    }
}
