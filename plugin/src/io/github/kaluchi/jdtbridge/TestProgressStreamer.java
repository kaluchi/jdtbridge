package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.jdt.internal.junit.model.ITestSessionListener;
import org.eclipse.jdt.internal.junit.model.TestCaseElement;
import org.eclipse.jdt.internal.junit.model.TestElement;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;
import org.eclipse.jdt.junit.model.ITestElementContainer;
import org.eclipse.jdt.junit.model.ITestSuiteElement;

/**
 * Streams test progress events from a {@link TestRunSession}
 * to an {@link OutputStream} as JSONL. Attaches an
 * {@link ITestSessionListener} for live events.
 */
@SuppressWarnings("restriction")
class TestProgressStreamer {

    /**
     * Stream test events. Blocks until session finishes or
     * IOException.
     */
    static void stream(TestRunSession session,
            OutputStream out, String filter)
            throws IOException {

        var done = new java.util.concurrent.CountDownLatch(1);

        ITestSessionListener listener =
                new ITestSessionListener() {

            @Override
            public void testEnded(TestCaseElement tc) {
                var testResult = tc.getTestResult(false);
                String status = mapStatus(testResult);

                if (!matchesFilter(status, filter)) return;

                String fqmn = tc.getTestClassName()
                        + "#" + tc.getTestMethodName();
                double time = tc.getElapsedTimeInSeconds();

                var event = new JsonObject();
                event.addProperty("event", "case");
                event.addProperty("fqmn", fqmn);
                event.addProperty("status", status);
                event.addProperty("time",
                        Double.isNaN(time) ? 0.0 : time);

                ITestElementContainer parent =
                        tc.getParentContainer();
                if (parent instanceof ITestSuiteElement suite)
                    event.addProperty("suite",
                            suite.getSuiteTypeName());

                if (testResult == ITestElement.Result.FAILURE
                        || testResult == ITestElement.Result.ERROR) {
                    FailureTrace ft = tc.getFailureTrace();
                    if (ft != null) {
                        if (ft.getTrace() != null)
                            event.addProperty("trace",
                                    ft.getTrace());
                        if (ft.getExpected() != null)
                            event.addProperty("expected",
                                    ft.getExpected());
                        if (ft.getActual() != null)
                            event.addProperty("actual",
                                    ft.getActual());
                    }
                }

                try {
                    writeLine(out, event.toString());
                } catch (IOException e) {
                    throw new StreamClosedException(e);
                }
            }

            @Override
            public void sessionEnded(long elapsedTime) {
                done.countDown();
            }

            @Override
            public void sessionStopped(long elapsedTime) {
                done.countDown();
            }

            @Override
            public void sessionTerminated() {
                done.countDown();
            }

            // Unused callbacks
            @Override public void sessionStarted() {}
            @Override public void testStarted(
                    TestCaseElement tc) {}
            @Override public void testAdded(
                    TestElement te) {}
            @Override public void runningBegins() {}
            @Override public void testFailed(
                    TestElement te, TestElement.Status s,
                    String trace, String expected,
                    String actual) {}
            @Override public void testReran(
                    TestCaseElement tc, TestElement.Status s,
                    String trace, String expected,
                    String actual) {}
            @Override public boolean acceptsSwapToDisk() {
                return false;
            }
        };

        session.addTestSessionListener(listener);
        try {
            // Replay existing results
            replayExisting(session, out, filter);

            if (!session.isRunning() && !session.isStarting())
                return;

            // Wait for session to finish
            done.await();
            synchronized (out) { out.flush(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            session.removeTestSessionListener(listener);
        }
    }

    private static void replayExisting(
            TestRunSession session, OutputStream out,
            String filter) throws IOException {
        replayContainer(session, out, filter);
    }

    private static void replayContainer(
            ITestElementContainer container, OutputStream out,
            String filter) throws IOException {
        try {
            for (ITestElement child : container.getChildren()) {
                if (child instanceof ITestCaseElement tc) {
                    var result = tc.getTestResult(false);
                    if (result == ITestElement.Result.UNDEFINED)
                        continue; // not finished yet
                    String status = mapStatus(result);
                    if (!matchesFilter(status, filter))
                        continue;

                    String fqmn = tc.getTestClassName()
                            + "#" + tc.getTestMethodName();
                    double time = tc.getElapsedTimeInSeconds();

                    var event = new JsonObject();
                    event.addProperty("event", "case");
                    event.addProperty("fqmn", fqmn);
                    event.addProperty("status", status);
                    event.addProperty("time",
                            Double.isNaN(time) ? 0.0 : time);

                    if (result == ITestElement.Result.FAILURE
                            || result == ITestElement.Result.ERROR) {
                        FailureTrace ft = tc.getFailureTrace();
                        if (ft != null) {
                            if (ft.getTrace() != null)
                                event.addProperty("trace",
                                        ft.getTrace());
                            if (ft.getExpected() != null)
                                event.addProperty("expected",
                                        ft.getExpected());
                            if (ft.getActual() != null)
                                event.addProperty("actual",
                                        ft.getActual());
                        }
                    }

                    writeLine(out, event.toString());
                } else if (child
                        instanceof ITestElementContainer c) {
                    replayContainer(c, out, filter);
                }
            }
        } catch (Exception e) {
            // tree may be incomplete
        }
    }

    private static String mapStatus(
            ITestElement.Result result) {
        if (result == ITestElement.Result.OK) return "PASS";
        if (result == ITestElement.Result.FAILURE) return "FAIL";
        if (result == ITestElement.Result.ERROR) return "ERROR";
        if (result == ITestElement.Result.IGNORED) return "IGNORED";
        return "UNKNOWN";
    }

    private static boolean matchesFilter(String status,
            String filter) {
        if (filter == null || "all".equals(filter))
            return true;
        if ("failures".equals(filter))
            return "FAIL".equals(status)
                    || "ERROR".equals(status);
        if ("ignored".equals(filter))
            return "IGNORED".equals(status);
        return true;
    }

    private static void writeLine(OutputStream out, String line)
            throws IOException {
        synchronized (out) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        }
    }

    @SuppressWarnings("serial")
    static class StreamClosedException extends RuntimeException {
        StreamClosedException(IOException cause) { super(cause); }
    }
}
