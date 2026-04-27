package io.github.kaluchi.jdtbridge;

import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;

import com.google.gson.JsonObject;

/**
 * Pure formatting helpers shared between {@link TestSessionHandler}
 * and {@link TestProgressStreamer}. No Eclipse runtime state — only
 * value mappings used to shape the wire format.
 */
@SuppressWarnings("restriction")
final class TestSessionFormat {

    private TestSessionFormat() {
    }

    /** Wire-format name for {@link ITestElement.Result}. */
    static String statusName(ITestElement.Result result) {
        if (result == ITestElement.Result.OK) return "PASS";
        if (result == ITestElement.Result.FAILURE) return "FAIL";
        if (result == ITestElement.Result.ERROR) return "ERROR";
        if (result == ITestElement.Result.IGNORED) return "IGNORED";
        return "UNKNOWN";
    }

    /** {@code "running" | "starting" | "finished"} from session flags. */
    static String stateOf(boolean running, boolean starting) {
        if (running) return "running";
        if (starting) return "starting";
        return "finished";
    }

    /** Bridge-side {@code passed} count: started minus every
     *  non-passing bucket (failures, errors, assumption failures). */
    static int passedCount(int started, int failures, int errors,
            int assumptionFailures) {
        return started - failures - errors - assumptionFailures;
    }

    /** Attach {@code trace}/{@code expected}/{@code actual} to
     *  {@code obj}, skipping fields that are absent on the trace. */
    static void attachFailureTrace(JsonObject obj, FailureTrace ft) {
        if (ft == null) return;
        if (ft.getTrace() != null)
            obj.addProperty("trace", ft.getTrace());
        if (ft.getExpected() != null)
            obj.addProperty("expected", ft.getExpected());
        if (ft.getActual() != null)
            obj.addProperty("actual", ft.getActual());
    }

    /** Streamer-side filter: null/"all" includes everything,
     *  "failures" → only FAIL/ERROR, "ignored" → only IGNORED,
     *  any other value falls back to "include". */
    static boolean streamerFilter(String status, String filter) {
        if (filter == null || "all".equals(filter)) return true;
        if ("failures".equals(filter))
            return "FAIL".equals(status)
                    || "ERROR".equals(status);
        if ("ignored".equals(filter))
            return "IGNORED".equals(status);
        return true;
    }
}
