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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for SearchHandler using a real JDT workspace.
 * Creates a test project with known classes, then verifies search results.
 */
@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")
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
        assertTrue(json.contains("test.model.Animal"),
                "Should find Animal: " + json);
    }

    @Test
    public void findByPattern() throws Exception {
        String json = handler.handleFind(Map.of("name", "*Service"));
        assertTrue(json.contains("test.service.AnimalService"),
                "Should find AnimalService: " + json);
    }

    @Test
    public void findSourceOnly() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "Dog", "source", ""));
        assertTrue(json.contains("test.model.Dog"),
                "Should find source Dog: " + json);
        // Should not include binary JDK types
        assertFalse(json.contains("binary"),
                "Should not contain binary: " + json);
    }

    @Test
    public void findMissingParam() throws Exception {
        String json = handler.handleFind(Map.of());
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void findNonExistent() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "NoSuchTypeXYZ"));
        assertEquals("[]", json);
    }

    @Test
    public void findByPackage() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "test.model"));
        assertTrue(json.contains("test.model.Animal"),
                "Should find Animal in package: " + json);
        assertTrue(json.contains("test.model.Dog"),
                "Should find Dog in package: " + json);
    }

    @Test
    public void findByPackageTrailingDot() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "test.model."));
        assertTrue(json.contains("test.model.Animal"),
                "Should find Animal: " + json);
    }

    @Test
    public void findByPackageTrailingDotStar() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "test.model.*"));
        assertTrue(json.contains("test.model.Animal"),
                "Should find Animal: " + json);
    }

    @Test
    public void findByPackageNonExistent() throws Exception {
        String json = handler.handleFind(
                Map.of("name", "no.such.package"));
        assertEquals("[]", json);
    }

    // ---- /subtypes ----

    @Test
    public void subtypesOfInterface() throws Exception {
        String json = handler.handleSubtypes(
                Map.of("class", "test.model.Animal"));
        assertTrue(json.contains("test.model.Dog"),
                "Should find Dog: " + json);
        assertTrue(json.contains("test.model.Cat"),
                "Should find Cat: " + json);
    }

    @Test
    public void subtypesOfClass() throws Exception {
        String json = handler.handleSubtypes(
                Map.of("class", "test.model.Dog"));
        assertEquals("[]", json, "Dog has no subtypes");
    }

    @Test
    public void subtypesNotFound() throws Exception {
        String json = handler.handleSubtypes(
                Map.of("class", "no.such.Type"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    // ---- /hierarchy ----

    @Test
    public void hierarchyOfDog() throws Exception {
        String json = handler.handleHierarchy(
                Map.of("class", "test.model.Dog"));
        // Dog implements Animal (in supertypes)
        assertTrue(json.contains("test.model.Animal"),
                "Should have Animal in supertypes: " + json);
        // Dog has no subtypes
        assertFalse(
                json.contains("\"subtypes\":[{"),
                "Should have empty subtypes: " + json);
    }

    @Test
    public void hierarchyOfAnimal() throws Exception {
        String json = handler.handleHierarchy(
                Map.of("class", "test.model.Animal"));
        assertTrue(json.contains("test.model.Dog"),
                "Should have Dog in subtypes: " + json);
        assertTrue(json.contains("test.model.Cat"),
                "Should have Cat in subtypes: " + json);
    }

    // ---- /references ----

    @Test
    public void referencesToType() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog"));
        assertTrue(json.contains("AnimalService"),
                "Should find ref in AnimalService: " + json);
    }

    @Test
    public void referencesToMethod() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog", "method", "bark"));
        assertTrue(json.contains("AnimalService"),
                "Should find bark() ref: " + json);
    }

    @Test
    public void referencesToField() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog", "field", "age"));
        // age is private, no external references
        assertEquals("[]", json);
    }

    @Test
    public void referencesMethodNotFound() throws Exception {
        String json = handler.handleReferences(
                Map.of("class", "test.model.Dog", "method", "fly"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    // ---- /implementors ----

    @Test
    public void implementorsOfInterfaceMethod() throws Exception {
        String json = handler.handleImplementors(
                Map.of("class", "test.model.Animal", "method", "name"));
        assertTrue(json.contains("test.model.Dog"),
                "Should find Dog.name: " + json);
        assertTrue(json.contains("test.model.Cat"),
                "Should find Cat.name: " + json);
    }

    // ---- /type-info ----

    @Test
    public void typeInfoClass() throws Exception {
        String json = handler.handleTypeInfo(
                Map.of("class", "test.model.Dog"));
        assertTrue(json.contains("\"kind\":\"class\""),
                "Should be class: " + json);
        assertTrue(json.contains("\"name\":\"name\""),
                "Should have name method: " + json);
        assertTrue(json.contains("\"name\":\"bark\""),
                "Should have bark method: " + json);
        assertTrue(json.contains("\"name\":\"age\""),
                "Should have age field: " + json);
        assertTrue(json.contains("Animal"),
                "Should implement Animal: " + json);
    }

    @Test
    public void typeInfoInterface() throws Exception {
        String json = handler.handleTypeInfo(
                Map.of("class", "test.model.Animal"));
        assertTrue(json.contains("\"kind\":\"interface\""),
                "Should be interface: " + json);
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

    @Test
    public void projectsIncludesTestProject() throws Exception {
        String json = handler.handleProjects();
        assertTrue(json.contains(TestFixture.PROJECT_NAME),
                "Should include test project: " + json);
    }
}
