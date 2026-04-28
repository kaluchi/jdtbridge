package io.github.kaluchi.jdtbridge.coverage;

import io.github.kaluchi.jdtbridge.support.TestFixture;

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

import io.github.kaluchi.jdtbridge.TestHandler;

/**
 * Tests for the {@code coverage=true} flag on {@code /test/run}
 * (handled in {@link TestHandler#handleTestRun}). Validation paths
 * exercised against the {@link TestFixture} workspace project — no
 * launches involved. Live coverage launches live in
 * {@code TestRunCoverageLiveTest} (UI runtime).
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
            params.put("target", "no.such.TestClass");
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
            params.put("target", "no.such.TestClass");
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
            params.put("target", "no.such.TestClass");
            params.put("no-refresh", "");
            params.put("coverage", "");
            String json = handler.handleTestRun(params);
            assertFalse(json.contains("\"coverageId\""),
                    "coverage='' → no coverageId: " + json);
            assertFalse(json.contains("\"launchMode\""),
                    "coverage='' → no launchMode: " + json);
        }
    }

    @Nested
    class ErrorPaths {

        @Test
        void unknownClassReturnsTypeNotFound() throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("target", "no.such.TestClass");
            params.put("no-refresh", "");
            params.put("coverage", "true");
            String json = handler.handleTestRun(params);
            // resolveElement returns null first → target-not-found
            // surfaces before coverage-mode-not-supported has a chance.
            assertTrue(json.contains("target-not-found"),
                    "Should report target-not-found: " + json);
        }

        @Test
        void unknownProjectReturnsProjectNotFound() throws Exception {
            Map<String, String> params = new HashMap<>();
            params.put("target", "nonexistent-project-xyz");
            params.put("no-refresh", "");
            params.put("coverage", "true");
            String json = handler.handleTestRun(params);
            assertTrue(json.contains("target-not-found"),
                    "Should report target-not-found: " + json);
        }
    }

}
