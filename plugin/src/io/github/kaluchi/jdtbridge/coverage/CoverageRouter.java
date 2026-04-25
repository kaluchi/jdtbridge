package io.github.kaluchi.jdtbridge.coverage;

import java.util.Map;

import com.google.gson.JsonObject;

import io.github.kaluchi.jdtbridge.ProjectScope;

/**
 * Internal {@code /coverage/*} dispatcher. Loaded only when
 * {@link CoverageBridge#isAvailable()} is true. Owns the lazy
 * lifecycle of every coverage-side stateful component
 * (tracker, analyzer, handlers, streamer).
 */
class CoverageRouter {

    private final CoverageAnalyzer analyzer = new CoverageAnalyzer();
    private final CoverageTracker tracker = new CoverageTracker(analyzer);
    private final CoverageHandler handler = new CoverageHandler(tracker);
    private final CoverageSessionHandler sessionHandler =
            new CoverageSessionHandler(tracker, analyzer);

    void start() {
        tracker.start();
    }

    void stop() {
        tracker.stop();
        analyzer.clear();
    }

    /** Test/inspection accessor. */
    CoverageTracker tracker() {
        return tracker;
    }

    /** Test/inspection accessor. */
    CoverageHandler handler() {
        return handler;
    }

    /** Test/inspection accessor. */
    CoverageAnalyzer analyzer() {
        return analyzer;
    }

    /** Test/inspection accessor. */
    CoverageSessionHandler sessionHandler() {
        return sessionHandler;
    }

    String dispatch(String path, Map<String, String> params,
            String body, ProjectScope scope) {
        return switch (path) {
            case "/coverage/run" -> handler.handleRun(params);
            case "/coverage/dump" -> handler.handleDump(body);
            case "/coverage/refresh" -> handler.handleRefresh();
            case "/coverage/relaunch" -> handler.handleRelaunch();
            case "/coverage/runs" -> sessionHandler.handleRuns(scope);
            case "/coverage/session" ->
                    sessionHandler.handleSession(params);
            case "/coverage/active" -> sessionHandler.handleActive();
            case "/coverage/activate" ->
                    sessionHandler.handleActivate(body);
            case "/coverage/merge" -> sessionHandler.handleMerge(body);
            case "/coverage/remove" ->
                    sessionHandler.handleRemove(body);
            default -> unknownPath(path);
        };
    }

    private static String unknownPath(String path) {
        var obj = new JsonObject();
        obj.addProperty("error", "coverage-unknown-path");
        obj.addProperty("path", path);
        return obj.toString();
    }
}
