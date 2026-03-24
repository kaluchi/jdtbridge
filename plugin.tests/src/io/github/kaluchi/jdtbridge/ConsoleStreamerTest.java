package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.eclipse.debug.core.ILaunch;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for {@link ConsoleStreamer} — streaming console output
 * and the tail utility.
 */
public class ConsoleStreamerTest {

    @Nested
    class Tail {

        @Test
        void tailLastLine() {
            assertEquals("c",
                    ConsoleStreamer.tail("a\nb\nc", 1));
        }

        @Test
        void tailLastTwoLines() {
            assertEquals("b\nc",
                    ConsoleStreamer.tail("a\nb\nc", 2));
        }

        @Test
        void tailMoreThanAvailable() {
            assertEquals("a\nb",
                    ConsoleStreamer.tail("a\nb", 10));
        }

        @Test
        void tailEmptyString() {
            assertEquals("",
                    ConsoleStreamer.tail("", 5));
        }

        @Test
        void tailZeroReturnsAll() {
            assertEquals("a\nb\nc",
                    ConsoleStreamer.tail("a\nb\nc", 0));
        }
    }

    @Nested
    @EnabledIfSystemProperty(
            named = "jdtbridge.integration-tests",
            matches = "true")
    class Stream {

        @Test
        void streamsAccumulatedContentForTerminated()
                throws Exception {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            tl.appendOut("line1\nline2\n");
            tl.terminated = true;

            var out = new ByteArrayOutputStream();
            ConsoleStreamer.stream(tl, out, null, -1);
            assertEquals("line1\nline2\n", out.toString());
        }

        @Test
        void streamsWithTail() throws Exception {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            tl.appendOut("a\nb\nc\nd\n");
            tl.terminated = true;

            // tail=3 because trailing \n counts as a line
            var out = new ByteArrayOutputStream();
            ConsoleStreamer.stream(tl, out, null, 3);
            String result = out.toString();
            assertTrue(result.contains("c"),
                    "Should have line c: " + result);
            assertTrue(result.contains("d"),
                    "Should have line d: " + result);
            assertFalse(result.contains("a"),
                    "Should not have line a: " + result);
        }

        @Test
        void filtersStdoutOnly() throws Exception {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            tl.appendOut("stdout-data");
            tl.appendErr("stderr-data");
            tl.terminated = true;

            var out = new ByteArrayOutputStream();
            ConsoleStreamer.stream(tl, out, "stdout", -1);
            assertEquals("stdout-data", out.toString());
        }

        @Test
        void filtersStderrOnly() throws Exception {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            tl.appendOut("stdout-data");
            tl.appendErr("stderr-data");
            tl.terminated = true;

            var out = new ByteArrayOutputStream();
            ConsoleStreamer.stream(tl, out, "stderr", -1);
            assertEquals("stderr-data", out.toString());
        }

        @Test
        void liveOutputDeliveredOnTermination()
                throws Exception {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);

            var out = new ByteArrayOutputStream();

            // Stream in background, terminate after adding content
            var streamThread = new Thread(() -> {
                try {
                    ConsoleStreamer.stream(tl, out, null, -1);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            streamThread.start();

            // Let streamer register listener
            Thread.sleep(100);

            // Add content while streaming
            tl.appendOut("live-data\n");

            // Terminate
            tl.terminated = true;

            streamThread.join(5000);
            assertFalse(streamThread.isAlive(),
                    "Stream should have ended");

            String result = out.toString();
            assertTrue(result.contains("live-data"),
                    "Should have live data: " + result);
        }
    }
}
