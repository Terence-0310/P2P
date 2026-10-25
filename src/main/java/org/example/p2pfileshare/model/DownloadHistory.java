package org.example.p2pfileshare.model;

import java.time.LocalDateTime;

public class DownloadHistory {
    private String fileName;
    private String savedPath;
    private String peerName;
    private String peerIp;
    private LocalDateTime downloadDate;

    public DownloadHistory() {
    }

    public DownloadHistory(String fileName, String savedPath, String peerName, String peerIp, LocalDateTime downloadDate) {
        this.fileName = fileName;
        this.savedPath = savedPath;
        this.peerName = peerName;
        this.peerIp = peerIp;
        this.downloadDate = downloadDate;
    }

    // Getters and Setters
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getSavedPath() { return savedPath; }
    public void setSavedPath(String savedPath) { this.savedPath = savedPath; }

    public String getPeerName() { return peerName; }
    public void setPeerName(String peerName) { this.peerName = peerName; }

    public String getPeerIp() { return peerIp; }
    public void setPeerIp(String peerIp) { this.peerIp = peerIp; }

    public LocalDateTime getDownloadDate() { return downloadDate; }
    public void setDownloadDate(LocalDateTime downloadDate) { this.downloadDate = downloadDate; }
}

