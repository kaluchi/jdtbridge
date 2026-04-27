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
import org.junit.jupiter.api.condition.EnabledIf;

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

        @Test
        void coverageEmptyDoesNotEnableCoverage() throws Exception {
            // Empty value (?coverage=) deliberately does NOT
            // enable coverage — fix for the empty-string surprise
            // flagged in PR review.
            Map<String, String> params = new HashMap<>();
            params.put("class", "no.such.TestClass");
            params.put("no-refresh", "");
            params.put("coverage", "");
            String json = handler.handleTestRun(params);
            assertFalse(json.contains("\"coverageId\""),
                    "coverage='' → no coverageId: " + json);
            assertFalse(json.contains("\"launchMode\""),
                    "coverage='' → no launchMode: " + json);
        }

        @Test
        @EnabledIf("io.github.kaluchi.jdtbridge.IntegrationGuards#canRunJunitCoverageLaunch")
        void coverageOneEnablesCoverage() throws Exception {
            // Spec accepts "true" or "1".
            Map<String, String> params = new HashMap<>();
            params.put("class", "test.edge.SimpleTest");
            params.put("no-refresh", "");
            params.put("coverage", "1");
            String json = handler.handleTestRun(params);
            assertTrue(json.contains("\"launchMode\":\"coverage\""),
                    "coverage=1 must enable coverage mode: " + json);
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
        @EnabledIf("io.github.kaluchi.jdtbridge.IntegrationGuards#canRunJunitCoverageLaunch")
        void coverageTrueLaunchesInCoverageMode() throws Exception {
            // End-to-end coverage launch needs the JUnit coverage
            // delegate registered AND the EclEmma UI bundle active —
            // gated above.
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
