package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.Launch;
import org.eclipse.eclemma.core.ICoverageSession;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CoverageRun} factories and accessors. Pure-data
 * tests — no listeners, no SessionManager interaction.
 */
public class CoverageRunTest {

    @Nested
    class LiveFactory {

        private final ILaunch launch = new Launch(null, "coverage", null);

        @Test
        void kindIsLive() {
            CoverageRun run = CoverageRun.live(
                    "MyTest:1700000000000", "MyTest", "JUnit",
                    "org.eclipse.jdt.junit.launchconfig", launch,
                    1700000000000L, Set.of());
            assertEquals(CoverageRun.Kind.LIVE, run.kind);
            assertEquals("live", run.kind.wireName());
        }

        @Test
        void copiesInputs() {
            CoverageRun run = CoverageRun.live(
                    "MyTest:1700000000000", "MyTest", "JUnit",
                    "org.eclipse.jdt.junit.launchconfig", launch,
                    1700000000000L, Set.of());
            assertEquals("MyTest:1700000000000", run.coverageId);
            assertEquals("MyTest", run.configId);
            assertEquals("JUnit", run.configType);
            assertEquals("org.eclipse.jdt.junit.launchconfig",
                    run.configTypeId);
            assertSame(launch, run.launch);
            assertEquals(1700000000000L, run.launchTimestamp);
        }

        @Test
        void initialFlags() {
            CoverageRun run = CoverageRun.live("X:1", "X", null,
                    null, launch, 1L, Set.of());
            assertFalse(run.terminated);
            assertFalse(run.dataReceived);
            assertFalse(run.analysisLoading);
            assertFalse(run.analysisReady);
            assertNull(run.terminatedAt);
            assertEquals(0, run.dumpCount());
            assertTrue(run.consumedCoverageIds.isEmpty());
        }

        @Test
        void createdAtIsRecent() {
            long before = System.currentTimeMillis();
            CoverageRun run = CoverageRun.live("X:1", "X", null,
                    null, launch, 1L, Set.of());
            long after = System.currentTimeMillis();
            assertTrue(run.createdAtMillis >= before
                    && run.createdAtMillis <= after);
        }
    }

    @Nested
    class MergedFactory {

        @Test
        void kindIsMerged() {
            CoverageRun run = CoverageRun.merged(
                    "merged:1700000000000", null, null, null,
                    Set.of(), "Merged (...)",
                    List.of("A:1", "B:2"));
            assertEquals(CoverageRun.Kind.MERGED, run.kind);
            assertEquals("merged", run.kind.wireName());
        }

        @Test
        void preTerminatedWithDataReceived() {
            CoverageRun run = CoverageRun.merged(
                    "merged:1", null, null, null,
                    Set.of(), "desc", List.of("A:1", "B:2"));
            assertTrue(run.terminated);
            assertTrue(run.dataReceived);
            assertNotNull(run.terminatedAt);
        }

        @Test
        void consumedIdsCopied() {
            List<String> inputs = List.of("A:1", "B:2", "C:3");
            CoverageRun run = CoverageRun.merged(
                    "merged:1", null, null, null,
                    Set.of(), "d", inputs);
            assertEquals(inputs, run.consumedCoverageIds);
        }

        @Test
        void noLaunchOrTimestamp() {
            CoverageRun run = CoverageRun.merged(
                    "merged:1", null, null, null,
                    Set.of(), "d", List.of("A:1", "B:2"));
            assertNull(run.launch);
            assertNull(run.launchTimestamp);
        }

        @Test
        void allowsAdoptedLaunchConfigMetadata() {
            CoverageRun run = CoverageRun.merged(
                    "merged:1", "SharedConfig", "JUnit",
                    "org.eclipse.jdt.junit.launchconfig",
                    Set.of(), "d", List.of("A:1", "B:2"));
            assertEquals("SharedConfig", run.configId);
            assertEquals("JUnit", run.configType);
        }
    }

    @Nested
    class ImportedFactory {

        @Test
        void kindIsImported() {
            CoverageRun run = CoverageRun.imported(
                    "imported:1", Set.of(), "Imported file");
            assertEquals(CoverageRun.Kind.IMPORTED, run.kind);
            assertEquals("imported", run.kind.wireName());
        }

        @Test
        void preTerminatedWithDataReceived() {
            CoverageRun run = CoverageRun.imported(
                    "imported:1", Set.of(), "d");
            assertTrue(run.terminated);
            assertTrue(run.dataReceived);
            assertNotNull(run.terminatedAt);
        }

        @Test
        void noConfigOrLaunchInfo() {
            CoverageRun run = CoverageRun.imported(
                    "imported:1", Set.of(), "d");
            assertNull(run.configId);
            assertNull(run.configType);
            assertNull(run.configTypeId);
            assertNull(run.launch);
            assertNull(run.launchTimestamp);
        }

        @Test
        void emptyConsumedIds() {
            CoverageRun run = CoverageRun.imported(
                    "imported:1", Set.of(), "d");
            assertTrue(run.consumedCoverageIds.isEmpty());
        }
    }

    @Nested
    @SuppressWarnings("restriction")
    class ResolveSession {

        private CoverageRun runWithDumps(int n) {
            CoverageRun run = CoverageRun.live("X:1", "X", null,
                    null, new Launch(null, "coverage", null), 1L,
                    Set.of());
            for (int i = 0; i < n; i++) {
                run.sessions.add(new FakeSession("dump-" + i));
                run.dumpedAt.add((long) i);
            }
            return run;
        }

        @Test
        void emptyReturnsNull() {
            CoverageRun run = runWithDumps(0);
            assertNull(run.resolveSession(null));
            assertNull(run.resolveSession(1));
        }

		@Test
        void nullSelectsLatest() {
            CoverageRun run = runWithDumps(3);
			ICoverageSession s = run.resolveSession(null);
            assertNotNull(s);
            assertEquals("dump-2", s.getDescription());
        }

		@Test
        void oneIsFirst() {
            CoverageRun run = runWithDumps(3);
			ICoverageSession s = run.resolveSession(1);
            assertEquals("dump-0", s.getDescription());
        }

		@Test
        void exactNumberOfDumpsReturnsLast() {
            CoverageRun run = runWithDumps(3);
			ICoverageSession s = run.resolveSession(3);
            assertEquals("dump-2", s.getDescription());
        }

        @Test
        void outOfRangeReturnsNull() {
            CoverageRun run = runWithDumps(2);
            assertNull(run.resolveSession(0));
            assertNull(run.resolveSession(3));
            assertNull(run.resolveSession(-1));
        }

        @Test
        void dumpCountMatchesSessionsSize() {
            CoverageRun run = runWithDumps(5);
            assertEquals(5, run.dumpCount());
        }
    }

    /** Bare-bones {@link ICoverageSession} stub for resolve tests —
     *  only {@code getDescription()} is queried. */
    @SuppressWarnings("restriction")
	private static final class FakeSession
            implements ICoverageSession {
        private final String desc;

        FakeSession(String desc) {
            this.desc = desc;
        }

        @Override
        public String getDescription() {
            return desc;
        }

        @Override
        public Set<org.eclipse.jdt.core.IPackageFragmentRoot>
                getScope() {
            return Set.of();
        }

        @Override
        public org.eclipse.debug.core.ILaunchConfiguration
                getLaunchConfiguration() {
            return null;
        }

        @Override
        public void accept(
                org.jacoco.core.data.IExecutionDataVisitor execVisitor,
                org.jacoco.core.data.ISessionInfoVisitor
                        sessionInfoVisitor) {
        }

        @Override
        public <T> T getAdapter(Class<T> adapter) {
            return null;
        }
    }
}
