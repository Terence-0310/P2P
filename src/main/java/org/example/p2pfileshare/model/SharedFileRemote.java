package org.example.p2pfileshare.model;

public class SharedFileRemote {

    private final String fileName;
    private final String relativePath;
    private final long size;

    private final String subject;
    private final String tags;

    // ===== CHỦ FILE (owner) =====
    private final String ownerPeerId;
    private final String ownerName;

    // ===== THÔNG TIN KẾT NỐI =====
    private final String peerIp;      // IP để tải
    private final int peerPort;       // FILE_PORT để tải

    public SharedFileRemote(
            String fileName,
            String relativePath,
            long size,
            String subject,
            String tags,
            String ownerPeerId,
            String ownerName,
            String peerIp,
            int peerPort
    ) {
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
