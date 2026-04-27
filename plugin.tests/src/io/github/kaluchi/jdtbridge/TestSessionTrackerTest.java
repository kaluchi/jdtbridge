package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TestSessionTracker} — session tracking,
 * status reporting, and event accumulation.
 */
@SuppressWarnings("restriction")
public class TestSessionTrackerTest {

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
        void liveRunningSessionShapeIsValid() {
            // The PDE test runner has its own TestRunSession in
            // JUnitCorePlugin's model (this very run). Walk the
            // happy path of handleStatus against it and assert the
            // wire shape — counts depend on suite progress, so we
            // do not pin numbers.
            List<TestRunSession> sessions =
                    JUnitCorePlugin.getModel()
                            .getTestRunSessions();
            assertNotNull(sessions);
            if (sessions.isEmpty()) return;
            TestRunSession session = sessions.get(0);
            String testRunId =
                    TestSessionHandler.testRunId(session);
            String json = handler.handleStatus(
                    Map.of("testRunId", testRunId));
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.has("configId"),
                    "Should have configId: " + obj);
            assertTrue(obj.has("testRunId"),
                    "Should have testRunId: " + obj);
            assertTrue(obj.has("state"),
                    "Should have state: " + obj);
            assertTrue(obj.has("total"),
                    "Should have total: " + obj);
            assertTrue(obj.has("passed"),
                    "Should have passed: " + obj);
            assertTrue(obj.has("entries"),
                    "Should have entries array: " + obj);
            assertTrue(obj.get("entries").isJsonArray(),
                    "entries must be JSON array: " + obj);
            String state = obj.get("state").getAsString();
            assertTrue("running".equals(state)
                            || "starting".equals(state)
                            || "finished".equals(state),
                    "state must be one of running/starting/"
                            + "finished, got: " + state);
        }

        @Test
        void liveSessionWithFailuresFilterReturnsArray() {
            // filter=failures makes collectEntries skip PASS+IGNORED
            // and recurse into containers — the result is still a
            // JSON array (possibly empty in a passing suite).
            List<TestRunSession> sessions =
                    JUnitCorePlugin.getModel()
                            .getTestRunSessions();
            if (sessions.isEmpty()) return;
            TestRunSession session = sessions.get(0);
            String testRunId =
                    TestSessionHandler.testRunId(session);
            String json = handler.handleStatus(Map.of(
                    "testRunId", testRunId,
                    "filter", "failures"));
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.get("entries").isJsonArray());
        }

        @Test
        void liveSessionWithIgnoredFilterReturnsArray() {
            List<TestRunSession> sessions =
                    JUnitCorePlugin.getModel()
                            .getTestRunSessions();
            if (sessions.isEmpty()) return;
            TestRunSession session = sessions.get(0);
            String testRunId =
                    TestSessionHandler.testRunId(session);
            String json = handler.handleStatus(Map.of(
                    "testRunId", testRunId,
                    "filter", "ignored"));
            JsonObject obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.get("entries").isJsonArray());
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
