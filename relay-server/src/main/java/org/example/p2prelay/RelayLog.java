package org.example.p2prelay;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Log ra stdout (docker logs). Quy ước: chỉ ghi id rút gọn của peer, không ghi mã mời, token,
 * chữ ký hay nội dung file.
 */
final class RelayLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static volatile boolean quiet = false;

    private RelayLog() {}

    static void info(String msg) {
        if (!quiet) System.out.println(LocalDateTime.now().format(FMT) + " INFO  " + msg);
    }

    static void warn(String msg) {
        System.out.println(LocalDateTime.now().format(FMT) + " WARN  " + msg);
    }
}
