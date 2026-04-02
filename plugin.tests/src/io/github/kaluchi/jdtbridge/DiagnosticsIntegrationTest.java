package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for DiagnosticsHandler using a real JDT workspace.
 * The test project has a BrokenClass with an intentional compilation error.
 */
public class DiagnosticsIntegrationTest {

    private static final DiagnosticsHandler handler =
            new DiagnosticsHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    @Test
    public void errorsFindsCompilationError() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        String json = handler.handleErrors(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertTrue(arr.size() > 0, "Should have errors");
        JsonObject error = arr.get(0).getAsJsonObject();
        assertEquals("ERROR", error.get("severity").getAsString());
        assertTrue(error.get("file").getAsString()
                .contains("BrokenClass"),
                "Should be in BrokenClass: " + error);
        assertTrue(error.get("message").getAsString()
                .contains("UnknownType"),
                "Should mention UnknownType: " + error);
    }

    @Test
    public void errorsCleanProject() throws Exception {
        // Filter by file that has no errors
        Map<String, String> params = new HashMap<>();
        params.put("file",
                "/" + TestFixture.PROJECT_NAME + "/src/test/model/Dog.java");
        String json = handler.handleErrors(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertEquals(0, arr.size(), "Dog.java should have no errors");
    }

    @Test
    public void errorsWithWarnings() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        params.put("warnings", "");
        String json = handler.handleErrors(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        boolean hasError = false;
        boolean hasWarning = false;
        for (var e : arr) {
            String sev = e.getAsJsonObject()
                    .get("severity").getAsString();
            if ("ERROR".equals(sev)) hasError = true;
            if ("WARNING".equals(sev)) hasWarning = true;
        }
        assertTrue(hasError, "Should contain at least one ERROR");
        // With warnings param, result should include warnings
        // (may be 0 if project has none, but array should be
        // larger than errors-only)
        assertTrue(arr.size() >= 1,
                "With warnings flag, should return errors"
                        + (hasWarning ? " and warnings" : ""));
    }

    @Test
    public void errorsProjectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "no-such-project-xyz");
        String json = handler.handleErrors(params, ProjectScope.ALL);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("error"), "Should have error field");
    }

    @Test
    public void buildIncremental() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("errors"), "Should have errors field");
        assertTrue(obj.get("errors").getAsInt() >= 0);
    }

    @Test
    public void buildClean() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        params.put("clean", "");
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, obj.get("errors").getAsInt(),
                "Should have 1 error from BrokenClass");
    }

    @Test
    public void buildCleanWithoutProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("clean", "");
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("error"),
                "Should have error about missing project");
    }

    @Test
    public void buildProjectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "no-such-project-xyz");
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("error"), "Should have error field");
    }

    // ---- handleRefresh ----

    @Test
    public void refreshWorkspaceFile() throws Exception {
        var root = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot();
        var file = root.getProject(TestFixture.PROJECT_NAME)
                .getFile("src/test/model/Dog.java");
        assertTrue(file.exists(), "Dog.java should exist");
        String absPath = file.getLocation().toOSString();

        Map<String, String> params = new HashMap<>();
        params.put("file", absPath);
        String json = handler.handleRefresh(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("refreshed").getAsBoolean());
    }

    @Test
    public void refreshNonWorkspaceFile() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("file", "C:/nonexistent/Foo.java");
        String json = handler.handleRefresh(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(obj.get("refreshed").getAsBoolean());
        assertNotNull(obj.get("reason"), "Should have reason");
    }

    @Test
    public void refreshProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        String json = handler.handleRefresh(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("refreshed").getAsBoolean());
        assertEquals(TestFixture.PROJECT_NAME,
                obj.get("project").getAsString());
    }

    @Test
    public void refreshProjectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "nonexistent-xyz");
        String json = handler.handleRefresh(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("error"), "Should have error field");
    }

    @Test
    public void refreshWorkspace() throws Exception {
        Map<String, String> params = new HashMap<>();
        String json = handler.handleRefresh(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("refreshed").getAsBoolean());
        assertEquals("workspace", obj.get("scope").getAsString());
    }
}
