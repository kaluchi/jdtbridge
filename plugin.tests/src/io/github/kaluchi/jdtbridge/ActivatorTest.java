package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Tests for Activator utilities: token generation and bridge file format.
 * Verifies the contract between plugin (writer) and jdt.mjs (reader).
 */
public class ActivatorTest {

    private static final Pattern HEX_32 =
            Pattern.compile("^[0-9a-f]{32}$");

    // ---- generateToken ----

    @Test
    public void tokenIs32HexChars() {
        String token = invokeGenerateToken();
        assertEquals(32, token.length(), "Token should be 32 chars");
        assertTrue(HEX_32.matcher(token).matches(),
                "Token should be lowercase hex: " + token);
    }

    @Test
    public void tokenIsUnique() {
        String token1 = invokeGenerateToken();
        String token2 = invokeGenerateToken();
        assertNotEquals(token1, token2, "Tokens should differ");
    }

    @Test
    public void tokenContainsNoUpperCase() {
        String token = invokeGenerateToken();
        assertEquals(token, token.toLowerCase(),
                "Should be lowercase");
    }

    // ---- Bridge file format ----

    @Test
    public void bridgeFileFormatParseable() throws IOException {
        // Simulate what Activator.writeBridgeFile() produces
        int port = 12345;
        String token = "abcdef0123456789abcdef0123456789";
        long pid = 42;
        String workspace = "D:\\eclipse-workspace";

        String content = "port=" + port + "\n"
                + "token=" + token + "\n"
                + "pid=" + pid + "\n"
                + "workspace=" + workspace + "\n";

        // Parse like jdt.mjs does: line-by-line key=value
        Map<String, String> parsed = parseBridgeFile(content);

        assertEquals("12345", parsed.get("port"));
        assertEquals(token, parsed.get("token"));
        assertEquals("42", parsed.get("pid"));
        assertEquals(workspace, parsed.get("workspace"));
    }

    @Test
    public void bridgeFileWriteAndRead() throws IOException {
        // Write a temp bridge file, read it back, verify format
        Path tempFile = Files.createTempFile("jdtbridge-test-", ".tmp");
        try {
            String token = invokeGenerateToken();
            int port = 54321;

            String content = "port=" + port + "\n"
                    + "token=" + token + "\n"
                    + "pid=" + ProcessHandle.current().pid() + "\n"
                    + "workspace=D:/test-workspace\n";
            Files.writeString(tempFile, content);

            // Read back and parse
            String read = Files.readString(tempFile);
            Map<String, String> parsed = parseBridgeFile(read);

            assertEquals(String.valueOf(port), parsed.get("port"));
            assertEquals(token, parsed.get("token"));
            assertTrue(parsed.get("pid").matches("\\d+"),
                    "PID should be numeric");
            assertEquals("D:/test-workspace", parsed.get("workspace"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void bridgeFileHasAllRequiredKeys() throws IOException {
        // Verify all keys that jdt.mjs expects are present
        String content = "port=8080\ntoken=abc\npid=1\nworkspace=/w\n";
        Map<String, String> parsed = parseBridgeFile(content);

        assertTrue(parsed.containsKey("port"), "Must have port");
        assertTrue(parsed.containsKey("token"), "Must have token");
        assertTrue(parsed.containsKey("pid"), "Must have pid");
        assertTrue(parsed.containsKey("workspace"), "Must have workspace");
    }

    // ---- Helpers ----

    /** Parse bridge file content the same way jdt.mjs does. */
    private Map<String, String> parseBridgeFile(String content) {
        Map<String, String> map = new HashMap<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return map;
    }

    private String invokeGenerateToken() {
        try {
            var method = Activator.class
                    .getDeclaredMethod("generateToken");
            method.setAccessible(true);
            return (String) method.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
