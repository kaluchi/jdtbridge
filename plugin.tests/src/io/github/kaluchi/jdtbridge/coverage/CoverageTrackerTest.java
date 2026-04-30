package io.github.kaluchi.jdtbridge.coverage;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionImporter;
import org.eclipse.eclemma.core.ISessionManager;

import io.github.kaluchi.jdtbridge.support.FakeCoverageLaunch;
import io.github.kaluchi.jdtbridge.support.TestCoverageStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CoverageTracker}. Lifecycle / resolution paths
 * exercise pure tracker state; the deferred-classification path is
 * driven through the real EclEmma {@link ISessionManager} via
 * {@link CoverageTools#getImporter()}.
 */
@SuppressWarnings("restriction")
public class CoverageTrackerTest {

    private CoverageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new CoverageTracker();
        tracker.start();
    }

    @AfterEach
    void tearDown() {
        tracker.stop();
        // Clear any sessions this test added so subsequent tests
        // start from a clean SessionManager.
        ISessionManager sm = CoverageTools.getSessionManager();
        sm.removeAllSessions();
    }

    @Nested
    class Lifecycle {

        @Test
        void startIsIdempotent() {
            tracker.start();
            tracker.start();
        }

        @Test
        void stopThenStartReregisters() throws Exception {
            tracker.stop();
            tracker.start();
            String coverageId = importAndAwait("alpha");
            assertNotNull(tracker.byCoverageId(coverageId));
        }

        @Test
        void stopWithoutStartIsNoOp() {
            CoverageTracker fresh = new CoverageTracker();
            fresh.stop();
        }

        @Test
        void snapshotIsImmutable() {
            io.github.kaluchi.jdtbridge.support.TestAwait
                    .assertUnmodifiableMap(tracker.snapshot());
        }
    }

    @Nested
    class Resolution {

        @Test
        void byCoverageIdNullReturnsNull() {
            assertNull(tracker.byCoverageId(null));
        }

        @Test
        void byCoverageIdUnknownReturnsNull() {
            assertNull(tracker.byCoverageId("Bogus:9999"));
        }

        @Test
        void byCoverageIdResolvesAfterImport() throws Exception {
            String coverageId = importAndAwait("resolution-test");
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertNotNull(run);
            assertEquals(coverageId, run.coverageId);
        }

        @Test
        void byCoverageIdStripsDumpSuffix() throws Exception {
            String coverageId = importAndAwait("suffix-test");
            // Imported runs only have one session, so :1 suffix
            // resolves to the same run.
            CoverageRun viaSuffix = tracker.byCoverageId(
                    coverageId + ":1");
            CoverageRun viaPlain = tracker.byCoverageId(coverageId);
            assertNotNull(viaSuffix);
            assertSame(viaPlain, viaSuffix);
        }
    }

    @Nested
    class ImportClassification {

        @Test
        void importedSessionGetsImportedKind() throws Exception {
            String coverageId = importAndAwait("imp-kind");
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertEquals(CoverageRun.Kind.IMPORTED, run.kind);
        }

        @Test
        void importedCoverageIdHasImportedPrefix() throws Exception {
            String coverageId = importAndAwait("imp-prefix");
            assertTrue(coverageId.startsWith("imported:"),
                    "Expected imported: prefix, got: " + coverageId);
        }

        @Test
        void importedRunIsTerminated() throws Exception {
            String coverageId = importAndAwait("imp-term");
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertTrue(run.terminated);
            assertTrue(run.dataReceived);
        }

        @Test
        void importedRunHasOneSession() throws Exception {
            String coverageId = importAndAwait("imp-one");
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertEquals(1, run.dumpCount());
        }

        @Test
        void importedRunHasNoConsumedIds() throws Exception {
            String coverageId = importAndAwait("imp-none");
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertTrue(run.consumedCoverageIds.isEmpty());
        }

        @Test
        void descriptionMirroredFromSession() throws Exception {
            String description = "imp-desc-" + System.nanoTime();
            String coverageId = importAndAwait(description);
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertEquals(description, run.description);
        }
    }

    @Nested
    class ActivationFlagReset {

        @Test
        void switchingActiveClearsLoadingButPreservesReady() throws Exception {
            String firstId = importAndAwait("first");
            CoverageRun first = tracker.byCoverageId(firstId);
            // Pretend EclEmma had finished analysis on first.
            first.analysisLoading = false;
            first.analysisReady = true;

            importAndAwait("second");
            // Importing a second session activates it; the
            // first is no longer the loader's target, but its
            // analysis result lives in CoverageAnalyzer cache.

            CoverageRun firstAgain = tracker.byCoverageId(firstId);
            assertNotNull(firstAgain);
            assertTrue(firstAgain.analysisReady,
                    "analysisReady must persist on deactivation —"
                            + " analysis is durable in the cache");
            assertFalse(firstAgain.analysisLoading);
        }

        @Test
        void switchingActiveClearsLoadingFromPreviousRun() throws Exception {
            String firstId = importAndAwait("loading-first");
            CoverageRun first = tracker.byCoverageId(firstId);
            // EclEmma was mid-load when the user switched away.
            first.analysisLoading = true;
            first.analysisReady = false;

            importAndAwait("loading-second");

            CoverageRun firstAgain = tracker.byCoverageId(firstId);
            assertNotNull(firstAgain);
            assertFalse(firstAgain.analysisLoading,
                    "Loader stops being authoritative for the"
                            + " previous active session — its"
                            + " loading bit must drop");
        }
    }

    @Nested
    class Activation {

        @Test
        void importedSessionBecomesActive() throws Exception {
            String coverageId = importAndAwait("active-test");
            // Importer activates by default — see
            // SessionImporter.importSession.
            assertEquals(coverageId, tracker.activeCoverageId());
        }

        @Test
        void removeAllClearsActive() throws Exception {
            importAndAwait("clear-test");
            CoverageTools.getSessionManager().removeAllSessions();
            assertNull(tracker.activeCoverageId());
        }
    }

    @Nested
    class CollisionSuffix {

        @Test
        void twoImportsInSameMillisecondGetUniqueIds()
                throws Exception {
            String firstId = importAndAwait("collide-1");
            String secondId = importAndAwait("collide-2");
            assertNotNull(firstId);
            assertNotNull(secondId);
            assertNotEquals(firstId, secondId);
        }
    }

    @Nested
    class LaunchLifecycle {

        private ILaunchConfiguration config;

        @BeforeEach
        void createConfig() throws Exception {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType type =
                    mgr.getLaunchConfigurationType(
                            "org.eclipse.jdt.junit.launchconfig");
            assertNotNull(type);
            String name = "tracker-test-" + UUID.randomUUID();
            ILaunchConfigurationWorkingCopy wc =
                    type.newInstance(null, name);
            config = wc.doSave();
        }

        @AfterEach
        void deleteConfig() throws Exception {
            config.delete();
        }

        @Test
        void launchesAddedRegistersLiveRun() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});

            Map<String, CoverageRun> snap = tracker.snapshot();
            assertEquals(1, snap.size());
            CoverageRun run = snap.values().iterator().next();
            assertEquals(CoverageRun.Kind.LIVE, run.kind);
            assertEquals(config.getName(), run.configId);
            assertFalse(run.terminated);
            assertSame(launch, run.launch);
        }

        @Test
        void launchesAddedIgnoresNonCoverageLaunch() {
            ILaunch plain = new org.eclipse.debug.core.Launch(
                    config, "run", null);
            tracker.launchesAdded(new ILaunch[]{plain});
            assertTrue(tracker.snapshot().isEmpty());
        }

        @Test
        void launchesAddedSkipsNullConfig() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(null, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});
            assertTrue(tracker.snapshot().isEmpty());
        }

        @Test
        void launchesAddedIdempotent() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});
            tracker.launchesAdded(new ILaunch[]{launch});
            assertEquals(1, tracker.snapshot().size());
        }

        @Test
        void launchesTerminatedMarksCoverageRun() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});

            tracker.launchesTerminated(new ILaunch[]{launch});

            CoverageRun run = tracker.snapshot().values()
                    .iterator().next();
            assertTrue(run.terminated);
            assertNotNull(run.terminatedAt);
        }

        @Test
        void launchesTerminatedIdempotentOnTimestamp() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});
            tracker.launchesTerminated(new ILaunch[]{launch});

            CoverageRun run = tracker.snapshot().values()
                    .iterator().next();
            Long firstTerminatedAt = run.terminatedAt;

            tracker.launchesTerminated(new ILaunch[]{launch});
            assertEquals(firstTerminatedAt, run.terminatedAt);
        }

        @Test
        void launchesChangedNoOp() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});
            tracker.launchesChanged(new ILaunch[]{launch});
            assertEquals(1, tracker.snapshot().size());
        }

        @Test
        void launchesRemovedKeepsRun() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});
            tracker.launchesRemoved(new ILaunch[]{launch});
            assertEquals(1, tracker.snapshot().size());
        }

        @Test
        void liveRunCoverageIdContainsConfigNameAndTimestamp() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});

            CoverageRun run = tracker.snapshot().values()
                    .iterator().next();
            assertTrue(run.coverageId.startsWith(
                    config.getName() + ":"));
        }

        @Test
        void liveRunHasConfigType() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});

            CoverageRun run = tracker.snapshot().values()
                    .iterator().next();
            assertNotNull(run.configType);
            assertNotNull(run.configTypeId);
        }

        @Test
        void launchWithoutTimestampIsSkipped() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            launch.setAttribute(
                    org.eclipse.debug.core.DebugPlugin
                            .ATTR_LAUNCH_TIMESTAMP, null);
            tracker.launchesAdded(new ILaunch[]{launch});
            assertTrue(tracker.snapshot().isEmpty());
        }

        @Test
        void sessionAddedMatchesLiveRun() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});

            String coverageId = tracker.snapshot().keySet()
                    .iterator().next();

            ICoverageSession session = TestCoverageStubs
                    .fakeSession("live-dump", config);
            tracker.sessionAdded(session);

            CoverageRun run = tracker.byCoverageId(coverageId);
            assertEquals(1, run.dumpCount());
            assertTrue(run.dataReceived);
            assertEquals("live-dump", run.description);
        }

        @Test
        void sessionAddedIdempotent() {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});

            String coverageId = tracker.snapshot().keySet()
                    .iterator().next();

            ICoverageSession session = TestCoverageStubs
                    .fakeSession("dup-dump", config);
            tracker.sessionAdded(session);
            tracker.sessionAdded(session);

            CoverageRun run = tracker.byCoverageId(coverageId);
            assertEquals(1, run.dumpCount());
        }

        @Test
        void terminatedLiveRunDoesNotMatchNewSession() throws Exception {
            FakeCoverageLaunch launch =
                    new FakeCoverageLaunch(config, Set.of());
            tracker.launchesAdded(new ILaunch[]{launch});
            tracker.launchesTerminated(new ILaunch[]{launch});

            int before = tracker.snapshot().size();
            ICoverageSession session = TestCoverageStubs
                    .fakeSession("post-term", config);
            tracker.sessionAdded(session);
            Job.getJobManager().join(
                    CoverageTracker.CLASSIFY_FAMILY, null);

            assertEquals(before + 1, tracker.snapshot().size());
        }

        @Test
        void flushPendingSettlesAllDeferred() throws Exception {
            ICoverageSession session = TestCoverageStubs
                    .fakeSession("flush-test");
            tracker.sessionAdded(session);
            tracker.flushPending();

            CoverageRun run = tracker.snapshot().values().stream()
                    .filter(r -> "flush-test".equals(r.description))
                    .findFirst()
                    .orElseThrow();
            assertTrue(run.coverageId.startsWith("imported:"));
        }
    }

    @Nested
    class CoverageChangedCallback {

        @Test
        void coverageChangedUpdatesActiveRunState()
                throws Exception {
            String coverageId = importAndAwait("cc-active");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.analysisLoading = true;

            tracker.coverageChanged();

            assertEquals(coverageId, tracker.activeCoverageId());
        }

        @Test
        void coverageChangedIgnoresUnknownSession()
                throws Exception {
            importAndAwait("cc-ignore");
            int before = tracker.snapshot().size();
            tracker.coverageChanged();
            assertEquals(before, tracker.snapshot().size());
        }
    }

    @Nested
    class SessionRemoval {

        @Test
        void removedSessionDeletesEmptyRun() throws Exception {
            String coverageId = importAndAwait("remove-test");
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertEquals(1, run.sessions.size());

            ICoverageSession session = run.sessions.get(0);
            tracker.sessionRemoved(session);

            assertNull(tracker.byCoverageId(coverageId));
        }

        @Test
        void removedSessionKeepsRunWithRemainingSessions()
                throws Exception {
            String coverageId = importAndAwait("keep-run");
            CoverageRun run = tracker.byCoverageId(coverageId);
            assertEquals(1, run.dumpCount());

            ICoverageSession original = run.sessions.get(0);
            ICoverageSession extra = TestCoverageStubs
                    .fakeSession("extra-session");
            run.sessions.add(extra);
            run.dumpedAt.add(System.currentTimeMillis());
            assertEquals(2, run.dumpCount());

            tracker.sessionRemoved(original);
            assertEquals(1, run.dumpCount());
            assertNotNull(tracker.byCoverageId(coverageId));
        }

        @Test
        void sessionActivatedWithNull() {
            tracker.sessionActivated(null);
            assertNull(tracker.activeCoverageId());
        }
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
