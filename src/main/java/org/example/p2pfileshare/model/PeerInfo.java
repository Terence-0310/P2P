package org.example.p2pfileshare.model;

public class PeerInfo {

    public enum ConnectionState {
        NOT_CONNECTED,
        PENDING,
        CONNECTED,
        REJECTED
    }

    /** Đường đi tới peer: TCP trực tiếp trong LAN, hoặc luồng ảo qua Relay Server (Internet Mode) */
    public enum Route {
        LAN,
        RELAY
    }

    /** Giá trị hiển thị ở cột IP cho peer đi qua relay (không biết / không cần IP thật của peer) */
    public static final String RELAY_ADDRESS_LABEL = "Internet (relay)";

    // Unique identifier cho peer (UUID string)
    private final String peerId;

    // Tên hiển thị do người dùng đặt (có thể trùng giữa các peer)
    private String displayName;

    private final String ip;
    private final int fileServerPort;   // port dùng để tải file
    private final int controlPort;      // port dùng cho control (connect, list...)
    private String status;              // "Online", "Offline" (từ Discovery)
    private ConnectionState connectionState;
    private final Route route;

    public PeerInfo(String peerId,
                    String displayName,
                    String ip,
                    int fileServerPort,
                    int controlPort,
                    String status) {
        this(peerId, displayName, ip, fileServerPort, controlPort, status, Route.LAN);
    }

    private PeerInfo(String peerId, String displayName, String ip, int fileServerPort, int controlPort,
                     String status, Route route) {
        this.peerId = peerId;
        this.displayName = displayName;
        this.ip = ip;
        this.fileServerPort = fileServerPort;
        this.controlPort = controlPort;
        this.status = status;
        this.connectionState = ConnectionState.NOT_CONNECTED;
        this.route = route;
    }

    /** Peer ở mạng khác, liên lạc qua relay. peerId là danh tính đã được relay xác thực bằng chữ ký. */
    public static PeerInfo viaRelay(String peerId, String displayName) {
        return new PeerInfo(peerId, displayName, RELAY_ADDRESS_LABEL, 0, 0, "Online", Route.RELAY);
    }

    // Gói discovery định dạng cũ không có peerId -> dùng tên làm peerId
    public PeerInfo(String name,
                    String ip,
                    int fileServerPort,
                    int controlPort,
                    String status) {
        this.peerId = name; // fallback, nhưng discovery sẽ cung cấp correct peerId
        this.displayName = name;
        this.ip = ip;
        this.fileServerPort = fileServerPort;
        this.controlPort = controlPort;
        this.status = status;
        this.connectionState = ConnectionState.NOT_CONNECTED;
        this.route = Route.LAN;
    }

    // ===== GETTER =====
    public String getPeerId() {
        return peerId;
    }

    public Route getRoute() {
        return route;
    }

    public boolean isRelay() {
        return route == Route.RELAY;
    }

    // Giữ phương thức getName() cũ cho binding UI, nhưng thực chất trả displayName
    public String getName() {
        return displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIp() {
        return ip;
    }

    public int getFileServerPort() {
        return fileServerPort;
    }

    public int getControlPort() {
        return controlPort;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public void setConnectionState(ConnectionState connectionState) {
        this.connectionState = connectionState;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}