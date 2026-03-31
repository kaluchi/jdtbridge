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
 * Integration tests for SearchHandler using a real JDT workspace.
 * Creates a test project with known classes, then verifies search results.
 */
public class SearchIntegrationTest {

    private static final SearchHandler handler = new SearchHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- /find ----

    @Test
    public void findByExactName() throws Exception {
        String json = handler.handleFind(Map.of("name", "Animal"));
        JsonArray arr = parseArray(json);
        assertNotNull(findByFqn(arr, "test.model.Animal"),
                "Should find Animal");
    }

    @Test
    public void findByPattern() throws Exception {
        String json = handler.handleFind(Map.of("name", "*Service"));
        JsonArray arr = parseArray(json);
        assertNotNull(findByFqn(arr, "test.service.AnimalService"),
                "Should find AnimalService");
    }

    @Test
    public void findSourceOnly() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "Dog", "source", ""));
        JsonArray arr = parseArray(json);
        assertNotNull(findByFqn(arr, "test.model.Dog"),
                "Should find source Dog");
    }

    @Test
    public void findMissingParam() throws Exception {
        String json = handler.handleFind(Map.of());
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("error"), "Should have error field");
    }

    @Test
    public void findNonExistent() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "NoSuchTypeXYZ"));
        assertEquals(0, parseArray(json).size());
    }

    @Test
    public void findByPackage() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "test.model"));
        JsonArray arr = parseArray(json);
        assertNotNull(findByFqn(arr, "test.model.Animal"),
                "Should find Animal in package");
        assertNotNull(findByFqn(arr, "test.model.Dog"),
                "Should find Dog in package");
    }

    @Test
    public void findByPackageTrailingDot() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "test.model."));
        assertNotNull(findByFqn(parseArray(json),
                "test.model.Animal"), "Should find Animal");
    }

    @Test
    public void findByPackageTrailingDotStar() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "test.model.*"));
        assertNotNull(findByFqn(parseArray(json),
                "test.model.Animal"), "Should find Animal");
    }

    @Test
    public void findByPackageNonExistent() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "no.such.package"));
        assertEquals(0, parseArray(json).size());
    }

    // ---- /subtypes ----

    @Test
    public void subtypesOfInterface() throws Exception {
        String json = handler.handleSubtypes(
                Map.of("class", "test.model.Animal"));
        JsonArray arr = parseArray(json);
        assertNotNull(findByFqn(arr, "test.model.Dog"),
                "Should find Dog");
        assertNotNull(findByFqn(arr, "test.model.Cat"),
                "Should find Cat");
    }

    @Test
    public void subtypesOfClass() throws Exception {
        String json = handler.handleSubtypes(
                Map.of("class", "test.model.Dog"));
        assertEquals(0, parseArray(json).size(),
                "Dog has no subtypes");
    }

    @Test
    public void subtypesNotFound() throws Exception {
        String json = handler.handleSubtypes(
                Map.of("class", "no.such.Type"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertNotNull(obj.get("error"));
    }

    @Test
    public void subtypesRejectsObject() throws Exception {
        String json = handler.handleSubtypes(
                Map.of("class", "java.lang.Object"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("error").getAsString()
                .contains("too broad"));
    }

    // ---- /hierarchy ----

    @Test
    public void hierarchyRejectsObject() throws Exception {
        String json = handler.handleHierarchy(
                Map.of("class", "java.lang.Object"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("error").getAsString()
                .contains("too broad"));
    }

    @Test
    public void hierarchyOfDog() throws Exception {
        String json = handler.handleHierarchy(
                Map.of("class", "test.model.Dog"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        JsonArray supertypes = obj.getAsJsonArray("supertypes");
        assertNotNull(supertypes);
        assertNotNull(findByFqn(supertypes, "test.model.Animal"),
                "Should have Animal in supertypes");
        JsonArray subtypes = obj.getAsJsonArray("subtypes");
        assertNotNull(subtypes);
        assertEquals(0, subtypes.size(),
                "Dog should have no subtypes");
    }

    @Test
    public void hierarchyOfAnimal() throws Exception {
        String json = handler.handleHierarchy(
                Map.of("class", "test.model.Animal"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        JsonArray subtypes = obj.getAsJsonArray("subtypes");
        assertNotNull(findByFqn(subtypes, "test.model.Dog"),
                "Should have Dog");
        assertNotNull(findByFqn(subtypes, "test.model.Cat"),
                "Should have Cat");
    }

    // ---- /references ----

    @Test
    public void referencesToType() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog"));
        JsonArray arr = parseArray(json);
        assertTrue(arr.size() > 0, "Should have references");
        assertTrue(hasRefIn(arr, "AnimalService"),
                "Should find ref in AnimalService");
    }

    @Test
    public void referencesToMethod() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog", "method", "bark"));
        JsonArray arr = parseArray(json);
        assertTrue(arr.size() > 0, "Should have references");
        assertTrue(hasRefIn(arr, "AnimalService"),
                "Should find bark() ref in AnimalService");
    }

    @Test
    public void referencesToField() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog", "field", "age"));
        assertEquals(0, parseArray(json).size(),
                "age is private, no external references");
    }

    @Test
    public void referencesMethodNotFound() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog", "method", "fly"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertNotNull(obj.get("error"));
    }

    // ---- /implementors ----

    @Test
    public void implementorsOfInterfaceMethod() throws Exception {
        String json = handler.handleImplementors(
                Map.of("class", "test.model.Animal", "method", "name"));
        JsonArray arr = parseArray(json);
        assertTrue(arr.size() >= 2,
                "Should find at least Dog + Cat");
        assertNotNull(findByFqn(arr, "test.model.Dog"),
                "Should find Dog.name");
        assertNotNull(findByFqn(arr, "test.model.Cat"),
                "Should find Cat.name");
    }

    // ---- /type-info ----

    @Test
    public void typeInfoClass() throws Exception {
        String json = handler.handleTypeInfo(
                Map.of("class", "test.model.Dog"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertEquals("class", obj.get("kind").getAsString());
        assertTrue(hasNamed(obj.getAsJsonArray("methods"), "name"),
                "Should have name method");
        assertTrue(hasNamed(obj.getAsJsonArray("methods"), "bark"),
                "Should have bark method");
        assertTrue(hasNamed(obj.getAsJsonArray("fields"), "age"),
                "Should have age field");
    }

    @Test
    public void typeInfoInterface() throws Exception {
        String json = handler.handleTypeInfo(
                Map.of("class", "test.model.Animal"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertEquals("interface", obj.get("kind").getAsString());
    }

    // ---- /source ----

    @Test
    public void sourceFullClass() throws Exception {
        var resp = handler.handleSource(
                Map.of("class", "test.model.Dog"));
        assertEquals("application/json", resp.contentType());
        var json = parse(resp);
        assertEquals("test.model.Dog",
                json.get("fqmn").getAsString());
        String source = json.get("source").getAsString();
        assertTrue(source.contains("public class Dog"));
        assertTrue(source.contains("public void bark()"));
        assertTrue(json.has("supertypes"));
    }

    @Test
    public void sourceMethod() throws Exception {
        var resp = handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "bark"));
        assertEquals("application/json", resp.contentType());
        var json = parse(resp);
        assertEquals("test.model.Dog#bark()",
                json.get("fqmn").getAsString());
        assertNotNull(json.get("source").getAsString());
        assertTrue(json.get("startLine").getAsInt() > 0);
    }

    @Test
    public void sourceMethodRefsVerified() throws Exception {
        var json = parse(handler.handleSource(
                Map.of("class", "test.service.AnimalService",
                        "method", "process")));
        assertEquals("test.service.AnimalService#process(Animal)",
                json.get("fqmn").getAsString());

        JsonArray allRefs = json.getAsJsonArray("refs");
        for (JsonElement e : allRefs) {
            assertNotNull(
                    e.getAsJsonObject().get("direction"),
                    "Every ref must have direction");
        }

        // Outgoing: Animal#name() with interface typeKind
        JsonObject animalName = findRef(allRefs,
                "test.model.Animal#name()");
        assertNotNull(animalName);
        assertEquals("interface",
                animalName.get("typeKind").getAsString());
        assertEquals("method",
                animalName.get("kind").getAsString());

        // Implementations of Animal#name
        JsonArray impls = filterRefs(allRefs,
                "implementationOf");
        assertTrue(impls.size() >= 2);
    }

    @Test
    public void sourceMethodIncomingCallers() throws Exception {
        var json = parse(handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "bark")));
        var incoming = refsDir(json, "incoming");
        assertTrue(incoming.size() > 0);
        JsonObject caller = findRef(incoming,
                "AnimalService#createDog");
        assertNotNull(caller);
        assertEquals("project",
                caller.get("scope").getAsString());
    }

    @Test
    public void sourceMethodOverrideTarget() throws Exception {
        var json = parse(handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "name")));
        var ot = json.getAsJsonObject("overrideTarget");
        assertNotNull(ot);
        assertEquals("method",
                ot.get("kind").getAsString());
        assertEquals("test.model.Animal#name()",
                ot.get("fqmn").getAsString());
    }

    @Test
    public void sourceTypeHierarchyParsed() throws Exception {
        var json = parse(handler.handleSource(
                Map.of("class", "test.model.Animal")));
        assertEquals("test.model.Animal",
                json.get("fqmn").getAsString());

        JsonArray subs = json.getAsJsonArray("subtypes");
        assertNotNull(subs);
        assertNotNull(findByFqn(subs, "test.model.Dog"));
        assertNotNull(findByFqn(subs, "test.model.Cat"));
        assertFalse(json.has("refs"));
    }

    @Test
    public void sourcePreservesLeadingIndent() throws Exception {
        var json = parse(handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "bark")));
        String source = json.get("source").getAsString();
        assertNotNull(source);
        assertFalse(source.startsWith("public"));
        assertTrue(source.contains("    public void bark()"));
    }

    @Test
    public void sourceNotFound() throws Exception {
        var json = parse(handler.handleSource(
                Map.of("class", "no.such.Type")));
        assertNotNull(json.get("error"));
    }

    // ---- /projects ----

    // ---- Helpers ----

    private static JsonArray parseArray(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static JsonObject parse(HttpServer.Response resp) {
        return JsonParser.parseString(resp.body())
                .getAsJsonObject();
    }

    private static JsonArray refsDir(JsonObject json,
            String direction) {
        var result = new JsonArray();
        if (!json.has("refs")) return result;
        for (JsonElement e : json.getAsJsonArray("refs")) {
            var ref = e.getAsJsonObject();
            if (ref.has("direction") && direction.equals(
                    ref.get("direction").getAsString())) {
                result.add(ref);
            }
        }
        return result;
    }

    private static JsonObject findRef(JsonArray refs,
            String fqmnPart) {
        for (JsonElement e : refs) {
            var ref = e.getAsJsonObject();
            if (ref.has("fqmn") && ref.get("fqmn")
                    .getAsString().contains(fqmnPart)) {
                return ref;
            }
        }
        return null;
    }

    private static JsonArray filterRefs(JsonArray refs,
            String hasField) {
        var result = new JsonArray();
        for (JsonElement e : refs) {
            if (e.getAsJsonObject().has(hasField)) {
                result.add(e);
            }
        }
        return result;
    }

    private static JsonObject findByFqn(JsonArray arr,
            String fqn) {
        for (JsonElement e : arr) {
            var obj = e.getAsJsonObject();
            if (obj.has("fqn") && fqn.equals(
                    obj.get("fqn").getAsString())) {
                return obj;
            }
        }
        return null;
    }

    private static boolean hasNamed(JsonArray arr,
            String name) {
        for (JsonElement e : arr) {
            if (name.equals(e.getAsJsonObject()
                    .get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRefIn(JsonArray arr,
            String enclosingPart) {
        for (JsonElement e : arr) {
            var obj = e.getAsJsonObject();
            if (obj.has("in") && obj.get("in").getAsString()
                    .contains(enclosingPart)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void projectsIncludesTestProject() throws Exception {
        String json = handler.handleProjects();
        JsonArray arr = parseArray(json);
        boolean found = false;
        for (JsonElement e : arr) {
            if (e.getAsString().equals(TestFixture.PROJECT_NAME)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Should include test project");
    }
}
