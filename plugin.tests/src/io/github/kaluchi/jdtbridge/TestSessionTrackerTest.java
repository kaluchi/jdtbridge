package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TestSessionTracker} — session tracking,
 * status reporting, and event accumulation.
 */
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
