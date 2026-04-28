package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link TestProgressStreamer}. Drives {@code stream()}
 * against a real, finished {@link TestRunSession} produced by
 * launching {@code test.edge.SimpleTest} from {@link TestFixture}
 * once in @BeforeAll. The session has exactly one passing test
 * case, so all stream invariants are observable and stable.
 */
@SuppressWarnings("restriction")
public class TestProgressStreamerTest {

    private static final String SIMPLE_TEST_FQN =
            "test.edge.SimpleTest";
    private static final String SIMPLE_TEST_METHOD =
            "test.edge.SimpleTest#onePlusOne";

    private static TestRunSession session;

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();

        Map<String, String> params = new HashMap<>();
        params.put("target", SIMPLE_TEST_FQN);
        params.put("no-refresh", "");

        String json = new TestHandler().handleTestRun(params);
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "handleTestRun must succeed: " + json);
        String testRunId = obj.get("testRunId").getAsString();

        session = TestSessionAwait.awaitFinished(testRunId, 60_000);
        assertNotNull(session,
                "Launched session must finish within 60s: "
                        + testRunId);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (session != null) {
            JUnitCorePlugin.getModel()
                    .removeTestRunSession(session);
            session = null;
        }
        TestFixture.destroy();
    }

    @Test
    void streamReplaysSinglePassingCase() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TestProgressStreamer.stream(session, baos, null);

        String[] lines = baos.toString(StandardCharsets.UTF_8)
                .split("\n");
        assertEquals(1, lines.length,
                "Replay must emit exactly one JSON line: "
                        + baos);
        JsonObject event = JsonParser.parseString(lines[0])
                .getAsJsonObject();
        assertEquals("case", event.get("event").getAsString());
        assertEquals(SIMPLE_TEST_METHOD,
                event.get("fqn").getAsString());
        assertEquals("PASS", event.get("status").getAsString());
        assertTrue(event.has("time"),
                "Replay event must carry time field: " + event);
    }

    @Test
    void failuresFilterDropsPassingCases() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TestProgressStreamer.stream(session, baos, "failures");
        assertEquals(0, baos.size(),
                "PASS case must be dropped under filter=failures");
    }

    @Test
    void ignoredFilterDropsPassingCases() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TestProgressStreamer.stream(session, baos, "ignored");
        assertEquals(0, baos.size(),
                "PASS case must be dropped under filter=ignored");
    }

    @Test
    void allFilterIncludesPassingCase() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TestProgressStreamer.stream(session, baos, "all");
        String[] lines = baos.toString(StandardCharsets.UTF_8)
                .split("\n");
        assertEquals(1, lines.length);
        JsonObject event = JsonParser.parseString(lines[0])
                .getAsJsonObject();
        assertEquals("PASS", event.get("status").getAsString());
    }

}
