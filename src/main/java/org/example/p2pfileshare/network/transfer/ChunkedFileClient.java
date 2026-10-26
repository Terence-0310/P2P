package org.example.p2pfileshare.network.transfer;

import org.example.p2pfileshare.model.DownloadProgress;
import org.example.p2pfileshare.model.FileMetadata;
import org.example.p2pfileshare.network.protocol.FileTransferProtocol;
import org.example.p2pfileshare.util.FileHashUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ChunkedFileClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 8000;
    private static final int MAX_RETRIES = 3;
    // Số kết nối TCP tải song song cho 1 file (mặc định và giới hạn trên)
    public static final int DEFAULT_PARALLEL_CONNECTIONS = 4;
    public static final int MAX_PARALLEL_CONNECTIONS = 16;

    /**
     * Bước 1: Request metadata từ server (binary protocol)
     *
     * Expected server response:
     *   - type = FILE_META_RESPONSE
     *   - name (UTF)
     *   - fileSize (long)
     *   - chunkSize (int)
     *   - totalChunks (int)
     *   - fileSha256 (UTF)
     *   - chunkHash[i] (UTF) * totalChunks
     *
     * Or error:
     *   - type = ERROR
     *   - reason (UTF)
     */
    public static FileMetadata requestMetadata(String host, int port, String myPeerId, String fileName) throws IOException {
        try (Socket socket = openSocket(host, port)) {
            // Server có thể mất thời gian băm file lớn ở lần đầu
            socket.setSoTimeout(0);

            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

                // Send request
                out.writeUTF(FileTransferProtocol.buildMetaRequest(myPeerId, fileName));
                // đảm bảo gửi ngay bây giờ
                out.flush();

                // Read type
                String type = in.readUTF();

                if ("ERROR".equals(type)) {
                    String reason = in.readUTF();
                    throw new IOException("Server error: " + reason);
                }

                if (!FileTransferProtocol.FILE_META_RESPONSE.equals(type)) {
                    throw new IOException("Unexpected response type: " + type);
                }

                // Read fields
                String name = in.readUTF();
                long fileSize = in.readLong();
                int chunkSize = in.readInt();
                int totalChunks = in.readInt();
                String fileSha256 = in.readUTF();

                // Read chunk hashes
                List<String> chunkHashes = new ArrayList<>(totalChunks);
                for (int i = 0; i < totalChunks; i++) {
                    chunkHashes.add(in.readUTF());
                }

                return new FileMetadata(name, fileSize, chunkSize, totalChunks, fileSha256, chunkHashes);
            }
        }
    }

    /**
     * Download: Chunk + Resume + Integrity + tải song song nhiều kết nối
     */
    public static boolean downloadFile(String host, int port, String myPeerId, String fileName, Path saveTo,
                                       Consumer<Double> progressCallback, DownloadControl control) throws IOException {
        return downloadFile(host, port, myPeerId, fileName, saveTo, progressCallback, control,
                DEFAULT_PARALLEL_CONNECTIONS);
    }

    /**
     * Như trên, nhưng chọn số kết nối song song. Giá trị được giới hạn trong 1..MAX_PARALLEL_CONNECTIONS:
     * quá nhiều kết nối không nhanh hơn mà chỉ tốn luồng/socket ở cả hai phía.
     */
    public static boolean downloadFile(String host, int port, String myPeerId, String fileName, Path saveTo,
                                       Consumer<Double> progressCallback, DownloadControl control,
                                       int parallelConnections) throws IOException {
        int connections = clampParallelConnections(parallelConnections);

        // Paths for resume
        // địa chỉ file tạm khi đang tải
        Path partFile = Path.of(saveTo.toString() + ".part");
        // địa chỉ lưu thông tin metadata
        Path metaFile = Path.of(saveTo.toString() + ".meta.properties");
        // địa chỉ lưu bitmap tiến độ, đánh dấu chunk đã tải xong
        Path bitmapFile = Path.of(saveTo.toString() + ".bitmap");

        // 1) Request metadata
        FileMetadata meta = requestMetadata(host, port, myPeerId, fileName);
        System.out.println("[ChunkedFileClient] Metadata: chunks=" + meta.getTotalChunks()
                + ", size=" + meta.getFileSize() + ", chunkSize=" + meta.getChunkSize());

        // 2) Nếu có meta cũ mà mismatch -> reset (tránh resume nhầm file)
        if (Files.exists(metaFile)) {
            if (!isSameMeta(metaFile, meta)) {
                System.out.println("[ChunkedFileClient] Meta mismatch -> reset resume files");
                safeDelete(partFile);
                safeDelete(bitmapFile);
                safeDelete(metaFile);
            }
        }

        // 3) Lưu meta (để lần sau resume)
        saveMeta(metaFile, meta);

        // 4) Tạo .part đúng size (pre-allocate) hoặc fix size nếu lệch
        ensurePartFileSized(partFile, meta.getFileSize());

        // 5) Load bitmap (resume)
        DownloadProgress progress = new DownloadProgress(
                fileName, meta.getFileSize(), meta.getChunkSize(), meta.getTotalChunks(), meta.getFileSha256()
        );
        progress.loadBitmap(bitmapFile);

        // Update initial progress
        if (progressCallback != null && meta.getTotalChunks() > 0) {
            progressCallback.accept(progress.getProgressPercent() / 100.0);
        }

        // 6) Download missing chunks bằng nhiều worker song song
        Queue<Integer> pending = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < meta.getTotalChunks(); i++) {
            if (!progress.isChunkComplete(i)) pending.add(i);
        }

        AtomicBoolean failed = new AtomicBoolean(false);
        int workers = Math.max(1, Math.min(connections, pending.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "chunk-worker-" + fileName);
            t.setDaemon(true);
            return t;
        });

        try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
            // Ghi theo vị trí (positional write) của FileChannel an toàn khi nhiều thread cùng ghi
            FileChannel channel = raf.getChannel();
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                futures.add(pool.submit(() -> runWorker(host, port, myPeerId, fileName, meta, pending,
                        channel, progress, bitmapFile, progressCallback, control, failed)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    failed.set(true);
                    System.err.println("[ChunkedFileClient] Worker crashed: " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failed.set(true);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // CANCEL: dọn file tạm (sau khi đã đóng file .part)
        if (control != null && control.isCancelled()) {
            cleanupOnCancel(partFile, bitmapFile, metaFile);
            return false;
        }
        if (failed.get()) {
            System.err.println("[ChunkedFileClient] Download failed, giữ lại .part để resume lần sau");
            return false;
        }

        // 7) Verify whole file hash (final integrity)
        try {
            if (control != null) control.checkpoint(); // trước khi verify
        } catch (InterruptedException e) {
            cleanupOnCancel(partFile, bitmapFile, metaFile);
            return false;
        }
        String actualHash = FileHashUtil.sha256(partFile);
        if (!actualHash.equals(meta.getFileSha256())) {
            System.err.println("[ChunkedFileClient] File hash mismatch! expected=" + meta.getFileSha256()
                    + " actual=" + actualHash);
            // bitmap không còn đáng tin -> lần sau tải lại từ đầu
            safeDelete(bitmapFile);
            return false;
        }

        // 8) Rename & cleanup
        Files.move(partFile, saveTo, StandardCopyOption.REPLACE_EXISTING);
        safeDelete(bitmapFile);
        safeDelete(metaFile);

        System.out.println("[ChunkedFileClient] Download complete: " + saveTo);
        return true;
    }

    /**
     * 1 worker: giữ 1 kết nối TCP, lần lượt lấy chunk còn thiếu trong hàng đợi để tải.
     * Lỗi mạng -> đóng kết nối, mở lại và thử tối đa MAX_RETRIES lần cho mỗi chunk.
     */
    private static void runWorker(String host, int port, String myPeerId, String fileName, FileMetadata meta,
                                  Queue<Integer> pending, FileChannel channel, DownloadProgress progress,
                                  Path bitmapFile, Consumer<Double> progressCallback,
                                  DownloadControl control, AtomicBoolean failed) {
        ChunkConnection conn = null;
        try {
            Integer chunkIndex;
            while (!failed.get() && (chunkIndex = pending.poll()) != null) {
                byte[] data = null;

                for (int retry = 1; retry <= MAX_RETRIES && data == null; retry++) {
                    // checkpoint trước khi request (pause sẽ chờ ở đây, cancel sẽ ném exception)
                    if (control != null) control.checkpoint();
                    try {
                        if (conn == null) conn = new ChunkConnection(host, port);
                        data = conn.fetchChunk(myPeerId, fileName, chunkIndex, meta);
                        if (data == null) {
                            System.err.println("[ChunkedFileClient] Chunk " + chunkIndex + " hash mismatch (retry "
                                    + retry + "/" + MAX_RETRIES + ")");
                        }
                    } catch (IOException e) {
                        System.err.println("[ChunkedFileClient] Chunk " + chunkIndex + " error (retry "
                                + retry + "/" + MAX_RETRIES + "): " + e.getMessage());
                        closeQuietly(conn);
                        conn = null;
                    }
                }

                if (data == null) {
                    System.err.println("[ChunkedFileClient] Failed to download chunk " + chunkIndex);
                    failed.set(true);
                    return;
                }

                // checkpoint trước khi write
                if (control != null) control.checkpoint();

                long offset = (long) chunkIndex * meta.getChunkSize();
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) {
                    offset += channel.write(buf, offset);
                }

                double percent;
                synchronized (progress) {
                    progress.markChunkComplete(chunkIndex);
                    progress.saveBitmap(bitmapFile);
                    percent = progress.getProgressPercent();
                }
                if (progressCallback != null) {
                    progressCallback.accept(percent / 100.0);
                }
            }
        } catch (InterruptedException e) {
            // cancel
            failed.set(true);
        } catch (IOException e) {
            System.err.println("[ChunkedFileClient] Write error: " + e.getMessage());
            failed.set(true);
        } finally {
            closeQuietly(conn);
        }
    }

    /** Kết nối TCP dùng lại cho nhiều request GET_CHUNK liên tiếp */
    private static final class ChunkConnection implements Closeable {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        ChunkConnection(String host, int port) throws IOException {
            socket = openSocket(host, port);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        /**
         * EXPECTED server response for chunk:
         *   - type = CHUNK_DATA
         *   - receivedIndex (int)
         *   - dataLen (int)
         *   - chunkSha256 (UTF)
         *   - chunkBytes (byte[dataLen])
         *
         * Or:
         *   - type = ERROR
         *   - reason (UTF)
         *
         * @return dữ liệu chunk, hoặc null nếu hash không khớp (stream vẫn đồng bộ, có thể thử lại)
         * @throws IOException lỗi mạng / phản hồi sai giao thức -> nên mở lại kết nối
         */
        byte[] fetchChunk(String myPeerId, String fileName, int chunkIndex, FileMetadata meta) throws IOException {
            out.writeUTF(FileTransferProtocol.buildChunkRequest(myPeerId, fileName, chunkIndex));
            out.flush();

            String type = in.readUTF();
            if ("ERROR".equals(type)) {
                throw new IOException("Server error: " + in.readUTF());
            }
            if (!FileTransferProtocol.CHUNK_DATA.equals(type)) {
                throw new IOException("Unexpected response type: " + type);
            }

            int receivedIndex = in.readInt();
            int dataLen = in.readInt();
            String serverHash = in.readUTF();

            if (receivedIndex != chunkIndex) throw new IOException("Wrong chunk index " + receivedIndex);
            if (dataLen < 0 || dataLen > meta.getChunkSize()) throw new IOException("Invalid chunk length " + dataLen);

            byte[] chunkData = new byte[dataLen];
            in.readFully(chunkData);

            // So với hash trong metadata (không chỉ hash server gửi kèm) để chắc chắn đúng file ban đầu
            String expectedHash = chunkIndex < meta.getChunkHashes().size()
                    ? meta.getChunkHashes().get(chunkIndex)
                    : serverHash;
            String actualHash = FileHashUtil.sha256(chunkData);
            return actualHash.equals(expectedHash) ? chunkData : null;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    // ---------------- helpers: socket/meta/bitmap/part ----------------

    static int clampParallelConnections(int requested) {
        return Math.max(1, Math.min(MAX_PARALLEL_CONNECTIONS, requested));
    }

    private static Socket openSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {}
    }

    private static void ensurePartFileSized(Path partFile, long fileSize) throws IOException {
        if (!Files.exists(partFile)) {
            try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                raf.setLength(fileSize);
            }
            return;
        }
        long current = Files.size(partFile);
        if (current != fileSize) {
            System.out.println("[ChunkedFileClient] .part size mismatch (" + current + " != " + fileSize + ") -> reset");
            safeDelete(partFile);
            try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                raf.setLength(fileSize);
            }
        }
    }

    private static void safeDelete(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (IOException ignored) {}
    }

    private static void saveMeta(Path metaFile, FileMetadata meta) throws IOException {
        Properties props = new Properties();
        props.setProperty("fileName", meta.getFileName());
        props.setProperty("fileSize", String.valueOf(meta.getFileSize()));
        props.setProperty("chunkSize", String.valueOf(meta.getChunkSize()));
        props.setProperty("totalChunks", String.valueOf(meta.getTotalChunks()));
        props.setProperty("fileSha256", meta.getFileSha256());

        try (OutputStream os = Files.newOutputStream(metaFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            props.store(os, "P2P download metadata");
        }
    }

    private static boolean isSameMeta(Path metaFile, FileMetadata meta) {
        try (InputStream is = Files.newInputStream(metaFile)) {
            Properties props = new Properties();
            props.load(is);

            String fileName = props.getProperty("fileName", "");
            long fileSize = Long.parseLong(props.getProperty("fileSize", "-1"));
            int chunkSize = Integer.parseInt(props.getProperty("chunkSize", "-1"));
            int totalChunks = Integer.parseInt(props.getProperty("totalChunks", "-1"));
            String fileSha256 = props.getProperty("fileSha256", "");

            return fileName.equals(meta.getFileName())
                    && fileSize == meta.getFileSize()
                    && chunkSize == meta.getChunkSize()
                    && totalChunks == meta.getTotalChunks()
                    && fileSha256.equals(meta.getFileSha256());

        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupOnCancel(Path partFile, Path bitmapFile, Path metaFile) {
        System.out.println("[ChunkedFileClient] Cancel -> cleanup temp files");
        safeDelete(partFile);
        safeDelete(bitmapFile);
        safeDelete(metaFile);
    }

}
