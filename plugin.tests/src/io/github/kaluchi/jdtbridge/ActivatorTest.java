package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

public class ActivatorTest {

    private static final Pattern HEX_32 =
            Pattern.compile("^[0-9a-f]{32}$");

    @Test
    public void tokenIs32HexChars() {
        String token = Activator.generateToken();
        assertEquals(32, token.length());
        assertTrue(HEX_32.matcher(token).matches());
    }

    @Test
    public void tokenIsUnique() {
        assertNotEquals(Activator.generateToken(),
                Activator.generateToken());
    }

    @Test
    public void bridgeFileJsonFormat() {
        var obj = new JsonObject();
        obj.addProperty("port", 12345);
        obj.addProperty("token",
                "abcdef0123456789abcdef0123456789");
        obj.addProperty("pid", 42L);
        obj.addProperty("workspace", "D:\\eclipse-workspace");
        obj.addProperty("version", "1.0.0.202603181541");
        obj.addProperty("location",
                "reference:file:plugins/io.github.kaluchi"
                + ".jdtbridge_1.0.0.jar");
        String json = obj.toString();

        var parsed = JsonParser.parseString(json)
                .getAsJsonObject();
        assertEquals(12345, parsed.get("port").getAsInt());
        assertEquals("abcdef0123456789abcdef0123456789",
                parsed.get("token").getAsString());
        assertEquals("D:\\eclipse-workspace",
                parsed.get("workspace").getAsString());
        assertEquals("1.0.0.202603181541",
                parsed.get("version").getAsString());
        assertTrue(parsed.get("location").getAsString()
                .startsWith("reference:file:plugins/"));
    }

    @Test
    public void bridgeFileWriteAndRead() throws IOException {
        Path tempFile = Files.createTempFile(
                "jdtbridge-test-", ".json");
        try {
            String token = Activator.generateToken();
            var obj = new JsonObject();
            obj.addProperty("port", 54321);
            obj.addProperty("token", token);
            obj.addProperty("pid",
                    ProcessHandle.current().pid());
            obj.addProperty("workspace", "D:/test-workspace");
            obj.addProperty("version", "1.0.0.qualifier");
            obj.addProperty("location",
                    "reference:file:dropins/jdtbridge.jar");
            Files.writeString(tempFile,
                    obj.toString() + "\n");

            var parsed = JsonParser.parseString(
                    Files.readString(tempFile))
                    .getAsJsonObject();
            assertEquals(54321,
                    parsed.get("port").getAsInt());
            assertEquals(token,
                    parsed.get("token").getAsString());
            assertEquals("1.0.0.qualifier",
                    parsed.get("version").getAsString());
            assertTrue(parsed.get("location").getAsString()
                    .startsWith("reference:file:dropins/"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void bridgeFileHasAllRequiredKeys() {
        var obj = new JsonObject();
        obj.addProperty("port", 8080);
        obj.addProperty("token", "abc");
        obj.addProperty("pid", 1L);
        obj.addProperty("workspace", "/w");
        obj.addProperty("version", "1.0.0");
        obj.addProperty("location",
                "file:plugins/bundle.jar");
        String json = obj.toString();

        var parsed = JsonParser.parseString(json)
                .getAsJsonObject();
        assertNotEquals(0, parsed.get("port").getAsInt());
        assertNotNull(parsed.get("token").getAsString());
        assertTrue(parsed.has("pid"));
        assertNotNull(parsed.get("workspace").getAsString());
        assertNotNull(parsed.get("version").getAsString());
        assertNotNull(parsed.get("location").getAsString());
    }

    private static final Pattern HEX_12 =
            Pattern.compile("^[0-9a-f]{12}$");

    @Test
    public void workspaceHashIsTwelveLowercaseHex() {
        String h = Activator.workspaceHash("/some/path");
        assertEquals(12, h.length());
        assertTrue(HEX_12.matcher(h).matches(),
                "Should be 12 hex chars: " + h);
    }

    @Test
    public void workspaceHashIsDeterministic() {
        assertEquals(
                Activator.workspaceHash("/x"),
                Activator.workspaceHash("/x"));
    }

    @Test
    public void workspaceHashDiffersForDifferentInputs() {
        assertNotEquals(
                Activator.workspaceHash("/a"),
                Activator.workspaceHash("/b"));
    }

    @Test
    public void workspaceHashOfEmptyStringIsKnownPrefix() {
        // SHA-256("") =
        // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        // First 6 bytes hex-encoded:
        assertEquals("e3b0c44298fc",
                Activator.workspaceHash(""));
    }

    @Test
    public void workspaceHashHandlesUtf8() {
        // Non-ASCII input must round-trip through UTF-8 cleanly.
        String h = Activator.workspaceHash("проект/тест");
        assertEquals(12, h.length());
        assertTrue(HEX_12.matcher(h).matches());
    }
}
