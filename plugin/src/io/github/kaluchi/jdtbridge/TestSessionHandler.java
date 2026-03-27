package io.github.kaluchi.jdtbridge;

import java.util.Map;

/**
 * HTTP handlers for test session endpoints: status and sessions.
 * Reads data from {@link TestSessionTracker} — separation of
 * concerns: tracker listens and accumulates, handler queries
 * and serializes.
 */
class TestSessionHandler {

    private final TestSessionTracker tracker;

    TestSessionHandler(TestSessionTracker tracker) {
        this.tracker = tracker;
    }

    String handleStatus(Map<String, String> params) {
        String name = params.get("session");
        if (name == null || name.isBlank()) {
            return Json.error("Missing 'session' parameter");
        }
        var ts = tracker.get(name);
        if (ts == null) {
            return Json.error(
                    "Test session not found: " + name);
        }

        String filter = params.get("filter");
        Json entries = Json.array();
        for (String eventLine : ts.events) {
            var parsed = Json.parse(eventLine);
            String event = Json.getString(parsed, "event");
            if (!"case".equals(event)) continue;
            String status = Json.getString(parsed, "status");

            // Apply filter
            if ("ignored".equals(filter)) {
                if (!"IGNORED".equals(status)) continue;
            } else if ("all".equals(filter)) {
                // show everything
            } else {
                // default: failures only
                if ("PASS".equals(status)
                        || "IGNORED".equals(status)) continue;
            }

            Object timeVal = parsed.get("time");
            double time = timeVal instanceof Number n
                    ? n.doubleValue() : 0.0;
            Json f = Json.object()
                    .put("fqmn",
                            Json.getString(parsed, "fqmn"))
                    .put("status", status)
                    .put("time", time);
            String trace = Json.getString(parsed, "trace");
            if (trace != null) f.put("trace", trace);
            String expected =
                    Json.getString(parsed, "expected");
            if (expected != null) f.put("expected", expected);
            String actual =
                    Json.getString(parsed, "actual");
            if (actual != null) f.put("actual", actual);
            entries.add(f);
        }

        return Json.object()
                .put("session", ts.name)
                .putIf(ts.label != null, "label", ts.label)
                .putIf(ts.project != null,
                        "project", ts.project)
                .put("state", ts.state)
                .put("total", ts.total)
                .put("completed", ts.completed.get())
                .put("passed", ts.passed.get())
                .put("failed", ts.failed.get())
                .put("errors", ts.errors.get())
                .put("ignored", ts.ignored.get())
                .put("time", Double.isNaN(ts.time)
                        ? 0.0 : ts.time)
                .put("entries", entries)
                .toString();
    }

    String handleClear(Map<String, String> params) {
        String name = params.get("session");
        int removed = 0;
        for (var ts : tracker.all()) {
            if (!"finished".equals(ts.state)
                    && !"stopped".equals(ts.state)) continue;
            if (name != null && !name.isBlank()
                    && !name.equals(ts.name)) continue;
            tracker.remove(ts.name);
            removed++;
        }
        return Json.object()
                .put("removed", removed)
                .toString();
    }

    String handleSessions(Map<String, String> params) {
        Json arr = Json.array();
        for (var ts : tracker.all()) {
            arr.add(Json.object()
                    .put("session", ts.name)
                    .putIf(ts.label != null, "label", ts.label)
                    .put("state", ts.state)
                    .put("total", ts.total)
                    .put("completed", ts.completed.get())
                    .put("passed", ts.passed.get())
                    .put("failed", ts.failed.get())
                    .put("errors", ts.errors.get())
                    .put("ignored", ts.ignored.get())
                    .put("time", Double.isNaN(ts.time)
                            ? 0.0 : ts.time));
        }
        return arr.toString();
    }
}
