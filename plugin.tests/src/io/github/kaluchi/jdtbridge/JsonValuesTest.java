package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Pure-data tests for {@link JsonValues#toElement}. */
public class JsonValuesTest {

    @Test
    void nullBecomesJsonNull() {
        assertEquals(JsonNull.INSTANCE, JsonValues.toElement(null));
    }

    @Test
    void stringBecomesPrimitive() {
        JsonElement e = JsonValues.toElement("hello");
        assertTrue(e.isJsonPrimitive());
        assertEquals("hello", e.getAsString());
    }

    @Test
    void booleanBecomesPrimitive() {
        assertTrue(JsonValues.toElement(true).getAsBoolean());
        assertFalse(JsonValues.toElement(false).getAsBoolean());
    }

    @Test
    void integerBecomesPrimitive() {
        assertEquals(42, JsonValues.toElement(42).getAsInt());
    }

    @Test
    void doubleBecomesPrimitive() {
        assertEquals(3.14,
                JsonValues.toElement(3.14).getAsDouble(),
                1e-9);
    }

    @Test
    void listBecomesArray() {
        JsonElement e = JsonValues.toElement(
                List.of("a", 1, true));
        assertTrue(e.isJsonArray());
        var arr = e.getAsJsonArray();
        assertEquals(3, arr.size());
        assertEquals("a", arr.get(0).getAsString());
        assertEquals(1, arr.get(1).getAsInt());
        assertTrue(arr.get(2).getAsBoolean());
    }

    @Test
    void setBecomesArray() {
        JsonElement e = JsonValues.toElement(Set.of("only"));
        assertTrue(e.isJsonArray());
        assertEquals(1, e.getAsJsonArray().size());
    }

    @Test
    void mapBecomesObject() {
        JsonElement e = JsonValues.toElement(
                Map.of("k", "v", "n", 7));
        assertTrue(e.isJsonObject());
        var obj = e.getAsJsonObject();
        assertEquals("v", obj.get("k").getAsString());
        assertEquals(7, obj.get("n").getAsInt());
    }

    @Test
    void nestedListInMapInList() {
        JsonElement e = JsonValues.toElement(
                List.of(Map.of("xs", List.of(1, 2))));
        var outer = e.getAsJsonArray();
        var inner = outer.get(0).getAsJsonObject()
                .get("xs").getAsJsonArray();
        assertEquals(1, inner.get(0).getAsInt());
        assertEquals(2, inner.get(1).getAsInt());
    }

    @Test
    void unknownTypeFallsBackToToString() {
        // StringBuilder is not String/Number/Boolean/List/Set/Map,
        // so the fallback `value.toString()` branch fires.
        JsonElement e = JsonValues.toElement(
                new StringBuilder("sb"));
        assertEquals("sb", e.getAsString());
    }

    @Test
    void emptyCollectionsRoundTrip() {
        assertEquals(0,
                JsonValues.toElement(List.of()).getAsJsonArray().size());
        assertEquals(0,
                JsonValues.toElement(Set.of()).getAsJsonArray().size());
        assertEquals(0,
                JsonValues.toElement(Map.of()).getAsJsonObject().size());
    }
}
