package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured error descriptor for HTTP responses.
 * <p>
 * Wire shape — JSON object with a single discriminator key {@code _error}:
 * <pre>
 * { "_error": { "kind": "type-not-found",
 *               "thrown": "TypeNotFound",
 *               "origin": "jdt/plugin",
 *               "message": "Type not found: com.example.Missing",
 *               "context": { "fqn": "com.example.Missing" } } }
 * </pre>
 * <p>
 * The leading-underscore key on the outer object is the discriminator
 * the CLI-side qlang impl scans for; lifting into a qlang Error value
 * happens on the JS side and rides the fail-track.
 * <p>
 * Each error site uses a dedicated factory method that fixes the
 * {@code kind} / {@code thrown} pair; context fields are seeded from
 * the factory's positional arguments and may be extended with the
 * fluent {@link #with(String, String)} builder API.
 */
class ErrorDescriptor {

    private static final String ORIGIN = "jdt/plugin";

    private final String kind;
    private final String thrown;
    private final String message;
    private final Map<String, JsonElement> context;

    private ErrorDescriptor(String kind, String thrown, String message) {
        this.kind = kind;
        this.thrown = thrown;
        this.message = message;
        this.context = new LinkedHashMap<>();
    }

    // ── Factory methods per error site ──────────────────────────────

    static ErrorDescriptor typeNotFound(String fqn) {
        return new ErrorDescriptor("type-not-found", "TypeNotFound",
                "Type not found: " + fqn).with("fqn", fqn);
    }

    static ErrorDescriptor methodNotFound(String fqmn) {
        return new ErrorDescriptor("method-not-found", "MethodNotFound",
                "Method not found: " + fqmn).with("fqmn", fqmn);
    }

    static ErrorDescriptor fieldNotFound(String fqmn) {
        return new ErrorDescriptor("field-not-found", "FieldNotFound",
                "Field not found: " + fqmn).with("fqmn", fqmn);
    }

    static ErrorDescriptor packageNotFound(String fqn) {
        return new ErrorDescriptor("package-not-found", "PackageNotFound",
                "Package not found: " + fqn).with("fqn", fqn);
    }

    static ErrorDescriptor projectNotFound(String name) {
        return new ErrorDescriptor("project-not-found", "ProjectNotFound",
                "Project not found: " + name).with("project", name);
    }

    static ErrorDescriptor fileNotFound(String path) {
        return new ErrorDescriptor("file-not-found", "FileNotFound",
                "File not found: " + path).with("path", path);
    }

    static ErrorDescriptor invalidFqn(String value) {
        return new ErrorDescriptor("invalid-fqn", "InvalidFqn",
                "Invalid fully qualified name: " + value).with("value", value);
    }

    static ErrorDescriptor invalidFqmn(String value) {
        return new ErrorDescriptor("invalid-fqmn", "InvalidFqmn",
                "Invalid fully qualified member name: " + value)
                .with("value", value);
    }

    static ErrorDescriptor missingParameter(String name) {
        return new ErrorDescriptor("missing-parameter", "MissingParameter",
                "Missing parameter: " + name).with("parameter", name);
    }

    static ErrorDescriptor invalidModifier(String parameter,
            String value, java.util.Set<String> allowed) {
        var arr = new com.google.gson.JsonArray();
        var sorted = new java.util.ArrayList<>(allowed);
        java.util.Collections.sort(sorted);
        for (String a : sorted) arr.add(a);
        return new ErrorDescriptor("invalid-modifier", "InvalidModifier",
                "Unrecognised " + parameter + " \"" + value
                + "\"; expected one of "
                + String.join(", ", sorted))
                .with("parameter", parameter)
                .with("value", value)
                .with("allowed", arr);
    }

    static ErrorDescriptor ambiguousMatch(String fqmn,
            java.util.List<String> candidates) {
        var arr = new com.google.gson.JsonArray();
        for (String candidate : candidates) arr.add(candidate);
        return new ErrorDescriptor("ambiguous-match", "AmbiguousMatch",
                "Ambiguous match for " + fqmn + ": "
                + candidates.size() + " candidates — "
                + String.join(", ", candidates))
                .with("fqmn", fqmn)
                .with("matchCount", candidates.size())
                .with("candidates", arr);
    }

    static ErrorDescriptor wrongSubjectKind(String operand,
            String expected, String actual) {
        return new ErrorDescriptor("wrong-subject-kind", "WrongSubjectKind",
                operand + " expects subject of kind " + expected
                + ", got " + actual)
                .with("operand", operand)
                .with("expected", expected)
                .with("actual", actual);
    }

    static ErrorDescriptor ioError(String message) {
        return new ErrorDescriptor("io-error", "IoError", message);
    }

    static ErrorDescriptor jdtInternalError(String message, Throwable cause) {
        return new ErrorDescriptor("jdt-internal-error",
                "JdtInternalError", message)
                .with("causeClass",
                        cause != null ? cause.getClass().getName() : null)
                .with("causeMessage",
                        cause != null ? cause.getMessage() : null);
    }

    // ── Fluent builder for context fields ───────────────────────────
    // Null values skipped — context entries are opt-in.

    ErrorDescriptor with(String key, String value) {
        if (value != null) context.put(key, new JsonPrimitive(value));
        return this;
    }

    ErrorDescriptor with(String key, Number value) {
        if (value != null) context.put(key, new JsonPrimitive(value));
        return this;
    }

    ErrorDescriptor with(String key, Boolean value) {
        if (value != null) context.put(key, new JsonPrimitive(value));
        return this;
    }

    ErrorDescriptor with(String key, JsonElement value) {
        if (value != null) context.put(key, value);
        return this;
    }

    // ── Accessors ───────────────────────────────────────────────────

    String kind() { return kind; }
    String thrown() { return thrown; }
    String message() { return message; }
    Map<String, JsonElement> context() { return Map.copyOf(context); }

    // ── Serialization ───────────────────────────────────────────────

    JsonObject toJson() {
        var inner = new JsonObject();
        inner.addProperty("kind", kind);
        inner.addProperty("thrown", thrown);
        inner.addProperty("origin", ORIGIN);
        inner.addProperty("message", message);
        var ctx = new JsonObject();
        for (var entry : context.entrySet()) {
            ctx.add(entry.getKey(), entry.getValue());
        }
        inner.add("context", ctx);
        var outer = new JsonObject();
        outer.add("_error", inner);
        return outer;
    }

    String toJsonString() {
        return toJson().toString();
    }
}
