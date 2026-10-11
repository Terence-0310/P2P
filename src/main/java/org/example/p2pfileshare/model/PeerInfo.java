package org.example.p2pfileshare.model;

public class PeerInfo {

    public enum ConnectionState {
        NOT_CONNECTED,
        PENDING,
        CONNECTED,
        REJECTED
    }

    // Unique identifier cho peer (UUID string)
    private final String peerId;

    // Tên hiển thị do người dùng đặt (có thể trùng giữa các peer)
    private  String displayName;

    // Deprecated/kept for backward compatibility: getName() will return displayName
    // private final String name;

    private final String ip;
    private final int fileServerPort;   // port dùng để tải file
    private final int controlPort;      // port dùng cho control (connect, list...)
    private String status;              // "Online", "Offline" (từ Discovery)
    private ConnectionState connectionState;

    public PeerInfo(String peerId,
                    String displayName,
                    String ip,
                    int fileServerPort,
                    int controlPort,
                    String status) {
        this.peerId = peerId;
        this.displayName = displayName;
        this.ip = ip;
        this.fileServerPort = fileServerPort;
        this.controlPort = controlPort;
        this.status = status;
        this.connectionState = ConnectionState.NOT_CONNECTED;
    }

    // Hỗ trợ constructor cũ (chỉ tên) — giả định tên nhập là displayName và peerId = name (không được dùng ở runtime)
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
    }

    // ===== GETTER =====
    public String getPeerId() {
        return peerId;
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