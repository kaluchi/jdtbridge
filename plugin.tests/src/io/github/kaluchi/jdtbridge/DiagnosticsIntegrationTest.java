package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertFalse(arr.isEmpty(), "Should have errors");
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
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertEquals(0, arr.size(), "Dog.java should have no errors");
    }

    @Test
    public void errorsWithWarnings() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        params.put("warnings", "");
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertFalse(arr.isEmpty(),
                "With warnings flag, should return results");
        assertTrue(json.contains("\"severity\":\"ERROR\""),
                "Should contain at least one ERROR");
    }

    @Test
    public void errorsBlankFileParamFallsToWorkspace()
            throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("file", "  ");
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertFalse(arr.isEmpty(),
                "Blank file → workspace scope should find errors");
    }

    @Test
    public void errorsBlankProjectParamFallsToWorkspace()
            throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "");
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertFalse(arr.isEmpty(),
                "Blank project → workspace scope should find errors");
    }

    @Test
    public void buildBlankProjectRunsWorkspaceIncremental()
            throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", " ");
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNull(obj.get("error"));
        assertNotNull(obj.get("errors"));
    }

    @Test
    public void errorsProjectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "no-such-project-xyz");
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("error"), "Should have error field");
    }

    @Test
    public void errorsFileNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("file", "/no/such/path/Foo.java");
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.has("error"),
                "Missing file should error: " + obj);
        assertTrue(obj.get("error").getAsString()
                        .contains("Resource not found"),
                "Error mentions missing resource: " + obj);
    }

    @Test
    public void errorsWorkspaceWideAggregatesAllProjects()
            throws Exception {
        String json = handler.handleProblems(
                new HashMap<>(), ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertFalse(arr.isEmpty(),
                "Workspace-wide scope should find errors");
        assertTrue(json.contains("BrokenClass"),
                "Should surface BrokenClass: " + arr);
    }

    @Test
    public void buildIncrementalWithoutProjectRunsWorkspaceWide()
            throws Exception {
        // No project + no clean → workspace-wide INCREMENTAL_BUILD.
        Map<String, String> params = new HashMap<>();
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNull(obj.get("error"),
                "Workspace incremental must not error: " + obj);
        assertNotNull(obj.get("errors"),
                "Should report errors count");
    }

    @Test
    public void errorsAllAttachesMarkerOrigin() throws Exception {
        // The `all` flag widens the marker scope from JDT-only to
        // every IMarker.PROBLEM (covers the includeSource branch
        // that decorates each entry with its marker type).
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        params.put("all", "");
        String json = handler.handleProblems(params, ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertFalse(arr.isEmpty(), "expected fixture errors");
        JsonObject first = arr.get(0).getAsJsonObject();
        assertTrue(first.has("source"),
                "all flag must attach marker origin: " + first);
    }

    @Test
    public void buildIncremental() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("errors"), "Should have errors field");
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
    public void buildCleanWithoutProjectRunsWorkspaceWide()
            throws Exception {
        // clean without project = Project > Clean > Clean all
        // projects: workspace-wide CLEAN_BUILD + FULL_BUILD.
        // Verifies the call returns an errors count and no error.
        Map<String, String> params = new HashMap<>();
        params.put("clean", "");
        String json = handler.handleBuild(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNull(obj.get("error"),
                "Workspace clean should not return an error");
        assertNotNull(obj.get("errors"),
                "Should report errors count");
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
    public void refreshBlankFileRefreshesWorkspace()
            throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("file", "");
        String json = handler.handleRefresh(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("refreshed").getAsBoolean());
        assertEquals("workspace", obj.get("scope").getAsString());
    }

    @Test
    public void refreshBlankProjectRefreshesWorkspace()
            throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "  ");
        String json = handler.handleRefresh(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("refreshed").getAsBoolean());
        assertEquals("workspace", obj.get("scope").getAsString());
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
