package org.example.p2pfileshare.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Theo dõi 1 thư mục (kể cả thư mục con) bằng WatchService.
 * Nhiều thay đổi liên tiếp (copy nhiều file, file đang được ghi...) được gộp lại:
 * callback chỉ chạy khi thư mục "yên" trong {@code debounceMillis}.
 */
public class FolderWatcher implements Closeable {

    private final Path root;
    private final Runnable onChanged;
    private final long debounceMillis;

    private final WatchService watchService;
    private final ScheduledExecutorService scheduler;
    private final Thread watchThread;
    private ScheduledFuture<?> pending;
    private volatile boolean running = true;

    public FolderWatcher(Path root, long debounceMillis, Runnable onChanged) throws IOException {
        this.root = root;
        this.onChanged = onChanged;
        this.debounceMillis = debounceMillis;
        this.watchService = root.getFileSystem().newWatchService();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "folder-watcher-debounce");
            t.setDaemon(true);
            return t;
        });

        registerAll(root);

        watchThread = new Thread(this::loop, "folder-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // bỏ qua thư mục không có quyền đọc
            }
        });
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }

            Path dir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    // thư mục con mới tạo cũng phải được theo dõi
                    Path child = dir.resolve((Path) event.context());
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            registerAll(child);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
            key.reset();
            scheduleCallback();
        }
    }

    private synchronized void scheduleCallback() {
        if (pending != null) pending.cancel(false);
        pending = scheduler.schedule(() -> {
            try {
                onChanged.run();
            } catch (Exception e) {
                System.err.println("[FolderWatcher] Callback error: " + e.getMessage());
            }
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    public Path getRoot() {
        return root;
    }

    @Override
    public void close() {
        running = false;
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        scheduler.shutdownNow();
        watchThread.interrupt();
    }
}
