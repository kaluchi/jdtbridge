package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.IExecutionDataSource;
import org.eclipse.eclemma.core.ISessionImporter;
import org.eclipse.eclemma.core.ISessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CoverageTracker}. Lifecycle / resolution paths
 * exercise pure tracker state; the deferred-classification path is
 * driven through the real EclEmma {@link ISessionManager} via
 * {@link CoverageTools#getImporter()}.
 */
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
            // No assertion beyond "did not throw" — duplicate
            // start should be a no-op.
        }

        @Test
        void stopThenStartReregisters() throws Exception {
            tracker.stop();
            tracker.start();
            // Listener registration must succeed — verify by
            // firing an import and seeing the run appear.
            String coverageId = importAndAwait("alpha");
            assertNotNull(tracker.byCoverageId(coverageId));
        }

        @Test
        void snapshotIsImmutable() {
            var snapshot = tracker.snapshot();
            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class,
                    () -> snapshot.put("X", null));
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

            String secondId = importAndAwait("second");
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
            // Force the issue by reusing the same start time —
            // we can't fully control Java's clock granularity, but
            // calling import twice back-to-back routinely lands
            // both events in the same millisecond on modern CPUs.
            String firstId = importAndAwait("collide-1");
            String secondId = importAndAwait("collide-2");
            assertNotNull(firstId);
            assertNotNull(secondId);
            // The two coverage IDs must differ regardless of
            // millisecond clock alignment.
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    firstId, secondId);
        }
    }

    /** Trigger {@code SessionImporter.importSession} with a no-op
     *  execution-data source, then block until the deferred
     *  classification job has run. Returns the assigned
     *  {@code coverageId}. */
    private String importAndAwait(String description) throws Exception {
        ISessionImporter importer = CoverageTools.getImporter();
        importer.setDescription(description);
        importer.setScope(Set.of());
        importer.setExecutionDataSource(emptyDataSource());
        importer.setCopy(false);
        importer.importSession(new NullProgressMonitor());
        Job.getJobManager().join(
                CoverageTracker.CLASSIFY_FAMILY, null);
        // Find the run whose latest dump's description matches —
        // gives us the coverageId without depending on the
        // System.currentTimeMillis() value the tracker assigned.
        return tracker.snapshot().values().stream()
                .filter(r -> description.equals(r.description))
                .map(r -> r.coverageId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No run with description " + description));
    }

    /** Empty {@link IExecutionDataSource} — emits no data and no
     *  session info. SessionImporter accepts it without error. */
    private static IExecutionDataSource emptyDataSource() {
        return (execVisitor, sessionInfoVisitor) -> {
            // Intentionally empty — no exec data, no session info.
        };
    }
}
