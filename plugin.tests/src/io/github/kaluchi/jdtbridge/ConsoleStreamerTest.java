package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.debug.core.ILaunch;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConsoleStreamer} — streaming console output
 * and the tail utility.
 */
public class ConsoleStreamerTest {

    @Test
    void utilityClassInstantiable() {
        org.junit.jupiter.api.Assertions.assertNotNull(
                new ConsoleStreamer());
    }

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
        void liveStderrFilteredFromStdout()
                throws Exception {
            var tl = newTrackedLaunch();
            var out = new ByteArrayOutputStream();
            var future = streamAsync(tl, out, "stdout", -1);
            Thread.sleep(100);
            tl.appendErr("stderr-noise");
            tl.appendOut("stdout-signal\n");
            tl.terminated = true;
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(out.toString().contains("stdout-signal"));
            assertFalse(out.toString().contains("stderr-noise"));
        }

        @Test
        void interruptedThreadStopsStreaming()
                throws Exception {
            var tl = newTrackedLaunch();
            var out = new ByteArrayOutputStream();
            var future = streamAsync(tl, out, null, -1);
            Thread.sleep(100);
            future.cancel(true);
            assertFalse(tl.terminated);
        }

        @Test
        void closedOutputThrowsStreamClosedException()
                throws Exception {
            assertNull(catchAppendException(newTrackedLaunch()));

            var tl = newTrackedLaunch();
            var failingOut = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("closed");
                }
            };
            streamAsync(tl, failingOut, null, -1);
            Thread.sleep(100);
            assertEquals(ConsoleStreamer.StreamClosedException.class,
                    catchAppendException(tl));
            tl.terminated = true;
        }

        private static Class<?> catchAppendException(
                LaunchTracker.TrackedLaunch tl) {
            try {
                tl.appendOut("trigger\n");
                return null;
            } catch (RuntimeException e) {
                return e.getClass();
            }
        }

        @Test
        void liveOutputDeliveredOnTermination()
                throws Exception {
            var tl = newTrackedLaunch();
            var out = new ByteArrayOutputStream();
            var future = streamAsync(tl, out, null, -1);
            Thread.sleep(100);
            tl.appendOut("live-data\n");
            tl.terminated = true;
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(out.toString().contains("live-data"),
                    "Should have live data: " + out);
        }

        private static LaunchTracker.TrackedLaunch
                newTrackedLaunch() {
            return new LaunchTracker.TrackedLaunch(
                    new org.eclipse.debug.core.Launch(
                            null, "run", null));
        }

        private static java.util.concurrent.Future<?>
                streamAsync(LaunchTracker.TrackedLaunch tl,
                        OutputStream out, String stream,
                        int tail) {
            return java.util.concurrent.Executors
                    .newSingleThreadExecutor()
                    .submit(() -> {
                        ConsoleStreamer.stream(tl, out,
                                stream, tail);
                        return null;
                    });
        }
    }
}
