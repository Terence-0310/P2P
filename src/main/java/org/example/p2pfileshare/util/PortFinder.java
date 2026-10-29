package org.example.p2pfileshare.util;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chọn cổng TCP còn trống trong một khoảng cố định (giữ khoảng 6000–6999 / 7000–7999 như README
 * để quy tắc firewall vẫn đúng). Thử ngẫu nhiên trước để 2 app trên cùng máy ít khi tranh nhau,
 * sau đó quét lần lượt cả khoảng.
 */
public final class PortFinder {

    private static final int RANDOM_ATTEMPTS = 20;

    private PortFinder() {}

    /** Cổng trống trong [from, to], ném IllegalStateException nếu cả khoảng đều bị chiếm */
    public static int findFreePort(int from, int to) {
        if (from < 1 || to > 65535 || from > to) {
            throw new IllegalArgumentException("Invalid port range " + from + "-" + to);
        }
        for (int i = 0; i < RANDOM_ATTEMPTS; i++) {
            int port = ThreadLocalRandom.current().nextInt(from, to + 1);
            if (isFree(port)) return port;
        }
        for (int port = from; port <= to; port++) {
            if (isFree(port)) return port;
        }
        throw new IllegalStateException("No free port in range " + from + "-" + to);
    }

    static boolean isFree(int port) {
        try (ServerSocket ignored = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
