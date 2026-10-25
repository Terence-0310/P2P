package org.example.p2pfileshare.util;

/**
 * Tính tốc độ tải và thời gian còn lại từ tiến độ (0..1) của 1 file có kích thước biết trước.
 * Tốc độ được làm mượt (trung bình trượt) để con số không nhảy liên tục.
 */
public class TransferStats {

    private static final double SMOOTHING = 0.3;
    // Cập nhật tốc độ tối đa mỗi khoảng này để tránh chia cho khoảng thời gian quá nhỏ
    private static final long MIN_SAMPLE_NANOS = 300_000_000L;

    private final long totalBytes;
    private long lastNanos;
    private long lastBytes = -1;
    private double bytesPerSecond = -1;

    public TransferStats(long totalBytes) {
        this.totalBytes = Math.max(0, totalBytes);
    }

    /** Ghi nhận tiến độ mới, trả về chuỗi hiển thị vd. "45.2% • 3.10 MB/s • còn 12 giây" */
    public synchronized String update(double fraction) {
        return update(fraction, System.nanoTime());
    }

    synchronized String update(double fraction, long nowNanos) {
        long doneBytes = (long) (Math.min(1.0, Math.max(0.0, fraction)) * totalBytes);

        if (lastBytes < 0) {
            // mẫu đầu tiên (có thể là phần đã tải từ lần trước khi resume) -> chưa tính tốc độ
            lastBytes = doneBytes;
            lastNanos = nowNanos;
        } else if (nowNanos - lastNanos >= MIN_SAMPLE_NANOS) {
            double instant = (doneBytes - lastBytes) / ((nowNanos - lastNanos) / 1e9);
            bytesPerSecond = bytesPerSecond < 0 ? instant : SMOOTHING * instant + (1 - SMOOTHING) * bytesPerSecond;
            lastBytes = doneBytes;
            lastNanos = nowNanos;
        }

        StringBuilder sb = new StringBuilder(String.format("%.1f%%", fraction * 100));
        if (bytesPerSecond > 0 && fraction < 1.0) {
            long remainingSeconds = (long) Math.ceil((totalBytes - doneBytes) / bytesPerSecond);
            sb.append(" • ").append(formatSize((long) bytesPerSecond)).append("/s")
              .append(" • còn ").append(formatDuration(remainingSeconds));
        }
        return sb.toString();
    }

    /** Đổi bytes -> B/KB/MB/GB */
    public static String formatSize(long bytes) {
        final double KB = 1024.0;
        final double MB = KB * 1024.0;
        final double GB = MB * 1024.0;
        if (bytes >= GB) return String.format("%.2f GB", bytes / GB);
        if (bytes >= MB) return String.format("%.2f MB", bytes / MB);
        if (bytes >= KB) return String.format("%.2f KB", bytes / KB);
        return bytes + " B";
    }

    static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + " giây";
        if (seconds < 3600) return (seconds / 60) + " phút " + (seconds % 60) + " giây";
        return (seconds / 3600) + " giờ " + ((seconds % 3600) / 60) + " phút";
    }
}
