package org.example.p2pfileshare.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

public class DownloadProgress {
    private final String fileName;
    private final long fileSize;
    private final int chunkSize;
    private final int totalChunks;
    private final String fileSha256;
    private final BitSet completedChunks;

    public DownloadProgress(String fileName, long fileSize, int chunkSize,
                            int totalChunks, String fileSha256) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.fileSha256 = fileSha256;
        this.completedChunks = new BitSet(totalChunks);
    }

    public void markChunkComplete(int chunkIndex) {
        completedChunks.set(chunkIndex);
    }

    public boolean isChunkComplete(int chunkIndex) {
        return completedChunks.get(chunkIndex);
    }

    public boolean isComplete() {
        return completedChunks.cardinality() == totalChunks;
    }

    public double getProgressPercent() {
        return (completedChunks.cardinality() * 100.0) / totalChunks;
    }

    public int getCompletedChunks() {
        return completedChunks.cardinality();
    }

    public BitSet getCompletedChunksBitSet() {
        return completedChunks;
    }

    // --- Persist bitmap (resume thật sự) ---

    public void loadBitmap(Path bitmapFile) throws IOException {
        if (!Files.exists(bitmapFile)) return;
        byte[] data = Files.readAllBytes(bitmapFile);
        BitSet loaded = BitSet.valueOf(data);

        // copy loaded -> completedChunks (giữ totalChunks)
        for (int i = loaded.nextSetBit(0); i >= 0; i = loaded.nextSetBit(i + 1)) {
            if (i < totalChunks) completedChunks.set(i);
        }
    }

    public void saveBitmap(Path bitmapFile) throws IOException {
        byte[] data = completedChunks.toByteArray();
        Files.write(bitmapFile, data);
    }

    public void clearAll() {
        completedChunks.clear();
    }

    // Getters
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public int getChunkSize() { return chunkSize; }
    public int getTotalChunks() { return totalChunks; }
    public String getFileSha256() { return fileSha256; }
}
