package io.github.kaluchi.jdtbridge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streams test progress events from a
 * {@link TestSessionTracker.TrackedTestSession} to an
 * {@link OutputStream} as JSONL (one JSON object per line).
 * Writes accumulated events first, then live events via
 * listener until the session finishes or the stream is closed.
 */
class TestProgressStreamer {

    /**
     * Stream test events. Blocks until session finishes or
     * IOException.
     *
     * @param ts     tracked test session
     * @param out    destination stream (socket output)
     * @param filter "all", "failures", "ignored", or null
     *               (defaults to "all" for streaming)
     */
    static void stream(TestSessionTracker.TrackedTestSession ts,
            OutputStream out, String filter) throws IOException {
        // Listener FIRST — so no events are lost between
        // replay and registration. Duplicate delivery from
        // replay is harmless; a gap is not.
        // (Same pattern as LaunchTracker.attachStreams)
        TestSessionTracker.TestEventListener listener = line -> {
            if (!matchesFilter(line, filter)) return;
            try {
                writeLine(out, line);
            } catch (IOException e) {
                throw new StreamClosedException(e);
            }
        };

        ts.addListener(listener);
        try {
            // Replay accumulated events (may duplicate with
            // listener, but CopyOnWriteArrayList snapshot is
            // consistent and duplicates are harmless)
            for (String event : ts.events) {
                if (matchesFilter(event, filter)) {
                    writeLine(out, event);
                }
            }

            if ("finished".equals(ts.state)
                    || "stopped".equals(ts.state)) {
                return;
            }

            awaitCompletion(ts);
            synchronized (out) { out.flush(); }
        } finally {
            ts.removeListener(listener);
        }
    }

    private static boolean matchesFilter(String jsonLine,
            String filter) {
        if (filter == null || "all".equals(filter)) return true;

        // Quick check without full parse
        if (jsonLine.contains("\"event\":\"started\"")
                || jsonLine.contains("\"event\":\"finished\"")) {
            return true; // always include session events
        }

        if ("failures".equals(filter)) {
            return jsonLine.contains("\"status\":\"FAIL\"")
                    || jsonLine.contains("\"status\":\"ERROR\"");
        }
        if ("ignored".equals(filter)) {
            return jsonLine.contains("\"status\":\"IGNORED\"");
        }
        return true;
    }

    private static void writeLine(OutputStream out, String line)
            throws IOException {
        synchronized (out) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        }
    }

    private static void awaitCompletion(
            TestSessionTracker.TrackedTestSession ts)
            throws IOException {
        while ("running".equals(ts.state)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @SuppressWarnings("serial")
    static class StreamClosedException extends RuntimeException {
        StreamClosedException(IOException cause) { super(cause); }
    }
}
