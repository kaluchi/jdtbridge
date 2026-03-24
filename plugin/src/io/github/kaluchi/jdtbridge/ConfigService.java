package io.github.kaluchi.jdtbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages persistent configuration stored in {@code config.json}
 * under the JDT Bridge home directory ({@code ~/.jdtbridge/}).
 *
 * <p>All config access goes through this service — no other class
 * should read or write config.json directly.
 */
class ConfigService {

    private final Path homeDir;
    private final Path configFile;

    ConfigService(Path homeDir) {
        this.homeDir = homeDir;
        this.configFile = homeDir.resolve("config.json");
    }

    boolean getBoolean(String key, boolean defaultValue) {
        return Json.getBool(load(), key, defaultValue);
    }

    String getString(String key) {
        return Json.getString(load(), key);
    }

    void putBoolean(String key, boolean value) throws IOException {
        Map<String, Object> config = load();
        config.put(key, value);
        save(config);
    }

    void putString(String key, String value) throws IOException {
        Map<String, Object> config = load();
        config.put(key, value);
        save(config);
    }

    private Map<String, Object> load() {
        try {
            if (Files.exists(configFile)) {
                return Json.parse(Files.readString(configFile,
                        StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.warn("Failed to read config", e);
        }
        return new LinkedHashMap<>();
    }

    record BridgeInfo(int port, String version) {
    }

    BridgeInfo readBridgeInfo() {
        try {
            Path instances = homeDir.resolve("instances");
            if (!Files.isDirectory(instances)) {
                return new BridgeInfo(0, "");
            }
            try (var files = Files.list(instances)) {
                return files
                        .filter(f -> f.toString().endsWith(".json"))
                        .findFirst()
                        .map(this::parseBridgeFile)
                        .orElse(new BridgeInfo(0, ""));
            }
        } catch (IOException e) {
            return new BridgeInfo(0, "");
        }
    }

    private BridgeInfo parseBridgeFile(Path file) {
        try {
            var map = Json.parse(Files.readString(file,
                    StandardCharsets.UTF_8));
            int port = Json.getInt(map, "port", 0);
            String version = Json.getString(map, "version");
            return new BridgeInfo(port,
                    version != null ? version : "");
        } catch (IOException e) {
            return new BridgeInfo(0, "");
        }
    }

    private void save(Map<String, Object> config) throws IOException {
        Files.createDirectories(configFile.getParent());
        Json builder = Json.object();
        for (var entry : config.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String s)
                builder.put(entry.getKey(), s);
            else if (v instanceof Boolean b)
                builder.put(entry.getKey(), b);
            else if (v instanceof Integer i)
                builder.put(entry.getKey(), i);
            else if (v instanceof Long l)
                builder.put(entry.getKey(), l);
            else if (v instanceof Double d)
                builder.put(entry.getKey(), d);
            else if (v == null)
                builder.put(entry.getKey(), (String) null);
        }
        Files.writeString(configFile, builder.toString() + "\n",
                StandardCharsets.UTF_8);
    }
}
