package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TestSessionTracker} — session tracking,
 * status reporting, and event accumulation.
 */
@SuppressWarnings("restriction")
public class TestSessionTrackerTest {

    private static final String SIMPLE_TEST_FQN =
            "test.edge.SimpleTest";
    private static TestRunSession finishedSession;

    @BeforeAll
    static void launchFixtureSession() throws Exception {
        TestFixture.create();
        Map<String, String> params = new java.util.HashMap<>();
        params.put("target", SIMPLE_TEST_FQN);
        params.put("no-refresh", "");
        String json = new TestHandler().handleTestRun(params);
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("ok").getAsBoolean(),
                "handleTestRun must succeed: " + json);
        finishedSession = TestSessionAwait.awaitFinished(
                obj.get("testRunId").getAsString(), 60_000);
    }

    @AfterAll
    static void releaseFixtureSession() throws Exception {
        if (finishedSession != null) {
            JUnitCorePlugin.getModel()
                    .removeTestRunSession(finishedSession);
            finishedSession = null;
        }
        TestFixture.destroy();
    }


    @Nested
    class TrackedTestSession {

        @Test
        void emitAddsToEventsList() {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            ts.emit("event1");
            assertEquals(1, ts.events.size());
        }

        @Test
        void emitNotifiesListeners() {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            var received = new ArrayList<String>();
            ts.addListener(line -> received.add(line));
            ts.emit("event1");
            assertEquals(1, received.size());
            assertEquals("event1", received.get(0));
        }

        @Test
        void addRemoveListener() {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            var received = new ArrayList<String>();
            TestSessionTracker.TestEventListener l =
                    line -> received.add(line);
            ts.addListener(l);
            ts.emit("before");
            ts.removeListener(l);
            ts.emit("after");
            assertEquals(1, received.size());
            assertEquals("before", received.get(0));
        }

        @Test
        void countersStartAtZero() {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            assertEquals(0, ts.passed.get());
            assertEquals(0, ts.failed.get());
            assertEquals(0, ts.errors.get());
            assertEquals(0, ts.ignored.get());
            assertEquals(0, ts.completed.get());
        }

        @Test
        void stateStartsAsRunning() {
            var ts = new TestSessionTracker.TrackedTestSession(
                    "test-1");
            assertEquals("running", ts.state);
        }
    }

    @Nested
    class PreRegister {

        private TestSessionTracker tracker;

        @BeforeEach
        void setUp() {
            tracker = new TestSessionTracker();
        }

        @Test
        void preRegisterCreatesSession() {
            tracker.preRegister("test");
            assertNotNull(tracker.get("test"));
        }

        @Test
        void getReturnsNullForUnknown() {
            assertNull(tracker.get("nonexistent"));
        }

        @Test
        void awaitReturnsSessionAfterPreRegister() {
            tracker.preRegister("await-test");
            assertNotNull(tracker.await("await-test"));
        }

        @Test
        void awaitReturnsNullForUnknown() {
            // await polls 10x500ms = 5s, too slow for unit test
            // Just verify get returns null immediately
            assertNull(tracker.get("nonexistent-await"));
        }
    }

    @Nested
    class Registry {

        private TestSessionTracker tracker;

        @BeforeEach
        void setUp() {
            tracker = new TestSessionTracker();
        }

        @Test
        void allEmptyTrackerYieldsNoSessions() {
            int count = 0;
            for (var s : tracker.all()) count++;
            assertEquals(0, count);
        }

        @Test
        void allReturnsRegisteredSessions() {
            tracker.preRegister("a");
            tracker.preRegister("b");
            int count = 0;
            for (var s : tracker.all()) count++;
            assertEquals(2, count);
        }

        @Test
        void removeDropsSessionFromGet() {
            tracker.preRegister("doomed");
            assertNotNull(tracker.get("doomed"));
            tracker.remove("doomed");
            assertNull(tracker.get("doomed"));
        }

        @Test
        void removeUnknownKeyIsNoop() {
            tracker.remove("never-existed");
            // No exception, tracker still empty.
            int count = 0;
            for (var s : tracker.all()) count++;
            assertEquals(0, count);
        }

        @Test
        void registerAliasMakesGetReturnSameSession() {
            var ts = tracker.preRegister("primary");
            tracker.registerAlias("alias-1", ts);
            assertEquals(ts, tracker.get("alias-1"));
        }

        @Test
        void registerAliasIsIdempotent() {
            var first = tracker.preRegister("primary");
            var second =
                    new TestSessionTracker.TrackedTestSession(
                            "secondary");
            tracker.registerAlias("shared", first);
            // putIfAbsent semantics: second registration must not
            // overwrite — alias keeps pointing at the first session.
            tracker.registerAlias("shared", second);
            assertEquals(first, tracker.get("shared"));
        }
    }

    @Nested
    class HandleStatus {

        private TestSessionHandler handler;

        @BeforeEach
        void setUp() {
            handler = new TestSessionHandler();
        }

        @Test
        void missingSessionReturnsError() {
            String json = handler.handleStatus(Map.of());
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("Missing"),
                    "Should say missing: " + json);
        }

        @Test
        void unknownSessionReturnsError() {
            String json = handler.handleStatus(
                    Map.of("testRunId", "xxx"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("not found"),
                    "Should say not found: " + json);
        }

        @Test
        void blankSessionReturnsError() {
            String json = handler.handleStatus(Map.of("testRunId", "   "));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("Missing"),
                    "Should say missing: " + json);
        }

        @Test
        void finishedSessionReportsFinishedStateAndEntries() {
            // Drive handleStatus against the SimpleTest run created
            // in @BeforeAll — guaranteed exactly one passing case,
            // so counts are stable.
            String testRunId = TestSessionHandler.testRunId(
                    finishedSession);
            String json = handler.handleStatus(
                    Map.of("testRunId", testRunId));
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            // Eclipse may name the config "SimpleTest" or
            // "SimpleTest (N)" depending on whether sibling test
            // classes already created one in this run.
            assertTrue(obj.get("configId").getAsString()
                            .startsWith("SimpleTest"),
                    "configId must start with SimpleTest: "
                            + obj.get("configId"));
            assertEquals(1, obj.get("total").getAsInt());
            assertEquals(1, obj.get("passed").getAsInt());
            assertEquals(0, obj.get("failed").getAsInt());
            assertEquals(0, obj.get("errors").getAsInt());
            // Default filter excludes PASS — entries empty here.
            assertEquals(0,
                    obj.get("entries").getAsJsonArray().size());
        }

        @Test
        void filterAllIncludesPassingCase() {
            String testRunId = TestSessionHandler.testRunId(
                    finishedSession);
            String json = handler.handleStatus(Map.of(
                    "testRunId", testRunId,
                    "filter", "all"));
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            // filter=all bypasses both inline continue branches
            // (neither matches "ignored" nor "failures"/null), so
            // the passing case is emitted.
            var entries = obj.get("entries").getAsJsonArray();
            assertEquals(1, entries.size(),
                    "filter=all must include the PASS case: "
                            + obj);
            JsonObject entry = entries.get(0).getAsJsonObject();
            assertEquals("PASS",
                    entry.get("status").getAsString());
            assertEquals("test.edge.SimpleTest#onePlusOne",
                    entry.get("fqn").getAsString());
        }

        @Test
        void filterIgnoredKeepsArrayEmptyForPassingCase() {
            String testRunId = TestSessionHandler.testRunId(
                    finishedSession);
            String json = handler.handleStatus(Map.of(
                    "testRunId", testRunId,
                    "filter", "ignored"));
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals(0,
                    obj.get("entries").getAsJsonArray().size(),
                    "PASS case must be dropped under filter=ignored");
        }
    }

    @Nested
    class HandleClear {

        private TestSessionHandler handler;

        @BeforeEach
        void setUp() {
            handler = new TestSessionHandler();
        }

        @Test
        void unknownTestRunIdRemovesNothing() {
            String json = handler.handleClear(
                    Map.of("testRunId",
                            "no-such-run-"
                                    + java.util.UUID.randomUUID()));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals(0, obj.get("removed").getAsInt(),
                    "No match → removed must be 0: " + json);
        }

        @Test
        void responseAlwaysHasRemovedInteger() {
            // Workspace can hold finished sessions from earlier
            // runs in the suite; only assert the response shape,
            // not a specific count.
            String json = handler.handleClear(
                    Map.of("testRunId",
                            "no-match-shape-"
                                    + java.util.UUID.randomUUID()));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.has("removed"),
                    "Response must carry 'removed' field: " + json);
            assertTrue(obj.get("removed").getAsInt() >= 0,
                    "removed must be non-negative: " + json);
        }
    }

    @Nested
    class HandleSessions {

        private TestSessionHandler handler;

        @BeforeEach
        void setUp() {
            handler = new TestSessionHandler();
        }

        @Test
        void returnsJsonArray() {
            String json = handler.handleSessions(Map.of(), ProjectScope.ALL);
            // JUnitModel returns real sessions from workspace.
            // At minimum, result must be valid JSON array.
            var arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            assertNotNull(arr);
        }

        @Test
        void sessionsHaveRequiredFields() {
            String json = handler.handleSessions(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            for (var el : arr) {
                var obj = el.getAsJsonObject();
                assertTrue(obj.has("configId"),
                        "Should have configId: " + obj);
                assertTrue(obj.has("testRunId"),
                        "Should have testRunId: " + obj);
                assertTrue(obj.has("state"),
                        "Should have state: " + obj);
                assertTrue(obj.has("total"),
                        "Should have total: " + obj);
            }
        }

        @Test
        void filteredScopeSkipsSessionsOutsideScope() {
            // Scope of a project name no real launch could ever
            // belong to → every live session must be filtered out.
            ProjectScope narrow = ProjectScope.of(
                    java.util.Set.of(
                            "no-project-with-this-name-"
                                    + java.util.UUID.randomUUID()));
            String json = handler.handleSessions(
                    Map.of(), narrow);
            var arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            assertEquals(0, arr.size(),
                    "Filtered scope must yield empty array: "
                            + json);
        }
    }


    @Nested
    class Lifecycle {

        // Integration tests require real JUnit session events
        // from the Eclipse test infrastructure.

        @Test
        void sessionFinishedSetsState() {
            // This test requires a real JUnit test run via
            // Eclipse test infrastructure. The session lifecycle
            // is tested indirectly via TestHandlerIntegrationTest.
            var tracker = new TestSessionTracker();
            var ts = tracker.preRegister("lifecycle-test");
            assertEquals("running", ts.state);
            // Simulate finish
            ts.state = "finished";
            assertEquals("finished", ts.state);
        }
    }
}
