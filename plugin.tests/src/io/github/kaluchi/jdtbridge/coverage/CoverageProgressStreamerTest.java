package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
            CoverageProgressStreamer.stream(out, null, tracker);
            JsonObject obj = parseLine(out, 0);
            assertEquals("failed",
                    obj.get("event").getAsString());
            assertEquals("missing-coverageId",
                    obj.get("reason").getAsString());
        }

        @Test
        void blankCoverageIdEmitsFailedEvent() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, "  ", tracker);
            JsonObject obj = parseLine(out, 0);
            assertEquals("failed",
                    obj.get("event").getAsString());
        }

        @Test
        void unknownCoverageIdEmitsFailed() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, "Bogus:9999",
                    tracker);
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
                    tracker);
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
                    tracker);
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
                    tracker);
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
                    tracker);
            JsonObject snap = parseLine(out, 0);
            assertEquals("imported",
                    snap.get("coverageSessionKind").getAsString());
        }
    }

    @Nested
    class Newlines {

        @Test
        void everyEventIsOneLine() {
            String coverageId = importAndAwait("oneline");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CoverageProgressStreamer.stream(out, coverageId,
                    tracker);
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
                    tracker);
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
}
