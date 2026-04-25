package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.eclemma.core.CoverageTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.kaluchi.jdtbridge.TestFixture;
import io.github.kaluchi.jdtbridge.TestHandler;

/**
 * Tests for the {@code coverage=true} flag on {@code /test/run}
 * (handled in {@link TestHandler#handleTestRun}). Validation paths
 * are exercised against the {@link TestFixture} workspace project;
 * happy-path tests skip unless the local Eclipse runtime has a
 * coverage delegate registered for the JUnit launch type (which it
 * does whenever EclEmma is installed).
 */
public class TestRunCoverageFlagTest {

    private static final TestHandler handler = new TestHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    @AfterEach
    void cleanupCoverageSessions() {
        // Active coverage launches and their sessions accumulate
        // across tests in the same Eclipse runtime — drop them.
        CoverageTools.getSessionManager().removeAllSessions();
    }

    @Nested
    class FlagParsing {

        @Test
        void coverageAbsentDoesNotEnableCoverage() throws Exception {
            // No coverage param → response must not carry the
            // coverage fields, regardless of the prepare result.
            Map<String, String> params = new HashMap<>();
            params.put("class", "no.such.TestClass");
            params.put("no-refresh", "");
            String json = handler.handleTestRun(params);
            assertFalse(json.contains("\"coverageId\""),
                    "No coverage param → no coverageId: " + json);
            assertFalse(json.contains("\"launchMode\""),
                    "No coverage param → no launchMode: " + json);
        }

        @Test
        void coverageFalseDoesNotEnableCoverage() throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("class", "no.such.TestClass");
            params.put("no-refresh", "");
            params.put("coverage", "false");
            String json = handler.handleTestRun(params);
            assertFalse(json.contains("\"coverageId\""),
                    "coverage=false → no coverageId: " + json);
        }
    }

    @Nested
    class ErrorPaths {

        @Test
        void unknownClassReturnsTypeNotFound() throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("class", "no.such.TestClass");
            params.put("no-refresh", "");
            params.put("coverage", "true");
            String json = handler.handleTestRun(params);
            // prepareLaunch fails first → "Type not found" surfaces
            // before coverage-mode-not-supported has a chance.
            assertTrue(json.contains("not found"),
                    "Should report type not found: " + json);
        }

        @Test
        void unknownProjectReturnsProjectNotFound() throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("project", "nonexistent-project-xyz");
            params.put("no-refresh", "");
            params.put("coverage", "true");
            String json = handler.handleTestRun(params);
            assertTrue(json.contains("error"),
                    "Should error: " + json);
        }
    }

    @Nested
    class HappyPath {

        @Test
        void coverageTrueLaunchesInCoverageMode() throws Exception {
            // Skip when the headless runtime can't actually drive
            // a coverage launch end-to-end. Two preconditions:
            // (a) JUnit type has a coverage delegate registered;
            // (b) EclEmma UI bundle is activated (the launch path
            //     goes through UIPreferences for default scope).
            if (!CoverageTypes.isSupported(
                    "org.eclipse.jdt.junit.launchconfig")) {
                return;
            }
            org.osgi.framework.Bundle eclemmaUi =
                    org.eclipse.core.runtime.Platform.getBundle(
                            "org.eclipse.eclemma.ui");
            if (eclemmaUi == null
                    || eclemmaUi.getState()
                            < org.osgi.framework.Bundle.ACTIVE) {
                return;
            }
            Map<String, String> params = new HashMap<>();
            params.put("class", "test.edge.SimpleTest");
            params.put("no-refresh", "");
            params.put("coverage", "true");
            String json = handler.handleTestRun(params);
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.get("ok").getAsBoolean(),
                    "Expected ok: " + json);
            assertEquals("coverage",
                    obj.get("launchMode").getAsString());
            assertTrue(obj.has("coverageId"),
                    "Expected coverageId: " + json);
            assertEquals(obj.get("testRunId").getAsString(),
                    obj.get("coverageId").getAsString(),
                    "coverageId must equal testRunId byte-for-byte");
        }
    }
}
