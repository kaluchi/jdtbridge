package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.eclipse.jdt.junit.model.ITestElement;

class TestSessionHandler {

    private final TestSessionTracker tracker;

    TestSessionHandler(TestSessionTracker tracker) {
        this.tracker = tracker;
    }

    String handleStatus(Map<String, String> params) {
        String name = params.get("testRunId");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'testRunId' parameter");
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
        result.addProperty("configId", ts.name);
        result.addProperty("testRunId",
                ts.name + ":" + ts.startedAt);
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
        String name = params.get("testRunId");
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

    @SuppressWarnings("restriction")
    String handleSessions(Map<String, String> params) {
        List<TestRunSession> sessions =
                JUnitCorePlugin.getModel()
                        .getTestRunSessions();
        var arr = new JsonArray();
        for (TestRunSession s : sessions) {
            var obj = new JsonObject();
            String configId = s.getTestRunName();
            long startTime = s.getStartTime();
            obj.addProperty("configId", configId);
            obj.addProperty("testRunId",
                    startTime > 0
                            ? configId + ":" + startTime
                            : configId);

            // LaunchId from ILaunch → PID
            var launch = s.getLaunch();
            if (launch != null) {
                var procs = launch.getProcesses();
                if (procs.length > 0) {
                    String pid = procs[0].getAttribute(
                            org.eclipse.debug.core.model
                                    .IProcess.ATTR_PROCESS_ID);
                    if (pid != null)
                        obj.addProperty("launchId",
                                configId + ":" + pid);
                }
            }

            String state;
            if (s.isRunning()) state = "running";
            else if (s.isStarting()) state = "starting";
            else state = "finished";
            obj.addProperty("state", state);

            obj.addProperty("total", s.getTotalCount());
            obj.addProperty("completed",
                    s.getStartedCount());
            obj.addProperty("passed",
                    s.getStartedCount()
                            - s.getFailureCount()
                            - s.getErrorCount()
                            - s.getAssumptionFailureCount());
            obj.addProperty("failed", s.getFailureCount());
            obj.addProperty("errors", s.getErrorCount());
            obj.addProperty("ignored",
                    s.getIgnoredCount());

            double elapsed = s.getElapsedTimeInSeconds();
            obj.addProperty("time",
                    Double.isNaN(elapsed) ? 0.0 : elapsed);
            if (startTime > 0)
                obj.addProperty("startedAt", startTime);
            arr.add(obj);
        }
        return arr.toString();
    }
}
