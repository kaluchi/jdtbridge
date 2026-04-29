package io.github.kaluchi.jdtbridge.coverage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kaluchi.jdtbridge.ProjectScope;
import io.github.kaluchi.jdtbridge.support.TestCoverageStubs;
import io.github.kaluchi.jdtbridge.support.TestFixture;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.IExecutionDataSource;
import org.eclipse.eclemma.core.ISessionImporter;
import org.eclipse.eclemma.core.analysis.IJavaModelCoverage;
import org.eclipse.jdt.core.IJavaProject;
import org.jacoco.core.analysis.ICoverageNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CoverageSessionHandler}. Drives state through
 * the real {@link CoverageTools#getSessionManager()} via
 * {@link ISessionImporter}.
 */
@SuppressWarnings("restriction")
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
        void importedSessionAppearsInRuns() throws Exception {
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
        void entryShapeHasAllRequiredFields() throws Exception {
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
                    "launchTimestamp", "terminatedAt", "active"
            }) {
                assertTrue(entry.has(required),
                        "Missing required field '" + required
                                + "': " + entry);
            }
        }

        @Test
        void importedRunHasNullConfigFields() throws Exception {
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
        void importedRunIsTerminated() throws Exception {
            importAndAwait("terminated-test");
            JsonObject entry = JsonParser.parseString(
                            handler.handleRuns(ProjectScope.ALL))
                    .getAsJsonArray().get(0).getAsJsonObject();
            assertTrue(entry.get("terminated").getAsBoolean());
            assertTrue(entry.get("dataReceived").getAsBoolean());
            assertEquals(1, entry.get("dumpCount").getAsInt());
        }

        @Test
        void activeRunMarkedActiveTrue() throws Exception {
            String coverageId = importAndAwait("active-marker");
            JsonArray arr = JsonParser.parseString(
                            handler.handleRuns(ProjectScope.ALL))
                    .getAsJsonArray();
            JsonObject entry = arr.get(0).getAsJsonObject();
            assertTrue(entry.get("active").getAsBoolean(),
                    "Imported session is auto-activated, runs"
                            + " must mark it active=true");
            assertEquals(coverageId,
                    entry.get("coverageId").getAsString());
        }

        @Test
        void inactiveRunsMarkedActiveFalse() throws Exception {
            importAndAwait("first");
            String activeId = importAndAwait("second");
            JsonArray arr = JsonParser.parseString(
                            handler.handleRuns(ProjectScope.ALL))
                    .getAsJsonArray();
            int activeCount = 0;
            for (var el : arr) {
                if (el.getAsJsonObject().get("active").getAsBoolean()) {
                    activeCount++;
                    assertEquals(activeId, el.getAsJsonObject()
                            .get("coverageId").getAsString());
                }
            }
            assertEquals(1, activeCount,
                    "Exactly one run must be active at a time");
        }

        @Test
        void scopeFilteringExcludesUnscopedImported() throws Exception {
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
        void unknownDumpSuffixReturnsDumpNotFound() throws Exception {
            String coverageId = importAndAwait("dump-out-of-range");
            JsonObject obj = parseObj(handler.handleSession(
                    Map.of("coverageId", coverageId + ":99")));
            assertEquals("coverage-dump-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void importedSessionHasCountersAndInfos() throws Exception {
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
        void emptyExecDataYieldsZeroCounters() throws Exception {
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
        void zeroTotalsYieldNullRatios() throws Exception {
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
        void coverageStatusIsConstantName() throws Exception {
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
    class HandleNode {

        @BeforeEach
        void seedFixture() throws Exception {
            // Need a real workspace type to test the
            // resolvable-fqn → no-data-for-element path; the
            // imported empty session has no scope, so even with
            // the type present getCoverageFor returns null.
            TestFixture.create();
        }

        @Test
        void missingCoverageIdReturnsNotFound() {
            JsonObject obj = parseObj(
                    handler.handleNode(Map.of()));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void missingFqnReturnsFqnUnresolved() throws Exception {
            String coverageId = importAndAwait("node-no-fqn");
            JsonObject obj = parseObj(handler.handleNode(
                    Map.of("coverageId", coverageId)));
            assertEquals("coverage-fqn-unresolved",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownCoverageIdReturnsNotFound() {
            JsonObject obj = parseObj(handler.handleNode(Map.of(
                    "coverageId", "Bogus:9999",
                    "fqn", "anything")));
            assertEquals("coverage-not-found",
                    obj.get("error").getAsString());
        }

        @Test
        void unresolvableFqnReturnsFqnUnresolved() throws Exception {
            String coverageId = importAndAwait("node-bad-fqn");
            JsonObject obj = parseObj(handler.handleNode(Map.of(
                    "coverageId", coverageId,
                    "fqn", "no.such.Type")));
            assertEquals("coverage-fqn-unresolved",
                    obj.get("error").getAsString());
        }

        @Test
        void resolvableFqnOutsideScopeReturnsNoDataForElement() throws Exception {
            // The imported empty-scope session has no projects in
            // modelCoverage; the fixture type resolves but
            // getCoverageFor returns null.
            String coverageId = importAndAwait("node-no-data");
            JsonObject obj = parseObj(handler.handleNode(Map.of(
                    "coverageId", coverageId,
                    "fqn", "test.model.Animal")));
            assertEquals("coverage-no-data-for-element",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownDumpSuffixReturnsDumpNotFound() throws Exception {
            String coverageId = importAndAwait("node-dump");
            JsonObject obj = parseObj(handler.handleNode(Map.of(
                    "coverageId", coverageId + ":99",
                    "fqn", "test.model.Animal")));
            assertEquals("coverage-dump-not-found",
                    obj.get("error").getAsString());
        }
    }

    @Nested
    class AggregateProjectCounters {

        @Test
        void nullModelReturnsEmptyNode() {
            ICoverageNode agg = CoverageSessionHandler
                    .aggregateProjectCounters(null);
            assertNotNull(agg);
            assertEquals(0, agg.getInstructionCounter().getTotalCount());
            assertEquals(0, agg.getBranchCounter().getTotalCount());
            assertEquals(0, agg.getLineCounter().getTotalCount());
        }

        @Test
        void noProjectsReturnsEmptyNode() {
            IJavaModelCoverage model = TestCoverageStubs.fakeModel(Map.of());
            ICoverageNode agg = CoverageSessionHandler
                    .aggregateProjectCounters(model);
            assertEquals(0, agg.getInstructionCounter().getTotalCount());
        }

        @Test
        void singleProjectReturnsItsCounters() {
            IJavaProject p = TestCoverageStubs.fakeProject();
            IJavaModelCoverage model = TestCoverageStubs.fakeModel(Map.of(
                    p, TestCoverageStubs.fakeNode(50, 100, 5, 10)));
            ICoverageNode agg = CoverageSessionHandler
                    .aggregateProjectCounters(model);
            assertEquals(100,
                    agg.getInstructionCounter().getCoveredCount());
            assertEquals(50,
                    agg.getInstructionCounter().getMissedCount());
            assertEquals(150,
                    agg.getInstructionCounter().getTotalCount());
            assertEquals(10,
                    agg.getBranchCounter().getCoveredCount());
            assertEquals(5,
                    agg.getBranchCounter().getMissedCount());
        }

        @Test
        void twoProjectsSumCounters() {
            IJavaProject p1 = TestCoverageStubs.fakeProject();
            IJavaProject p2 = TestCoverageStubs.fakeProject();
            IJavaModelCoverage model = TestCoverageStubs.fakeModel(Map.of(
                    p1, TestCoverageStubs.fakeNode(50, 100, 5, 10),
                    p2, TestCoverageStubs.fakeNode(200, 30, 0, 4)));
            ICoverageNode agg = CoverageSessionHandler
                    .aggregateProjectCounters(model);
            assertEquals(130,
                    agg.getInstructionCounter().getCoveredCount());
            assertEquals(250,
                    agg.getInstructionCounter().getMissedCount());
            assertEquals(380,
                    agg.getInstructionCounter().getTotalCount());
            assertEquals(14,
                    agg.getBranchCounter().getCoveredCount());
            assertEquals(5,
                    agg.getBranchCounter().getMissedCount());
        }

        @Test
        void nullChildCoverageSkippedWithoutNpe() {
            IJavaProject present = TestCoverageStubs.fakeProject();
            IJavaProject missing = TestCoverageStubs.fakeProject();
            Map<IJavaProject, ICoverageNode> map = new HashMap<>();
            map.put(present, TestCoverageStubs.fakeNode(10, 90, 0, 0));
            map.put(missing, null);
            IJavaModelCoverage model = TestCoverageStubs.fakeModel(map);
            ICoverageNode agg = CoverageSessionHandler
                    .aggregateProjectCounters(model);
            assertEquals(90,
                    agg.getInstructionCounter().getCoveredCount());
            assertEquals(10,
                    agg.getInstructionCounter().getMissedCount());
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
        void activeReturnsId() throws Exception {
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
        void activatesAndReturnsPrevious() throws Exception {
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
        void singleInputReturnsTooFewInputs() throws Exception {
            String single = importAndAwait("merge-1");
            JsonObject obj = parseObj(handler.handleMerge(
                    "{\"coverageIds\":[\"" + single + "\"]}"));
            assertEquals("coverage-merge-too-few-inputs",
                    obj.get("error").getAsString());
        }

        @Test
        void unknownIdSurfacesContextMissing() throws Exception {
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
        void twoImportedSessionsMergeSuccessfully() throws Exception {
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
        void omittedDescriptionUsesEclipseDefault() throws Exception {
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
    class ParseDumpIndex {

        @Test
        void nullReturnsNull() {
            assertNull(CoverageSessionHandler.parseDumpIndex(null));
        }

        @Test
        void noColonReturnsNull() {
            assertNull(CoverageSessionHandler.parseDumpIndex("MyTest"));
        }

        @Test
        void singleColonNotADumpIndex() {
            // "MyTest:1700000000000" — bare configId:timestamp,
            // no dump suffix.
            assertNull(CoverageSessionHandler.parseDumpIndex(
                    "MyTest:1700000000000"));
        }

        @Test
        void doubleColonReturnsDumpIndex() {
            assertEquals(2,
                    (int) CoverageSessionHandler.parseDumpIndex(
                            "MyTest:1700000000000:2"));
        }

        @Test
        void importedHasNoDumpIndex() {
            assertNull(CoverageSessionHandler.parseDumpIndex(
                    "imported:1700000000000"));
        }

        @Test
        void mergedHasNoDumpIndex() {
            assertNull(CoverageSessionHandler.parseDumpIndex(
                    "merged:1700000000000"));
        }

        @Test
        void collisionSuffixIsNotDumpIndex() {
            // "merged:1700000000000#2" — # in suffix, not digits.
            assertNull(CoverageSessionHandler.parseDumpIndex(
                    "merged:1700000000000#2"));
        }

        @Test
        void colonInConfigIdWithTimestampDoesNotOverflow() {
            // Regression — fix for PR review: configIds with ':'
            // produce three-or-more colon ids; the trailing
            // 13-digit timestamp overflows Integer.parseInt and
            // used to throw NumberFormatException. Now must
            // return null gracefully.
            Integer result = CoverageSessionHandler.parseDumpIndex(
                    "Foo:Bar:1700000000000");
            assertNull(result, "13-digit timestamp must NOT be"
                    + " interpreted as a dump index: got "
                    + result);
        }

        @Test
        void colonInConfigIdWithRealDumpIndexResolves() {
            // "Foo:Bar:1700000000000:3" — three-colon id with a
            // small int tail. The current heuristic (last colon
            // tail must be parseable) treats the last segment as
            // the dump index. This is best-effort given the
            // ambiguity introduced by ':' in configIds; documented.
            assertEquals(3,
                    (int) CoverageSessionHandler.parseDumpIndex(
                            "Foo:Bar:1700000000000:3"));
        }

        @Test
        void zeroIsNotAValidDumpIndex() {
            // Dump indices are 1-based.
            assertNull(CoverageSessionHandler.parseDumpIndex(
                    "MyTest:1700000000000:0"));
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
        void emptyBodyRemovesActiveOnly() throws Exception {
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
        void allTrueRemovesEverything() throws Exception {
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
        void allFalseDefaultsToActiveRemoval() throws Exception {
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

    private String importAndAwait(String description) throws Exception {
        ISessionImporter importer = CoverageTools.getImporter();
        importer.setDescription(description);
        importer.setScope(Set.of());
        importer.setExecutionDataSource(
                TestCoverageStubs.emptyDataSource());
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
}
