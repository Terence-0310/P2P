package org.example.p2pfileshare.network.transfer;

import org.example.p2pfileshare.network.protocol.FileTransferProtocol;
import org.example.p2pfileshare.util.FileHashUtil;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class ChunkedFileServer {

    private final int port;
    private final AtomicReference<Path> shareFolder = new AtomicReference<>();
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MB
    private static final int POOL_SIZE = 64;
    // Đóng kết nối nếu client không gửi request nào trong khoảng này (vd. đang pause)
    private static final int IDLE_TIMEOUT_MS = 60_000;

    // Đỡ tạo vô hạn thread; tạo khi start()
    private ExecutorService pool;

    // Kiểm tra peerId đã được chấp nhận kết nối chưa (null = cho phép tất cả)
    private final Predicate<String> accessChecker;

    // Cache metadata để không phải băm lại toàn bộ file mỗi lần có người tải
    private final ConcurrentHashMap<Path, CachedMeta> metaCache = new ConcurrentHashMap<>();

    private record CachedMeta(long size, long lastModified, String fileSha256, List<String> chunkHashes) {}

    // ===== lifecycle =====
    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;

    public ChunkedFileServer(int port, Path initialFolder) {
        this(port, initialFolder, null);
    }

    public ChunkedFileServer(int port, Path initialFolder, Predicate<String> accessChecker) {
        this.port = port;
        this.accessChecker = accessChecker;
        changeFolder(initialFolder);
    }

    /** Cho phép set null để "tắt share" */
    public void changeFolder(Path newFolder) {
        if (newFolder == null) {
            shareFolder.set(null);
            System.out.println("[ChunkedFileServer] Share folder cleared (no sharing)");
            return;
        }
        if (Files.isDirectory(newFolder)) {
            shareFolder.set(newFolder.toAbsolutePath().normalize());
            metaCache.clear();
            System.out.println("[ChunkedFileServer] Folder changed to: " + newFolder);
        } else {
            System.out.println("[ChunkedFileServer] Ignored changeFolder (not a directory): " + newFolder);
        }
    }

    /** Trạng thái chạy để UI check */
    public boolean isRunning() {
        return running;
    }

    /** Start server (idempotent) */
    public synchronized void start() {
        if (running) {
            System.out.println("[ChunkedFileServer] Already running on port " + port);
            return;
        }

        running = true;

        // Nếu pool đã shutdown từ lần trước, tạo lại. Luồng daemon để không giữ JVM khi tắt app
        if (pool == null || pool.isShutdown()) {
            pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
                Thread t = new Thread(r, "chunked-file-handler");
                t.setDaemon(true);
                return t;
            });
        }

        serverThread = new Thread(this::runLoop, "chunked-file-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void runLoop() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("[ChunkedFileServer] Listening on port " + port);

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    pool.submit(() -> handleClient(client));
                } catch (SocketException se) {
                    // thường xảy ra khi stopServer() -> serverSocket.close()
                    if (running) {
                        System.err.println("[ChunkedFileServer] accept() socket error: " + se.getMessage());
                    }
                    break;
                } catch (RejectedExecutionException ree) {
                    // xảy ra khi pool đã shutdown mà vẫn có accept/submit
                    System.err.println("[ChunkedFileServer] Rejected client task (server stopping): " + ree.getMessage());
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[ChunkedFileServer] accept() error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[ChunkedFileServer] Failed to bind/listen port " + port + ": " + e.getMessage());
            }
        } finally {
            // cleanup
            closeServerSocketQuietly();
            System.out.println("[ChunkedFileServer] Server loop exited");
        }
    }

    /** Stop server */
    public synchronized void stopServer() {
        if (!running) {
            System.out.println("[ChunkedFileServer] Already stopped");
            return;
        }

        System.out.println("[ChunkedFileServer] Stopping server...");
        running = false;

        // 1) Đóng server socket để accept() thoát ngay
        closeServerSocketQuietly();

        // 2) Dừng worker đang xử lý client
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // 3) Cho thread server kết thúc
        if (serverThread != null && serverThread.isAlive()) {
            try {
                serverThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("[ChunkedFileServer] Stopped");
    }

    private void closeServerSocketQuietly() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        } finally {
            serverSocket = null;
        }
    }

    // Một kết nối có thể gửi nhiều request liên tiếp (client giữ kết nối để tải nhiều chunk)
    private void handleClient(Socket socket) {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))) {

            s.setSoTimeout(IDLE_TIMEOUT_MS);

            while (running) {
                // Client gửi writeUTF -> server phải readUTF
                String request = in.readUTF();
                if (request.isBlank()) return;

                FileTransferProtocol.ParsedCommand cmd = FileTransferProtocol.parse(request);
                if (cmd == null) {
                    sendError(out, "Invalid command");
                    continue;
                }

                if (accessChecker != null && !accessChecker.test(cmd.peerId)) {
                    sendError(out, "Access denied: peer chưa được chấp nhận kết nối");
                    continue;
                }

                Path root = shareFolder.get();
                if (root == null) {
                    sendError(out, "No share folder set");
                    continue;
                }

                if (FileTransferProtocol.FILE_META_REQUEST.equals(cmd.command)) {
                    handleMetaRequest(cmd, root, out);
                } else if (FileTransferProtocol.GET_CHUNK.equals(cmd.command)) {
                    handleChunkRequest(cmd, root, out);
                } else {
                    sendError(out, "Unknown command: " + cmd.command);
                }
            }

        } catch (EOFException | SocketTimeoutException eof) {
            // client đóng kết nối hoặc không hoạt động quá lâu
        } catch (IOException e) {
            System.err.println("[ChunkedFileServer] Client error: " + e.getMessage());
        }
    }

    // Trả về đường dẫn file nằm trong thư mục chia sẻ, hoặc null nếu không hợp lệ
    private Path resolveSharedFile(Path root, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        Path filePath = root.resolve(fileName).normalize();
        if (!filePath.startsWith(root) || !Files.isRegularFile(filePath)) return null;
        return filePath;
    }

    // hàm xử lý handleMetaRequest
    private void handleMetaRequest(FileTransferProtocol.ParsedCommand cmd, Path root, DataOutputStream out) throws IOException {
        String fileName = cmd.fileName;
        Path filePath = resolveSharedFile(root, fileName);
        if (filePath == null) {
            sendError(out, "File not found");
            return;
        }

        CachedMeta meta = getOrComputeMeta(filePath);
        int totalChunks = meta.chunkHashes().size();

        out.writeUTF(FileTransferProtocol.FILE_META_RESPONSE);
        out.writeUTF(fileName);
        out.writeLong(meta.size());
        out.writeInt(DEFAULT_CHUNK_SIZE);
        out.writeInt(totalChunks);
        out.writeUTF(meta.fileSha256());
        for (String h : meta.chunkHashes()) {
            out.writeUTF(h);
        }
        out.flush();

        System.out.println("[ChunkedFileServer] Sent metadata for " + fileName + " chunks=" + totalChunks);
    }

    private CachedMeta getOrComputeMeta(Path filePath) throws IOException {
        long size = Files.size(filePath);
        long lastModified = Files.getLastModifiedTime(filePath).toMillis();

        CachedMeta cached = metaCache.get(filePath);
        if (cached != null && cached.size() == size && cached.lastModified() == lastModified) {
            return cached;
        }

        CachedMeta computed = computeMeta(filePath, size, lastModified);
        metaCache.put(filePath, computed);
        return computed;
    }

    // Đọc file 1 lượt: vừa băm từng chunk, vừa băm cả file
    private CachedMeta computeMeta(Path filePath, long fileSize, long lastModified) throws IOException {
        int totalChunks = (int) Math.ceil((double) fileSize / DEFAULT_CHUNK_SIZE);
        List<String> chunkHashes = new ArrayList<>(totalChunks);

        MessageDigest fileDigest;
        try {
            fileDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        try (InputStream in = new BufferedInputStream(Files.newInputStream(filePath))) {
            byte[] buffer = new byte[DEFAULT_CHUNK_SIZE];
            for (int i = 0; i < totalChunks; i++) {
                long offset = (long) i * DEFAULT_CHUNK_SIZE;
                int toRead = (int) Math.min(DEFAULT_CHUNK_SIZE, fileSize - offset);
                int read = in.readNBytes(buffer, 0, toRead);

                byte[] chunkData = new byte[read];
                // chunk cuối có thể có kích thước nhỏ hơn
                System.arraycopy(buffer, 0, chunkData, 0, read);
                chunkHashes.add(FileHashUtil.sha256(chunkData));
                fileDigest.update(chunkData);
            }
        }

        String fileSha256 = FileHashUtil.bytesToHex(fileDigest.digest());
        return new CachedMeta(fileSize, lastModified, fileSha256, List.copyOf(chunkHashes));
    }

    private void handleChunkRequest(FileTransferProtocol.ParsedCommand cmd, Path root, DataOutputStream out) throws IOException {
        String fileName = cmd.fileName;
        String indexStr = cmd.chunkIndex;

        if (fileName == null || indexStr == null) {
            sendError(out, "Missing parameters");
            return;
        }

        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            sendError(out, "Invalid chunk index");
            return;
        }

        Path filePath = resolveSharedFile(root, fileName);
        if (filePath == null) {
            sendError(out, "File not found");
            return;
        }

        long fileSize = Files.size(filePath);
        long offset = (long) chunkIndex * DEFAULT_CHUNK_SIZE;
        if (offset >= fileSize || chunkIndex < 0) {
            sendError(out, "Chunk index out of range");
            return;
        }

        int dataLen = (int) Math.min(DEFAULT_CHUNK_SIZE, fileSize - offset);

        byte[] chunkData = new byte[dataLen];
        // RandomAccessFile cho phép nhảy thẳng tới chunk cần đọc
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(chunkData);
        }

        String chunkHash = FileHashUtil.sha256(chunkData);

        out.writeUTF(FileTransferProtocol.CHUNK_DATA);
        out.writeInt(chunkIndex);
        out.writeInt(dataLen);
        out.writeUTF(chunkHash);
        out.write(chunkData);
        out.flush();

        System.out.println("[ChunkedFileServer] Sent chunk " + chunkIndex + " len=" + dataLen);
    }

    private void sendError(DataOutputStream out, String reason) throws IOException {
        out.writeUTF("ERROR");
        out.writeUTF(reason);
        out.flush();
    }
}
