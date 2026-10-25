package org.example.p2pfileshare.model;

public class SearchResult {
    private final String fileName;
    private final String relativePath; // đường dẫn bên peer sở hữu, dùng khi tải
    private final long size;
    private final String subject; // Môn học (nếu có logic phân loại)
    private final PeerInfo owner; // Người sở hữu file

    public SearchResult(String fileName, String relativePath, long size, String subject, PeerInfo owner) {
        this.fileName = fileName;
        this.relativePath = relativePath;
        this.size = size;
        this.subject = subject;
        this.owner = owner;
    }

    public String getFileName() { return fileName; }
    public String getRelativePath() { return relativePath; }
    public long getSize() { return size; }
    public String getSubject() { return subject; }
    public PeerInfo getOwner() { return owner; }

    // Helper để hiển thị tên chủ sở hữu trên bảng
    public String getOwnerName() {
        return owner != null ? owner.getName() : "Unknown";
    }
}
