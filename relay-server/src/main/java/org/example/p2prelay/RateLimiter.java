package org.example.p2prelay;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Đếm số lần thất bại theo khoá (phiên hoặc IP) trong cửa sổ trượt */
final class RateLimiter {

    private final int maxEvents;
    private final long windowMs;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();

    RateLimiter(int maxEvents, long windowMs, LongSupplier clock) {
        this.maxEvents = maxEvents;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** true nếu khoá đã dùng hết số lần cho phép trong cửa sổ hiện tại */
    boolean isLimited(String key) {
        Deque<Long> q = events.get(key);
        if (q == null) return false;
        synchronized (q) {
            prune(q);
            return q.size() >= maxEvents;
        }
    }

    void record(String key) {
        Deque<Long> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.getAsLong());
        }
    }

    /** Dọn khoá không còn sự kiện trong cửa sổ (gọi định kỳ) */
    void cleanup() {
        events.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }

    private void prune(Deque<Long> q) {
        long cutoff = clock.getAsLong() - windowMs;
        while (!q.isEmpty() && q.peekFirst() <= cutoff) q.pollFirst();
    }
}
