package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ConfigService — config.json read/write/roundtrip.
 */
public class ConfigServiceTest {

    @TempDir
    Path tempDir;
    ConfigService config;

    @BeforeEach
    void setUp() {
        config = new ConfigService(tempDir);
    }

    @Nested
    class GetBoolean {

        @Test
        void defaultWhenNoFile() {
            assertFalse(config.getBoolean("key", false));
            assertTrue(config.getBoolean("key", true));
        }

        @Test
        void defaultWhenKeyMissing() throws IOException {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"other\":true}");
            assertFalse(config.getBoolean("key", false));
        }

        @Test
        void readsTrue() throws IOException {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"flag\":true}");
            assertTrue(config.getBoolean("flag", false));
        }

        @Test
        void readsFalse() throws IOException {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"flag\":false}");
            assertFalse(config.getBoolean("flag", true));
        }
    }

    @Nested
    class GetString {

        @Test
        void nullWhenNoFile() {
            assertNull(config.getString("key"));
        }

        @Test
        void nullWhenKeyMissing() throws IOException {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"other\":\"val\"}");
            assertNull(config.getString("key"));
        }

        @Test
        void readsValue() throws IOException {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"path\":\"D:/eclipse\"}");
            assertEquals("D:/eclipse", config.getString("path"));
        }
    }

    @Nested
    class PutBoolean {

        @Test
        void createsFileIfMissing() throws IOException {
            config.putBoolean("dismissed", true);
            assertTrue(Files.exists(tempDir.resolve("config.json")));
            assertTrue(config.getBoolean("dismissed", false));
        }

        @Test
        void preservesExistingKeys() throws Exception {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"eclipse\":\"D:/eclipse\"}");
            config.putBoolean("dismissed", true);
            assertEquals("D:/eclipse", config.getString("eclipse"));
            assertTrue(config.getBoolean("dismissed", false));
        }

        @Test
        void overwritesExistingValue() throws Exception {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"dismissed\":true}");
            config.putBoolean("dismissed", false);
            assertFalse(config.getBoolean("dismissed", true));
        }
    }

    @Nested
    class PutString {

        @Test
        void createsFileIfMissing() throws IOException {
            config.putString("eclipse", "/opt/eclipse");
            assertEquals("/opt/eclipse",
                    config.getString("eclipse"));
        }

        @Test
        void preservesExistingKeys() throws Exception {
            Files.writeString(tempDir.resolve("config.json"),
                    "{\"dismissed\":true}");
            config.putString("eclipse", "/opt/eclipse");
            assertTrue(config.getBoolean("dismissed", false));
            assertEquals("/opt/eclipse",
                    config.getString("eclipse"));
        }
    }

    @Nested
    class ReadBridgeInfo {

        @Test
        void noInstancesDir() {
            var info = config.readBridgeInfo();
            assertEquals(0, info.port());
            assertEquals("", info.version());
        }

        @Test
        void emptyInstancesDir() throws IOException {
            Files.createDirectories(tempDir.resolve("instances"));
            var info = config.readBridgeInfo();
            assertEquals(0, info.port());
        }

        @Test
        void readsPortAndVersion() throws IOException {
            Path instances = Files.createDirectories(
                    tempDir.resolve("instances"));
            Files.writeString(instances.resolve("test.json"),
                    "{\"port\":54321,\"version\":\"1.2.0\","
                            + "\"token\":\"abc\"}");
            var info = config.readBridgeInfo();
            assertEquals(54321, info.port());
            assertEquals("1.2.0", info.version());
        }

        @Test
        void corruptInstanceFile() throws IOException {
            Path instances = Files.createDirectories(
                    tempDir.resolve("instances"));
            Files.writeString(instances.resolve("bad.json"),
                    "not json");
            var info = config.readBridgeInfo();
            assertEquals(0, info.port());
        }

        @Test
        void ignoresNonJsonFiles() throws IOException {
            Path instances = Files.createDirectories(
                    tempDir.resolve("instances"));
            Files.writeString(instances.resolve("notes.txt"),
                    "not a bridge file");
            var info = config.readBridgeInfo();
            assertEquals(0, info.port());
        }
    }

    @Nested
    class Roundtrip {

        @Test
        void multipleKeyTypes() throws Exception {
            config.putString("eclipse", "D:/eclipse");
            config.putBoolean("dismissed", true);
            config.putString("theme", "dark");

            assertEquals("D:/eclipse",
                    config.getString("eclipse"));
            assertTrue(config.getBoolean("dismissed", false));
            assertEquals("dark", config.getString("theme"));
        }

        @Test
        void validJsonOnDisk() throws Exception {
            config.putString("path", "C:/test");
            config.putBoolean("flag", true);

            String raw = Files.readString(
                    tempDir.resolve("config.json"),
                    StandardCharsets.UTF_8).trim();
            assertTrue(raw.startsWith("{"),
                    "Should be JSON object: " + raw);
            assertTrue(raw.contains("\"path\":\"C:/test\""),
                    "Should contain path: " + raw);
            assertTrue(raw.contains("\"flag\":true"),
                    "Should contain flag: " + raw);
        }

        @Test
        void corruptFileReturnsDefaults() throws IOException {
            Files.writeString(tempDir.resolve("config.json"),
                    "not json at all");
            assertFalse(config.getBoolean("key", false));
            assertNull(config.getString("key"));
        }

        @Test
        void emptyFileReturnsDefaults() throws IOException {
            Files.writeString(tempDir.resolve("config.json"), "");
            assertFalse(config.getBoolean("key", false));
            assertNull(config.getString("key"));
        }
    }
}
