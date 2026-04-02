package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class SearchHandlerSourceTest {

    private static final SearchHandler handler =
            new SearchHandler();

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    static JsonObject src(String cls, String method)
            throws Exception {
        var params = method != null
                ? Map.of("class", cls, "method", method)
                : Map.of("class", cls);
        return JsonParser.parseString(
                handler.handleSource(params, ProjectScope.ALL).body())
                .getAsJsonObject();
    }

    static JsonObject srcMethod(String cls, String method)
            throws Exception {
        return src(cls, method);
    }

    static JsonArray refsDir(JsonObject json, String dir) {
        var result = new JsonArray();
        if (!json.has("refs")) return result;
        for (JsonElement e : json.getAsJsonArray("refs")) {
            var ref = e.getAsJsonObject();
            if (ref.has("direction") && dir.equals(
                    ref.get("direction").getAsString())) {
                result.add(ref);
            }
        }
        return result;
    }

    static JsonObject findRef(JsonArray refs, String fqmnPart) {
        for (JsonElement e : refs) {
            var ref = e.getAsJsonObject();
            if (ref.has("fqmn") && ref.get("fqmn")
                    .getAsString().contains(fqmnPart)) {
                return ref;
            }
        }
        return null;
    }

    @Nested
    class ErrorHandling {

        @Test
        void missingClassParam() throws Exception {
            var json = JsonParser.parseString(
                    handler.handleSource(Map.of(), ProjectScope.ALL).body())
                    .getAsJsonObject();
            assertTrue(json.has("error"));
        }

        @Test
        void emptyClassParam() throws Exception {
            var json = JsonParser.parseString(
                    handler.handleSource(
                            Map.of("class", ""), ProjectScope.ALL).body())
                    .getAsJsonObject();
            assertTrue(json.has("error"));
        }

        @Test
        void typeNotFound() throws Exception {
            var json = JsonParser.parseString(
                    handler.handleSource(
                            Map.of("class", "no.such.Type"), ProjectScope.ALL)
                            .body())
                    .getAsJsonObject();
            assertTrue(json.get("error").getAsString()
                    .contains("not found"));
        }

        @Test
        void methodNotFound() throws Exception {
            var json = srcMethod("test.model.Dog",
                    "noSuchMethod");
            assertTrue(json.has("error"));
        }
    }

    @Nested
    class MethodSourceStructure {

        @Test
        void fqmnExact() throws Exception {
            var json = srcMethod("test.model.Dog", "bark");
            assertEquals("test.model.Dog#bark()",
                    json.get("fqmn").getAsString());
        }

        @Test
        void fileIsAbsolutePath() throws Exception {
            var json = srcMethod("test.model.Dog", "bark");
            String file = json.get("file").getAsString();
            assertTrue(file.endsWith("Dog.java"));
            assertTrue(file.contains("jdtbridge-test"));
        }

        @Test
        void startLinePositive() throws Exception {
            var json = srcMethod("test.model.Dog", "bark");
            assertTrue(json.get("startLine").getAsInt() > 0);
        }

        @Test
        void endLineAfterStartLine() throws Exception {
            var json = srcMethod("test.model.Dog", "bark");
            int start = json.get("startLine").getAsInt();
            int end = json.get("endLine").getAsInt();
            assertTrue(end >= start);
        }

        @Test
        void sourceContainsMethodBody() throws Exception {
            var json = srcMethod("test.model.Dog", "bark");
            assertTrue(json.get("source").getAsString()
                    .contains("bark"));
        }

        @Test
        void contentTypeIsJson() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark"), ProjectScope.ALL);
            assertEquals("application/json",
                    resp.contentType());
        }
    }

    @Nested
    class TypeSourceStructure {

        @Test
        void fqmnExact() throws Exception {
            var json = src("test.model.Dog", null);
            assertEquals("test.model.Dog",
                    json.get("fqmn").getAsString());
        }

        @Test
        void hasSupertypes() throws Exception {
            var json = src("test.model.Dog", null);
            assertTrue(json.has("supertypes"));
        }

        @Test
        void hasSubtypes() throws Exception {
            var json = src("test.model.Dog", null);
            assertTrue(json.has("subtypes"));
        }

        @Test
        void noRefsField() throws Exception {
            var json = src("test.model.Dog", null);
            assertFalse(json.has("refs"));
        }

        @Test
        void noOverrideTarget() throws Exception {
            var json = src("test.model.Dog", null);
            assertFalse(json.has("overrideTarget"));
        }

        @Test
        void sourceContainsFullClass() throws Exception {
            var json = src("test.model.Dog", null);
            String source = json.get("source").getAsString();
            assertTrue(source.contains("class Dog"));
            assertTrue(source.contains("bark"));
            assertTrue(source.contains("name"));
        }

        @Test
        void interfaceSourceContainsBody() throws Exception {
            var json = src("test.model.Animal", null);
            String source = json.get("source").getAsString();
            assertTrue(source.contains("interface Animal"));
            assertTrue(source.contains("name()"));
        }

        @Test
        void enumSourceContainsConstants() throws Exception {
            var json = src("test.edge.Color", null);
            String source = json.get("source").getAsString();
            assertTrue(source.contains("RED"));
            assertTrue(source.contains("GREEN"));
            assertTrue(source.contains("BLUE"));
        }
    }

    @Nested
    class OverloadedMethods {

        @Test
        void returnsArrayForOverloads() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add"), ProjectScope.ALL);
            var arr = JsonParser.parseString(resp.body())
                    .getAsJsonArray();
            assertEquals(3, arr.size());
        }

        @Test
        void eachOverloadHasFqmn() throws Exception {
            var resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add"), ProjectScope.ALL);
            var arr = JsonParser.parseString(resp.body())
                    .getAsJsonArray();
            var fqmns = new java.util.HashSet<String>();
            for (JsonElement e : arr) {
                fqmns.add(e.getAsJsonObject()
                        .get("fqmn").getAsString());
            }
            assertTrue(fqmns.contains(
                    "test.edge.Calculator#add(int, int)"));
            assertTrue(fqmns.contains(
                    "test.edge.Calculator#add(double, double)"));
            assertTrue(fqmns.contains(
                    "test.edge.Calculator#add(int, int, int)"));
        }

        @Test
        void singleOverloadWithArity() throws Exception {
            var json = JsonParser.parseString(
                    handler.handleSource(
                            Map.of("class",
                                    "test.edge.Calculator",
                                    "method", "add",
                                    "paramTypes", "int,int,int"), ProjectScope.ALL)
                            .body()).getAsJsonObject();
            assertEquals(
                    "test.edge.Calculator#add(int, int, int)",
                    json.get("fqmn").getAsString());
        }
    }

    @Nested
    class EnrichedOutgoingFields {

        @Test
        void everyOutgoingRefHasRequiredFields()
                throws Exception {
            var json = srcMethod(
                    "test.service.AnimalService", "process");
            for (JsonElement e : refsDir(json, "outgoing")) {
                var ref = e.getAsJsonObject();
                assertTrue(ref.has("fqmn"));
                assertTrue(ref.has("kind"));
                assertTrue(ref.has("scope"));
            }
        }

        @Test
        void interfaceRefHasCorrectTypeKind() throws Exception {
            var json = srcMethod(
                    "test.service.AnimalService", "process");
            var ref = findRef(refsDir(json, "outgoing"),
                    "test.model.Animal#name()");
            assertNotNull(ref);
            assertEquals("interface",
                    ref.get("typeKind").getAsString());
            assertEquals("method",
                    ref.get("kind").getAsString());
        }

        @Test
        void staticRefFlagged() throws Exception {
            var json = srcMethod(
                    "test.service.EnrichedRefService",
                    "getStaticValue");
            var ref = findRef(refsDir(json, "outgoing"),
                    "VALUE");
            assertNotNull(ref);
            assertTrue(ref.get("static").getAsBoolean());
        }

        @Test
        void inheritedRefFlagged() throws Exception {
            var json = srcMethod(
                    "test.service.EnrichedRefService",
                    "getParrotName");
            var ref = findRef(refsDir(json, "outgoing"),
                    "#name(");
            assertNotNull(ref);
            assertTrue(ref.get("inherited").getAsBoolean());
            assertEquals("test.edge.AbstractPet",
                    ref.get("inheritedFrom").getAsString());
        }
    }
}
