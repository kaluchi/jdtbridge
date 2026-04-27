package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElementContainer;

import java.util.List;
import java.util.Map;

@SuppressWarnings("restriction")
class TestSessionHandler {

    static String testRunId(TestRunSession session) {
        String configId = session.getTestRunName();
        ILaunch launch = session.getLaunch();
        if (launch == null) return configId;
        String ts = launch.getAttribute(
                DebugPlugin.ATTR_LAUNCH_TIMESTAMP);
        if (ts == null) return configId;
        return configId + ":" + ts;
    }

    TestSessionHandler() {
    }

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

        String state = TestSessionFormat.stateOf(
                session.isRunning(), session.isStarting());

        int passed = TestSessionFormat.passedCount(
                session.getStartedCount(),
                session.getFailureCount(),
                session.getErrorCount(),
                session.getAssumptionFailureCount());

        var result = new JsonObject();
        result.addProperty("configId", configId);
        result.addProperty("testRunId", testRunId(session));

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
            if (testRunId.equals(testRunId(s))
                    || testRunId.equals(s.getTestRunName())) {
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
                    String status = TestSessionFormat.statusName(
                            testResult);

                    if ("ignored".equals(filter)
                            && !"IGNORED".equals(status))
                        continue;
                    if (filter == null
                            || "failures".equals(filter)) {
                        if ("PASS".equals(status)
                                || "IGNORED".equals(status))
                            continue;
                    }

                    String fqn = tc.getTestClassName()
                            + "#" + tc.getTestMethodName();
                    double time = tc.getElapsedTimeInSeconds();

                    var entry = new JsonObject();
                    entry.addProperty("fqn", fqn);
                    entry.addProperty("status", status);
                    entry.addProperty("time",
                            Double.isNaN(time) ? 0.0 : time);

                    if (testResult == ITestElement.Result.FAILURE
                            || testResult == ITestElement.Result.ERROR) {
                        TestSessionFormat.attachFailureTrace(
                                entry, tc.getFailureTrace());
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

    String handleSessions(Map<String, String> params,
            ProjectScope scope) {
        List<TestRunSession> sessions =
                JUnitCorePlugin.getModel()
                        .getTestRunSessions();
        var arr = new JsonArray();
        for (TestRunSession s : sessions) {
            if (s.getLaunch() != null
                    && !scope.containsLaunch(s.getLaunch()))
                continue;
            var obj = new JsonObject();
            String configId = s.getTestRunName();
            obj.addProperty("configId", configId);
            obj.addProperty("testRunId", testRunId(s));

            ILaunch launch = s.getLaunch();
            if (launch != null) {
                String pid = LaunchAttrs.firstPid(launch);
                if (pid != null) {
                    obj.addProperty("launchId",
                            configId + ":" + pid);
                }
            }

            obj.addProperty("state", TestSessionFormat.stateOf(
                    s.isRunning(), s.isStarting()));

            obj.addProperty("total", s.getTotalCount());
            obj.addProperty("completed",
                    s.getStartedCount());
            obj.addProperty("passed",
                    TestSessionFormat.passedCount(
                            s.getStartedCount(),
                            s.getFailureCount(),
                            s.getErrorCount(),
                            s.getAssumptionFailureCount()));
            obj.addProperty("failed", s.getFailureCount());
            obj.addProperty("errors", s.getErrorCount());
            obj.addProperty("ignored",
                    s.getIgnoredCount());

            double elapsed = s.getElapsedTimeInSeconds();
            obj.addProperty("time",
                    Double.isNaN(elapsed) ? 0.0 : elapsed);
            if (launch != null) {
                Long ts = LaunchAttrs.launchTimestamp(launch);
                if (ts != null) {
                    obj.addProperty("startedAt", ts);
                }
            }
            arr.add(obj);
        }
        return arr.toString();
    }

}
