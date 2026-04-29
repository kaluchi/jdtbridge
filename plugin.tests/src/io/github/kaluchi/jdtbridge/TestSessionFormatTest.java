package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Pure-data tests for {@link TestSessionFormat} — the formatting
 * helpers shared between {@link TestSessionHandler} and
 * {@link TestProgressStreamer}.
 */
public class TestSessionFormatTest {

    @Nested
    class StreamerFilter {

        @Test
        void nullFilterIncludesEverything() {
            assertTrue(TestSessionFormat.streamerFilter("PASS", null));
            assertTrue(TestSessionFormat.streamerFilter("FAIL", null));
            assertTrue(TestSessionFormat.streamerFilter("ERROR", null));
            assertTrue(TestSessionFormat.streamerFilter("IGNORED", null));
            assertTrue(TestSessionFormat.streamerFilter("UNKNOWN", null));
        }

        @Test
        void allFilterIncludesEverything() {
            assertTrue(TestSessionFormat.streamerFilter("PASS", "all"));
            assertTrue(TestSessionFormat.streamerFilter("FAIL", "all"));
            assertTrue(TestSessionFormat.streamerFilter("IGNORED", "all"));
        }

        @Test
        void failuresFilterIncludesOnlyFailAndError() {
            assertTrue(TestSessionFormat.streamerFilter("FAIL", "failures"));
            assertTrue(TestSessionFormat.streamerFilter("ERROR", "failures"));
            assertFalse(TestSessionFormat.streamerFilter("PASS", "failures"));
            assertFalse(TestSessionFormat.streamerFilter("IGNORED", "failures"));
            assertFalse(TestSessionFormat.streamerFilter("UNKNOWN", "failures"));
        }

        @Test
        void ignoredFilterIncludesOnlyIgnored() {
            assertTrue(TestSessionFormat.streamerFilter("IGNORED", "ignored"));
            assertFalse(TestSessionFormat.streamerFilter("PASS", "ignored"));
            assertFalse(TestSessionFormat.streamerFilter("FAIL", "ignored"));
            assertFalse(TestSessionFormat.streamerFilter("ERROR", "ignored"));
        }

        @Test
        void unknownFilterValueExcludes() {
            // fail-closed for typos — silent flood is worse than
            // an empty stream the caller can spot immediately.
            assertFalse(TestSessionFormat.streamerFilter("PASS", "garbage"));
            assertFalse(TestSessionFormat.streamerFilter("FAIL", ""));
            assertFalse(TestSessionFormat.streamerFilter("IGNORED", "skipped"));
        }
    }

    @Nested
    class StatusName {

        @Test
        void okMapsToPass() {
            assertEquals("PASS",
                    TestSessionFormat.statusName(
                            ITestElement.Result.OK));
        }

        @Test
        void failureMapsToFail() {
            assertEquals("FAIL",
                    TestSessionFormat.statusName(
                            ITestElement.Result.FAILURE));
        }

        @Test
        void errorMapsToError() {
            assertEquals("ERROR",
                    TestSessionFormat.statusName(
                            ITestElement.Result.ERROR));
        }

        @Test
        void ignoredMapsToIgnored() {
            assertEquals("IGNORED",
                    TestSessionFormat.statusName(
                            ITestElement.Result.IGNORED));
        }

        @Test
        void undefinedMapsToUnknown() {
            assertEquals("UNKNOWN",
                    TestSessionFormat.statusName(
                            ITestElement.Result.UNDEFINED));
        }

        @Test
        void nullMapsToUnknown() {
            assertEquals("UNKNOWN",
                    TestSessionFormat.statusName(null));
        }
    }

    @Nested
    class StateOf {

        @Test
        void runningWins() {
            assertEquals("running",
                    TestSessionFormat.stateOf(true, false));
        }

        @Test
        void runningWinsOverStarting() {
            // session can briefly report both flags during
            // transitions — running has priority.
            assertEquals("running",
                    TestSessionFormat.stateOf(true, true));
        }

        @Test
        void startingWhenNotRunning() {
            assertEquals("starting",
                    TestSessionFormat.stateOf(false, true));
        }

        @Test
        void neitherFlagFinished() {
            assertEquals("finished",
                    TestSessionFormat.stateOf(false, false));
        }
    }

    @Nested
    class PassedCount {

        @Test
        void zeroStartedYieldsZero() {
            assertEquals(0,
                    TestSessionFormat.passedCount(0, 0, 0, 0));
        }

        @Test
        void allCountersSubtracted() {
            assertEquals(10 - 1 - 2 - 3,
                    TestSessionFormat.passedCount(10, 1, 2, 3));
        }

        @Test
        void allPassed() {
            assertEquals(7,
                    TestSessionFormat.passedCount(7, 0, 0, 0));
        }

        @Test
        void clampsAtZeroWhenBucketsExceedStarted() {
            assertEquals(0,
                    TestSessionFormat.passedCount(0, 1, 0, 0));
            assertEquals(0,
                    TestSessionFormat.passedCount(2, 5, 0, 0));
        }
    }

    @Nested
    class AttachFailureTrace {

        @Test
        void nullTraceLeavesObjectUntouched() {
            JsonObject obj = new JsonObject();
            TestSessionFormat.attachFailureTrace(obj, null);
            assertEquals(0, obj.size());
        }

        @Test
        void allThreeFieldsPresent() {
            FailureTrace ft = new FailureTrace(
                    "stack", "exp", "act");
            JsonObject obj = new JsonObject();
            TestSessionFormat.attachFailureTrace(obj, ft);
            assertEquals("stack", obj.get("trace").getAsString());
            assertEquals("exp",
                    obj.get("expected").getAsString());
            assertEquals("act",
                    obj.get("actual").getAsString());
        }

        @Test
        void onlyTracePresent() {
            FailureTrace ft = new FailureTrace(
                    "stack", null, null);
            JsonObject obj = new JsonObject();
            TestSessionFormat.attachFailureTrace(obj, ft);
            assertTrue(obj.has("trace"));
            assertFalse(obj.has("expected"));
            assertFalse(obj.has("actual"));
        }

        @Test
        void onlyExpectedAndActual() {
            FailureTrace ft = new FailureTrace(
                    null, "exp", "act");
            JsonObject obj = new JsonObject();
            TestSessionFormat.attachFailureTrace(obj, ft);
            assertFalse(obj.has("trace"));
            assertEquals("exp",
                    obj.get("expected").getAsString());
            assertEquals("act",
                    obj.get("actual").getAsString());
        }

        @Test
        void allThreeNullSkipsEverything() {
            FailureTrace ft = new FailureTrace(null, null, null);
            JsonObject obj = new JsonObject();
            TestSessionFormat.attachFailureTrace(obj, ft);
            assertEquals(0, obj.size());
        }
    }
}
