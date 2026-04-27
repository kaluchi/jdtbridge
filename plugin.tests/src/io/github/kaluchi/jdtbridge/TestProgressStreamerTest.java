package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link TestProgressStreamer}. The streamer's {@code
 * stream()} blocks until the session ends, so each test runs it
 * on a worker thread and interrupts that thread once the replay
 * has had a chance to complete — exercising the listener
 * lifecycle and the {@code InterruptedException} branch.
 */
@SuppressWarnings("restriction")
public class TestProgressStreamerTest {

    @Nested
    class StreamLifecycle {

        @Test
        void interruptStopsStreamCleanly() throws Exception {
            // The PDE test runner has its own running TestRunSession
            // in JUnitCorePlugin's model. stream() will replay any
            // already-finished cases, then await() — interrupt the
            // worker so the await returns; finally must run and
            // remove the listener.
            TestRunSession session = currentSession();
            if (session == null) return;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AtomicReference<Throwable> error =
                    new AtomicReference<>();

            Thread worker = new Thread(() -> {
                try {
                    TestProgressStreamer.stream(
                            session, baos, null);
                } catch (Throwable th) {
                    error.set(th);
                }
            }, "stream-test-worker");

            worker.start();
            worker.interrupt();
            worker.join(5000);

            assertFalse(worker.isAlive(),
                    "Worker must exit after interrupt");
            assertNull(error.get(),
                    "stream() must not propagate exceptions: "
                            + error.get());
        }

        @Test
        void replayWithFailuresFilterEmitsOnlyFailures()
                throws Exception {
            TestRunSession session = currentSession();
            if (session == null) return;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AtomicReference<Throwable> error =
                    new AtomicReference<>();

            Thread worker = new Thread(() -> {
                try {
                    TestProgressStreamer.stream(
                            session, baos, "failures");
                } catch (Throwable th) {
                    error.set(th);
                }
            }, "stream-test-failures");
            worker.start();
            worker.interrupt();
            worker.join(5000);

            assertFalse(worker.isAlive());
            assertNull(error.get());

            // Whatever was written by the replay must be valid JSONL
            // with status FAIL or ERROR.
            String output = baos.toString(StandardCharsets.UTF_8);
            for (String line : output.split("\n")) {
                if (line.isEmpty()) continue;
                JsonObject obj = JsonParser.parseString(line)
                        .getAsJsonObject();
                String status = obj.get("status").getAsString();
                assertTrue("FAIL".equals(status)
                                || "ERROR".equals(status),
                        "failures filter must yield FAIL/ERROR: "
                                + line);
            }
        }

        @Test
        void replayWithIgnoredFilterEmitsOnlyIgnored()
                throws Exception {
            TestRunSession session = currentSession();
            if (session == null) return;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AtomicReference<Throwable> error =
                    new AtomicReference<>();

            Thread worker = new Thread(() -> {
                try {
                    TestProgressStreamer.stream(
                            session, baos, "ignored");
                } catch (Throwable th) {
                    error.set(th);
                }
            }, "stream-test-ignored");
            worker.start();
            worker.interrupt();
            worker.join(5000);

            assertFalse(worker.isAlive());
            assertNull(error.get());

            String output = baos.toString(StandardCharsets.UTF_8);
            for (String line : output.split("\n")) {
                if (line.isEmpty()) continue;
                JsonObject obj = JsonParser.parseString(line)
                        .getAsJsonObject();
                assertTrue("IGNORED".equals(
                        obj.get("status").getAsString()),
                        "ignored filter must yield IGNORED: " + line);
            }
        }

        @Test
        void replayWithNullFilterAllowsAnyStatus()
                throws Exception {
            TestRunSession session = currentSession();
            if (session == null) return;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            AtomicReference<Throwable> error =
                    new AtomicReference<>();

            Thread worker = new Thread(() -> {
                try {
                    TestProgressStreamer.stream(
                            session, baos, null);
                } catch (Throwable th) {
                    error.set(th);
                }
            }, "stream-test-null-filter");
            worker.start();
            worker.interrupt();
            worker.join(5000);

            assertFalse(worker.isAlive());
            assertNull(error.get());

            // Any line written must at least carry event=case + fqn.
            String output = baos.toString(StandardCharsets.UTF_8);
            for (String line : output.split("\n")) {
                if (line.isEmpty()) continue;
                JsonObject obj = JsonParser.parseString(line)
                        .getAsJsonObject();
                assertTrue("case".equals(
                        obj.get("event").getAsString()),
                        "Every line must be event=case: " + line);
                assertNotNull(obj.get("fqn"),
                        "Every line must carry fqn: " + line);
            }
        }
    }

    private static TestRunSession currentSession() {
        List<TestRunSession> sessions =
                JUnitCorePlugin.getModel().getTestRunSessions();
        return sessions.isEmpty() ? null : sessions.get(0);
    }
}
