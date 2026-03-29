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
import org.junit.jupiter.api.Test;

/**
 * Integration tests for edge cases: overloaded methods, inner classes,
 * enums, annotations, abstract classes.
 */
public class EdgeCaseIntegrationTest {

    private static final SearchHandler search = new SearchHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- Overloaded methods ----

    @Test
    public void typeInfoShowsAllOverloads() throws Exception {
        String json = search.handleTypeInfo(
                Map.of("class", "test.edge.Calculator"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertEquals("class", obj.get("kind").getAsString());
        // Count add methods in the full JSON
        String full = obj.toString();
        int count = 0;
        int idx = 0;
        while ((idx = full.indexOf("\"name\":\"add\"", idx)) >= 0) {
            count++;
            idx++;
        }
        assertEquals(3, count, "Should have 3 add() overloads");
    }

    @Test
    public void sourceByMethodFindsAllOverloads() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Calculator", "method", "add"));
        // Without arity, all overloads should be returned
        String body = resp.body();
        assertTrue(body.contains("int add(int a, int b)"),
                "Should contain int overload: " + body);
        assertTrue(body.contains("double add(double a, double b)"),
                "Should contain double overload: " + body);
        assertTrue(body.contains("int add(int a, int b, int c)"),
                "Should contain 3-arg overload: " + body);
    }

    @Test
    public void sourceByMethodWithArity() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Calculator",
                        "method", "add", "arity", "3"));
        String body = resp.body();
        assertTrue(body.contains("int a, int b, int c"),
                "Should contain 3-arg overload: " + body);
        assertEquals("application/json", resp.contentType(),
                "Should be JSON");
    }

    @Test
    public void referencesWithArity() throws Exception {
        // add(int, int) has arity 2
        String json = search.handleReferences(
                Map.of("class", "test.edge.Calculator",
                        "method", "add", "arity", "2"));
        // No external callers in test project
        assertEquals("[]", json);
    }

    // ---- Inner classes ----

    @Test
    public void findInnerClass() throws Exception {
        String json = search.handleFind(Map.of("name", "Inner"));
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        boolean found = false;
        for (JsonElement e : arr) {
            String fqn = e.getAsJsonObject().get("fqn").getAsString();
            if (fqn.contains("Outer.Inner") || fqn.contains("Outer$Inner")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Should find Outer.Inner");
    }

    @Test
    public void findStaticNested() throws Exception {
        String json = search.handleFind(Map.of("name", "StaticNested"));
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertTrue(arr.size() > 0, "Should find StaticNested");
    }

    @Test
    public void typeInfoInnerClass() throws Exception {
        String json = search.handleTypeInfo(
                Map.of("class", "test.edge.Outer"));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("class", obj.get("kind").getAsString());
    }

    @Test
    public void sourceOuter() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Outer"));
        assertTrue(resp.body().contains("public class Inner"),
                "Should contain Inner: " + resp.body());
        assertTrue(resp.body().contains("public static class StaticNested"),
                "Should contain StaticNested: " + resp.body());
    }

    // ---- Enum ----

    @Test
    public void typeInfoEnum() throws Exception {
        String json = search.handleTypeInfo(
                Map.of("class", "test.edge.Color"));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("enum", obj.get("kind").getAsString());
    }

    @Test
    public void sourceEnum() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Color"));
        assertTrue(resp.body().contains("RED"),
                "Should contain RED: " + resp.body());
    }

    @Test
    public void findEnum() throws Exception {
        String json = search.handleFind(Map.of("name", "Color"));
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertNotNull(findByFqn(arr, "test.edge.Color"),
                "Should find Color");
    }

    // ---- Annotation ----

    @Test
    public void typeInfoAnnotation() throws Exception {
        String json = search.handleTypeInfo(
                Map.of("class", "test.edge.Marker"));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("annotation", obj.get("kind").getAsString());
    }

    @Test
    public void sourceAnnotation() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Marker"));
        assertTrue(resp.body().contains("@Retention"),
                "Should contain @Retention: " + resp.body());
        assertTrue(resp.body().contains("String value()"),
                "Should contain value(): " + resp.body());
    }

    // ---- Abstract class + deeper hierarchy ----

    @Test
    public void subtypesOfAbstract() throws Exception {
        String json = search.handleSubtypes(
                Map.of("class", "test.edge.AbstractPet"));
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertNotNull(findByFqn(arr, "test.edge.Parrot"),
                "Should find Parrot");
    }

    @Test
    public void hierarchyOfParrot() throws Exception {
        String json = search.handleHierarchy(
                Map.of("class", "test.edge.Parrot"));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonArray supers = obj.getAsJsonArray("supertypes");
        assertNotNull(findByFqn(supers, "test.edge.AbstractPet"),
                "Should have AbstractPet in supertypes");
        assertNotNull(findByFqn(supers, "test.model.Animal"),
                "Should have Animal in supertypes");
    }

    @Test
    public void deepSubtypesOfAnimal() throws Exception {
        String json = search.handleSubtypes(
                Map.of("class", "test.model.Animal"));
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertNotNull(findByFqn(arr, "test.model.Dog"),
                "Should find Dog");
        assertNotNull(findByFqn(arr, "test.model.Cat"),
                "Should find Cat");
        assertNotNull(findByFqn(arr, "test.edge.AbstractPet"),
                "Should find AbstractPet");
        assertNotNull(findByFqn(arr, "test.edge.Parrot"),
                "Should find Parrot");
    }

    @Test
    public void implementorsIncludesDeepHierarchy() throws Exception {
        String json = search.handleImplementors(
                Map.of("class", "test.model.Animal", "method", "name"));
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        assertNotNull(findByFqn(arr, "test.model.Dog"),
                "Should find Dog");
        assertNotNull(findByFqn(arr, "test.model.Cat"),
                "Should find Cat");
        assertNotNull(findByFqn(arr, "test.edge.AbstractPet"),
                "Should find AbstractPet");
    }

    // ---- Enriched source output ----

    @Test
    public void sourceOverloadsHaveEnrichedFormat()
            throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Calculator",
                        "method", "add"));
        JsonArray arr = JsonParser.parseString(resp.body())
                .getAsJsonArray();
        assertEquals(3, arr.size(), "Should have 3 overloads");
        boolean hasIntInt = false, hasDoubleDouble = false,
                hasIntIntInt = false;
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            assertNotNull(o.get("source"), "Should have source");
            assertTrue(o.has("refs"), "Should have refs");
            String fqmn = o.get("fqmn").getAsString();
            if (fqmn.equals("test.edge.Calculator#add(int, int)"))
                hasIntInt = true;
            if (fqmn.equals("test.edge.Calculator#add(double, double)"))
                hasDoubleDouble = true;
            if (fqmn.equals("test.edge.Calculator#add(int, int, int)"))
                hasIntIntInt = true;
        }
        assertTrue(hasIntInt, "Should have int overload");
        assertTrue(hasDoubleDouble, "Should have double overload");
        assertTrue(hasIntIntInt, "Should have 3-arg overload");
    }

    @Test
    public void sourceEnumHasHierarchy() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Color"));
        String body = resp.body();
        assertTrue(body.contains("\"supertypes\""),
                "Enum should have supertypes: " + body);
        assertTrue(body.contains("\"subtypes\""),
                "Enum should have subtypes: " + body);
        assertFalse(body.contains("\"refs\""),
                "Type-level enum should not have refs: "
                + body);
    }

    @Test
    public void sourceAnnotationHasHierarchy() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Marker"));
        String body = resp.body();
        assertTrue(body.contains("\"supertypes\""),
                "Annotation should have supertypes: " + body);
        assertFalse(body.contains("\"refs\""),
                "Type-level annotation should not have refs: "
                + body);
    }

    @Test
    public void sourceInnerTypeHasEnclosingType() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.edge.Outer.Inner"));
        String body = resp.body();
        assertTrue(body.contains("\"enclosingType\""),
                "Inner should have enclosingType: " + body);
        assertTrue(body.contains("test.edge.Outer"),
                "Enclosing should be Outer: " + body);
    }

    @Test
    public void sourceInheritedMethodFlagged() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.service.EnrichedRefService",
                        "method", "getParrotName"));
        String body = resp.body();
        assertTrue(body.contains("\"inherited\":true"),
                "Should flag inherited call: " + body);
        assertTrue(body.contains("\"inheritedFrom\""),
                "Should have inheritedFrom: " + body);
    }

    @Test
    public void sourceStaticMethodFlagged() throws Exception {
        HttpServer.Response resp = search.handleSource(
                Map.of("class", "test.service.EnrichedRefService",
                        "method", "getStaticValue"));
        String body = resp.body();
        assertTrue(body.contains("\"static\":true"),
                "Should flag static ref: " + body);
    }

    // ---- Project info with edge types ----

    @Test
    public void projectInfoIncludesEdgePackage() throws Exception {
        ProjectHandler handler = new ProjectHandler();
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonArray roots = obj.getAsJsonArray("sourceRoots");
        assertTrue(hasPackageNamed(roots, "test.edge"),
                "Should have test.edge");
        assertTrue(hasTypeNamed(roots, "Calculator"),
                "Should have Calculator");
        assertTrue(hasTypeWithKind(roots, "enum"),
                "Should have Color as enum");
        assertTrue(hasTypeWithKind(roots, "annotation"),
                "Should have Marker as annotation");
    }

    private static boolean hasPackageNamed(JsonArray roots,
            String name) {
        for (JsonElement root : roots) {
            for (JsonElement p : root.getAsJsonObject()
                    .getAsJsonArray("packages")) {
                if (name.equals(p.getAsJsonObject()
                        .get("name").getAsString()))
                    return true;
            }
        }
        return false;
    }

    private static boolean hasTypeNamed(JsonArray roots,
            String name) {
        for (JsonElement root : roots) {
            for (JsonElement p : root.getAsJsonObject()
                    .getAsJsonArray("packages")) {
                for (JsonElement t : p.getAsJsonObject()
                        .getAsJsonArray("types")) {
                    if (name.equals(t.getAsJsonObject()
                            .get("name").getAsString()))
                        return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTypeWithKind(JsonArray roots,
            String kind) {
        for (JsonElement root : roots) {
            for (JsonElement p : root.getAsJsonObject()
                    .getAsJsonArray("packages")) {
                for (JsonElement t : p.getAsJsonObject()
                        .getAsJsonArray("types")) {
                    JsonObject type = t.getAsJsonObject();
                    if (type.has("kind") && kind.equals(
                            type.get("kind").getAsString()))
                        return true;
                }
            }
        }
        return false;
    }

    private static JsonObject findByFqn(JsonArray arr, String fqn) {
        for (JsonElement e : arr) {
            var obj = e.getAsJsonObject();
            if (obj.has("fqn") && fqn.equals(
                    obj.get("fqn").getAsString())) {
                return obj;
            }
        }
        return null;
    }
}
