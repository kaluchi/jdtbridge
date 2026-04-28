package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@SuppressWarnings("restriction")
public class TestSessionHandlerTest {

    private static final String SIMPLE_TEST_FQN =
            "test.edge.SimpleTest";

    private static TestRunSession session;
    private static String testRunId;
    private static String configId;
    private static final TestSessionHandler handler =
            new TestSessionHandler();

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
        testRunId = obj.get("testRunId").getAsString();
        configId = obj.get("configId").getAsString();

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

    @Nested
    class FindSession {

        @Test
        void findsByExactTestRunId() {
            TestRunSession found = handler.findSession(testRunId);
            assertNotNull(found);
            assertEquals(testRunId,
                    TestSessionHandler.testRunId(found));
        }

        @Test
        void findsByConfigId() {
            TestRunSession found = handler.findSession(configId);
            assertNotNull(found);
        }

        @Test
        void returnsNullForUnknownId() {
            TestRunSession found =
                    handler.findSession("no-such-run:999");
            assertEquals(null, found);
        }
    }

    @Nested
    class HandleStatus {

        @Test
        void returnsFinishedStatusForCompletedRun() {
            JsonObject result = parse(handler.handleStatus(
                    params("testRunId", testRunId)));
            assertEquals(configId,
                    result.get("configId").getAsString());
            assertEquals(testRunId,
                    result.get("testRunId").getAsString());
            assertEquals("finished",
                    result.get("state").getAsString());
            assertTrue(result.get("total").getAsInt() >= 1);
            assertTrue(result.get("passed").getAsInt() >= 1);
            assertEquals(0, result.get("failed").getAsInt());
            assertEquals(0, result.get("errors").getAsInt());
            assertTrue(result.get("time").getAsDouble() >= 0.0);
            assertNotNull(result.get("entries"));
        }

        @Test
        void failuresFilterReturnsEmptyEntriesForAllPassing() {
            JsonObject result = parse(handler.handleStatus(
                    params("testRunId", testRunId)));
            JsonArray entries = result.getAsJsonArray("entries");
            assertEquals(0, entries.size(),
                    "all tests pass — default failures filter "
                    + "yields empty entries");
        }

        @Test
        void allFilterReturnsTestEntries() {
            JsonObject result = parse(handler.handleStatus(
                    paramsMulti("testRunId", testRunId,
                                "filter", "all")));
            JsonArray entries = result.getAsJsonArray("entries");
            assertTrue(entries.size() >= 1,
                    "all filter should include passing tests");
            JsonObject first = entries.get(0).getAsJsonObject();
            assertEquals("PASS",
                    first.get("status").getAsString());
            assertNotNull(first.get("fqn"));
            assertTrue(first.get("time").getAsDouble() >= 0.0);
        }

        @Test
        void missingTestRunIdReturnsError() {
            String result = handler.handleStatus(Map.of());
            assertTrue(result.contains("Missing"));
        }

        @Test
        void unknownTestRunIdReturnsError() {
            String result = handler.handleStatus(
                    params("testRunId", "bogus:0"));
            assertTrue(result.contains("not found"));
        }

        @Test
        void ignoredFilterReturnsEmptyForNoIgnored() {
            JsonObject result = parse(handler.handleStatus(
                    paramsMulti("testRunId", testRunId,
                                "filter", "ignored")));
            JsonArray entries = result.getAsJsonArray("entries");
            assertEquals(0, entries.size(),
                    "SimpleTest has no @Disabled methods");
        }
    }

    @Nested
    class HandleSessions {

        @Test
        void listsAtLeastTheFixtureSession() {
            JsonArray arr = JsonParser.parseString(
                    handler.handleSessions(Map.of(),
                            ProjectScope.ALL)).getAsJsonArray();
            assertTrue(arr.size() >= 1);
            boolean found = arr.asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .anyMatch(e -> testRunId.equals(
                            e.get("testRunId").getAsString()));
            assertTrue(found,
                    "fixture session must appear in /test/sessions");
        }

        @Test
        void sessionEntryCarriesAllFields() {
            JsonArray arr = JsonParser.parseString(
                    handler.handleSessions(Map.of(),
                            ProjectScope.ALL)).getAsJsonArray();
            JsonObject entry = arr.asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .filter(e -> testRunId.equals(
                            e.get("testRunId").getAsString()))
                    .findFirst().orElseThrow();
            assertEquals(configId,
                    entry.get("configId").getAsString());
            assertEquals("finished",
                    entry.get("state").getAsString());
            assertTrue(entry.has("total"));
            assertTrue(entry.has("completed"));
            assertTrue(entry.has("passed"));
            assertTrue(entry.has("failed"));
            assertTrue(entry.has("errors"));
            assertTrue(entry.has("ignored"));
            assertTrue(entry.has("time"));
            assertTrue(entry.has("startedAt"));
        }
    }

    @Nested
    class HandleClear {

        @Test
        void clearUnknownIdRemovesNothing() {
            JsonObject result = parse(handler.handleClear(
                    params("testRunId", "bogus:0")));
            assertEquals(0, result.get("removed").getAsInt());
        }

        @Test
        void clearByTestRunIdRemovesThatSession() {
            Map<String, String> launchParams = new HashMap<>();
            launchParams.put("target", SIMPLE_TEST_FQN);
            launchParams.put("no-refresh", "");
            try {
                String json = new TestHandler()
                        .handleTestRun(launchParams);
                JsonObject obj = JsonParser.parseString(json)
                        .getAsJsonObject();
                String tempRunId =
                        obj.get("testRunId").getAsString();
                TestSessionAwait.awaitFinished(
                        tempRunId, 60_000);

                JsonObject result = parse(
                        handler.handleClear(
                                params("testRunId", tempRunId)));
                assertEquals(1,
                        result.get("removed").getAsInt());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static Map<String, String> params(
            String key, String value) {
        var m = new HashMap<String, String>();
        m.put(key, value);
        return m;
    }

    private static Map<String, String> paramsMulti(
            String... pairs) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
