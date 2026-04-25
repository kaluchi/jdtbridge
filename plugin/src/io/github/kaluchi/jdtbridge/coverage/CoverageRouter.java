package io.github.kaluchi.jdtbridge.coverage;

import java.util.Map;

import com.google.gson.JsonObject;

import io.github.kaluchi.jdtbridge.ProjectScope;

/**
 * Internal {@code /coverage/*} dispatcher. Loaded only when
 * {@link CoverageBridge#isAvailable()} is true. Owns the lazy
 * lifecycle of every coverage-side stateful component
 * (tracker, analyzer, handlers, streamer).
 * <p>
 * Phase 1 only knows the path list — every endpoint returns the
 * stub {@link #notImplemented(String)} JSON. Subsequent phases
 * replace each branch with a real handler call.
 */
class CoverageRouter {

    private final CoverageTracker tracker = new CoverageTracker();

    void start() {
        tracker.start();
    }

    void stop() {
        tracker.stop();
    }

    /** Test/inspection accessor. */
    CoverageTracker tracker() {
        return tracker;
    }

    String dispatch(String path, Map<String, String> params,
            String body, ProjectScope scope) {
        return switch (path) {
            case "/coverage/run" -> notImplemented(path);
            case "/coverage/dump" -> notImplemented(path);
            case "/coverage/refresh" -> notImplemented(path);
            case "/coverage/relaunch" -> notImplemented(path);
            case "/coverage/runs" -> notImplemented(path);
            case "/coverage/session" -> notImplemented(path);
            case "/coverage/active" -> notImplemented(path);
            case "/coverage/activate" -> notImplemented(path);
            case "/coverage/merge" -> notImplemented(path);
            case "/coverage/remove" -> notImplemented(path);
            default -> unknownPath(path);
        };
    }

    /** Stub response while the corresponding handler is not yet
     *  wired in. Replaced phase by phase as handlers land. */
    private static String notImplemented(String path) {
        var obj = new JsonObject();
        obj.addProperty("error", "coverage-not-implemented");
        obj.addProperty("path", path);
        return obj.toString();
    }

    private static String unknownPath(String path) {
        var obj = new JsonObject();
        obj.addProperty("error", "coverage-unknown-path");
        obj.addProperty("path", path);
        return obj.toString();
    }
}
