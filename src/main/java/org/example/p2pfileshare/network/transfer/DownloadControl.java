package org.example.p2pfileshare.network.transfer;

public class DownloadControl {
    private volatile boolean paused = false;
    private volatile boolean cancelled = false;

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
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
