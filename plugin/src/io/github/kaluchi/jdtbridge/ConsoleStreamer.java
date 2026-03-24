package io.github.kaluchi.jdtbridge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streams console output from a {@link LaunchTracker.TrackedLaunch}
 * to an {@link OutputStream}. Writes accumulated content first,
 * then live output via listener until the process terminates or
 * the stream is closed.
 */
class ConsoleStreamer {

    /**
     * Stream console output. Blocks until terminated or IOException.
     *
     * @param tl      tracked launch to read from
     * @param out     destination stream (socket, pipe, etc.)
     * @param stream  filter: "stdout", "stderr", or null for both
     * @param tail    number of tail lines for initial dump, -1 for all
     */
    static void stream(LaunchTracker.TrackedLaunch tl,
            OutputStream out, String stream, int tail)
            throws IOException {
        // Write accumulated content
        String existing = tl.getOutput(stream);
        if (tail >= 0) {
            existing = tail(existing, tail);
        }
        writeFlush(out, existing);

        if (tl.terminated) return;

        // Live stream via listener
        LaunchTracker.OutputListener listener = (text, stderr) -> {
            if (!matches(stream, stderr)) return;
            try {
                writeFlush(out, text);
            } catch (IOException e) {
                throw new StreamClosedException(e);
            }
        };

        tl.addOutputListener(listener);
        try {
            awaitTermination(tl);
            synchronized (out) { out.flush(); }
        } finally {
            tl.removeOutputListener(listener);
        }
    }

    private static boolean matches(String filter, boolean stderr) {
        if (filter == null) return true;
        return "stderr".equals(filter) == stderr;
    }

    private static void writeFlush(OutputStream out, String text)
            throws IOException {
        if (text.isEmpty()) return;
        synchronized (out) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void awaitTermination(
            LaunchTracker.TrackedLaunch tl) throws IOException {
        while (!tl.terminated) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    static String tail(String text, int lines) {
        if (lines <= 0 || text.isEmpty()) return text;
        int pos = text.length();
        for (int i = 0; i < lines && pos > 0; i++) {
            pos = text.lastIndexOf('\n', pos - 1);
        }
        return pos <= 0 ? text : text.substring(pos + 1);
    }

    /** Thrown by listener when output stream is closed. */
    static class StreamClosedException extends RuntimeException {
        StreamClosedException(IOException cause) { super(cause); }
    }
}
