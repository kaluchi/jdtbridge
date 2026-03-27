package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the welcome/status HTML page and handles the dismiss action.
 * No auth required — loopback only, read-only page + boolean config.
 *
 * Reads port/version from the bridge instance files in ~/.jdtbridge/
 * rather than relying on static state.
 */
class WelcomeHandler {

    private static final String DISMISSED_KEY = "welcomeDismissed";

    private final ConfigService config;

    WelcomeHandler(ConfigService config) {
        this.config = config;
    }

    HttpServer.Response handleStatus() throws IOException {
        String template = loadResource("welcome.html");
        ConfigService.BridgeInfo info = config.readBridgeInfo();
        CliInfo cli = detectCli();
        String html = template
                .replace("{{version}}", info.version())
                .replace("{{port}}", String.valueOf(info.port()))
                .replace("{{cliInstalled}}",
                        String.valueOf(cli.installed))
                .replace("{{cliVersion}}", cli.version)
                .replace("{{dismissed}}",
                        String.valueOf(isDismissed()));
        return HttpServer.Response.html(html);
    }

    HttpServer.Response handleDismiss() {
        return setDismissed(true);
    }

    HttpServer.Response handleUndismiss() {
        return setDismissed(false);
    }

    private HttpServer.Response setDismissed(boolean value) {
        try {
            config.putBoolean(DISMISSED_KEY, value);
            return HttpServer.Response.json("{\"ok\":true}");
        } catch (IOException e) {
            Log.warn("Failed to save dismiss preference", e);
            return HttpServer.Response.json(
                    HttpServer.jsonError("Failed to save: " + e.getMessage()));
        }
    }

    boolean isDismissed() {
        return config.getBoolean(DISMISSED_KEY, false);
    }

    boolean isCliInstalled() {
        return detectCli().installed;
    }

    private static CliInfo detectCli() {
        try {
            boolean win = System.getProperty("os.name")
                    .toLowerCase().contains("win");
            String[] cmd = win
                    ? new String[]{"cmd", "/c", "npm", "list", "-g",
                            "@kaluchi/jdtbridge", "--json"}
                    : new String[]{"npm", "list", "-g",
                            "@kaluchi/jdtbridge", "--json"};
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            boolean exited = p.waitFor(10,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return new CliInfo(false, "");
            }
            var outer = JsonParser.parseString(output)
                    .getAsJsonObject();
            if (!outer.has("dependencies"))
                return new CliInfo(false, "");
            var deps = outer.getAsJsonObject("dependencies");
            if (!deps.has("@kaluchi/jdtbridge"))
                return new CliInfo(false, "");
            var pkg = deps.getAsJsonObject(
                    "@kaluchi/jdtbridge");
            String version = pkg.has("version")
                    ? pkg.get("version").getAsString()
                    : null;
            if (version != null && !version.isEmpty()) {
                return new CliInfo(true, version);
            }
            return new CliInfo(false, "");
        } catch (Exception e) {
            return new CliInfo(false, "");
        }
    }

    private record CliInfo(boolean installed, String version) {
    }

    private String loadResource(String name) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
