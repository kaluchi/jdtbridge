package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.eclemma.core.CoverageTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.kaluchi.jdtbridge.TestHandler;
import io.github.kaluchi.jdtbridge.support.TestAwait;
import io.github.kaluchi.jdtbridge.support.TestFixture;

/**
 * UI-runtime coverage tests — JUnit coverage delegate is registered
 * only when EclEmma UI is active, which the headless tycho-surefire
 * harness in plugin.tests does not provide.
 */
public class TestRunCoverageLiveTest {

    private static final TestHandler handler = new TestHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
        // Suppress the "Errors in required project(s)" prompt — it
        // would block headless UI runtime on Display.sleep forever.
        // These tests assert launch initiation, not SimpleTest result.
        var prefs = InstanceScope.INSTANCE.getNode(
                "org.eclipse.debug.ui");
        prefs.put(
                "org.eclipse.debug.ui.cancel_launch_with_compile_errors",
                "always");
        prefs.flush();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    /** handleTestRun returns immediately with the testRunId; the
     *  child JVM keeps holding jdtbridge-test/bin/*.class until it
     *  exits. Match the launch by ATTR_LAUNCH_TIMESTAMP suffix of
     *  testRunId ({@code configId:timestamp}, see TestHandler). */
    private static void awaitLaunchTerminated(String testRunId) {
        String timestamp = testRunId.substring(
                testRunId.indexOf(':') + 1);
        var mgr = DebugPlugin.getDefault().getLaunchManager();
        TestAwait.pollUntil(30_000, () -> {
            for (ILaunch l : mgr.getLaunches()) {
                if (timestamp.equals(l.getAttribute(
                        DebugPlugin.ATTR_LAUNCH_TIMESTAMP))
                        && l.isTerminated()) {
                    return true;
                }
            }
            return false;
        }, "Launch did not terminate within 30s: " + testRunId);
    }

    @SuppressWarnings("restriction")
	@AfterEach
    void cleanupCoverageSessions() {
        CoverageTools.getSessionManager().removeAllSessions();
    }

    @Test
    void coverageOneEnablesCoverage() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "test.edge.SimpleTest");
        params.put("no-refresh", "");
        params.put("coverage", "1");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("\"launchMode\":\"coverage\""),
                "coverage=1 must enable coverage mode: " + json);
        String testRunId = JsonParser.parseString(json)
                .getAsJsonObject().get("testRunId").getAsString();
        awaitLaunchTerminated(testRunId);
    }

    @Test
    void coverageTrueLaunchesInCoverageMode() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("target", "test.edge.SimpleTest");
        params.put("no-refresh", "");
        params.put("coverage", "true");
        String json = handler.handleTestRun(params);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "Expected ok: " + json);
        assertEquals("coverage",
                obj.get("launchMode").getAsString());
        assertTrue(obj.has("coverageId"),
                "Expected coverageId: " + json);
        assertEquals(obj.get("testRunId").getAsString(),
                obj.get("coverageId").getAsString(),
                "coverageId must equal testRunId byte-for-byte");
        awaitLaunchTerminated(obj.get("testRunId").getAsString());
    }
}
