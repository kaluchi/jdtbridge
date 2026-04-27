package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    public void detectTestKindJunit5() throws Exception {
        // test project has JUnit 5 on classpath
        var type = JdtUtils.findType("test.model.Dog");
        String kind = handler.detectTestKind(type);
        assertEquals("org.eclipse.jdt.junit.loader.junit5", kind);
    }
}
