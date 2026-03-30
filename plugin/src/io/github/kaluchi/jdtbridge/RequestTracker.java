package io.github.kaluchi.jdtbridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks HTTP requests per session for telemetry.
 * Each session (identified by X-Bridge-Session header) accumulates
 * request logs that can be streamed to Eclipse Console.
 */
public class RequestTracker {

    /** Single request log entry. */
    public record RequestLog(
            long timestamp,
            String method,
            String path,
            String session,
            int status,
            long durationMs) {

        public String format() {
            return String.format("[BRIDGE] %s %s (%d, %dms)",
                    method, path, status, durationMs);
        }
    }

    /** Listener notified on new log entries. */
    public interface Listener {
        void onRequest(RequestLog log);

        default void onTelemetry(String session, String text) {}
    }

    private final Map<String, List<RequestLog>> logs =
            new ConcurrentHashMap<>();
    private final List<Listener> listeners =
            new CopyOnWriteArrayList<>();

    public void log(RequestLog entry) {
        if (entry.session() != null && !entry.session().isEmpty()) {
            logs.computeIfAbsent(entry.session(),
                    k -> new CopyOnWriteArrayList<>()).add(entry);
        }
        for (Listener l : listeners) {
            l.onRequest(entry);
        }
    }

    /** Log raw telemetry text from CLI (stdout/stderr). */
    public void logTelemetry(String session, String text) {
        for (Listener l : listeners) {
            l.onTelemetry(session, text);
        }
    }

    public List<RequestLog> getSessionLogs(String session) {
        return logs.getOrDefault(session, List.of());
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void clearSession(String session) {
        logs.remove(session);
    }
}
