package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

public class WelcomeHandlerTest {

    @Test
    public void npmListDetectsInstalledPackage() {
        String npmOutput = """
                {
                  "name": "npm",
                  "dependencies": {
                    "@kaluchi/jdtbridge": {
                      "version": "1.1.1",
                      "resolved": "file:cli"
                    }
                  }
                }
                """;
        var json = JsonParser.parseString(npmOutput)
                .getAsJsonObject();
        var deps = json.getAsJsonObject("dependencies");
        assertNotNull(deps);
        var pkg = deps.getAsJsonObject("@kaluchi/jdtbridge");
        assertNotNull(pkg);
        assertEquals("1.1.1",
                pkg.get("version").getAsString());
    }

    @Test
    public void npmListEmptyDependencies() {
        var json = JsonParser.parseString(
                "{\"dependencies\":{}}").getAsJsonObject();
        var deps = json.getAsJsonObject("dependencies");
        assertEquals(0, deps.size());
    }

    @Test
    public void npmListNoDependenciesKey() {
        var json = JsonParser.parseString(
                "{\"name\":\"npm\"}").getAsJsonObject();
        assertFalse(json.has("dependencies"));
    }

    @Test
    public void bridgeFileExtractsPortAndVersion() {
        var obj = new JsonObject();
        obj.addProperty("port", 54321);
        obj.addProperty("token", "abc");
        obj.addProperty("pid", 100L);
        obj.addProperty("workspace", "/ws");
        obj.addProperty("version", "1.1.0.202603231744");
        obj.addProperty("location",
                "file:plugins/bundle.jar");

        var parsed = JsonParser.parseString(obj.toString())
                .getAsJsonObject();
        assertEquals(54321, parsed.get("port").getAsInt());
        assertEquals("1.1.0.202603231744",
                parsed.get("version").getAsString());
    }

    @Test
    public void bridgeFileMissingVersionReturnsNull() {
        var obj = new JsonObject();
        obj.addProperty("port", 8080);
        obj.addProperty("token", "abc");

        var parsed = JsonParser.parseString(obj.toString())
                .getAsJsonObject();
        assertEquals(8080, parsed.get("port").getAsInt());
        assertNull(parsed.get("version"));
    }

    @Test
    public void configDismissedTrue() {
        var json = JsonParser.parseString(
                "{\"eclipse\":\"D:/eclipse\","
                + "\"welcomeDismissed\":true}")
                .getAsJsonObject();
        assertTrue(json.get("welcomeDismissed")
                .getAsBoolean());
    }

    @Test
    public void configDismissedFalse() {
        var json = JsonParser.parseString(
                "{\"welcomeDismissed\":false}")
                .getAsJsonObject();
        assertFalse(json.get("welcomeDismissed")
                .getAsBoolean());
    }

    @Test
    public void configDismissedMissing() {
        var json = JsonParser.parseString(
                "{\"eclipse\":\"D:/eclipse\"}")
                .getAsJsonObject();
        assertFalse(json.has("welcomeDismissed"));
    }

    @Test
    public void configDismissedWithWhitespace() {
        var json = JsonParser.parseString(
                "{ \"welcomeDismissed\" : true }")
                .getAsJsonObject();
        assertTrue(json.get("welcomeDismissed")
                .getAsBoolean());
    }
}
