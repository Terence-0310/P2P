package org.example.p2pfileshare.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Ghép lệnh PowerShell: đường dẫn có khoảng trắng, dấu nháy, ký tự tiếng Việt không được làm hỏng lệnh. */
class WindowsFirewallTest {

    @Test
    void quoteEscapesSingleQuote() {
        assertEquals("'C:\\Program Files\\java.exe'", WindowsFirewall.psQuote("C:\\Program Files\\java.exe"));
        assertEquals("'C:\\O''Brien\\java.exe'", WindowsFirewall.psQuote("C:\\O'Brien\\java.exe"));
    }

    @Test
    void elevatedScriptAllowsJavaAndPingAndRemovesBlockRules() {
        String script = WindowsFirewall.elevatedScript("C:\\Program Files\\Java\\bin\\java.exe");
        assertTrue(script.contains("'C:\\Program Files\\Java\\bin\\java.exe'"));
        assertTrue(script.contains("-Program $p -Action Allow -Profile Any"));
        assertTrue(script.contains("-Protocol ICMPv4 -IcmpType 8 -Action Allow -Profile Any"), "phải cho phép ping");
        assertTrue(script.contains("$_.Action -eq 'Block'"), "phải xoá rule Block cũ");
    }

    @Test
    void visibleScriptWritesResultFileAndStaysUnderCmdLimit() {
        String script = WindowsFirewall.visibleScript("C:\\Java\\bin\\java.exe", java.nio.file.Path.of("C:\\Temp\\r.txt"));
        assertTrue(script.contains("-Verb RunAs"));
        assertTrue(script.contains("'C:\\Temp\\r.txt'"));
        // chỉ có chữ ASCII: cửa sổ console nào cũng hiển thị đúng
        assertTrue(script.chars().allMatch(c -> c < 128), "script hiện ra phải là ASCII");
    }

    @Test
    void encodeIsBase64OfUtf16le() {
        String script = "$p = 'D:\\Đồ án\\java.exe'";
        String decoded = new String(Base64.getDecoder().decode(WindowsFirewall.encode(script)), StandardCharsets.UTF_16LE);
        assertEquals(script, decoded);
    }
}
