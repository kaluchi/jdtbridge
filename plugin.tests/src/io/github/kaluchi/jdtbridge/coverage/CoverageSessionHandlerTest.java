package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.IExecutionDataSource;
import org.eclipse.eclemma.core.ISessionImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.kaluchi.jdtbridge.ProjectScope;

/**
 * Tests for {@link CoverageSessionHandler}. Drives state through
 * the real {@link CoverageTools#getSessionManager()} via
 * {@link ISessionImporter}.
 */
public class CoverageSessionHandlerTest {

    private CoverageAnalyzer analyzer;
    private CoverageTracker tracker;
    private CoverageSessionHandler handler;

    @BeforeEach
    void setUp() {
        analyzer = new CoverageAnalyzer();
        tracker = new CoverageTracker(analyzer);
        tracker.start();
        handler = new CoverageSessionHandler(tracker, analyzer);
    }

    @AfterEach
    void tearDown() {
        tracker.stop();
        CoverageTools.getSessionManager().removeAllSessions();
    }

    @Nested
    class HandleRuns {

        @Test
        void emptyTrackerReturnsEmptyArray() {
            String json = handler.handleRuns(ProjectScope.ALL);
            assertEquals("[]", json);
        }

        @Test
        void importedSessionAppearsInRuns() {
            String coverageId = importAndAwait("runs-test");
            JsonArray arr = JsonParser.parseString(
                            handler.handleRuns(ProjectScope.ALL))
                    .getAsJsonArray();
            assertEquals(1, arr.size());
            JsonObject entry = arr.get(0).getAsJsonObject();
            assertEquals(coverageId,
                    entry.get("coverageId").getAsString());
            assertEquals("imported",
                    entry.get("coverageSessionKind").getAsString());
        }

        @Test
        void entryShapeHasAllRequiredFields() {
            importAndAwait("shape-test");
            JsonArray arr = JsonParser.parseString(
                            handler.handleRuns(ProjectScope.ALL))
                    .getAsJsonArray();
            JsonObject entry = arr.get(0).getAsJsonObject();
            for (String required : new String[] {
                    "coverageId", "coverageSessionKind",
                    "configId", "configType", "configTypeId",
                    "launchId", "description", "coverageScope",
                    "dumpCount", "terminated", "dataReceived",
                    "analysisLoading", "analysisReady",
                    "launchTimestamp", "terminatedAt"
            }) {
                assertTrue(entry.has(required),
                        "Missing required field '" + required
                                + "': " + entry);
            }
        }

        @Test
        void importedRunHasNullConfigFields() {
            importAndAwait("null-config-test");
            JsonObject entry = JsonParser.parseString(
                            handler.handleRuns(ProjectScope.ALL))
                    .getAsJsonArray().get(0).getAsJsonObject();
            assertTrue(entry.get("configId").isJsonNull());
            assertTrue(entry.get("configType").isJsonNull());
            assertTrue(entry.get("configTypeId").isJsonNull());
            assertTrue(entry.get("launchId").isJsonNull());
            assertTrue(entry.get("launchTimestamp").isJsonNull());
        }

        @Test
        void importedRunIsTerminated() {
            importAndAwait("terminated-test");
            JsonObject entry = JsonParser.parseString(
                            handler.handleRuns(ProjectScope.ALL))
                    .getAsJsonArray().get(0).getAsJsonObject();
            assertTrue(entry.get("terminated").getAsBoolean());
            assertTrue(entry.get("dataReceived").getAsBoolean());
            assertEquals(1, entry.get("dumpCount").getAsInt());
        }

        @Test
        void scopeFilteringExcludesUnscopedImported() {
            importAndAwait("scope-out");
            // Imported with empty scope set — containsAnyOfRoots
            // returns false for any non-null projects scope.
            ProjectScope filtered = ProjectScope.of(
                    Set.of("nonexistent-project-name"));
            assertEquals("[]", handler.handleRuns(filtered));
        }
    }

    @Nested
    class HandleSession {

        @Test
        void missingIdReturnsCoverageNotFound() {
            JsonObject obj = parseObj(
                    handler.handleSession(Map.of()));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownIdReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleSession(
                    Map.of("coverageId", "Bogus:9999")));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void importedSessionHasCountersAndInfos() {
            String coverageId = importAndAwait("session-test");
            JsonObject obj = parseObj(handler.handleSession(
                    Map.of("coverageId", coverageId)));
            assertNotNull(obj.get("counters"),
                    "Missing counters field: " + obj);
            JsonObject counters = obj.getAsJsonObject("counters");
            for (String c : new String[] {
                    "instruction", "branch", "line",
                    "complexity", "method", "class"
            }) {
                assertTrue(counters.has(c),
                        "Missing counter '" + c + "': " + counters);
            }
            assertTrue(obj.has("jacocoSessionInfos"));
        }

        @Test
        void emptyExecDataYieldsZeroCounters() {
            String coverageId = importAndAwait("zero-counters");
            JsonObject obj = parseObj(handler.handleSession(
                    Map.of("coverageId", coverageId)));
            JsonObject instr = obj.getAsJsonObject("counters")
                    .getAsJsonObject("instruction");
            assertEquals(0, instr.get("coveredCount").getAsInt());
            assertEquals(0, instr.get("missedCount").getAsInt());
            assertEquals(0, instr.get("totalCount").getAsInt());
        }

        @Test
        void zeroTotalsYieldNullRatios() {
            String coverageId = importAndAwait("nan-ratios");
            JsonObject instr = parseObj(handler.handleSession(
                            Map.of("coverageId", coverageId)))
                    .getAsJsonObject("counters")
                    .getAsJsonObject("instruction");
            // ICounter returns NaN for ratios when totalCount==0;
            // bridge maps NaN → JSON null.
            assertTrue(instr.get("coveredRatio").isJsonNull(),
                    "coveredRatio should be null for empty: "
                            + instr);
            assertTrue(instr.get("missedRatio").isJsonNull());
        }

        @Test
        void coverageStatusIsConstantName() {
            String coverageId = importAndAwait("status-test");
            String status = parseObj(handler.handleSession(
                            Map.of("coverageId", coverageId)))
                    .getAsJsonObject("counters")
                    .getAsJsonObject("instruction")
                    .get("coverageStatus").getAsString();
            // Empty execution data → EMPTY status from JaCoCo.
            assertEquals("EMPTY", status);
        }
    }

    @Nested
    class HandleActive {

        @Test
        void noActiveReturnsNull() {
            CoverageTools.getSessionManager().removeAllSessions();
            JsonObject obj = parseObj(handler.handleActive());
            assertTrue(obj.get("activeCoverageId").isJsonNull());
        }

        @Test
        void activeReturnsId() {
            String coverageId = importAndAwait("active-test");
            JsonObject obj = parseObj(handler.handleActive());
            assertEquals(coverageId,
                    obj.get("activeCoverageId").getAsString());
        }
    }

    @Nested
    class HandleActivate {

        @Test
        void missingBodyReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleActivate(null));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownIdReturnsCoverageNotFound() {
            JsonObject obj = parseObj(handler.handleActivate(
                    "{\"coverageId\":\"Bogus:1\"}"));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void activatesAndReturnsPrevious() {
            String firstId = importAndAwait("activate-1");
            String secondId = importAndAwait("activate-2");
            // After two imports, second one is active by default
            // (importer activates new). Activate the first.
            JsonObject obj = parseObj(handler.handleActivate(
                    "{\"coverageId\":\"" + firstId + "\"}"));
            assertTrue(obj.get("ok").getAsBoolean());
            assertEquals(firstId,
                    obj.get("activeCoverageId").getAsString());
            assertEquals(secondId,
                    obj.get("previousActiveCoverageId").getAsString());
        }
    }

    @Nested
    class HandleMerge {

        @Test
        void missingArrayReturnsTooFewInputs() {
            JsonObject obj = parseObj(handler.handleMerge("{}"));
            assertEquals("coverage-merge-too-few-inputs",
                    obj.get("error").getAsString());
        }

        @Test
        void singleInputReturnsTooFewInputs() {
            String single = importAndAwait("merge-1");
            JsonObject obj = parseObj(handler.handleMerge(
                    "{\"coverageIds\":[\"" + single + "\"]}"));
            assertEquals("coverage-merge-too-few-inputs",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownIdSurfacesContextMissing() {
            String first = importAndAwait("merge-known");
            JsonObject obj = parseObj(handler.handleMerge(
                    "{\"coverageIds\":[\"" + first
                            + "\",\"BogusUnknown:1\"]}"));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
            assertEquals("BogusUnknown:1",
                    obj.getAsJsonObject("context")
                            .get("missing").getAsString());
        }

        @Test
        void twoImportedSessionsMergeSuccessfully() {
            String a = importAndAwait("merge-a");
            String b = importAndAwait("merge-b");
            JsonObject obj = parseObj(handler.handleMerge(
                    "{\"coverageIds\":[\"" + a + "\",\""
                            + b + "\"],\"description\":\"combined\"}"));
            assertTrue(obj.get("ok").getAsBoolean(),
                    "Expected ok: " + obj);
            String mergedId = obj.get("mergedCoverageId").getAsString();
            assertNotNull(mergedId);
            assertTrue(mergedId.startsWith("merged:"),
                    "Expected merged: prefix: " + mergedId);
            assertEquals(2, obj.getAsJsonArray(
                    "consumedCoverageIds").size());
        }

        @Test
        void mergedRunHasMergedKindAndConsumedIds() throws Exception {
            String a = importAndAwait("kind-a");
            String b = importAndAwait("kind-b");
            String mergedId = parseObj(handler.handleMerge(
                            "{\"coverageIds\":[\"" + a + "\",\""
                                    + b + "\"]}"))
                    .get("mergedCoverageId").getAsString();
            CoverageRun run = tracker.byCoverageId(mergedId);
            assertNotNull(run);
            assertEquals(CoverageRun.Kind.MERGED, run.kind);
            assertEquals(2, run.consumedCoverageIds.size());
        }

        @Test
        void omittedDescriptionUsesEclipseDefault() {
            String a = importAndAwait("desc-a");
            String b = importAndAwait("desc-b");
            String mergedId = parseObj(handler.handleMerge(
                            "{\"coverageIds\":[\"" + a + "\",\""
                                    + b + "\"]}"))
                    .get("mergedCoverageId").getAsString();
            CoverageRun run = tracker.byCoverageId(mergedId);
            assertTrue(run.description.startsWith("Merged ("),
                    "Expected Eclipse default template, got: "
                            + run.description);
        }
    }

    @Nested
    class HandleRemove {

        @Test
        void emptyBodyWithNoActiveReturnsNoActiveError() {
            CoverageTools.getSessionManager().removeAllSessions();
            JsonObject obj = parseObj(handler.handleRemove("{}"));
            assertEquals("coverage-no-active-session",
                    obj.get("error").getAsString());
        }

        @Test
        void emptyBodyRemovesActiveOnly() {
            importAndAwait("remove-keep");
            String activeId = importAndAwait("remove-active");
            JsonObject obj = parseObj(handler.handleRemove("{}"));
            assertTrue(obj.get("ok").getAsBoolean());
            assertEquals(1, obj.getAsJsonArray(
                    "removedCoverageIds").size());
            assertEquals(activeId, obj.getAsJsonArray(
                    "removedCoverageIds").get(0).getAsString());
            // The other session survives.
            assertEquals(1, tracker.snapshot().size());
        }

        @Test
        void allTrueRemovesEverything() {
            importAndAwait("all-1");
            importAndAwait("all-2");
            JsonObject obj = parseObj(handler.handleRemove(
                    "{\"all\":true}"));
            assertTrue(obj.get("ok").getAsBoolean());
            assertEquals(2, obj.getAsJsonArray(
                    "removedCoverageIds").size());
            assertEquals(0, tracker.snapshot().size());
        }

        @Test
        void allFalseDefaultsToActiveRemoval() {
            importAndAwait("default-keep");
            String activeId = importAndAwait("default-active");
            JsonObject obj = parseObj(handler.handleRemove(
                    "{\"all\":false}"));
            assertTrue(obj.get("ok").getAsBoolean());
            assertEquals(activeId, obj.getAsJsonArray(
                    "removedCoverageIds").get(0).getAsString());
        }
    }

    // -- helpers --

    private static JsonObject parseObj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private String importAndAwait(String description) {
        ISessionImporter importer = CoverageTools.getImporter();
        importer.setDescription(description);
        importer.setScope(Set.of());
        importer.setExecutionDataSource(emptyDataSource());
        importer.setCopy(false);
        try {
            importer.importSession(new NullProgressMonitor());
            Job.getJobManager().join(
                    CoverageTracker.CLASSIFY_FAMILY, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
