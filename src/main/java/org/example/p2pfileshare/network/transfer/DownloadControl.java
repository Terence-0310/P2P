package org.example.p2pfileshare.network.transfer;

public class DownloadControl {
    private volatile boolean paused = false;
    private volatile boolean cancelled = false;
    private boolean retryRequested = false;

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        retryRequested = true; // nếu đang chờ mạng thì thử lại ngay
        notifyAll();
    }

    public synchronized void cancel() {
        cancelled = true;
        paused = false;     // nếu đang pause thì cho thoát luôn
        notifyAll();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Đánh thức tác vụ đang chờ kết nối lại để thử ngay (vd. peer vừa online trở lại) */
    public synchronized void requestRetry() {
        retryRequested = true;
        notifyAll();
    }

    /**
     * Chờ trước lần thử lại tiếp theo: tối đa ms, hoặc sớm hơn khi có requestRetry()/resume().
     * @throws InterruptedException nếu bị huỷ trong lúc chờ
     */
    public synchronized void awaitRetry(long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!retryRequested && !cancelled) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            wait(left);
        }
        retryRequested = false;
        if (cancelled) throw new InterruptedException("Download cancelled");
    }

    // Gọi ở những điểm an toàn (giữa các chunk / trước retry / trước read)
    public void checkpoint() throws InterruptedException {
        synchronized (this) {
            while (paused && !cancelled) {
                wait();
            }
            if (cancelled) throw new InterruptedException("Download cancelled");
        }
    }
}
