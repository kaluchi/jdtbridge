package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestAwait;

import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.eclipse.jdt.junit.model.ITestElement.Result;

/**
 * Locate a {@link TestRunSession} produced by
 * {@link TestHandler#handleTestRun} and wait until per-case events
 * have been applied to the model — package-private fragment-only
 * helper because {@link TestSessionHandler#testRunId} is not
 * exported.
 */
@SuppressWarnings("restriction")
final class TestSessionAwait {

    private TestSessionAwait() {
    }

    /** Lifecycle flags ({@code !isRunning && !isStarting}) flip
     *  before the streamed PASS/FAIL events land — slower CI runners
     *  observe status=UNKNOWN if we read at that moment.
     *  {@link TestRunSession#getTestResult(boolean)} stays UNDEFINED
     *  until every child case has been evaluated. */
    static TestRunSession awaitFinished(String testRunId,
            long timeoutMs) {
        TestRunSession[] result = new TestRunSession[1];
        TestAwait.pollUntil(timeoutMs, () -> {
            for (TestRunSession s : JUnitCorePlugin.getModel()
                    .getTestRunSessions()) {
                if (testRunId.equals(
                        TestSessionHandler.testRunId(s))
                        && !s.isRunning() && !s.isStarting()
                        && s.getTestResult(true) != Result.UNDEFINED) {
                    result[0] = s;
                    return true;
                }
            }
            return false;
        }, "Launched session did not finish within "
                + timeoutMs + "ms: " + testRunId);
        return result[0];
    }
}
