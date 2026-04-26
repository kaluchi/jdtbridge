package io.github.kaluchi.jdtbridge.coverage;

import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageNode;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Shared wire-format helpers for {@code /coverage/*} responses.
 * All shapes are documented in
 * {@code docs/bridge-coverage-spec.md}.
 */
final class CoverageJson {

    private CoverageJson() {
    }

    /** Standard {@code {"error": kind, "message": ...}} envelope. */
    static String error(String kind, String message) {
        var obj = new JsonObject();
        obj.addProperty("error", kind);
        if (message != null && !message.isEmpty()) {
            obj.addProperty("message", message);
        }
        return obj.toString();
    }

    /** Parse a JSON object body, or {@code null} on missing /
     *  malformed / non-object input. */
    static JsonObject parseObjectBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            return null;
        }
        return null;
    }

    static String optString(JsonObject body, String key) {
        JsonElement el = body.get(key);
        return el != null && !el.isJsonNull()
                ? el.getAsString() : null;
    }

    static boolean optBool(JsonObject body, String key,
            boolean defaultValue) {
        JsonElement el = body.get(key);
        return el != null && !el.isJsonNull()
                ? el.getAsBoolean() : defaultValue;
    }

    static void addNullableString(JsonObject obj, String key,
            String value) {
        if (value == null) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, value);
        }
    }

    static void addNullableLong(JsonObject obj, String key,
            Long value) {
        if (value == null) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, value);
        }
    }

    /** All six {@link ICounter}s wrapped in a single counters
     *  object — wire shape per spec § Counter shape. */
    static JsonObject countersOf(ICoverageNode cov) {
        var obj = new JsonObject();
        if (cov == null) {
            return obj;
        }
        obj.add("instruction",
                counterJson(cov.getInstructionCounter()));
        obj.add("branch", counterJson(cov.getBranchCounter()));
        obj.add("line", counterJson(cov.getLineCounter()));
        obj.add("complexity",
                counterJson(cov.getComplexityCounter()));
        obj.add("method", counterJson(cov.getMethodCounter()));
        obj.add("class", counterJson(cov.getClassCounter()));
        return obj;
    }

    /** Single-counter wire shape. {@code coveredRatio /
     *  missedRatio} become JSON {@code null} when JaCoCo returns
     *  {@code NaN} (i.e. {@code totalCount == 0}). */
    static JsonObject counterJson(ICounter counter) {
        var obj = new JsonObject();
        if (counter == null) {
            return obj;
        }
        obj.addProperty("coveredCount", counter.getCoveredCount());
        obj.addProperty("missedCount", counter.getMissedCount());
        obj.addProperty("totalCount", counter.getTotalCount());
        addRatio(obj, "coveredRatio", counter.getCoveredRatio());
        addRatio(obj, "missedRatio", counter.getMissedRatio());
        obj.addProperty("coverageStatus",
                statusName(counter.getStatus()));
        return obj;
    }

    private static void addRatio(JsonObject obj, String key,
            double value) {
        if (Double.isNaN(value)) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, value);
        }
    }

    /** {@link ICounter#getStatus()} bit-flag → constant name from
     *  {@link ICoverageNode}. */
    static String statusName(int status) {
        return switch (status) {
            case ICounter.EMPTY -> "EMPTY";
            case ICounter.NOT_COVERED -> "NOT_COVERED";
            case ICounter.FULLY_COVERED -> "FULLY_COVERED";
            case ICounter.PARTLY_COVERED -> "PARTLY_COVERED";
            default -> "UNKNOWN";
        };
    }
}
