package org.example.p2pfileshare.service;

import org.example.p2pfileshare.network.relay.RelayException;
import org.example.p2pfileshare.network.transfer.ChunkedFileClient;
import org.example.p2pfileshare.network.transfer.DownloadControl;
import org.example.p2pfileshare.network.transfer.RemoteRejectedException;
import org.example.p2pfileshare.network.transport.ConnectionOpener;
import org.example.p2pfileshare.network.transport.SocketStreamConnection;
import org.example.p2pfileshare.util.NetworkSettings;
import org.example.p2prelay.protocol.RelayProtocol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DownloadJob {

    public enum State {
        NEW,
        RUNNING,
        PAUSED,
        /** mất kết nối giữa chừng: giữ chunk đã xác minh, tự thử lại và tải tiếp khi kết nối phục hồi */
        WAITING_NETWORK,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    // thử lại: 2s, 4s, 8s rồi mỗi 15s, trong tối đa maxWaitMs kể từ lần cuối còn tải được dữ liệu
    private static final long RETRY_INITIAL_MS = 2000;
    private static final long RETRY_MAX_MS = 15_000;
    // file nguồn đổi giữa chừng (SHA-256 cả file sai) thì tải lại từ đầu tối đa 1 lần
    private static final int MAX_HASH_MISMATCH = 2;

    private final DownloadControl control = new DownloadControl();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean progressed = new AtomicBoolean(false);

    private final ConnectionOpener opener; // mở kết nối kênh file tới peer (TCP hoặc relay)
    private final String myPeerId;     // peerId của mình, server dùng để kiểm tra quyền tải
    private final String remotePath;   // fileName / relativePath phía server
    private final Path saveTo;
    private final long maxWaitMs;

    private final Consumer<Double> onProgress; // 0..1
    private final Consumer<State> onState;     // notify state change
    private final Consumer<Throwable> onError; // notify error if any

    private volatile State state = State.NEW;
    private volatile boolean success = false;
    private volatile Throwable error = null;
    private volatile int retryAttempt = 0;

    private Thread worker;

    public DownloadJob(String host, int port, String myPeerId, String remotePath, Path saveTo,
                       Consumer<Double> onProgress,
                       Consumer<State> onState,
                       Consumer<Throwable> onError) {
        this(lanOpener(Objects.requireNonNull(host), port), myPeerId, remotePath, saveTo,
                NetworkSettings.downloadRetryMaxWaitSeconds() * 1000L, onProgress, onState, onError);
    }

    public DownloadJob(ConnectionOpener opener, String myPeerId, String remotePath, Path saveTo, long maxWaitMs,
                       Consumer<Double> onProgress,
                       Consumer<State> onState,
                       Consumer<Throwable> onError) {

        this.opener = Objects.requireNonNull(opener);
        this.myPeerId = Objects.requireNonNull(myPeerId);
        this.remotePath = Objects.requireNonNull(remotePath);
        this.saveTo = Objects.requireNonNull(saveTo);
        this.maxWaitMs = maxWaitMs;

        this.onProgress = onProgress;
        this.onState = onState;
        this.onError = onError;
    }

    private static ConnectionOpener lanOpener(String host, int port) {
        return () -> SocketStreamConnection.connect(host, port, 5000);
    }

    /** Bắt đầu tải (chỉ gọi 1 lần). */
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        setState(State.RUNNING);

        worker = new Thread(this::run, "DownloadJob-" + remotePath);
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        long lostSince = 0;
        int hashMismatches = 0;
        try {
            while (true) {
                control.checkpoint(); // đang tạm dừng thì chờ ở đây, huỷ thì thoát
                if (state == State.WAITING_NETWORK) setState(State.RUNNING);

                ChunkedFileClient.Outcome outcome;
                try {
                    outcome = ChunkedFileClient.download(opener, myPeerId, remotePath, saveTo,
                            p -> {
                                progressed.set(true);
                                if (onProgress != null) onProgress.accept(p);
                            },
                            control, ChunkedFileClient.DEFAULT_PARALLEL_CONNECTIONS);
                } catch (RemoteRejectedException e) {
                    outcome = new ChunkedFileClient.Outcome(ChunkedFileClient.Status.REJECTED, e);
                } catch (IOException e) {
                    outcome = new ChunkedFileClient.Outcome(ChunkedFileClient.Status.NETWORK_ERROR, e);
                }
                // relay báo đã huỷ liên kết (đã ngắt kết nối): chờ cũng vô ích
                if (outcome.status() == ChunkedFileClient.Status.NETWORK_ERROR && isPermanent(outcome.error())) {
                    outcome = new ChunkedFileClient.Outcome(ChunkedFileClient.Status.REJECTED, outcome.error());
                }

                if (control.isCancelled() || outcome.status() == ChunkedFileClient.Status.CANCELLED) {
                    setState(State.CANCELLED);
                    return;
                }
                switch (outcome.status()) {
                    case COMPLETED -> {
                        success = true;
                        setState(State.COMPLETED);
                        return;
                    }
                    case REJECTED -> {
                        fail(outcome.error());
                        return;
                    }
                    case HASH_MISMATCH -> {
                        if (++hashMismatches >= MAX_HASH_MISMATCH) {
                            fail(new IOException("File nguồn thay đổi trong lúc tải (SHA-256 không khớp)"));
                            return;
                        }
                        // bitmap đã bị xoá: tải lại từ đầu với metadata mới
                    }
                    case NETWORK_ERROR -> {
                        long now = System.currentTimeMillis();
                        // còn tải thêm được chunk kể từ lần lỗi trước -> tính lại thời gian chờ từ đầu
                        if (lostSince == 0 || progressed.getAndSet(false)) {
                            lostSince = now;
                            retryAttempt = 0;
                        }
                        if (now - lostSince > maxWaitMs) {
                            fail(new IOException("Mất kết nối quá " + maxWaitMs / 1000 + " giây: "
                                    + (outcome.error() == null ? "" : outcome.error().getMessage())));
                            return;
                        }
                        retryAttempt++;
                        error = outcome.error();
                        System.err.println("[DownloadJob] Mất kết nối khi tải " + remotePath + " ("
                                + (error == null ? "?" : error.getMessage()) + "), chờ thử lại lần " + retryAttempt);
                        if (state != State.PAUSED) setState(State.WAITING_NETWORK);
                        long delay = Math.min(RETRY_MAX_MS, RETRY_INITIAL_MS << Math.min(10, retryAttempt - 1));
                        control.awaitRetry(delay);
                    }
                    default -> {
                    }
                }
            }
        } catch (InterruptedException e) {
            setState(State.CANCELLED);
        } catch (Throwable t) {
            if (control.isCancelled()) {
                setState(State.CANCELLED);
            } else {
                fail(t);
            }
        }
    }

    private static boolean isPermanent(IOException e) {
        return e instanceof RelayException re && RelayProtocol.E_NOT_LINKED.equals(re.getCode());
    }

    private void fail(Throwable t) {
        error = t;
        setState(State.FAILED);
        // báo lý do sau cùng để UI hiển thị lý do cụ thể thay vì "Tải thất bại" chung chung
        if (onError != null && t != null) onError.accept(t);
    }

    /** Tạm dừng (pause sẽ hiệu lực ở checkpoint). */
    public void pause() {
        if (state == State.RUNNING || state == State.WAITING_NETWORK) {
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

    /** Đang chờ mạng thì thử lại ngay (gọi khi phát hiện peer online trở lại) */
    public void retryNow() {
        if (state == State.WAITING_NETWORK) control.requestRetry();
    }

    /** Hủy tải (cancel sẽ hiệu lực ở checkpoint). */
    public void cancel() {
        if (state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED) return;
        control.cancel();
        // state sẽ được cập nhật khi worker thoát (worker dọn file tạm rồi báo CANCELLED)
    }

    public boolean isActive() {
        return state == State.RUNNING || state == State.PAUSED || state == State.WAITING_NETWORK;
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

    /** Số lần đã thử lại liên tiếp khi mất kết nối */
    public int getRetryAttempt() {
        return retryAttempt;
    }

    public DownloadControl getControl() {
        return control;
    }

    public Path getSaveTo() {
        return saveTo;
    }

    private synchronized void setState(State s) {
        // không đổi trạng thái sau khi job đã kết thúc (tránh báo CANCELLED/FAILED 2 lần)
        if (state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED) return;
        if (state == s && s != State.WAITING_NETWORK) return;
        state = s;
        if (onState != null) onState.accept(s);
    }
}
