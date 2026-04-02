package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestCaseElement;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;
import org.eclipse.jdt.junit.model.ITestElementContainer;
import org.eclipse.jdt.junit.model.ITestSuiteElement;

class TestSessionHandler {

    TestSessionHandler() {
    }

    @SuppressWarnings("restriction")
    String handleStatus(Map<String, String> params) {
        String testRunId = params.get("testRunId");
        if (testRunId == null || testRunId.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'testRunId' parameter");
        }

        TestRunSession session = findSession(testRunId);
        if (session == null) {
            return HttpServer.jsonError(
                    "Test run not found: " + testRunId);
        }

        String filter = params.get("filter");
        var entries = new JsonArray();
        collectEntries(session, entries, filter);

        String configId = session.getTestRunName();
        long startTime = session.getStartTime();

        String state;
        if (session.isRunning()) state = "running";
        else if (session.isStarting()) state = "starting";
        else state = "finished";

        int passed = session.getStartedCount()
                - session.getFailureCount()
                - session.getErrorCount()
                - session.getAssumptionFailureCount();

        var result = new JsonObject();
        result.addProperty("configId", configId);
        result.addProperty("testRunId",
                startTime > 0
                        ? configId + ":" + startTime
                        : testRunId);

        var launchedProject = session.getLaunchedProject();
        if (launchedProject != null)
            result.addProperty("project",
                    launchedProject.getElementName());

        result.addProperty("state", state);
        result.addProperty("total", session.getTotalCount());
        result.addProperty("completed",
                session.getStartedCount());
        result.addProperty("passed", passed);
        result.addProperty("failed",
                session.getFailureCount());
        result.addProperty("errors",
                session.getErrorCount());
        result.addProperty("ignored",
                session.getIgnoredCount());

        double elapsed = session.getElapsedTimeInSeconds();
        result.addProperty("time",
                Double.isNaN(elapsed) ? 0.0 : elapsed);
        result.add("entries", entries);
        return result.toString();
    }

    TestRunSession findSession(String testRunId) {
        List<TestRunSession> sessions =
                JUnitCorePlugin.getModel()
                        .getTestRunSessions();
        for (TestRunSession s : sessions) {
            String configId = s.getTestRunName();
            long startTime = s.getStartTime();
            String id = startTime > 0
                    ? configId + ":" + startTime
                    : configId;
            if (testRunId.equals(id)
                    || testRunId.equals(configId)) {
                return s;
            }
        }
        return null;
    }

    private void collectEntries(ITestElementContainer container,
            JsonArray entries, String filter) {
        try {
            for (ITestElement child : container.getChildren()) {
                if (child instanceof ITestCaseElement tc) {
                    var testResult = tc.getTestResult(false);
                    String status;
                    if (testResult == ITestElement.Result.OK)
                        status = "PASS";
                    else if (testResult
                            == ITestElement.Result.FAILURE)
                        status = "FAIL";
                    else if (testResult
                            == ITestElement.Result.ERROR)
                        status = "ERROR";
                    else if (testResult
                            == ITestElement.Result.IGNORED)
                        status = "IGNORED";
                    else status = "UNKNOWN";

                    if ("ignored".equals(filter)
                            && !"IGNORED".equals(status))
                        continue;
                    if (filter == null
                            || "failures".equals(filter)) {
                        if ("PASS".equals(status)
                                || "IGNORED".equals(status))
                            continue;
                    }

                    String fqmn = tc.getTestClassName()
                            + "#" + tc.getTestMethodName();
                    double time = tc.getElapsedTimeInSeconds();

                    var entry = new JsonObject();
                    entry.addProperty("fqmn", fqmn);
                    entry.addProperty("status", status);
                    entry.addProperty("time",
                            Double.isNaN(time) ? 0.0 : time);

                    if (testResult == ITestElement.Result.FAILURE
                            || testResult == ITestElement.Result.ERROR) {
                        FailureTrace ft = tc.getFailureTrace();
                        if (ft != null) {
                            if (ft.getTrace() != null)
                                entry.addProperty("trace",
                                        ft.getTrace());
                            if (ft.getExpected() != null)
                                entry.addProperty("expected",
                                        ft.getExpected());
                            if (ft.getActual() != null)
                                entry.addProperty("actual",
                                        ft.getActual());
                        }
                    }
                    entries.add(entry);
                } else if (child instanceof ITestElementContainer c) {
                    collectEntries(c, entries, filter);
                }
            }
        } catch (Exception e) {
            // ignore — tree may be incomplete
        }
    }

    @SuppressWarnings("restriction")
    String handleClear(Map<String, String> params) {
        String testRunId = params.get("testRunId");
        var model = JUnitCorePlugin.getModel();
        int removed = 0;
        for (TestRunSession s
                : model.getTestRunSessions()) {
            if (s.isRunning() || s.isStarting()) continue;
            if (testRunId != null
                    && !testRunId.isBlank()) {
                TestRunSession match =
                        findSession(testRunId);
                if (match != s) continue;
            }
            model.removeTestRunSession(s);
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
