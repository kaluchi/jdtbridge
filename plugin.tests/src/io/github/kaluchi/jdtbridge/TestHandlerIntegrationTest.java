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

    private static final TestHandler handler = new TestHandler(new TestSessionTracker());

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
        params.put("class", "no.such.TestClass");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
        assertTrue(json.contains("not found"),
                "Should mention not found: " + json);
    }

    @Test
    public void projectNotFound() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", "nonexistent-project-xyz");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void notJavaProject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("project", TestFixture.NON_JAVA_PROJECT_NAME);
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("error"),
                "Should return error: " + json);
        assertTrue(json.contains("Not a Java project"),
                "Should say not Java: " + json);
    }

    @Test
    public void detectTestKindJunit5() throws Exception {
        // test project has JUnit 5 on classpath
        Object type = invokeFindType("test.model.Dog");
        String kind = invokeDetectTestKind(type);
        assertEquals("org.eclipse.jdt.junit.loader.junit5", kind);
    }

    private Object invokeFindType(String fqn) {
        try {
            Class<?> clazz = Class.forName(
                    "io.github.kaluchi.jdtbridge.JdtUtils");
            var method = clazz
                    .getDeclaredMethod("findType", String.class);
            method.setAccessible(true);
            return method.invoke(null, fqn);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String invokeDetectTestKind(Object type) {
        try {
            var method = TestHandler.class
                    .getDeclaredMethod("detectTestKind",
                            org.eclipse.jdt.core.IType.class);
            method.setAccessible(true);
            return (String) method.invoke(handler, type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
