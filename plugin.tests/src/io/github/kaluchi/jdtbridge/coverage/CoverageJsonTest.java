package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jacoco.core.analysis.ICounter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Pure-data tests for {@link CoverageJson} wire-format helpers.
 * Exercises every public branch without touching EclEmma or the
 * Eclipse runtime — JaCoCo {@link ICounter} is stubbed inline.
 */
public class CoverageJsonTest {

    @Nested
    class Error {

        @Test
        void includesKindAndMessage() {
            String json = CoverageJson.error("not-found", "missing");
            JsonObject obj = CoverageJson.parseObjectBody(json);
            assertEquals("not-found", obj.get("error").getAsString());
            assertEquals("missing", obj.get("message").getAsString());
        }

        @Test
        void omitsNullMessage() {
            String json = CoverageJson.error("not-found", null);
            JsonObject obj = CoverageJson.parseObjectBody(json);
            assertEquals("not-found", obj.get("error").getAsString());
            assertFalse(obj.has("message"));
        }

        @Test
        void omitsEmptyMessage() {
            String json = CoverageJson.error("not-found", "");
            JsonObject obj = CoverageJson.parseObjectBody(json);
            assertFalse(obj.has("message"));
        }
    }

    @Nested
    class ParseObjectBody {

        @Test
        void nullInputReturnsNull() {
            assertNull(CoverageJson.parseObjectBody(null));
        }

        @Test
        void blankInputReturnsNull() {
            assertNull(CoverageJson.parseObjectBody(""));
            assertNull(CoverageJson.parseObjectBody("   "));
            assertNull(CoverageJson.parseObjectBody("\n\t"));
        }

        @Test
        void malformedJsonReturnsNull() {
            assertNull(CoverageJson.parseObjectBody("{not-json"));
        }

        @Test
        void jsonArrayReturnsNull() {
            assertNull(CoverageJson.parseObjectBody("[1,2,3]"));
        }

        @Test
        void jsonPrimitiveReturnsNull() {
            assertNull(CoverageJson.parseObjectBody("\"plain\""));
            assertNull(CoverageJson.parseObjectBody("42"));
        }

        @Test
        void validObjectIsReturned() {
            JsonObject obj = CoverageJson.parseObjectBody("{\"a\":1}");
            assertEquals(1, obj.get("a").getAsInt());
        }
    }

    @Nested
    class OptString {

        @Test
        void missingKeyReturnsNull() {
            assertNull(CoverageJson.optString(new JsonObject(), "k"));
        }

        @Test
        void jsonNullReturnsNull() {
            JsonObject obj = CoverageJson.parseObjectBody("{\"k\":null}");
            assertNull(CoverageJson.optString(obj, "k"));
        }

        @Test
        void presentValueReturnsString() {
            JsonObject obj = CoverageJson.parseObjectBody("{\"k\":\"v\"}");
            assertEquals("v", CoverageJson.optString(obj, "k"));
        }
    }

    @Nested
    class OptBool {

        @Test
        void missingKeyReturnsDefault() {
            JsonObject empty = new JsonObject();
            assertTrue(CoverageJson.optBool(empty, "k", true));
            assertFalse(CoverageJson.optBool(empty, "k", false));
        }

        @Test
        void jsonNullReturnsDefault() {
            JsonObject obj = CoverageJson.parseObjectBody("{\"k\":null}");
            assertTrue(CoverageJson.optBool(obj, "k", true));
            assertFalse(CoverageJson.optBool(obj, "k", false));
        }

        @Test
        void presentTrueReturnsTrue() {
            JsonObject obj = CoverageJson.parseObjectBody("{\"k\":true}");
            assertTrue(CoverageJson.optBool(obj, "k", false));
        }

        @Test
        void presentFalseReturnsFalse() {
            JsonObject obj = CoverageJson.parseObjectBody("{\"k\":false}");
            assertFalse(CoverageJson.optBool(obj, "k", true));
        }
    }

    @Nested
    class AddNullableString {

        @Test
        void nullValueWritesJsonNull() {
            JsonObject obj = new JsonObject();
            CoverageJson.addNullableString(obj, "k", null);
            assertTrue(obj.get("k").isJsonNull());
        }

        @Test
        void presentValueWritesPrimitive() {
            JsonObject obj = new JsonObject();
            CoverageJson.addNullableString(obj, "k", "v");
            assertEquals("v", obj.get("k").getAsString());
        }
    }

    @Nested
    class AddNullableLong {

        @Test
        void nullValueWritesJsonNull() {
            JsonObject obj = new JsonObject();
            CoverageJson.addNullableLong(obj, "k", null);
            assertTrue(obj.get("k").isJsonNull());
        }

        @Test
        void presentValueWritesPrimitive() {
            JsonObject obj = new JsonObject();
            CoverageJson.addNullableLong(obj, "k", 42L);
            assertEquals(42L, obj.get("k").getAsLong());
        }
    }

    @Nested
    class CounterJsonShape {

        @Test
        void nullCounterYieldsEmptyObject() {
            JsonObject obj = CoverageJson.counterJson(null);
            assertEquals(0, obj.size());
        }
    }

    @Nested
    class CountersOf {

        @Test
        void nullNodeYieldsEmptyObject() {
            JsonObject obj = CoverageJson.countersOf(null);
            assertEquals(0, obj.size());
        }
    }

    @Nested
    class StatusName {

        @Test
        void mapsKnownStatuses() {
            assertEquals("EMPTY",
                    CoverageJson.statusName(ICounter.EMPTY));
            assertEquals("NOT_COVERED",
                    CoverageJson.statusName(ICounter.NOT_COVERED));
            assertEquals("FULLY_COVERED",
                    CoverageJson.statusName(ICounter.FULLY_COVERED));
            assertEquals("PARTLY_COVERED",
                    CoverageJson.statusName(ICounter.PARTLY_COVERED));
        }

        @Test
        void unknownStatusFallsBack() {
            assertEquals("UNKNOWN", CoverageJson.statusName(0xFF));
        }
    }
}
