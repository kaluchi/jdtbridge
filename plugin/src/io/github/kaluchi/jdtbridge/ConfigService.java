package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

class ConfigService {

    private final Path homeDir;
    private final Path configFile;

    ConfigService(Path homeDir) {
        this.homeDir = homeDir;
        this.configFile = homeDir.resolve("config.json");
    }

    boolean getBoolean(String key, boolean defaultValue) {
        var obj = load();
        return obj.has(key) ? obj.get(key).getAsBoolean()
                : defaultValue;
    }

    String getString(String key) {
        var obj = load();
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }

    void putBoolean(String key, boolean value)
            throws IOException {
        var obj = load();
        obj.addProperty(key, value);
        save(obj);
    }

    void putString(String key, String value)
            throws IOException {
        var obj = load();
        obj.addProperty(key, value);
        save(obj);
    }

    private JsonObject load() {
        try {
            if (Files.exists(configFile)) {
                return JsonParser.parseString(
                        Files.readString(configFile,
                                StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        } catch (Exception e) {
            Log.warn("Failed to read config", e);
        }
        return new JsonObject();
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
                        .filter(f -> f.toString()
                                .endsWith(".json"))
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
            var obj = JsonParser.parseString(
                    Files.readString(file,
                            StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int port = obj.has("port")
                    ? obj.get("port").getAsInt() : 0;
            String version = obj.has("version")
                    && !obj.get("version").isJsonNull()
                    ? obj.get("version").getAsString() : "";
            return new BridgeInfo(port, version);
        } catch (Exception e) {
            return new BridgeInfo(0, "");
        }
    }

    private void save(JsonObject config) throws IOException {
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile,
                config.toString() + "\n",
                StandardCharsets.UTF_8);
    }
}
