package io.github.kaluchi.jdtbridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-session event queue. Producers (HTTP request logging,
 * CLI telemetry POST) enqueue text. Consumer (polling GET)
 * drains the queue.
 */
public class RequestTracker {

    private final Map<String, ConcurrentLinkedQueue<String>> queues =
            new ConcurrentHashMap<>();

    /** Enqueue a formatted request log line. */
    public void logRequest(String session, String method,
            String path, int status, long durationMs) {
        if (invalidSession(session)) return;
        enqueue(session, String.format("[BRIDGE] %s %s (%d, %dms)\n",
                method, path, status, durationMs));
    }

    /** Enqueue raw CLI output text. */
    public void logTelemetry(String session, String text) {
        if (invalidSession(session)) return;
        enqueue(session, text);
    }

    /** Drain all queued events for a session. */
    public String drain(String session) {
        if (invalidSession(session)) return "";
        var queue = queues.get(session);
        if (queue == null || queue.isEmpty()) return "";
        var sb = new StringBuilder();
        String item;
        while ((item = queue.poll()) != null) {
            sb.append(item);
        }
        return sb.toString();
    }

    public void clearSession(String session) {
        if (invalidSession(session)) return;
        queues.remove(session);
    }

    private void enqueue(String session, String text) {
        queues.computeIfAbsent(session,
                k -> new ConcurrentLinkedQueue<>()).add(text);
    }

    private static boolean invalidSession(String session) {
        return session == null || session.isEmpty();
    }
}
