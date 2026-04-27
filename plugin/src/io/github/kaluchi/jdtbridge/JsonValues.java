package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convert arbitrary Java values to gson {@link JsonElement}s.
 * Recursive type-dispatch — String / Boolean / Number become
 * primitives; List / Set become JsonArrays; Map becomes
 * JsonObject; null becomes JsonNull; anything else falls back to
 * {@code toString}.
 *
 * <p>Pure data — no JDT, no SWT, no IO. Lives outside the launch
 * handler so its wire shape can be unit-tested headlessly and
 * reused by any other handler that needs to serialize an
 * unknown-typed Map of attributes.
 */
final class JsonValues {

    private JsonValues() { }

    @SuppressWarnings("unchecked")
    static JsonElement toElement(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (Object item : list) arr.add(toElement(item));
            return arr;
        }
        if (value instanceof Set<?> set) {
            JsonArray arr = new JsonArray();
            for (Object item : set) arr.add(toElement(item));
            return arr;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Object> entry
                    : ((Map<String, Object>) map).entrySet()) {
                obj.add(entry.getKey(), toElement(entry.getValue()));
            }
            return obj;
        }
        return new JsonPrimitive(value.toString());
    }
}
