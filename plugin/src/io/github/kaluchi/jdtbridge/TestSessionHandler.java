package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

class TestSessionHandler {

    private final TestSessionTracker tracker;

    TestSessionHandler(TestSessionTracker tracker) {
        this.tracker = tracker;
    }

    String handleStatus(Map<String, String> params) {
        String name = params.get("session");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'session' parameter");
        }
        var ts = tracker.get(name);
        if (ts == null) {
            return HttpServer.jsonError(
                    "Test session not found: " + name);
        }

        String filter = params.get("filter");
        var entries = new JsonArray();
        for (String eventLine : ts.events) {
            var parsed = JsonParser.parseString(eventLine)
                    .getAsJsonObject();
            String event = parsed.has("event")
                    ? parsed.get("event").getAsString() : "";
            if (!"case".equals(event)) continue;
            String status = parsed.has("status")
                    ? parsed.get("status").getAsString() : "";

            if ("ignored".equals(filter)) {
                if (!"IGNORED".equals(status)) continue;
            } else if ("all".equals(filter)) {
                // show everything
            } else {
                if ("PASS".equals(status)
                        || "IGNORED".equals(status)) continue;
            }

            double time = parsed.has("time")
                    ? parsed.get("time").getAsDouble() : 0.0;
            var f = new JsonObject();
            f.addProperty("fqmn",
                    parsed.has("fqmn")
                            ? parsed.get("fqmn").getAsString()
                            : "");
            f.addProperty("status", status);
            f.addProperty("time", time);
            if (parsed.has("trace")
                    && !parsed.get("trace").isJsonNull())
                f.addProperty("trace",
                        parsed.get("trace").getAsString());
            if (parsed.has("expected")
                    && !parsed.get("expected").isJsonNull())
                f.addProperty("expected",
                        parsed.get("expected").getAsString());
            if (parsed.has("actual")
                    && !parsed.get("actual").isJsonNull())
                f.addProperty("actual",
                        parsed.get("actual").getAsString());
            entries.add(f);
        }

        var result = new JsonObject();
        result.addProperty("session", ts.name);
        if (ts.label != null)
            result.addProperty("label", ts.label);
        if (ts.project != null)
            result.addProperty("project", ts.project);
        result.addProperty("state", ts.state);
        result.addProperty("total", ts.total);
        result.addProperty("completed",
                ts.completed.get());
        result.addProperty("passed", ts.passed.get());
        result.addProperty("failed", ts.failed.get());
        result.addProperty("errors", ts.errors.get());
        result.addProperty("ignored", ts.ignored.get());
        result.addProperty("time",
                Double.isNaN(ts.time) ? 0.0 : ts.time);
        result.add("entries", entries);
        return result.toString();
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
        var result = new JsonObject();
        result.addProperty("removed", removed);
        return result.toString();
    }

    String handleSessions(Map<String, String> params) {
        var arr = new JsonArray();
        for (var ts : tracker.all()) {
            var obj = new JsonObject();
            obj.addProperty("session", ts.name);
            if (ts.label != null)
                obj.addProperty("label", ts.label);
            obj.addProperty("state", ts.state);
            obj.addProperty("total", ts.total);
            obj.addProperty("completed",
                    ts.completed.get());
            obj.addProperty("passed", ts.passed.get());
            obj.addProperty("failed", ts.failed.get());
            obj.addProperty("errors", ts.errors.get());
            obj.addProperty("ignored", ts.ignored.get());
            obj.addProperty("time",
                    Double.isNaN(ts.time)
                            ? 0.0 : ts.time);
            arr.add(obj);
        }
        return arr.toString();
    }
}
