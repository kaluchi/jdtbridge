package io.github.kaluchi.jdtbridge.coverage;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.kaluchi.jdtbridge.coverage.CoverageTracker.CoverageEventListener;

/**
 * Per-{@code coverageId} subscribe / fire bus for the streaming
 * endpoint. Decoupled from {@link CoverageTracker} so the tracker
 * doesn't carry the listener bookkeeping itself.
 */
final class CoverageEventBus {

    private final ConcurrentHashMap<String,
            List<CoverageEventListener>> listeners =
            new ConcurrentHashMap<>();

    void subscribe(String coverageId,
            CoverageEventListener listener) {
        listeners.computeIfAbsent(coverageId,
                k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    void unsubscribe(String coverageId,
            CoverageEventListener listener) {
        List<CoverageEventListener> list = listeners.get(coverageId);
        if (list == null) return;
        list.remove(listener);
        if (list.isEmpty()) {
            listeners.remove(coverageId, list);
        }
    }

    /** Dispatch one event to every subscriber of {@code coverageId}.
     *  RuntimeExceptions thrown by individual listeners are swallowed
     *  so one bad subscriber can't prevent the rest from receiving. */
    void fire(String coverageId,
            Consumer<CoverageEventListener> event) {
        List<CoverageEventListener> list = listeners.get(coverageId);
        if (list == null) return;
        for (CoverageEventListener l : list) {
            try {
                event.accept(l);
            } catch (RuntimeException e) {
                // ignore — one listener shouldn't break the rest
            }
        }
    }

    void clear() {
        listeners.clear();
    }
}
