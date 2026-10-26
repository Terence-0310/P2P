package org.example.p2pfileshare;

/**
 * Điểm vào khi chạy trên classpath (NetBeans Run, mvn exec:java).
 * Lớp main không được kế thừa Application, nếu không JVM báo thiếu JavaFX runtime.
 */
public class Launcher {
    public static void main(String[] args) {
        HelloApplication.main(args);
    }
}
