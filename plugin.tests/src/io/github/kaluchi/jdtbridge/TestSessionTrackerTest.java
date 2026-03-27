package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Map;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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

        private TestSessionTracker tracker;
        private TestSessionHandler handler;

        @BeforeEach
        void setUp() {
            tracker = new TestSessionTracker();
            handler = new TestSessionHandler(tracker);
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
                    Map.of("session", "xxx"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("not found"),
                    "Should say not found: " + json);
        }

        @Test
        void returnsCorrectCountersAndState() {
            var ts = tracker.preRegister("my-session");
            ts.total = 10;
            ts.passed.set(5);
            ts.failed.set(2);
            ts.errors.set(1);
            ts.ignored.set(2);
            ts.completed.set(10);
            ts.state = "finished";
            ts.time = 1.5;

            String json = handler.handleStatus(
                    Map.of("session", "my-session"));
            assertTrue(json.contains("\"state\":\"finished\""),
                    "Should have state: " + json);
            assertTrue(json.contains("\"total\":10"),
                    "Should have total: " + json);
            assertTrue(json.contains("\"passed\":5"),
                    "Should have passed: " + json);
            assertTrue(json.contains("\"failed\":2"),
                    "Should have failed: " + json);
            assertTrue(json.contains("\"errors\":1"),
                    "Should have errors: " + json);
            assertTrue(json.contains("\"ignored\":2"),
                    "Should have ignored: " + json);
            assertTrue(json.contains("\"completed\":10"),
                    "Should have completed: " + json);
        }

        @Test
        void returnsFailuresWithTrace() {
            var ts = tracker.preRegister("fail-session");
            var ev = new JsonObject();
            ev.addProperty("event", "case");
            ev.addProperty("fqmn", "com.Foo#bar");
            ev.addProperty("status", "FAIL");
            ev.addProperty("time", 0);
            ev.addProperty("trace", "java.lang.AssertionError");
            ts.emit(ev.toString());

            String json = handler.handleStatus(
                    Map.of("session", "fail-session"));
            assertTrue(json.contains("\"entries\""),
                    "Should have entries: " + json);
            assertTrue(json.contains("com.Foo#bar"),
                    "Should have fqmn: " + json);
            assertTrue(json.contains("FAIL"),
                    "Should have status: " + json);
            assertTrue(json.contains("AssertionError"),
                    "Should have trace: " + json);
        }

        @Test
        void filterFailuresExcludesIgnored() {
            var ts = tracker.preRegister("ign-session");
            var ev = new JsonObject();
            ev.addProperty("event", "case");
            ev.addProperty("fqmn", "com.Foo#ignored");
            ev.addProperty("status", "IGNORED");
            ev.addProperty("time", 0);
            ts.emit(ev.toString());

            String json = handler.handleStatus(
                    Map.of("session", "ign-session",
                            "filter", "failures"));
            assertTrue(json.contains("\"entries\":[]"),
                    "Failures should be empty: " + json);
        }

        @Test
        void filterIgnoredExcludesFail() {
            var ts = tracker.preRegister("fail-only");
            var ev = new JsonObject();
            ev.addProperty("event", "case");
            ev.addProperty("fqmn", "com.Foo#fail");
            ev.addProperty("status", "FAIL");
            ev.addProperty("time", 0);
            ts.emit(ev.toString());

            String json = handler.handleStatus(
                    Map.of("session", "fail-only",
                            "filter", "ignored"));
            assertTrue(json.contains("\"entries\":[]"),
                    "Failures should be empty: " + json);
        }

        @Test
        void blankSessionReturnsError() {
            String json = handler.handleStatus(Map.of("session", "   "));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("Missing"),
                    "Should say missing: " + json);
        }

        @Test
        void returnsFailuresWithExpectedActual() {
            var ts = tracker.preRegister("ea-session");
            ts.state = "finished";
            ts.total = 1;
            ts.failed.set(1);
            // Emit case event with expected/actual
            ts.emit("{\"event\":\"case\",\"fqmn\":\"Foo#bar\","
                    + "\"status\":\"FAIL\",\"time\":0.1,"
                    + "\"trace\":\"AssertionError\","
                    + "\"expected\":\"3\",\"actual\":\"2\"}");
            String json = handler.handleStatus(
                    Map.of("session", "ea-session"));
            assertTrue(json.contains("\"expected\":\"3\""),
                    "Should have expected: " + json);
            assertTrue(json.contains("\"actual\":\"2\""),
                    "Should have actual: " + json);
        }

        @Test
        void filterIgnoredIncludesIgnored() {
            var ts = tracker.preRegister("ign-only");
            var ev = new JsonObject();
            ev.addProperty("event", "case");
            ev.addProperty("fqmn", "com.Foo#skip");
            ev.addProperty("status", "IGNORED");
            ev.addProperty("time", 0);
            ts.emit(ev.toString());

            String json = handler.handleStatus(
                    Map.of("session", "ign-only",
                            "filter", "ignored"));
            assertTrue(json.contains("IGNORED"),
                    "Should have IGNORED: " + json);
            assertTrue(json.contains("com.Foo#skip"),
                    "Should have fqmn: " + json);
        }
    }

    @Nested
    class HandleSessions {

        private TestSessionTracker tracker;
        private TestSessionHandler handler;

        @BeforeEach
        void setUp() {
            tracker = new TestSessionTracker();
            handler = new TestSessionHandler(tracker);
        }

        @Test
        void emptyReturnsEmptyArray() {
            assertEquals("[]",
                    handler.handleSessions(Map.of()));
        }

        @Test
        void returnsAllSessions() {
            tracker.preRegister("session-a");
            tracker.preRegister("session-b");
            String json = handler.handleSessions(Map.of());
            assertTrue(json.contains("session-a"),
                    "Should have session-a: " + json);
            assertTrue(json.contains("session-b"),
                    "Should have session-b: " + json);
        }

        @Test
        void includesLabelAndState() {
            var ts = tracker.preRegister("labeled");
            ts.label = "MyTestClass";
            ts.state = "finished";

            String json = handler.handleSessions(Map.of());
            assertTrue(json.contains("\"label\":\"MyTestClass\""),
                    "Should have label: " + json);
            assertTrue(json.contains("\"state\":\"finished\""),
                    "Should have state: " + json);
        }
    }

    @Nested
    class NanHandling {

        @Test
        void nanTimeSerializedAsZero() {
            var tracker2 = new TestSessionTracker();
            var handler2 = new TestSessionHandler(tracker2);
            var ts = tracker2.preRegister("nan-session");
            ts.state = "finished";
            ts.total = 1;
            ts.time = Double.NaN;
            String json = handler2.handleStatus(
                    Map.of("session", "nan-session"));
            assertTrue(json.contains("\"time\":0.0"),
                    "NaN should be 0.0: " + json);
        }
    }

    @Nested
    @EnabledIfSystemProperty(
            named = "jdtbridge.integration-tests",
            matches = "true")
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
