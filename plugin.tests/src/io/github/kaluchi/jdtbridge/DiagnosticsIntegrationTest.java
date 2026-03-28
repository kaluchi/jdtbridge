package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for DiagnosticsHandler using a real JDT workspace.
 * The test project has a BrokenClass with an intentional compilation error.
 */
@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")
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
        String json = handler.handleErrors(params);
        assertTrue(json.contains("BrokenClass"),
                "Should find error in BrokenClass: " + json);
        assertTrue(json.contains("\"severity\":\"ERROR\""),
                "Should be ERROR severity: " + json);
        assertTrue(json.contains("UnknownType"),
                "Should mention UnknownType: " + json);
    }

    @Test
    public void errorsCleanProject() throws Exception {
        // Filter by file that has no errors
        Map<String, String> params = new HashMap<>();
        params.put("file",
                "/" + TestFixture.PROJECT_NAME + "/src/test/model/Dog.java");
        String json = handler.handleErrors(params);
        assertEquals("[]", json, "Dog.java should have no errors");
    }

    @Test
    public void errorsWithWarnings() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        params.put("warnings", "");
        String json = handler.handleErrors(params);
        // Should at least find the ERROR
        assertTrue(json.contains("ERROR"),
                "Should find errors: " + json);
    }

    @Test
    public void errorsProjectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "no-such-project-xyz");
        String json = handler.handleErrors(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void buildIncremental() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        String json = handler.handleBuild(params);
        assertTrue(json.contains("\"errors\""),
                "Should return errors count: " + json);
    }

    @Test
    public void buildClean() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        params.put("clean", "");
        String json = handler.handleBuild(params);
        assertTrue(json.contains("\"errors\""),
                "Should return errors count: " + json);
        // BrokenClass has a compilation error
        assertTrue(json.contains("\"errors\":1") || json.contains("\"errors\": 1"),
                "Should have 1 error from BrokenClass: " + json);
    }

    @Test
    public void buildCleanWithoutProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("clean", "");
        String json = handler.handleBuild(params);
        assertTrue(json.contains("error"),
                "Should return error about missing project: " + json);
    }

    @Test
    public void buildProjectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "no-such-project-xyz");
        String json = handler.handleBuild(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    // ---- handleRefresh ----

    @Test
    public void refreshWorkspaceFile() throws Exception {
        // Get absolute path of a file in the test project
        var root = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot();
        var file = root.getProject(TestFixture.PROJECT_NAME)
                .getFile("src/test/model/Dog.java");
        assertTrue(file.exists(), "Dog.java should exist");
        String absPath = file.getLocation().toOSString();

        Map<String, String> params = new HashMap<>();
        params.put("file", absPath);
        String json = handler.handleRefresh(params);
        assertTrue(json.contains("\"refreshed\":true"),
                "Should refresh: " + json);
    }

    @Test
    public void refreshNonWorkspaceFile() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("file", "C:/nonexistent/Foo.java");
        String json = handler.handleRefresh(params);
        assertTrue(json.contains("\"refreshed\":false"),
                "Should not refresh: " + json);
        assertTrue(json.contains("not in workspace"),
                "Reason: " + json);
    }

    @Test
    public void refreshProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.PROJECT_NAME);
        String json = handler.handleRefresh(params);
        assertTrue(json.contains("\"refreshed\":true"),
                "Should refresh project: " + json);
        assertTrue(json.contains(TestFixture.PROJECT_NAME),
                "Should name project: " + json);
    }

    @Test
    public void refreshProjectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "nonexistent-xyz");
        String json = handler.handleRefresh(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void refreshWorkspace() throws Exception {
        Map<String, String> params = new HashMap<>();
        String json = handler.handleRefresh(params);
        assertTrue(json.contains("\"refreshed\":true"),
                "Should refresh workspace: " + json);
        assertTrue(json.contains("workspace"),
                "Should say workspace scope: " + json);
    }
}
