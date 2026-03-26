package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TestProgressStreamer} — streaming test
 * progress events and filter matching.
 */
public class TestProgressStreamerTest {

    @Nested
    class StreamCompleted {

        @Test
        void streamsAccumulatedEventsForFinished()
                throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"started\",\"session\":\"test-1\"}");
            ts.emit("{\"event\":\"case\",\"status\":\"PASS\"}");
            ts.emit("{\"event\":\"finished\",\"session\":\"test-1\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, null);

            String result = out.toString();
            assertTrue(result.contains("\"event\":\"started\""),
                    "Should have started: " + result);
            assertTrue(result.contains("\"event\":\"case\""),
                    "Should have case: " + result);
            assertTrue(result.contains("\"event\":\"finished\""),
                    "Should have finished: " + result);
            // Each event is a line
            String[] lines = result.trim().split("\n");
            assertEquals(3, lines.length,
                    "Should have 3 lines: " + result);
        }

        @Test
        void filterFailuresOnlyStreamsFails()
                throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"started\",\"session\":\"test-1\"}");
            ts.emit("{\"event\":\"case\",\"status\":\"PASS\","
                    + "\"fqmn\":\"a.B#pass\"}");
            ts.emit("{\"event\":\"case\",\"status\":\"FAIL\","
                    + "\"fqmn\":\"a.B#fail\"}");
            ts.emit("{\"event\":\"finished\",\"session\":\"test-1\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "failures");

            String result = out.toString();
            assertTrue(result.contains("\"event\":\"started\""),
                    "Should have started: " + result);
            assertTrue(result.contains("\"status\":\"FAIL\""),
                    "Should have FAIL: " + result);
            assertTrue(result.contains("\"event\":\"finished\""),
                    "Should have finished: " + result);
            assertFalse(result.contains("\"status\":\"PASS\""),
                    "Should not have PASS: " + result);
        }

        @Test
        void filterIgnoredOnlyStreamsIgnored()
                throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"started\",\"session\":\"test-1\"}");
            ts.emit("{\"event\":\"case\",\"status\":\"PASS\","
                    + "\"fqmn\":\"a.B#pass\"}");
            ts.emit("{\"event\":\"case\",\"status\":\"IGNORED\","
                    + "\"fqmn\":\"a.B#skip\"}");
            ts.emit("{\"event\":\"finished\",\"session\":\"test-1\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "ignored");

            String result = out.toString();
            assertTrue(result.contains("\"event\":\"started\""),
                    "Should have started: " + result);
            assertTrue(result.contains("\"status\":\"IGNORED\""),
                    "Should have IGNORED: " + result);
            assertTrue(result.contains("\"event\":\"finished\""),
                    "Should have finished: " + result);
            assertFalse(result.contains("\"status\":\"PASS\""),
                    "Should not have PASS: " + result);
        }

        @Test
        void filterAllStreamsEverything() throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"case\",\"status\":\"PASS\"}");
            ts.emit("{\"event\":\"case\",\"status\":\"FAIL\"}");
            ts.emit("{\"event\":\"case\",\"status\":\"IGNORED\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "all");

            String result = out.toString();
            assertTrue(result.contains("\"status\":\"PASS\""),
                    "Should have PASS: " + result);
            assertTrue(result.contains("\"status\":\"FAIL\""),
                    "Should have FAIL: " + result);
            assertTrue(result.contains("\"status\":\"IGNORED\""),
                    "Should have IGNORED: " + result);
        }

        @Test
        void listenerBeforeReplayPreventsEventLoss()
                throws Exception {
            // Verify the listener-first pattern: events emitted
            // during iteration should not be lost
            var ts = new TestSessionTracker.TrackedTestSession(
                    "race-test");

            // Pre-populate some events
            ts.emit("{\"event\":\"started\",\"total\":2}");
            ts.emit("{\"event\":\"case\",\"fqmn\":\"A#b\","
                    + "\"status\":\"PASS\",\"time\":0.1}");
            ts.state = "finished";
            ts.emit("{\"event\":\"finished\","
                    + "\"total\":2,\"passed\":2}");

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "all");
            String result = out.toString();

            // All events should be present
            assertTrue(result.contains("started"),
                    "Should have started: " + result);
            assertTrue(result.contains("A#b"),
                    "Should have test case: " + result);
            assertTrue(result.contains("finished"),
                    "Should have finished: " + result);
        }

        @Test
        void emptySessionStreamsNothing() throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, null);

            assertEquals("", out.toString());
        }
    }

    @Nested
    class StreamLive {

        @Test
        void liveEventsDeliveredOnCompletion()
                throws Exception {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "live-1");

            var out = new ByteArrayOutputStream();

            var streamThread = new Thread(() -> {
                try {
                    TestProgressStreamer.stream(ts, out, null);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            streamThread.start();

            // Let streamer register listener
            Thread.sleep(100);

            // Add event while streaming
            ts.emit("{\"event\":\"case\",\"status\":\"PASS\","
                    + "\"fqmn\":\"a.B#live\"}");

            // Finish session
            ts.state = "finished";

            streamThread.join(5000);
            assertFalse(streamThread.isAlive(),
                    "Stream should have ended");

            String result = out.toString();
            assertTrue(result.contains("a.B#live"),
                    "Should have live event: " + result);
        }

        @Test
        void stoppedSessionEndsStream() throws Exception {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "stop-1");

            var out = new ByteArrayOutputStream();

            var streamThread = new Thread(() -> {
                try {
                    TestProgressStreamer.stream(ts, out, null);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            streamThread.start();

            // Let streamer register listener
            Thread.sleep(100);

            ts.emit("{\"event\":\"case\",\"status\":\"PASS\"}");

            // Stop instead of finish
            ts.state = "stopped";

            streamThread.join(5000);
            assertFalse(streamThread.isAlive(),
                    "Stream should have ended");
        }
    }

    @Nested
    class FilterMatching {

        @Test
        void startedEventAlwaysPasses() throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"started\",\"session\":\"test-1\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "failures");
            assertTrue(out.toString().contains(
                    "\"event\":\"started\""),
                    "Started should pass any filter");
        }

        @Test
        void finishedEventAlwaysPasses() throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"finished\",\"session\":\"test-1\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "ignored");
            assertTrue(out.toString().contains(
                    "\"event\":\"finished\""),
                    "Finished should pass any filter");
        }

        @Test
        void passExcludedByFailuresFilter() throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"case\",\"status\":\"PASS\","
                    + "\"fqmn\":\"a.B#ok\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "failures");
            assertFalse(out.toString().contains(
                    "\"status\":\"PASS\""),
                    "PASS should be excluded by failures filter");
        }

        @Test
        void failIncludedByFailuresFilter() throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"case\",\"status\":\"FAIL\","
                    + "\"fqmn\":\"a.B#bad\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "failures");
            assertTrue(out.toString().contains(
                    "\"status\":\"FAIL\""),
                    "FAIL should be included by failures filter");
        }

        @Test
        void ignoredIncludedByIgnoredFilter() throws IOException {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("{\"event\":\"case\",\"status\":\"IGNORED\","
                    + "\"fqmn\":\"a.B#skip\"}");
            ts.state = "finished";

            var out = new ByteArrayOutputStream();
            TestProgressStreamer.stream(ts, out, "ignored");
            assertTrue(out.toString().contains(
                    "\"status\":\"IGNORED\""),
                    "IGNORED should be included by ignored filter");
        }
    }
}
