package org.example.p2pfileshare.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Cho phép Java nhận kết nối qua Windows Firewall, để peer khác trong LAN quét thấy và kết nối được.
 * <ul>
 *   <li>Rule Inbound Allow cho đúng file java.exe đang chạy app (mọi cổng, mọi loại mạng)</li>
 *   <li>Rule cho phép ping (ICMPv4 echo) để 2 máy kiểm tra được đường mạng bằng lệnh ping</li>
 *   <li>Xoá các rule Block mà Windows tạo khi người dùng lỡ bấm "Cancel" ở hộp thoại tường lửa
 *       (rule Block luôn thắng rule Allow)</li>
 * </ul>
 * Sửa rule cần quyền Admin. Windows chỉ đưa hộp thoại UAC lên trước nếu nó được mở từ cửa sổ đang ở trên cùng,
 * nên app mở 1 cửa sổ PowerShell <b>hiện ra</b> (không ẩn): cửa sổ này hướng dẫn người dùng và xin quyền Admin.
 */
public final class WindowsFirewall {

    static final String RULE_NAME = "P2PFileShare";
    static final String PING_RULE_NAME = "P2PFileShare Ping";

    /** Kết quả cấu hình, kèm lời khuyên cho người dùng */
    public enum Result {
        OK,
        /** Người dùng bấm No ở hộp thoại quyền Admin */
        CANCELLED,
        /** Đã có quyền Admin nhưng thêm rule lỗi (vd. tường lửa do tổ chức quản lý) */
        FAILED,
        /** Không chờ được kết quả (người dùng chưa bấm gì, cửa sổ bị đóng...) */
        NO_RESPONSE
    }

    private WindowsFirewall() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Đường dẫn java.exe / javaw.exe của tiến trình hiện tại, null nếu không lấy được */
    public static String javaExecutable() {
        return ProcessHandle.current().info().command().orElse(null);
    }

    /**
     * true nếu đã có rule Allow cho java hiện tại, không còn rule Block nào chặn nó và đã cho phép ping.
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
                + "$ping = @(Get-NetFirewallRule -DisplayName " + psQuote(PING_RULE_NAME) + " -ErrorAction SilentlyContinue"
                + " | Where-Object { $_.Enabled -eq 'True' }).Count\n"
                + "if ($allow -gt 0 -and $block -eq 0 -and $ping -gt 0) { exit 0 } else { exit 1 }";
        return runHidden(script, 20) == 0;
    }

    /**
     * Mở cửa sổ PowerShell xin quyền Admin rồi thêm rule. Chặn tới khi cửa sổ đóng (tối đa 3 phút).
     */
    public static Result configure() {
        if (!isWindows()) return Result.OK;
        String java = javaExecutable();
        if (java == null) return Result.FAILED;

        Path resultFile;
        Path scriptFile;
        try {
            resultFile = Files.createTempFile("p2pfileshare-firewall", ".txt");
            // ghi script ra file: lệnh qua cmd giới hạn 8191 ký tự, script mã hoá base64 dài hơn thế
            scriptFile = Files.createTempFile("p2pfileshare-firewall", ".ps1");
            Files.writeString(scriptFile, "﻿" + visibleScript(java, resultFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Firewall] Cannot create temp file: " + e.getMessage());
            return Result.FAILED;
        }
        try {
            // "start" mở cửa sổ console mới, hiện ra trên cùng ("" = tiêu đề rỗng, /wait = chờ cửa sổ đóng)
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", "", "/wait",
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
            }
            String status = Files.exists(resultFile)
                    ? Files.readString(resultFile, StandardCharsets.UTF_8).trim()
                    : "";
            System.out.println("[Firewall] configure result: '" + status + "'");
            if (isConfigured()) return Result.OK;
            return switch (status) {
                case "CANCELLED" -> Result.CANCELLED;
                case "FAILED" -> Result.FAILED;
                default -> Result.NO_RESPONSE;
            };
        } catch (IOException e) {
            System.err.println("[Firewall] Cannot run PowerShell: " + e.getMessage());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.NO_RESPONSE;
        } finally {
            try {
                Files.deleteIfExists(resultFile);
                Files.deleteIfExists(scriptFile);
            } catch (IOException ignored) {
            }
        }
    }

    /** Script chạy với quyền Admin: thêm rule cho java + ping, xoá rule Block */
    static String elevatedScript(String java) {
        return "$ErrorActionPreference = 'Stop'\n"
                + "try {\n"
                + "  $p = " + psQuote(java) + "\n"
                + "  Get-NetFirewallApplicationFilter -Program $p -ErrorAction SilentlyContinue"
                + " | Get-NetFirewallRule -ErrorAction SilentlyContinue"
                + " | Where-Object { $_.Direction -eq 'Inbound' -and ($_.Action -eq 'Block' -or $_.DisplayName -like " + psQuote(RULE_NAME + "*") + ") }"
                + " | Remove-NetFirewallRule\n"
                + "  New-NetFirewallRule -DisplayName " + psQuote(RULE_NAME)
                + " -Direction Inbound -Program $p -Action Allow -Profile Any | Out-Null\n"
                + "  Get-NetFirewallRule -DisplayName " + psQuote(PING_RULE_NAME) + " -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n"
                + "  New-NetFirewallRule -DisplayName " + psQuote(PING_RULE_NAME)
                + " -Direction Inbound -Protocol ICMPv4 -IcmpType 8 -Action Allow -Profile Any | Out-Null\n"
                + "  exit 0\n"
                + "} catch { exit 2 }";
    }

    /**
     * Script của cửa sổ hiện ra: hướng dẫn người dùng, xin quyền Admin (UAC hiện lên trước vì cửa sổ này đang
     * ở trên cùng), ghi kết quả OK / CANCELLED / FAILED vào {@code resultFile} để app đọc.
     */
    static String visibleScript(String java, Path resultFile) {
        String result = psQuote(resultFile.toString());
        return "$Host.UI.RawUI.WindowTitle = 'P2PFileShare - Tuong lua'\n"
                + "Write-Host ''\n"
                + "Write-Host '  P2PFileShare dang mo Windows Firewall cho Java va lenh ping.' -ForegroundColor Cyan\n"
                + "Write-Host '  Windows se hoi quyen Admin: hay bam YES.' -ForegroundColor Yellow\n"
                + "Write-Host '  (Khong thay hop thoai? Bam bieu tuong cai khien dang nhap nhay tren thanh taskbar.)'\n"
                + "try {\n"
                + "  $proc = Start-Process powershell -Verb RunAs -Wait -PassThru -WindowStyle Hidden"
                + " -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','" + encode(elevatedScript(java)) + "'\n"
                + "  $status = if ($proc.ExitCode -eq 0) { 'OK' } else { 'FAILED' }\n"
                + "} catch { $status = 'CANCELLED' }\n"
                + "[IO.File]::WriteAllText(" + result + ", $status)\n"
                + "if ($status -eq 'OK') { Write-Host '  Da cau hinh xong tuong lua.' -ForegroundColor Green }\n"
                + "elseif ($status -eq 'CANCELLED') { Write-Host '  Ban da bam No, tuong lua chua duoc cau hinh.' -ForegroundColor Red }\n"
                + "else { Write-Host '  Khong them duoc rule tuong lua (may co the do to chuc quan ly).' -ForegroundColor Red }\n"
                + "Start-Sleep -Seconds 3";
    }

    /** Chạy script PowerShell ẩn (chỉ đọc, không cần quyền Admin) */
    private static int runHidden(String script, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encode(script));
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
