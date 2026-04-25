package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.IExecutionDataSource;
import org.eclipse.eclemma.core.ISessionImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CoverageAnalyzer}. Drives analysis through real
 * {@link org.eclipse.eclemma.core.ISessionImporter} sessions so the
 * underlying {@link org.eclipse.eclemma.internal.core.analysis
 * .SessionAnalyzer} actually runs end-to-end.
 */
public class CoverageAnalyzerTest {

    private CoverageAnalyzer analyzer;
    private CoverageTracker tracker;

    @BeforeEach
    void setUp() {
        analyzer = new CoverageAnalyzer();
        tracker = new CoverageTracker(analyzer);
        tracker.start();
    }

    @AfterEach
    void tearDown() {
        tracker.stop();
        CoverageTools.getSessionManager().removeAllSessions();
    }

    @Nested
    class EnsureAnalyzed {

        @Test
        void returnsNonNullAnalysisForImportedSession() throws Exception {
            ICoverageSession session = importEmpty("ensure-1");
            CoverageAnalyzer.CachedAnalysis ca =
                    analyzer.ensureAnalyzed(session);
            assertNotNull(ca);
            assertNotNull(ca.modelCoverage);
            assertNotNull(ca.jacocoSessionInfos);
            assertNotNull(ca.jacocoExecData);
            assertTrue(ca.computedAtMillis > 0);
        }

        @Test
        void emptyExecDataYieldsEmptyInfos() throws Exception {
            ICoverageSession session = importEmpty("ensure-empty");
            CoverageAnalyzer.CachedAnalysis ca =
                    analyzer.ensureAnalyzed(session);
            // No execution data was emitted, so JaCoCo's
            // SessionInfoStore is empty.
            assertTrue(ca.jacocoSessionInfos.isEmpty());
            assertTrue(ca.jacocoExecData.isEmpty());
        }

        @Test
        void cachesResultAcrossCalls() throws Exception {
            ICoverageSession session = importEmpty("cache-hit");
            CoverageAnalyzer.CachedAnalysis first =
                    analyzer.ensureAnalyzed(session);
            CoverageAnalyzer.CachedAnalysis second =
                    analyzer.ensureAnalyzed(session);
            // Identity equality — second call must hit cache.
            assertSame(first, second);
        }

        @Test
        void cacheSizeReflectsUniqueSessions() throws Exception {
            ICoverageSession a = importEmpty("size-1");
            ICoverageSession b = importEmpty("size-2");
            assertEquals(0, analyzer.cacheSize());
            analyzer.ensureAnalyzed(a);
            assertEquals(1, analyzer.cacheSize());
            analyzer.ensureAnalyzed(b);
            assertEquals(2, analyzer.cacheSize());
            analyzer.ensureAnalyzed(a);
            assertEquals(2, analyzer.cacheSize());
        }
    }

    @Nested
    class Invalidation {

        @Test
        void invalidateRemovesEntry() throws Exception {
            ICoverageSession session = importEmpty("invalidate-1");
            analyzer.ensureAnalyzed(session);
            assertTrue(analyzer.isCached(session));
            analyzer.invalidate(session);
            assertFalse(analyzer.isCached(session));
        }

        @Test
        void invalidateUnknownIsNoop() {
            // Should not throw on a session never analyzed.
            ICoverageSession fake = new TestSessionStub();
            analyzer.invalidate(fake);
            assertEquals(0, analyzer.cacheSize());
        }

        @Test
        void clearDropsAllEntries() throws Exception {
            analyzer.ensureAnalyzed(importEmpty("clear-1"));
            analyzer.ensureAnalyzed(importEmpty("clear-2"));
            assertEquals(2, analyzer.cacheSize());
            analyzer.clear();
            assertEquals(0, analyzer.cacheSize());
        }

        @Test
        void trackerRemovalInvalidatesCache() throws Exception {
            ICoverageSession session = importEmpty("tracker-removal");
            analyzer.ensureAnalyzed(session);
            assertTrue(analyzer.isCached(session));
            // Tracker is wired with this analyzer; the session
            // listener removes it from cache on session removal.
            CoverageTools.getSessionManager().removeAllSessions();
            assertFalse(analyzer.isCached(session));
        }
    }

    // -- helpers --

    private ICoverageSession importEmpty(String description)
            throws Exception {
        ISessionImporter importer = CoverageTools.getImporter();
        importer.setDescription(description);
        importer.setScope(Set.of());
        importer.setExecutionDataSource(emptyDataSource());
        importer.setCopy(false);
        importer.importSession(new NullProgressMonitor());
        Job.getJobManager().join(
                CoverageTracker.CLASSIFY_FAMILY, null);
        // Fetch back the just-imported session by description.
        return CoverageTools.getSessionManager().getSessions().stream()
                .filter(s -> description.equals(s.getDescription()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No session " + description));
    }

    private static IExecutionDataSource emptyDataSource() {
        return (execVisitor, sessionInfoVisitor) -> {
            // empty
        };
    }

    /** Bare stub used only for {@link
     *  Invalidation#invalidateUnknownIsNoop} where we need an
     *  ICoverageSession instance that isn't from EclEmma. */
    private static final class TestSessionStub
            implements ICoverageSession {
        @Override public String getDescription() { return "stub"; }
        @Override public Set<org.eclipse.jdt.core.IPackageFragmentRoot>
                getScope() { return Set.of(); }
        @Override public org.eclipse.debug.core.ILaunchConfiguration
                getLaunchConfiguration() { return null; }
        @Override public void accept(
                org.jacoco.core.data.IExecutionDataVisitor execVisitor,
                org.jacoco.core.data.ISessionInfoVisitor
                        sessionInfoVisitor) { }
        @Override public <T> T getAdapter(Class<T> adapter) {
            return null;
        }
    }
}
