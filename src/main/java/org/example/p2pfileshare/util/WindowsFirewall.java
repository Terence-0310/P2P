package org.example.p2pfileshare.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Cho phép Java nhận kết nối qua Windows Firewall, để peer khác trong LAN quét thấy và kết nối được.
 * Thêm 1 rule Inbound Allow cho đúng file java.exe đang chạy app (mọi cổng, mọi loại mạng),
 * và xoá các rule Block mà Windows tạo khi người dùng lỡ bấm "Cancel" ở hộp thoại tường lửa
 * (rule Block luôn thắng rule Allow). Việc sửa rule cần quyền Admin -> Windows hiện hộp thoại UAC.
 */
public final class WindowsFirewall {

    static final String RULE_NAME = "P2PFileShare";

    private WindowsFirewall() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Đường dẫn java.exe / javaw.exe của tiến trình hiện tại, null nếu không lấy được */
    public static String javaExecutable() {
        return ProcessHandle.current().info().command().orElse(null);
    }

    /**
     * true nếu đã có rule Allow của app cho java hiện tại và không còn rule Block nào chặn nó.
     * Đọc rule không cần quyền Admin. Trả về true khi không phải Windows (không cần cấu hình).
     */
    public static boolean isConfigured() {
        if (!isWindows()) return true;
        String java = javaExecutable();
        if (java == null) return true;
        String script = "$p = " + psQuote(java) + "\n"
                + "$r = @(Get-NetFirewallApplicationFilter -Program $p -ErrorAction SilentlyContinue"
                + " | Get-NetFirewallRule -ErrorAction SilentlyContinue"
                + " | Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' })\n"
                + "$allow = @($r | Where-Object { $_.DisplayName -like " + psQuote(RULE_NAME + "*") + " -and $_.Action -eq 'Allow' }).Count\n"
                + "$block = @($r | Where-Object { $_.Action -eq 'Block' }).Count\n"
                + "if ($allow -gt 0 -and $block -eq 0) { exit 0 } else { exit 1 }";
        return runPowerShell(script, false, 20) == 0;
    }

    /**
     * Thêm rule (hiện hộp thoại UAC xin quyền Admin). Chặn tới khi người dùng xử lý xong.
     * @return true nếu sau đó tường lửa đã được cấu hình
     */
    public static boolean configure() {
        if (!isWindows()) return true;
        String java = javaExecutable();
        if (java == null) return false;
        String script = "$p = " + psQuote(java) + "\n"
                + "Get-NetFirewallApplicationFilter -Program $p -ErrorAction SilentlyContinue"
                + " | Get-NetFirewallRule -ErrorAction SilentlyContinue"
                + " | Where-Object { $_.Direction -eq 'Inbound' -and ($_.Action -eq 'Block' -or $_.DisplayName -eq " + psQuote(RULE_NAME) + ") }"
                + " | Remove-NetFirewallRule\n"
                + "New-NetFirewallRule -DisplayName " + psQuote(RULE_NAME)
                + " -Direction Inbound -Program $p -Action Allow -Profile Any | Out-Null";
        runPowerShell(script, true, 120);
        return isConfigured();
    }

    /** Chạy script PowerShell (qua -EncodedCommand để khỏi lo dấu nháy/khoảng trắng trong đường dẫn) */
    private static int runPowerShell(String script, boolean elevated, int timeoutSeconds) {
        String encoded = encode(script);
        String command = elevated
                // powershell thường bật 1 powershell khác với quyền Admin (UAC) và đợi nó xong
                ? "try { $p = Start-Process powershell -Verb RunAs -Wait -PassThru -WindowStyle Hidden"
                  + " -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','" + encoded + "';"
                  + " exit $p.ExitCode } catch { exit 1 }"
                : null;
        ProcessBuilder pb = elevated
                ? new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encode(command))
                : new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = pb.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            System.err.println("[Firewall] Cannot run PowerShell: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // PowerShell -EncodedCommand nhận Base64 của chuỗi UTF-16LE
    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    // chuỗi PowerShell trong nháy đơn: ' được viết thành ''
    static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
