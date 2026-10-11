package org.example.p2pfileshare.model;

import java.util.List;

public class FileMetadata {
    private final String fileName;
    private final long fileSize;
    private final int chunkSize;
    private final int totalChunks;
    private final String fileSha256;
    private final List<String> chunkHashes; // SHA-256 của từng chunk

    public FileMetadata(String fileName, long fileSize, int chunkSize,
                       int totalChunks, String fileSha256, List<String> chunkHashes) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.fileSha256 = fileSha256;
        this.chunkHashes = chunkHashes;
    }

    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public int getChunkSize() { return chunkSize; }
    public int getTotalChunks() { return totalChunks; }
    public String getFileSha256() { return fileSha256; }
    public List<String> getChunkHashes() { return chunkHashes; }
}

