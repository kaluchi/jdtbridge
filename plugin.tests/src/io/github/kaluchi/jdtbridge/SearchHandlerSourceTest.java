package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for SearchHandler source endpoint — verifies JSON
 * structure, error handling, and enriched fields through
 * the full handler stack.
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class SearchHandlerSourceTest {

    private static final SearchHandler handler =
            new SearchHandler();

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- Parse helpers ----

    static List<Map<String, Object>> parseRefs(String json) {
        var parsed = Json.parse(json);
        Object refsRaw = parsed.get("refs");
        if (refsRaw == null) return List.of();
        String s = refsRaw.toString().trim();
        if (!s.startsWith("[")) return List.of();
        var result = new ArrayList<Map<String, Object>>();
        s = s.substring(1, s.length() - 1);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0 && c == '}') {
                String elem = s.substring(start, i + 1).trim();
                if (elem.startsWith(","))
                    elem = elem.substring(1).trim();
                if (elem.startsWith("{"))
                    result.add(Json.parse(elem));
                start = i + 1;
            }
        }
        return result;
    }

    // ---- Error handling ----

    @Nested
    class ErrorHandling {

        @Test
        void missingClassParam() throws Exception {
            var resp = handler.handleSource(Map.of());
            var parsed = Json.parse(resp.body());
            assertNotNull(Json.getString(parsed, "error"));
        }

        @Test
        void emptyClassParam() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", ""));
            var parsed = Json.parse(resp.body());
            assertNotNull(Json.getString(parsed, "error"));
        }

        @Test
        void typeNotFound() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "no.such.Type"));
            var parsed = Json.parse(resp.body());
            assertNotNull(Json.getString(parsed, "error"));
            assertTrue(Json.getString(parsed, "error")
                    .contains("not found"));
        }

        @Test
        void methodNotFound() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "noSuchMethod"));
            var parsed = Json.parse(resp.body());
            assertNotNull(Json.getString(parsed, "error"));
        }
    }

    // ---- Method source structure ----

    @Nested
    class MethodSourceStructure {

        @Test
        void fqmnExact() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark"));
            var parsed = Json.parse(resp.body());
            assertEquals("test.model.Dog#bark()",
                    Json.getString(parsed, "fqmn"));
        }

        @Test
        void fileIsAbsolutePath() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark"));
            var parsed = Json.parse(resp.body());
            String file = Json.getString(parsed, "file");
            assertNotNull(file);
            assertTrue(file.endsWith("Dog.java"),
                    "File should end with Dog.java: " + file);
            assertTrue(file.contains("jdtbridge-test"),
                    "File should be in test project: " + file);
        }

        @Test
        void startLinePositive() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark"));
            var parsed = Json.parse(resp.body());
            assertTrue(
                    Json.getInt(parsed, "startLine", -1) > 0);
        }

        @Test
        void endLineAfterStartLine() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark"));
            var parsed = Json.parse(resp.body());
            int start = Json.getInt(parsed, "startLine", -1);
            int end = Json.getInt(parsed, "endLine", -1);
            assertTrue(end >= start,
                    "endLine >= startLine: " + start + "-" + end);
        }

        @Test
        void sourceContainsMethodBody() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark"));
            var parsed = Json.parse(resp.body());
            String source = Json.getString(parsed, "source");
            assertNotNull(source);
            assertTrue(source.contains("bark"),
                    "Source should contain bark");
        }

        @Test
        void contentTypeIsJson() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark"));
            assertEquals("application/json",
                    resp.contentType());
        }
    }

    // ---- Type source structure ----

    @Nested
    class TypeSourceStructure {

        @Test
        void fqmnExact() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog"));
            var parsed = Json.parse(resp.body());
            assertEquals("test.model.Dog",
                    Json.getString(parsed, "fqmn"));
        }

        @Test
        void hasSupertypes() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog"));
            var parsed = Json.parse(resp.body());
            assertNotNull(parsed.get("supertypes"));
        }

        @Test
        void hasSubtypes() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog"));
            var parsed = Json.parse(resp.body());
            assertNotNull(parsed.get("subtypes"));
        }

        @Test
        void noRefsField() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog"));
            var parsed = Json.parse(resp.body());
            assertNull(parsed.get("refs"));
        }

        @Test
        void noOverrideTarget() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog"));
            var parsed = Json.parse(resp.body());
            assertNull(parsed.get("overrideTarget"));
        }

        @Test
        void sourceContainsFullClass() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog"));
            var parsed = Json.parse(resp.body());
            String source = Json.getString(parsed, "source");
            assertTrue(source.contains("class Dog"),
                    "Should have class declaration");
            assertTrue(source.contains("bark"),
                    "Should have bark method");
            assertTrue(source.contains("name"),
                    "Should have name method");
        }

        @Test
        void interfaceSourceContainsBody() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Animal"));
            var parsed = Json.parse(resp.body());
            String source = Json.getString(parsed, "source");
            assertTrue(source.contains("interface Animal"));
            assertTrue(source.contains("name()"));
        }

        @Test
        void enumSourceContainsConstants() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.edge.Color"));
            var parsed = Json.parse(resp.body());
            String source = Json.getString(parsed, "source");
            assertTrue(source.contains("RED"));
            assertTrue(source.contains("GREEN"));
            assertTrue(source.contains("BLUE"));
        }
    }

    // ---- Overloaded methods ----

    @Nested
    class OverloadedMethods {

        @Test
        void returnsArrayForOverloads() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add"));
            String body = resp.body();
            assertTrue(body.startsWith("["),
                    "Overloads should be JSON array");
        }

        @Test
        void eachOverloadHasFqmn() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add"));
            String body = resp.body();
            assertTrue(body.contains(
                    "\"fqmn\":\"test.edge.Calculator#add(int, int)\""));
            assertTrue(body.contains(
                    "\"fqmn\":\"test.edge.Calculator#add(double, double)\""));
            assertTrue(body.contains(
                    "\"fqmn\":\"test.edge.Calculator#add(int, int, int)\""));
        }

        @Test
        void eachOverloadHasSource() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add"));
            String body = resp.body();
            assertTrue(body.contains("return a + b"));
            assertTrue(body.contains("return a + b + c"));
        }

        @Test
        void singleOverloadWithArity() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add",
                            "paramTypes", "int,int,int"));
            var parsed = Json.parse(resp.body());
            // Single match — not an array
            assertEquals(
                    "test.edge.Calculator#add(int, int, int)",
                    Json.getString(parsed, "fqmn"));
        }
    }

    // ---- Enriched outgoing ref fields ----

    @Nested
    class EnrichedOutgoingFields {

        @Test
        void everyOutgoingRefHasKindAndDirection()
                throws Exception {
            String json = handler.handleSource(
                    Map.of("class",
                            "test.service.AnimalService",
                            "method", "process")).body();
            for (var ref : parseRefs(json)) {
                String dir = Json.getString(ref, "direction");
                if (!"outgoing".equals(dir)) continue;
                assertNotNull(Json.getString(ref, "fqmn"));
                assertNotNull(Json.getString(ref, "kind"));
                assertNotNull(
                        Json.getString(ref, "scope"));
            }
        }

        @Test
        void interfaceRefHasCorrectTypeKind() throws Exception {
            String json = handler.handleSource(
                    Map.of("class",
                            "test.service.AnimalService",
                            "method", "process")).body();
            var refs = parseRefs(json);
            var animalName = refs.stream()
                    .filter(r -> {
                        String f = Json.getString(r, "fqmn");
                        return f != null
                                && f.equals(
                                "test.model.Animal#name()");
                    })
                    .findFirst().orElse(null);
            assertNotNull(animalName);
            assertEquals("interface",
                    Json.getString(animalName, "typeKind"));
            assertEquals("method",
                    Json.getString(animalName, "kind"));
            assertEquals("outgoing",
                    Json.getString(animalName, "direction"));
        }

        @Test
        void staticRefFlagged() throws Exception {
            String json = handler.handleSource(
                    Map.of("class",
                            "test.service.EnrichedRefService",
                            "method",
                            "getStaticValue")).body();
            var refs = parseRefs(json);
            var valueRef = refs.stream()
                    .filter(r -> {
                        String f = Json.getString(r, "fqmn");
                        return f != null
                                && f.contains("VALUE");
                    })
                    .findFirst().orElse(null);
            assertNotNull(valueRef, "VALUE ref: " + refs);
            assertEquals(Boolean.TRUE,
                    valueRef.get("static"));
        }

        @Test
        void inheritedRefFlagged() throws Exception {
            String json = handler.handleSource(
                    Map.of("class",
                            "test.service.EnrichedRefService",
                            "method",
                            "getParrotName")).body();
            var refs = parseRefs(json);
            var nameRef = refs.stream()
                    .filter(r -> {
                        String f = Json.getString(r, "fqmn");
                        return f != null
                                && f.contains("#name(");
                    })
                    .findFirst().orElse(null);
            assertNotNull(nameRef, "name ref: " + refs);
            assertEquals(Boolean.TRUE,
                    nameRef.get("inherited"));
            assertEquals("test.edge.AbstractPet",
                    Json.getString(nameRef, "inheritedFrom"));
        }
    }
}
