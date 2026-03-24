package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for WelcomeHandler — CLI detection parsing via Json.parse(),
 * bridge file parsing, and config logic.
 */
public class WelcomeHandlerTest {

    // ---- npm list JSON → CLI detection ----

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
        var outer = Json.parse(npmOutput);
        String depsRaw = Json.getString(outer, "dependencies");
        assertNotNull(depsRaw);
        var deps = Json.parse(depsRaw);
        String pkgRaw = Json.getString(deps, "@kaluchi/jdtbridge");
        assertNotNull(pkgRaw);
        var pkg = Json.parse(pkgRaw);
        assertEquals("1.1.1", Json.getString(pkg, "version"));
    }

    @Test
    public void npmListEmptyDependencies() {
        var outer = Json.parse("{\"dependencies\":{}}");
        String depsRaw = Json.getString(outer, "dependencies");
        assertNotNull(depsRaw);
        var deps = Json.parse(depsRaw);
        assertTrue(deps.isEmpty());
    }

    @Test
    public void npmListNoDependenciesKey() {
        var outer = Json.parse("{\"name\":\"npm\"}");
        String depsRaw = Json.getString(outer, "dependencies");
        // "dependencies" not present — getString returns null
        assertEquals(null, depsRaw);
    }

    // ---- Bridge file parsing ----

    @Test
    public void bridgeFileExtractsPortAndVersion() {
        String json = Json.object()
                .put("port", 54321)
                .put("token", "abc")
                .put("pid", 100L)
                .put("workspace", "/ws")
                .put("version", "1.1.0.202603231744")
                .put("location", "file:plugins/bundle.jar")
                .toString();
        var map = Json.parse(json);
        assertEquals(54321, Json.getInt(map, "port", 0));
        assertEquals("1.1.0.202603231744",
                Json.getString(map, "version"));
    }

    @Test
    public void bridgeFileMissingVersionReturnsNull() {
        String json = Json.object()
                .put("port", 8080)
                .put("token", "abc")
                .toString();
        var map = Json.parse(json);
        assertEquals(8080, Json.getInt(map, "port", 0));
        assertEquals(null, Json.getString(map, "version"));
    }

    // ---- Config / dismiss detection ----

    @Test
    public void configDismissedTrue() {
        String config = "{\"eclipse\":\"D:/eclipse\","
                + "\"welcomeDismissed\":true}";
        var map = Json.parse(config);
        assertTrue(Json.getBool(map, "welcomeDismissed", false));
    }

    @Test
    public void configDismissedFalse() {
        String config = "{\"welcomeDismissed\":false}";
        var map = Json.parse(config);
        assertFalse(Json.getBool(map, "welcomeDismissed", false));
    }

    @Test
    public void configDismissedMissing() {
        String config = "{\"eclipse\":\"D:/eclipse\"}";
        var map = Json.parse(config);
        assertFalse(Json.getBool(map, "welcomeDismissed", false));
    }

    @Test
    public void configDismissedWithWhitespace() {
        String config = "{ \"welcomeDismissed\" : true }";
        var map = Json.parse(config);
        assertTrue(Json.getBool(map, "welcomeDismissed", false));
    }

    // ---- Config roundtrip via ConfigService ----
    // (Detailed roundtrip tests are in ConfigServiceTest)
}
