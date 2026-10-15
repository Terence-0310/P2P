package org.example.p2pfileshare.service;

import org.example.p2pfileshare.network.transfer.DownloadControl;
import org.example.p2pfileshare.network.transfer.ChunkedFileClient;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DownloadJob {

    public enum State {
        NEW,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final DownloadControl control = new DownloadControl();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final String host;
    private final int port;
    private final String myPeerId;     // peerId của mình, server dùng để kiểm tra quyền tải
    private final String remotePath;   // fileName / relativePath phía server
    private final Path saveTo;

    private final Consumer<Double> onProgress; // 0..1
    private final Consumer<State> onState;     // notify state change
    private final Consumer<Throwable> onError; // notify error if any

    private volatile State state = State.NEW;
    private volatile boolean success = false;
    private volatile Throwable error = null;

    private Thread worker;

    public DownloadJob(String host, int port, String myPeerId, String remotePath, Path saveTo,
                       Consumer<Double> onProgress,
                       Consumer<State> onState,
                       Consumer<Throwable> onError) {

        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.myPeerId = Objects.requireNonNull(myPeerId);
        this.remotePath = Objects.requireNonNull(remotePath);
        this.saveTo = Objects.requireNonNull(saveTo);

        this.onProgress = onProgress;
        this.onState = onState;
        this.onError = onError;
    }

    /** Bắt đầu tải (chỉ gọi 1 lần). */
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        setState(State.RUNNING);

        worker = new Thread(() -> {
            try {
                // Gọi ChunkedFileClient có hỗ trợ control
                success = ChunkedFileClient.downloadFile(
                        host, port, myPeerId, remotePath, saveTo,
                        p -> {
                            if (onProgress != null) onProgress.accept(p);
                        },
                        control
                );

                if (control.isCancelled()) {
                    setState(State.CANCELLED);
                } else if (success) {
                    setState(State.COMPLETED);
                } else {
                    setState(State.FAILED);
                }
            } catch (Throwable t) {
                error = t;
                if (control.isCancelled()) {
                    setState(State.CANCELLED);
                } else {
                    setState(State.FAILED);
                    // báo lý do sau cùng để UI hiển thị lý do cụ thể thay vì "Tải thất bại" chung chung
                    if (onError != null) onError.accept(t);
                }
            }
        }, "DownloadJob-" + remotePath);

        worker.setDaemon(true);
        worker.start();
    }

    /** Tạm dừng (pause sẽ hiệu lực ở checkpoint). */
    public void pause() {
        if (state == State.RUNNING) {
            control.pause();
            setState(State.PAUSED);
        }
    }

    /** Tiếp tục tải. */
    public void resume() {
        if (state == State.PAUSED) {
            control.resume();
            setState(State.RUNNING);
        }
    }

    /** Hủy tải (cancel sẽ hiệu lực ở checkpoint). */
    public void cancel() {
        if (state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED) return;
        control.cancel();
        // state sẽ được cập nhật khi worker thoát (worker dọn file tạm rồi báo CANCELLED)
    }

    public boolean isActive() {
        return state == State.RUNNING || state == State.PAUSED;
    }

    /** Cho UI check nhanh */
    public State getState() {
        return state;
    }

    public boolean isSuccess() {
        return success;
    }

    public Throwable getError() {
        return error;
    }

    public DownloadControl getControl() {
        return control;
    }

    private synchronized void setState(State s) {
        // không đổi trạng thái sau khi job đã kết thúc (tránh báo CANCELLED/FAILED 2 lần)
        if (state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED) return;
        state = s;
        if (onState != null) onState.accept(s);
    }
}
