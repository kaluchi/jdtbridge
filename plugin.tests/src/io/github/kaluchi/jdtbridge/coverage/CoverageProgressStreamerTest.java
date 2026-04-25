package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link CoverageProgressStreamer}. Streams against
 * imported (already-terminal) sessions: the streamer emits
 * {@code snapshot} + {@code terminated} and returns immediately,
 * which is enough to assert the JSONL wire shape and event order
 * without spawning a real launch.
 */
public class CoverageProgressStreamerTest {

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
    class GuardErrors {

        @Test
        void missingCoverageIdEmitsFailedEvent() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, null, tracker, analyzer);
            JsonObject obj = parseLine(out, 0);
            assertEquals("failed",
                    obj.get("event").getAsString());
            assertEquals("missing-coverageId",
                    obj.get("reason").getAsString());
        }

        @Test
        void blankCoverageIdEmitsFailedEvent() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, "  ", tracker, analyzer);
            JsonObject obj = parseLine(out, 0);
            assertEquals("failed",
                    obj.get("event").getAsString());
        }

        @Test
        void unknownCoverageIdEmitsFailed() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, "Bogus:9999",
                    tracker, analyzer);
            JsonObject obj = parseLine(out, 0);
            assertEquals("failed",
                    obj.get("event").getAsString());
            assertEquals("coverage-not-found",
                    obj.get("reason").getAsString());
        }
    }

    @Nested
    class TerminalSession {

        @Test
        void importedSessionEmitsSnapshotThenTerminated() {
            String coverageId = importAndAwait("stream-terminal");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, coverageId,
                    tracker, analyzer);
            String[] lines = lines(out);
            assertEquals(2, lines.length,
                    "Expected snapshot + terminated lines, got: "
                            + java.util.Arrays.toString(lines));
            JsonObject snap = JsonParser.parseString(lines[0])
                    .getAsJsonObject();
            JsonObject term = JsonParser.parseString(lines[1])
                    .getAsJsonObject();
            assertEquals("snapshot",
                    snap.get("event").getAsString());
            assertEquals(coverageId,
                    snap.get("coverageId").getAsString());
            assertEquals("terminated",
                    term.get("event").getAsString());
            assertEquals(coverageId,
                    term.get("coverageId").getAsString());
        }

        @Test
        void snapshotCarriesAllStatusFlags() {
            String coverageId = importAndAwait("snap-flags");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, coverageId,
                    tracker, analyzer);
            JsonObject snap = parseLine(out, 0);
            for (String key : new String[] {
                    "coverageId", "coverageSessionKind",
                    "terminated", "dataReceived",
                    "analysisLoading", "analysisReady",
                    "dumpCount"
            }) {
                assertTrue(snap.has(key),
                        "Missing snapshot field '" + key + "': "
                                + snap);
            }
        }

        @Test
        void terminatedEventCarriesDataReceived() {
            String coverageId = importAndAwait("term-data");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, coverageId,
                    tracker, analyzer);
            JsonObject term = parseLine(out, 1);
            assertTrue(term.get("dataReceived").getAsBoolean());
            assertNotNull(term.get("terminatedAt"),
                    "terminated event should carry terminatedAt: "
                            + term);
        }

        @Test
        void importedRunSessionKindIsImported() {
            String coverageId = importAndAwait("kind-imported");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, coverageId,
                    tracker, analyzer);
            JsonObject snap = parseLine(out, 0);
            assertEquals("imported",
                    snap.get("coverageSessionKind").getAsString());
        }
    }

    @Nested
    class LiveEvents {

        /** Drive the streamer through a non-terminal run by forcing
         *  {@code terminated=false} on a freshly imported session
         *  (imported sessions are normally terminal). Lets us
         *  exercise the listener-loop path without spawning a real
         *  coverage launch. */
        @Test
        void analysisReadyEventCarriesCounters() throws Exception {
            String coverageId = importAndAwait("ready-counters");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.terminated = false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread worker = new Thread(() ->
                    CoverageProgressStreamer.stream(out, coverageId,
                            tracker, analyzer));
            worker.start();
            awaitListener(coverageId);

            tracker.eventBus().fire(coverageId,
                    l -> l.onAnalysisReady(run));

            run.terminated = true;
            tracker.eventBus().fire(coverageId,
                    l -> l.onTerminated(run));
            worker.join(5000);

            JsonObject readyEvent = findEvent(out, "analysisReady");
            assertNotNull(readyEvent,
                    "Expected analysisReady event in stream output");
            assertTrue(readyEvent.has("counters"),
                    "analysisReady must carry counters: "
                            + readyEvent);
        }

        @Test
        void onFailedEmitsAnalysisCancelledEvent() throws Exception {
            String coverageId = importAndAwait("cancel-test");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.terminated = false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread worker = new Thread(() ->
                    CoverageProgressStreamer.stream(out, coverageId,
                            tracker, analyzer));
            worker.start();
            awaitListener(coverageId);

            tracker.eventBus().fire(coverageId,
                    l -> l.onFailed(run, "analysis-cancelled"));
            worker.join(5000);

            JsonObject failedEvent = findEvent(out, "failed");
            assertNotNull(failedEvent,
                    "Expected failed event in stream output");
            assertEquals("analysis-cancelled",
                    failedEvent.get("reason").getAsString());
            assertEquals(coverageId,
                    failedEvent.get("coverageId").getAsString());
        }

        @Test
        void analysisLoadingEventFiresThroughBus() throws Exception {
            String coverageId = importAndAwait("loading-test");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.terminated = false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread worker = new Thread(() ->
                    CoverageProgressStreamer.stream(out, coverageId,
                            tracker, analyzer));
            worker.start();
            awaitListener(coverageId);

            tracker.eventBus().fire(coverageId,
                    l -> l.onAnalysisLoading(run));
            run.terminated = true;
            tracker.eventBus().fire(coverageId,
                    l -> l.onTerminated(run));
            worker.join(5000);

            assertNotNull(findEvent(out, "analysisLoading"));
        }

        @Test
        void terminatedDuringLoadingDoesNotCloseUntilReady()
                throws Exception {
            // Real coverage launches fire terminated BEFORE
            // analysisReady (LoadSessionJob completes after the
            // JVM exits). Stream must stay open through the
            // terminated event and close only when analysisReady
            // arrives — otherwise consumers never see counters.
            String coverageId = importAndAwait("late-ready");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.terminated = false;
            run.analysisLoading = true;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread worker = new Thread(() ->
                    CoverageProgressStreamer.stream(out, coverageId,
                            tracker, analyzer));
            worker.start();
            awaitListener(coverageId);

            // Step 1: terminate — stream still has analysisLoading
            // pending, must NOT close.
            run.terminated = true;
            tracker.eventBus().fire(coverageId,
                    l -> l.onTerminated(run));
            // Yield + brief sleep so the worker observes the
            // event without false-completing.
            Thread.sleep(100);
            assertTrue(worker.isAlive(),
                    "Stream must stay open after terminated when"
                            + " analysisLoading is still true");

            // Step 2: analysis finishes — now stream closes.
            run.analysisLoading = false;
            run.analysisReady = true;
            tracker.eventBus().fire(coverageId,
                    l -> l.onAnalysisReady(run));
            worker.join(5000);

            String body = out.toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"event\":\"terminated\""),
                    "terminated event must be present: " + body);
            assertTrue(body.contains("\"event\":\"analysisReady\""),
                    "analysisReady must be present after terminated"
                            + ": " + body);
        }

        @Test
        void dumpedEventCarriesIndexAndTimestamp() throws Exception {
            String coverageId = importAndAwait("dumped-test");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.terminated = false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread worker = new Thread(() ->
                    CoverageProgressStreamer.stream(out, coverageId,
                            tracker, analyzer));
            worker.start();
            awaitListener(coverageId);

            long t = System.currentTimeMillis();
            tracker.eventBus().fire(coverageId,
                    l -> l.onDumped(run, 7, t));
            run.terminated = true;
            tracker.eventBus().fire(coverageId,
                    l -> l.onTerminated(run));
            worker.join(5000);

            JsonObject dumped = findEvent(out, "dumped");
            assertNotNull(dumped);
            assertEquals(7, dumped.get("dumpIndex").getAsInt());
            assertEquals(t, dumped.get("dumpTimestamp").getAsLong());
        }
    }

    @Nested
    class ListenerCleanup {

        /** Reproduces the leak fixed by safeWrite: when a listener
         *  callback throws StreamClosedException because the peer
         *  has gone away, the streamer must trip its latch and
         *  unsubscribe — not sit on done.await() forever with an
         *  orphaned listener still registered. */
        @Test
        void brokenPipeUnsubscribesListenerAndExits()
                throws Exception {
            String coverageId = importAndAwait("leak-test");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.terminated = false;

            BreakAfterFirstLine out = new BreakAfterFirstLine();
            Thread worker = new Thread(() ->
                    CoverageProgressStreamer.stream(out, coverageId,
                            tracker, analyzer));
            worker.start();
            awaitListener(coverageId);

            tracker.eventBus().fire(coverageId,
                    l -> l.onDumped(run, 1, 0L));
            worker.join(5000);

            assertFalse(worker.isAlive(),
                    "Streamer thread should have exited after"
                            + " broken-pipe event");
            assertFalse(tracker.eventBus().hasListeners(coverageId),
                    "Listener must be unsubscribed after"
                            + " broken-pipe failure (leak fix)");
        }

        @Test
        void multipleSubscribersIndependent() throws Exception {
            String coverageId = importAndAwait("multi-sub");
            CoverageRun run = tracker.byCoverageId(coverageId);
            run.terminated = false;

            ByteArrayOutputStream a = new ByteArrayOutputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            Thread wa = new Thread(() ->
                    CoverageProgressStreamer.stream(a, coverageId,
                            tracker, analyzer));
            Thread wb = new Thread(() ->
                    CoverageProgressStreamer.stream(b, coverageId,
                            tracker, analyzer));
            wa.start(); wb.start();

            // Wait until both worker threads have registered as
            // subscribers. CoverageEventBus.hasListeners returns
            // true once the first subscribes; we want both, so we
            // also wait for both to write the snapshot line.
            for (int i = 0; i < 5000 && (
                    countLines(a.toString(StandardCharsets.UTF_8)) < 1
                    || countLines(b.toString(StandardCharsets.UTF_8)) < 1
                    ); i++) {
                Thread.sleep(1);
            }

            tracker.eventBus().fire(coverageId,
                    l -> l.onAnalysisReady(run));
            run.terminated = true;
            tracker.eventBus().fire(coverageId,
                    l -> l.onTerminated(run));

            wa.join(5000);
            wb.join(5000);

            assertNotNull(findEvent(a, "analysisReady"));
            assertNotNull(findEvent(b, "analysisReady"));
        }
    }

    @Nested
    class Newlines {

        @Test
        void everyEventIsOneLine() {
            String coverageId = importAndAwait("oneline");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, coverageId,
                    tracker, analyzer);
            String body = out.toString(
                    java.nio.charset.StandardCharsets.UTF_8);
            // Expect exactly two LF-terminated records.
            long lfCount = body.chars().filter(c -> c == '\n').count();
            assertEquals(2, lfCount,
                    "Expected 2 newlines, got " + lfCount + " in: "
                            + body);
        }

        @Test
        void noTrailingTextAfterLastNewline() {
            String coverageId = importAndAwait("no-trailer");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, coverageId,
                    tracker, analyzer);
            String body = out.toString(
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.endsWith("\n"),
                    "Body must end with newline: " + body);
        }
    }

    // -- helpers --

    private static JsonObject parseLine(ByteArrayOutputStream out,
            int index) {
        return JsonParser.parseString(lines(out)[index])
                .getAsJsonObject();
    }

    private static String[] lines(ByteArrayOutputStream out) {
        String body = out.toString(
                java.nio.charset.StandardCharsets.UTF_8);
        return body.split("\n");
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

    /** Wait for the streamer's listener to register before firing
     *  events. 5s budget — test-only, real flow doesn't need this. */
    private void awaitListener(String coverageId)
            throws InterruptedException {
        for (int i = 0; i < 5000
                && !tracker.eventBus().hasListeners(coverageId); i++) {
            Thread.sleep(1);
        }
        if (!tracker.eventBus().hasListeners(coverageId)) {
            throw new AssertionError(
                    "Streamer never subscribed for " + coverageId);
        }
    }

    /** Locate the first JSONL line in {@code out} whose {@code event}
     *  field equals {@code name}. Returns {@code null} when absent. */
    private static JsonObject findEvent(ByteArrayOutputStream out,
            String name) {
        for (String line : lines(out)) {
            if (line.isEmpty()) continue;
            JsonObject obj;
            try {
                obj = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }
            if (obj.has("event")
                    && name.equals(obj.get("event").getAsString())) {
                return obj;
            }
        }
        return null;
    }

    private static int countLines(String body) {
        return (int) body.chars().filter(c -> c == '\n').count();
    }

    /** OutputStream that succeeds for the first JSONL record (so the
     *  snapshot lands and the listener registers) and fails on any
     *  subsequent write — simulates a peer that disconnects after
     *  the first line. */
    private static final class BreakAfterFirstLine extends OutputStream {
        private boolean broken = false;
        @Override
        public void write(int b) throws IOException {
            if (broken) {
                throw new IOException("simulated broken pipe");
            }
            if (b == '\n') {
                broken = true;
            }
        }
        @Override
        public void flush() throws IOException {
            // Snapshot path calls flush after writing all bytes;
            // first call must succeed, later calls behave like the
            // pipe stayed broken.
            if (broken) {
                // After the newline turned `broken` true the next
                // listener-callback write will hit it. flush itself
                // is a no-op for ByteArrayOutputStream-style sinks,
                // so leave it intact here — the failure path is
                // exercised by `write` above.
            }
        }
    }
}
