package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for TestHandler that require a real JDT workspace.
 */
public class TestHandlerIntegrationTest {

    private static final TestHandler handler = new TestHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
        TestFixture.createNonJavaProject();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    @Test
    public void missingParams() throws Exception {
        String json = handler.handleTestRun(Map.of());
        assertTrue(json.contains("error"),
                "Should return error: " + json);
        assertTrue(json.contains("Missing"),
                "Should mention missing param: " + json);
    }

    @Test
    public void typeNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "no.such.TestClass");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
        assertTrue(json.contains("target-not-found"),
                "Should mention target-not-found: " + json);
    }

    @Test
    public void projectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "nonexistent-project-xyz");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
        assertTrue(json.contains("target-not-found"),
                "Should mention target-not-found: " + json);
    }

    @Test
    public void notJavaProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", TestFixture.NON_JAVA_PROJECT_NAME);
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        // IJavaProject.exists() is false on a project without the
        // Java nature, so resolveContainerOrType skips it and the
        // resolve falls through to the target-not-found error —
        // same shape as a non-existent project name.
        assertTrue(json.contains("target-not-found"),
                "Non-Java project should be unresolvable: " + json);
    }

    @Test
    public void targetByMethod() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "test.edge.SimpleTest#onePlusOne");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "method target must launch: " + json);
        terminateLaunch(obj.get("configId").getAsString());
    }

    @Test
    public void targetByProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "jdtbridge-test");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "project target must launch: " + json);
        terminateLaunch(obj.get("configId").getAsString());
    }

    @Test
    public void targetByPackageWithProjectOverride() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "test.edge");
        params.put("project", "jdtbridge-test");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "package target with project override must launch: "
                + json);
        terminateLaunch(obj.get("configId").getAsString());
    }

    @Test
    public void targetByPackageInfersProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "test.edge");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "package target without project must infer: " + json);
        terminateLaunch(obj.get("configId").getAsString());
    }

    @Test
    public void targetByCompilationUnit() throws Exception {
        var root = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot();
        var file = root.getProject("jdtbridge-test")
                .getFile("src/test/edge/SimpleTest.java");
        Map<String, String> params = new HashMap<>();
        params.put("target", file.getLocation().toOSString());
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "file target must launch: " + json);
        terminateLaunch(obj.get("configId").getAsString());
    }

    @Test
    public void detectTestKindJunit5() throws Exception {
        var type = JdtUtils.findType("test.model.Dog");
        String kind = handler.detectTestKind(type);
        assertEquals("org.eclipse.jdt.junit.loader.junit5", kind);
    }

    @Test
    public void successfulLaunchOnTestFixtureClass() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "test.edge.SimpleTest");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "handleTestRun must succeed: " + json);
        assertTrue(obj.has("launchId"),
                "Response must carry launchId: " + json);
        assertTrue(obj.has("testRunId"),
                "Response must carry testRunId: " + json);
        assertEquals("JUnit 5", obj.get("runner").getAsString(),
                "Runner must resolve to JUnit 5 for SimpleTest: " + json);
        terminateLaunch(obj.get("configId").getAsString());
    }

    @Test
    public void coverageFalseDoesNotEnableCoverage() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "test.edge.SimpleTest");
        params.put("no-refresh", "");
        params.put("coverage", "false");
        String json = handler.handleTestRun(params);
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "coverage=false must still launch: " + json);
        assertFalse(obj.has("coverageId"),
                "coverage=false must not produce coverageId: " + json);
        terminateLaunch(obj.get("configId").getAsString());
    }

    @Test
    public void emptyTargetReturnsError() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "   ");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("Missing"),
                "blank target must fail: " + json);
    }

    private static void terminateLaunch(String configId)
            throws org.eclipse.debug.core.DebugException {
        var mgr = org.eclipse.debug.core.DebugPlugin
                .getDefault().getLaunchManager();
        for (var launch : mgr.getLaunches()) {
            var cfg = launch.getLaunchConfiguration();
            if (cfg != null && configId.equals(cfg.getName())
                    && !launch.isTerminated()) {
                launch.terminate();
            }
        }
    }
}
