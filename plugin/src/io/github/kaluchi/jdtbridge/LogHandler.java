package io.github.kaluchi.jdtbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import com.google.gson.JsonArray;

import org.eclipse.core.runtime.Platform;

/**
 * Expose the Eclipse workspace log ({@code .metadata/.log}) over
 * HTTP. Canonical form returns the most recent N entries as a Vec
 * of :logEntry node-Maps parsed from the {@code !ENTRY} /
 * {@code !STACK} layout the platform writes; the text preceding
 * the first {@code !ENTRY} is treated as a header and ignored.
 *
 * Ordering: oldest first within the returned window. Callers that
 * want tail order reverse on the qlang side.
 */
class LogHandler {

    /**
     * Handle GET /log. Params: {@code tail} (default 100) bounds
     * how many trailing entries to return.
     */
    String handleLog(Map<String, String> params) {
        Path logFile = resolveLogPath();
        if (logFile == null || !Files.exists(logFile)) {
            return new JsonArray().toString();
        }

        int tail;
        try {
            tail = Integer.parseInt(params.getOrDefault("tail", "100"));
            if (tail <= 0) tail = 100;
        } catch (NumberFormatException e) {
            tail = 100;
        }

        String content;
        try {
            content = readTailBytes(logFile, MAX_READ_BYTES);
        } catch (IOException e) {
            return ErrorDescriptor.ioError(
                    "Cannot read " + logFile + ": " + e.getMessage())
                    .toJsonString();
        }

        var all = parseEntries(content);
        int from = Math.max(0, all.size() - tail);
        var arr = new JsonArray();
        for (int i = from; i < all.size(); i++) {
            arr.add(all.get(i).toJson());
        }
        return arr.toString();
    }

    /** Cap on how many bytes off the tail of .log are read per request. */
    private static final long MAX_READ_BYTES = 16L * 1024 * 1024;

    /**
     * Read at most {@code maxBytes} off the end of {@code path}.
     * Shorter files are read whole. Truncated reads are aligned to
     * the next {@code \n} so the result always starts on a line
     * boundary (the partial prefix before the first {@code !ENTRY}
     * is dropped by {@link #parseEntries}).
     */
    /** Package-private for direct testing — not part of the API. */
    static String readTailBytes(Path path, long maxBytes)
            throws IOException {
        long size = Files.size(path);
        if (size <= maxBytes) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        try (SeekableByteChannel ch = Files.newByteChannel(path)) {
            ch.position(size - maxBytes);
            ByteBuffer buf = ByteBuffer.allocate((int) maxBytes);
            while (buf.hasRemaining() && ch.read(buf) > 0) { /* drain */ }
            buf.flip();
            // Align to the next newline so we never hand parseEntries
            // half of a multi-byte UTF-8 sequence or a truncated
            // !ENTRY header.
            int start = 0;
            while (start < buf.limit()
                    && buf.get(start) != (byte) '\n') {
                start++;
            }
            if (start < buf.limit()) start++;
            return new String(buf.array(), start,
                    buf.limit() - start, StandardCharsets.UTF_8);
        }
    }

    /**
     * Resolve the current workspace's {@code .metadata/.log} path.
     * Uses {@link Platform#getLogFileLocation()} with a fallback via
     * {@link Platform#getInstanceLocation()} for the corner case where
     * the system property is unset.
     */
    private static Path resolveLogPath() {
        try {
            var path = Platform.getLogFileLocation();
            if (path != null) return Path.of(path.toOSString());
        } catch (Exception ignored) { /* fall through */ }
        try {
            var instance = Platform.getInstanceLocation();
            if (instance != null && instance.getURL() != null) {
                return Path.of(instance.getURL().toURI())
                        .resolve(".metadata").resolve(".log");
            }
        } catch (Exception ignored) { /* give up */ }
        return null;
    }

    /** Parsed {@code !ENTRY} block. */
    record Entry(int severity, String bundle, int code,
            String timestamp, String message, String stack) {

        com.google.gson.JsonObject toJson() {
            var obj = new com.google.gson.JsonObject();
            obj.addProperty("kind", "logEntry");
            obj.addProperty("severity", severityName(severity));
            obj.addProperty("bundle", bundle);
            obj.addProperty("timestamp", timestamp);
            obj.addProperty("message", message);
            if (stack != null && !stack.isEmpty()) {
                obj.addProperty("stack", stack);
            }
            return obj;
        }

        private static String severityName(int sev) {
            return switch (sev) {
                case 1 -> "info";
                case 2 -> "warning";
                case 4 -> "error";
                case 8 -> "cancel";
                default -> "unknown";
            };
        }
    }

    /**
     * Parse the full .log content into a list of {@link Entry}.
     * Eclipse format per entry:
     * <pre>
     * !ENTRY &lt;bundle&gt; &lt;severity&gt; &lt;code&gt; &lt;timestamp&gt;
     * !MESSAGE &lt;one-line message&gt;
     * !STACK &lt;code&gt;
     * &lt;multi-line stack trace&gt;
     * </pre>
     * Blocks start at {@code !ENTRY} and end at the next
     * {@code !ENTRY} or EOF. Text before the first {@code !ENTRY}
     * is workspace startup preamble — discarded.
     */
    static java.util.List<Entry> parseEntries(String content) {
        var out = new ArrayList<Entry>();
        String[] lines = content.split("\\R");
        int i = 0;
        // Skip preamble.
        while (i < lines.length && !lines[i].startsWith("!ENTRY ")) i++;
        while (i < lines.length) {
            String entryLine = lines[i++];
            if (!entryLine.startsWith("!ENTRY ")) continue;
            String[] parts = entryLine.substring(7).split(" ", 4);
            String bundle = parts.length > 0 ? parts[0] : "";
            int severity = parts.length > 1
                    ? Parsing.parseIntOr(parts[1], 0) : 0;
            int code = parts.length > 2
                    ? Parsing.parseIntOr(parts[2], 0) : 0;
            String timestamp = parts.length > 3 ? parts[3] : "";

            String message = "";
            var stack = new StringBuilder();
            while (i < lines.length && !lines[i].startsWith("!ENTRY ")) {
                String line = lines[i++];
                if (line.startsWith("!MESSAGE ")) {
                    message = line.substring(9);
                } else if (line.startsWith("!STACK ")) {
                    // consume; stack text follows
                } else if (line.startsWith("!SUBENTRY ")
                        || line.startsWith("!SESSION ")) {
                    // tolerate — append to stack so it's not lost
                    stack.append(line).append('\n');
                } else if (!line.isEmpty()) {
                    stack.append(line).append('\n');
                }
            }
            out.add(new Entry(severity, bundle, code, timestamp,
                    message, stack.toString().stripTrailing()));
        }
        return out;
    }
}
