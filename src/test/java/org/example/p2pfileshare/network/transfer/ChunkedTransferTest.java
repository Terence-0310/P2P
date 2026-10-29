package org.example.p2pfileshare.network.transfer;

import java.io.DataOutputStream;
import java.io.DataInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Chạy ChunkedFileServer + ChunkedFileClient thật trên localhost. */
class ChunkedTransferTest {

    private static final String ALLOWED_PEER = "peer-allowed";
    private ChunkedFileServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stopServer();
    }

    private int startServer(Path shareDir) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new ChunkedFileServer(port, shareDir, ALLOWED_PEER::equals);
        server.start();

        // chờ server lắng nghe
        for (int i = 0; i < 50; i++) {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return port;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        return fail("Server không khởi động được");
    }

    @Test
    void downloadsMultiChunkFileAndVerifiesContent(@TempDir Path share, @TempDir Path dest) throws Exception {
        // 5.5 MB -> 6 chunk, tải song song
        byte[] content = new byte[5 * 1024 * 1024 + 512 * 1024];
        new Random(42).nextBytes(content);
        Files.write(share.resolve("bai giang.pdf"), content);

        int port = startServer(share);
        Path saveTo = dest.resolve("bai giang.pdf");
        AtomicReference<Double> lastProgress = new AtomicReference<>(0.0);

        boolean ok = ChunkedFileClient.downloadFile("127.0.0.1", port, ALLOWED_PEER, "bai giang.pdf", saveTo,
                p -> lastProgress.accumulateAndGet(p, Math::max), new DownloadControl());

        assertTrue(ok);
        assertArrayEquals(content, Files.readAllBytes(saveTo));
        assertEquals(1.0, lastProgress.get(), 1e-9);
        assertFalse(Files.exists(dest.resolve("bai giang.pdf.part")));
        assertFalse(Files.exists(dest.resolve("bai giang.pdf.bitmap")));
    }

    @Test
    void downloadsSameContentWithOneOrEightConnections(@TempDir Path share, @TempDir Path dest) throws Exception {
        // 9 chunk: với 8 kết nối, nhiều worker cùng ghi vào 1 file .part
        byte[] content = new byte[8 * 1024 * 1024 + 300 * 1024];
        new Random(7).nextBytes(content);
        Files.write(share.resolve("slide.pptx"), content);
        int port = startServer(share);

        for (int connections : new int[]{1, 8}) {
            Path saveTo = dest.resolve(connections + "-slide.pptx");
            assertTrue(ChunkedFileClient.downloadFile("127.0.0.1", port, ALLOWED_PEER, "slide.pptx", saveTo,
                    null, new DownloadControl(), connections), "connections=" + connections);
            assertArrayEquals(content, Files.readAllBytes(saveTo), "connections=" + connections);
        }
    }

    @Test
    void parallelConnectionsAreClampedToValidRange() {
        assertEquals(1, ChunkedFileClient.clampParallelConnections(0));
        assertEquals(1, ChunkedFileClient.clampParallelConnections(-5));
        assertEquals(8, ChunkedFileClient.clampParallelConnections(8));
        assertEquals(ChunkedFileClient.MAX_PARALLEL_CONNECTIONS, ChunkedFileClient.clampParallelConnections(100));
    }

    @Test
    void downloadsEmptyFile(@TempDir Path share, @TempDir Path dest) throws Exception {
        Files.write(share.resolve("empty.txt"), new byte[0]);
        int port = startServer(share);

        Path saveTo = dest.resolve("empty.txt");
        assertTrue(ChunkedFileClient.downloadFile("127.0.0.1", port, ALLOWED_PEER, "empty.txt", saveTo,
                null, new DownloadControl()));
        assertEquals(0, Files.size(saveTo));
    }

    @Test
    void rejectsPeerThatWasNotAccepted(@TempDir Path share, @TempDir Path dest) throws Exception {
        Files.writeString(share.resolve("secret.txt"), "data", StandardCharsets.UTF_8);
        int port = startServer(share);

        IOException ex = assertThrows(IOException.class, () -> ChunkedFileClient.downloadFile(
                "127.0.0.1", port, "peer-stranger", "secret.txt", dest.resolve("secret.txt"),
                null, new DownloadControl()));
        assertTrue(ex.getMessage().contains("Access denied"));
    }

    @Test
    void blocksPathTraversal(@TempDir Path root, @TempDir Path dest) throws Exception {
        Path share = Files.createDirectory(root.resolve("share"));
        Files.writeString(root.resolve("outside.txt"), "private", StandardCharsets.UTF_8);
        int port = startServer(share);

        IOException ex = assertThrows(IOException.class, () -> ChunkedFileClient.downloadFile(
                "127.0.0.1", port, ALLOWED_PEER, "../outside.txt", dest.resolve("outside.txt"),
                null, new DownloadControl()));
        assertTrue(ex.getMessage().contains("File not found"));
    }

    @Test
    void cancelledDownloadCleansUpTempFiles(@TempDir Path share, @TempDir Path dest) throws Exception {
        Files.write(share.resolve("big.bin"), new byte[3 * 1024 * 1024]);
        int port = startServer(share);

        DownloadControl control = new DownloadControl();
        control.cancel();
        Path saveTo = dest.resolve("big.bin");

        assertFalse(ChunkedFileClient.downloadFile("127.0.0.1", port, ALLOWED_PEER, "big.bin", saveTo,
                null, control));
        assertFalse(Files.exists(saveTo));
        assertFalse(Files.exists(dest.resolve("big.bin.part")));
    }

    @Test
    void rejectsMetadataThatWouldAllocateHugeBuffers() {
        assertDoesNotThrow(() -> ChunkedFileClient.validateMetadata(0, 1024, 0));
        assertDoesNotThrow(() -> ChunkedFileClient.validateMetadata(2049, 1024, 3));
        assertThrows(IOException.class, () -> ChunkedFileClient.validateMetadata(-1, 1024, 0));
        assertThrows(IOException.class, () -> ChunkedFileClient.validateMetadata(10, 0, 1));
        assertThrows(IOException.class,
                () -> ChunkedFileClient.validateMetadata(10, ChunkedFileClient.MAX_CHUNK_SIZE + 1, 1));
        assertThrows(IOException.class, () -> ChunkedFileClient.validateMetadata(10, 1024, Integer.MAX_VALUE));
    }

    @Test
    void maliciousServerMetadataFailsFastInsteadOfOutOfMemory() throws Exception {
        try (ServerSocket fake = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = fake.accept();
                     DataInputStream in = new DataInputStream(s.getInputStream());
                     DataOutputStream out = new DataOutputStream(s.getOutputStream())) {
                    in.readUTF();
                    out.writeUTF("FILE_META_RESPONSE");
                    out.writeUTF("evil.bin");
                    out.writeLong(10);
                    out.writeInt(1024);
                    out.writeInt(Integer.MAX_VALUE); // client sẽ cấp phát list 2 tỷ phần tử nếu tin con số này
                    out.writeUTF("hash");
                    out.flush();
                    in.read(); // giữ kết nối tới khi client đóng
                } catch (IOException ignored) {
                }
            });
            t.setDaemon(true);
            t.start();

            IOException e = assertThrows(IOException.class, () ->
                    ChunkedFileClient.requestMetadata("127.0.0.1", fake.getLocalPort(), ALLOWED_PEER, "evil.bin"));
            assertTrue(e.getMessage().contains("chunk count"), e.getMessage());
        }
    }
}
