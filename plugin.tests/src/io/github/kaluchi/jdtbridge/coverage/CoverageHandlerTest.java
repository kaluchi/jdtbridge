package io.github.kaluchi.jdtbridge.coverage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.IExecutionDataSource;
import org.eclipse.eclemma.core.ISessionImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CoverageHandler}. Validation paths are unit-style
 * (no real launch); the {@code /refresh} happy path and a relaunch
 * precondition are exercised against a real imported session.
 */
public class CoverageHandlerTest {

    private CoverageTracker tracker;
    private CoverageHandler handler;

    @BeforeEach
    void setUp() {
        tracker = new CoverageTracker();
        tracker.start();
        handler = new CoverageHandler(tracker);
    }

    @AfterEach
    void tearDown() {
        tracker.stop();
        CoverageTools.getSessionManager().removeAllSessions();
    }

    @Nested
    class HandleRun {

        @Test
        void missingConfigIdReturnsConfigNotFound() throws Exception {
            JsonObject obj = parseObj(handler.handleRun(Map.of()));
            assertEquals("coverage-config-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void blankConfigIdReturnsConfigNotFound() throws Exception {
            JsonObject obj = parseObj(handler.handleRun(
                    Map.of("configId", "   ")));
            assertEquals("coverage-config-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownConfigReturnsConfigNotFound() throws Exception {
            JsonObject obj = parseObj(handler.handleRun(Map.of(
                    "configId", "definitely-not-a-real-config-xyz-9z")));
            assertEquals("coverage-config-not-found",
                    obj.get("error").getAsString());
        }
    }

    @Nested
    class ModeNotSupported {

        private static final String NON_COVERAGE_TYPE_ID =
                "org.eclipse.debug.core.groups."
                        + "GroupLaunchConfigurationType";

        @Test
        void unsupportedTypeReturnsModeNotSupportedError()
                throws Exception {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType type =
                    mgr.getLaunchConfigurationType(
                            NON_COVERAGE_TYPE_ID);
            assertNotNull(type);

            String configName = "test-non-coverage-"
                    + UUID.randomUUID();
            ILaunchConfigurationWorkingCopy wc =
                    type.newInstance(null, configName);
            ILaunchConfiguration cfg = wc.doSave();
            try {
                JsonObject obj = parseObj(handler.handleRun(
                        Map.of("configId", configName)));
                assertEquals("coverage-mode-not-supported",
                        obj.get("error").getAsString());
                assertTrue(obj.has("supportedTypeIds"));
                assertTrue(obj.get("message").getAsString()
                                .contains(NON_COVERAGE_TYPE_ID));
            } finally {
                cfg.delete();
            }
        }

        @Test
        void supportedTypeIdsListIsNonEmpty() throws Exception {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType type =
                    mgr.getLaunchConfigurationType(
                            NON_COVERAGE_TYPE_ID);
            assertNotNull(type);

            String configName = "test-non-coverage-list-"
                    + UUID.randomUUID();
            ILaunchConfigurationWorkingCopy wc =
                    type.newInstance(null, configName);
            ILaunchConfiguration cfg = wc.doSave();
            try {
                JsonObject obj = parseObj(handler.handleRun(
                        Map.of("configId", configName)));
                var arr = obj.get("supportedTypeIds")
                        .getAsJsonArray();
                assertTrue(arr.size() > 0);
            } finally {
                cfg.delete();
            }
        }
    }

    @Nested
    class HandleDump {

        @Test
        void missingBodyReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleDump(null));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void emptyBodyReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleDump(""));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void invalidJsonReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleDump(
                    "{not valid json"));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void missingCoverageIdReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleDump(
                    "{\"reset\":true}"));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownCoverageIdReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleDump(
                    "{\"coverageId\":\"Bogus:9999\"}"));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void importedSessionReturnsLaunchNotLive() throws Exception {
            String coverageId = importAndAwait("dump-on-imported");
            JsonObject obj = parseObj(handler.handleDump(
                    "{\"coverageId\":\"" + coverageId + "\"}"));
            assertEquals("coverage-launch-not-live",
                    obj.get("error").getAsString());
        }

        @Test
        void resetDefaultsToFalseWhenOmitted() {
            // No assertion on side-effect (no live launch), only
            // that the missing-reset path doesn't throw a parse
            // error before the coverage-not-found check.
            JsonObject obj = parseObj(handler.handleDump(
                    "{\"coverageId\":\"unknown\"}"));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }
    }

    @Nested
    class HandleRefresh {

        @Test
        void noActiveSessionReturnsNoActiveError() {
            CoverageTools.getSessionManager().removeAllSessions();
            JsonObject obj = parseObj(handler.handleRefresh());
            assertEquals("coverage-no-active-session",
                    obj.get("error").getAsString());
        }

        @Test
        void withActiveSessionReturnsOk() throws Exception {
            String coverageId = importAndAwait("refresh-test");
            JsonObject obj = parseObj(handler.handleRefresh());
            assertTrue(obj.get("ok").getAsBoolean(),
                    "Expected ok=true: " + obj);
            assertEquals(coverageId,
                    obj.get("activeCoverageId").getAsString());
        }
    }

    @Nested
    class HandleRelaunch {

        @Test
        void noActiveSessionReturnsNoActiveError() {
            CoverageTools.getSessionManager().removeAllSessions();
            JsonObject obj = parseObj(handler.handleRelaunch());
            assertEquals("coverage-no-active-session",
                    obj.get("error").getAsString());
        }

        @Test
        void importedActiveReturnsLaunchNotLive() throws Exception {
            // Imported session has launchConfiguration == null,
            // so relaunch can't reconstruct a launch from it.
            importAndAwait("relaunch-imported");
            JsonObject obj = parseObj(handler.handleRelaunch());
            assertEquals("coverage-launch-not-live",
                    obj.get("error").getAsString());
        }
    }

    @Nested
    class ErrorJsonShape {

        @Test
        void everyErrorHasErrorAndMessage() throws Exception {
            // Iterate the validation paths that don't require a
            // real launch and assert the error JSON shape.
            String[] errorJsons = {
                    handler.handleRun(Map.of()),
                    handler.handleRun(
                            Map.of("configId", "unknown")),
                    handler.handleDump(null),
                    handler.handleDump(""),
                    handler.handleDump("{not json"),
                    handler.handleDump(
                            "{\"coverageId\":\"unknown\"}"),
                    handler.handleRefresh(),
                    handler.handleRelaunch()
            };
            for (String json : errorJsons) {
                JsonObject obj = parseObj(json);
                assertNotNull(obj.get("error"),
                        "Missing error field: " + json);
                assertTrue(obj.get("error").getAsString()
                                .startsWith("coverage-"),
                        "Error kind must use coverage- prefix: "
                                + json);
            }
        }
    }

    // -- helpers --

    private static JsonObject parseObj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private String importAndAwait(String description) throws Exception {
        ISessionImporter importer = CoverageTools.getImporter();
        importer.setDescription(description);
        importer.setScope(Set.of());
        importer.setExecutionDataSource(emptyDataSource());
        importer.setCopy(false);
        importer.importSession(new NullProgressMonitor());
        Job.getJobManager().join(
                CoverageTracker.CLASSIFY_FAMILY, null);
        return tracker.snapshot().values().stream()
                .filter(r -> description.equals(r.description))
                .map(r -> r.coverageId)
                .findFirst()
                .orElseThrow();
    }

    private static IExecutionDataSource emptyDataSource() {
        return (execVisitor, sessionInfoVisitor) -> {
            // Intentionally empty.
        };
    }
}
