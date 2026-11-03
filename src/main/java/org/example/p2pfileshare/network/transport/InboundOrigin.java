package org.example.p2pfileshare.network.transport;

import org.example.p2pfileshare.model.PeerInfo;

/**
 * Nguồn gốc của 1 kết nối đến.
 *
 * @param remoteAddress       IP (LAN) hoặc "relay" để ghi log / dựng thông tin liên lạc
 * @param route               LAN hay RELAY
 * @param authenticatedPeerId danh tính đã được xác thực bởi tầng truyền thông (relay kiểm tra chữ ký),
 *                            null nếu tầng truyền thông không xác thực (TCP trong LAN)
 */
public record InboundOrigin(String remoteAddress, PeerInfo.Route route, String authenticatedPeerId) {

    public static InboundOrigin lan(String ip) {
        return new InboundOrigin(ip, PeerInfo.Route.LAN, null);
    }

    public static InboundOrigin relay(String authenticatedPeerId) {
        return new InboundOrigin("relay", PeerInfo.Route.RELAY, authenticatedPeerId);
    }

    /**
     * Peer tự khai báo id trong lệnh: nếu kết nối có danh tính đã xác thực thì id khai báo phải trùng,
     * nếu không ai đó có thể mạo danh peer đã được chấp nhận.
     */
    public boolean permitsClaimedId(String claimedPeerId) {
        return authenticatedPeerId == null || authenticatedPeerId.equals(claimedPeerId);
    }
}
