package io.github.kaluchi.jdtbridge;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;
import org.eclipse.jdt.junit.model.ITestElementContainer;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.junit.model.ITestSuiteElement;

/**
 * Tracks test sessions and accumulates test events via
 * {@link TestRunListener}. Pure listener + repository —
 * HTTP handlers are in {@link TestSessionHandler}.
 */
class TestSessionTracker extends TestRunListener {

    interface TestEventListener {
        void onEvent(String jsonLine);
    }

    static class TrackedTestSession {
        final String name;
        volatile String label;
        volatile String project;
        volatile String runner;
        volatile String state = "running";
        volatile int total;
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger passed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger ignored = new AtomicInteger();
        volatile double time;
        final List<String> events = new CopyOnWriteArrayList<>();
        private final List<TestEventListener> listeners =
                new CopyOnWriteArrayList<>();

        TrackedTestSession(String name) {
            this.name = name;
        }

        void addListener(TestEventListener l) {
            listeners.add(l);
        }

        void removeListener(TestEventListener l) {
            listeners.remove(l);
        }

        void emit(String jsonLine) {
            events.add(jsonLine);
            for (var l : listeners) l.onEvent(jsonLine);
        }
    }

    private final ConcurrentHashMap<String, TrackedTestSession> sessions =
            new ConcurrentHashMap<>();

    void start() {
        JUnitCore.addTestRunListener(this);
    }

    void stop() {
        JUnitCore.removeTestRunListener(this);
        sessions.clear();
    }

    /**
     * Pre-register a session before the JVM starts so
     * streaming clients can connect immediately.
     */
    TrackedTestSession preRegister(String name) {
        TrackedTestSession ts = new TrackedTestSession(name);
        sessions.put(name, ts);
        return ts;
    }

    TrackedTestSession get(String name) {
        return sessions.get(name);
    }

    /** All tracked sessions (for listing). */
    Iterable<TrackedTestSession> all() {
        return sessions.values();
    }

    /** Remove a session by name. */
    void remove(String name) {
        sessions.remove(name);
    }

    /** Wait briefly for a session to appear. */
    TrackedTestSession await(String name) {
        for (int i = 0; i < 10; i++) {
            TrackedTestSession ts = sessions.get(name);
            if (ts != null) return ts;
            try { Thread.sleep(500); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // -- TestRunListener --

    @Override
    public void sessionStarted(ITestRunSession session) {
        String name = session.getTestRunName();
        TrackedTestSession ts = sessions.computeIfAbsent(
                name, TrackedTestSession::new);

        // Resolve label from test tree
        ts.label = resolveLabel(session);

        var launchedProject = session.getLaunchedProject();
        if (launchedProject != null) {
            ts.project = launchedProject.getElementName();
        }

        // Count total tests
        ts.total = countTests(session);

        sessions.put(name, ts);

        ts.emit(Json.object()
                .put("event", "started")
                .put("session", name)
                .put("total", ts.total)
                .putIf(ts.label != null, "label", ts.label)
                .putIf(ts.project != null, "project", ts.project)
                .toString());
    }

    @Override
    public void testCaseFinished(ITestCaseElement tc) {
        String sessionName = tc.getTestRunSession().getTestRunName();
        TrackedTestSession ts = sessions.get(sessionName);
        if (ts == null) return;

        var result = tc.getTestResult(false);
        String status;
        if (result == ITestElement.Result.OK) {
            status = "PASS";
            ts.passed.incrementAndGet();
        } else if (result == ITestElement.Result.FAILURE) {
            status = "FAIL";
            ts.failed.incrementAndGet();
        } else if (result == ITestElement.Result.ERROR) {
            status = "ERROR";
            ts.errors.incrementAndGet();
        } else if (result == ITestElement.Result.IGNORED) {
            status = "IGNORED";
            ts.ignored.incrementAndGet();
        } else {
            status = "UNKNOWN";
        }
        ts.completed.incrementAndGet();

        String className = tc.getTestClassName();
        String methodName = tc.getTestMethodName();
        String fqmn = className + "#" + methodName;
        double elapsed = tc.getElapsedTimeInSeconds();

        Json event = Json.object()
                .put("event", "case")
                .put("fqmn", fqmn)
                .put("status", status)
                .put("time", Double.isNaN(elapsed) ? 0.0 : elapsed);

        // Parent suite name (for grouping)
        ITestElementContainer parent = tc.getParentContainer();
        if (parent instanceof ITestSuiteElement suite) {
            event.put("suite", suite.getSuiteTypeName());
        }

        // Failure trace
        if (result == ITestElement.Result.FAILURE
                || result == ITestElement.Result.ERROR) {
            FailureTrace ft = tc.getFailureTrace();
            if (ft != null) {
                event.putIf(ft.getTrace() != null,
                        "trace", ft.getTrace());
                event.putIf(ft.getExpected() != null,
                        "expected", ft.getExpected());
                event.putIf(ft.getActual() != null,
                        "actual", ft.getActual());
            }
        }

        ts.emit(event.toString());
    }

    @Override
    public void sessionFinished(ITestRunSession session) {
        String name = session.getTestRunName();
        TrackedTestSession ts = sessions.get(name);
        if (ts == null) return;

        ts.time = session.getElapsedTimeInSeconds();
        ts.state = "finished";

        ts.emit(Json.object()
                .put("event", "finished")
                .put("session", name)
                .put("total", ts.total)
                .put("passed", ts.passed.get())
                .put("failed", ts.failed.get())
                .put("errors", ts.errors.get())
                .put("ignored", ts.ignored.get())
                .put("time", Double.isNaN(ts.time) ? 0.0 : ts.time)
                .toString());
    }

    // -- Helpers --

    private String resolveLabel(ITestRunSession session) {
        try {
            ITestElement[] children = session.getChildren();
            if (children != null && children.length == 1
                    && children[0] instanceof ITestSuiteElement suite) {
                String suiteName = suite.getSuiteTypeName();
                if (suiteName != null) {
                    int dot = suiteName.lastIndexOf('.');
                    return dot >= 0
                            ? suiteName.substring(dot + 1)
                            : suiteName;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        // Fall back to config name without prefix
        String name = session.getTestRunName();
        if (name.startsWith("jdtbridge-test-")) {
            return null;
        }
        return name;
    }

    private int countTests(ITestElementContainer container) {
        int count = 0;
        try {
            for (ITestElement child : container.getChildren()) {
                if (child instanceof ITestCaseElement) {
                    count++;
                } else if (child instanceof ITestElementContainer c) {
                    count += countTests(c);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return count;
    }
}
