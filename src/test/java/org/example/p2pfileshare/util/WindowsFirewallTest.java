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
    void encodeIsBase64OfUtf16le() {
        String script = "$p = 'D:\\Đồ án\\java.exe'";
        String decoded = new String(Base64.getDecoder().decode(WindowsFirewall.encode(script)), StandardCharsets.UTF_16LE);
        assertEquals(script, decoded);
    }
}
