package org.example.p2pfileshare.model;

public class SharedFile {

    private final String fileName;        // Tên file
    private final String relativePath;    // Đường dẫn tương đối trong thư mục chia sẻ
    private final long size;              // Dung lượng file
    private final String subject;         // Chủ đề/môn học (optional)
    private final String tags;            // Tags để search (optional)
    private final String ownerPeerId;     // Peer ID của chủ file
    private final String ownerName;       // Tên peer (hiển thị)
    private final String peerIp;          // IP peer
    private final int peerPort;           // FILE_PORT để tải file

    public SharedFile(String fileName,
                        String relativePath,
                        long size,
                        String subject,
                        String tags,
                        String ownerPeerId,
                        String ownerName,
                        String peerIp,
                        int peerPort) {

        this.fileName = fileName;
        this.relativePath = relativePath;
        this.size = size;

        this.subject = subject;
        this.tags = tags;

        this.ownerPeerId = ownerPeerId;
        this.ownerName = ownerName;
        this.peerIp = peerIp;
        this.peerPort = peerPort;
    }

    // ===== GETTERS =====
    public String getFileName()      { return fileName; }
    public String getRelativePath()  { return relativePath; }
    public long getSize()            { return size; }
    public String getSubject()       { return subject; }
    public String getTags()          { return tags; }
    public String getOwnerPeerId()   { return ownerPeerId; }
    public String getOwnerName()     { return ownerName; }
    public String getPeerIp()        { return peerIp; }
    public int getPeerPort()         { return peerPort; }
}
