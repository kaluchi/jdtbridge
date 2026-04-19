package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Unit tests for {@link ErrorDescriptor} — wire shape, factory
 * invariants, and fluent builder semantics. All assertions go
 * through the JSON serialization path because that is the wire
 * contract the CLI side parses; in-memory accessor checks are
 * coincidental.
 */
public class ErrorDescriptorTest {

    private static JsonObject inner(ErrorDescriptor d) {
        return d.toJson().getAsJsonObject("_error");
    }

    private static JsonObject ctx(ErrorDescriptor d) {
        return inner(d).getAsJsonObject("context");
    }

    @Test
    void typeNotFoundCarriesFqnInContext() {
        var d = ErrorDescriptor.typeNotFound("com.example.Missing");
        assertEquals("type-not-found", inner(d).get("kind").getAsString());
        assertEquals("TypeNotFound", inner(d).get("thrown").getAsString());
        assertEquals("jdt/plugin", inner(d).get("origin").getAsString());
        assertTrue(inner(d).get("message").getAsString()
                .contains("com.example.Missing"));
        assertEquals("com.example.Missing",
                ctx(d).get("fqn").getAsString());
    }

    @Test
    void methodNotFoundCarriesFqmn() {
        var d = ErrorDescriptor.methodNotFound("com.example.Foo#bar()");
        assertEquals("method-not-found",
                inner(d).get("kind").getAsString());
        assertEquals("com.example.Foo#bar()",
                ctx(d).get("fqmn").getAsString());
    }

    @Test
    void fieldNotFoundCarriesFqmn() {
        var d = ErrorDescriptor.fieldNotFound("com.example.Foo#x");
        assertEquals("field-not-found", inner(d).get("kind").getAsString());
        assertEquals("com.example.Foo#x",
                ctx(d).get("fqmn").getAsString());
    }

    @Test
    void wrongSubjectKindCarriesAllThree() {
        var d = ErrorDescriptor.wrongSubjectKind(
                "@callers", "method", "type");
        assertEquals("@callers", ctx(d).get("operand").getAsString());
        assertEquals("method", ctx(d).get("expected").getAsString());
        assertEquals("type", ctx(d).get("actual").getAsString());
    }

    @Test
    void ambiguousMatchCarriesCandidates() {
        var d = ErrorDescriptor.ambiguousMatch("Foo#bar",
                List.of("Foo#bar()", "Foo#bar(String)",
                        "Foo#bar(int)"));
        assertEquals(3, ctx(d).get("matchCount").getAsInt());
        assertEquals("Foo#bar", ctx(d).get("fqmn").getAsString());
        var candidates = ctx(d).getAsJsonArray("candidates");
        assertEquals(3, candidates.size());
        assertEquals("Foo#bar()",
                candidates.get(0).getAsString());
        assertEquals("Foo#bar(String)",
                candidates.get(1).getAsString());
        assertEquals("Foo#bar(int)",
                candidates.get(2).getAsString());
    }

    @Test
    void ioErrorHasNoMandatoryContext() {
        var d = ErrorDescriptor.ioError("disk full");
        assertEquals("io-error", inner(d).get("kind").getAsString());
        assertEquals("disk full", inner(d).get("message").getAsString());
        assertTrue(ctx(d).entrySet().isEmpty());
    }

    @Test
    void jdtInternalErrorAttachesCauseFields() {
        var cause = new RuntimeException("inner-msg");
        var d = ErrorDescriptor.jdtInternalError("outer-msg", cause);
        assertEquals("jdt-internal-error",
                inner(d).get("kind").getAsString());
        assertEquals("outer-msg", inner(d).get("message").getAsString());
        assertEquals("java.lang.RuntimeException",
                ctx(d).get("causeClass").getAsString());
        assertEquals("inner-msg",
                ctx(d).get("causeMessage").getAsString());
    }

    @Test
    void jdtInternalErrorTolerantOfNullCause() {
        var d = ErrorDescriptor.jdtInternalError("just msg", null);
        assertNull(ctx(d).get("causeClass"));
        assertNull(ctx(d).get("causeMessage"));
    }

    @Test
    void contextWithBuilderChainAddsAllTypes() {
        var d = ErrorDescriptor.ioError("test")
                .with("strField", "value")
                .with("intField", 42)
                .with("boolField", true);
        assertEquals("value", ctx(d).get("strField").getAsString());
        assertEquals(42, ctx(d).get("intField").getAsInt());
        assertTrue(ctx(d).get("boolField").getAsBoolean());
    }

    @Test
    void nullContextValueIsSkipped() {
        var d = ErrorDescriptor.ioError("test")
                .with("present", "yes")
                .with("absent", (String) null);
        assertEquals("yes", ctx(d).get("present").getAsString());
        assertNull(ctx(d).get("absent"));
    }

    @Test
    void wireShapeHasSingleErrorDiscriminator() {
        var d = ErrorDescriptor.invalidFqn("not.a.real-fqn");
        var json = d.toJson();
        assertEquals(1, json.entrySet().size(),
                "outer object must have exactly one key");
        assertNotNull(json.get("_error"),
                "discriminator must be _error");
    }

    @Test
    void everyFactoryEmitsOriginJdtPlugin() {
        ErrorDescriptor[] cases = {
            ErrorDescriptor.typeNotFound("X"),
            ErrorDescriptor.methodNotFound("X#m"),
            ErrorDescriptor.fieldNotFound("X#f"),
            ErrorDescriptor.packageNotFound("p"),
            ErrorDescriptor.projectNotFound("p"),
            ErrorDescriptor.fileNotFound("/x"),
            ErrorDescriptor.invalidFqn("x"),
            ErrorDescriptor.invalidFqmn("x"),
            ErrorDescriptor.missingParameter("name"),
            ErrorDescriptor.ambiguousMatch("X#m",
                    List.of("X#m(int)", "X#m(String)")),
            ErrorDescriptor.wrongSubjectKind("@op", "type", "method"),
            ErrorDescriptor.ioError("boom"),
            ErrorDescriptor.jdtInternalError("oops",
                    new RuntimeException("c")),
        };
        for (var d : cases) {
            assertEquals("jdt/plugin",
                    inner(d).get("origin").getAsString(),
                    "origin must be jdt/plugin for " + d.thrown());
            assertNotNull(inner(d).get("context"),
                    "context object must always be present for "
                    + d.thrown());
        }
    }

    @Test
    void everyFactoryEmitsKebabKindAndPascalThrown() {
        // Discipline: kind is kebab-case for keyword interning,
        // thrown is PascalCase per-site error class name.
        ErrorDescriptor[] cases = {
            ErrorDescriptor.typeNotFound("X"),
            ErrorDescriptor.invalidFqn("x"),
            ErrorDescriptor.wrongSubjectKind("@op", "t", "m"),
            ErrorDescriptor.ioError("x"),
            ErrorDescriptor.jdtInternalError("x", null),
        };
        for (var d : cases) {
            String kind = d.kind();
            String thrown = d.thrown();
            assertTrue(kind.equals(kind.toLowerCase()),
                    "kind must be lowercase: " + kind);
            assertTrue(Character.isUpperCase(thrown.charAt(0)),
                    "thrown must be PascalCase: " + thrown);
            assertTrue(!thrown.contains("-") && !thrown.contains("_"),
                    "thrown must be PascalCase, no separators: "
                    + thrown);
        }
    }

    @Test
    void toJsonStringRoundTripsThroughGson() {
        var d = ErrorDescriptor.typeNotFound("com.example.X");
        var jsonString = d.toJsonString();
        assertTrue(jsonString.startsWith("{\"_error\":"));
        assertTrue(jsonString.contains("\"TypeNotFound\""));
        assertTrue(jsonString.contains("\"com.example.X\""));
    }
}
