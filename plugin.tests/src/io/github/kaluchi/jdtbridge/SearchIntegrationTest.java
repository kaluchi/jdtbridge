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
        // Dog extends Object
        assertTrue(json.contains("java.lang.Object"),
                "Should have Object in supers: " + json);
        // Dog implements Animal
        assertTrue(json.contains("test.model.Animal"),
                "Should have Animal in interfaces: " + json);
        // Dog has no subtypes
        assertTrue(json.contains("\"subtypes\":[]"),
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
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog"));
        assertEquals("application/json", resp.contentType());
        assertTrue(resp.body().contains("public class Dog"),
                "Should contain class body: " + resp.body());
        assertTrue(resp.body().contains("public void bark()"),
                "Should contain bark method: " + resp.body());
        assertTrue(resp.body().contains("\"supertypes\""),
                "Should have supertypes: " + resp.body());
    }

    @Test
    public void sourceMethod() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog", "method", "bark"));
        assertEquals("application/json", resp.contentType());
        var parsed = Json.parse(resp.body());
        assertEquals("test.model.Dog#bark()",
                Json.getString(parsed, "fqmn"));
        assertNotNull(Json.getString(parsed, "source"));
        assertTrue(Json.getInt(parsed, "startLine", -1) > 0);
    }

    @Test
    public void sourceMethodRefsVerified() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.service.AnimalService",
                        "method", "process"));
        var parsed = Json.parse(resp.body());
        assertEquals("test.service.AnimalService#process(Animal)",
                Json.getString(parsed, "fqmn"));

        var refs = parseRefs(resp.body());
        var outgoing = refs.stream()
                .filter(r -> "outgoing".equals(
                        Json.getString(r, "direction")))
                .toList();
        var incoming = refs.stream()
                .filter(r -> "incoming".equals(
                        Json.getString(r, "direction")))
                .toList();

        // Every ref has direction
        for (var ref : refs) {
            assertNotNull(Json.getString(ref, "direction"),
                    "Every ref must have direction: " + ref);
        }

        // Outgoing: Animal#name() with interface typeKind
        var animalName = outgoing.stream()
                .filter(r -> "test.model.Animal#name()".equals(
                        Json.getString(r, "fqmn")))
                .findFirst().orElse(null);
        assertNotNull(animalName,
                "Should have Animal#name ref: " + outgoing);
        assertEquals("interface",
                Json.getString(animalName, "typeKind"));
        assertEquals("method",
                Json.getString(animalName, "kind"));

        // Implementations of Animal#name
        var impls = outgoing.stream()
                .filter(r -> r.get("implementationOf") != null)
                .toList();
        assertTrue(impls.size() >= 2,
                "Should have Dog+Cat impls: " + impls);
        var implFqmns = impls.stream()
                .map(r -> Json.getString(r, "fqmn"))
                .toList();
        assertTrue(implFqmns.stream()
                .anyMatch(f -> f.contains("Dog#name")),
                "Dog should be impl: " + implFqmns);
        assertTrue(implFqmns.stream()
                .anyMatch(f -> f.contains("Cat#name")),
                "Cat should be impl: " + implFqmns);
    }

    @Test
    public void sourceMethodIncomingCallers() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "bark"));
        var refs = parseRefs(resp.body());
        var incoming = refs.stream()
                .filter(r -> "incoming".equals(
                        Json.getString(r, "direction")))
                .toList();
        assertFalse(incoming.isEmpty(),
                "Should have incoming callers");
        // Caller is AnimalService#createDog
        var caller = incoming.stream()
                .filter(r -> Json.getString(r, "fqmn")
                        .contains("AnimalService#createDog"))
                .findFirst().orElse(null);
        assertNotNull(caller,
                "AnimalService#createDog should call bark: "
                + incoming);
        assertEquals("project",
                Json.getString(caller, "scope"));
    }

    @Test
    public void sourceMethodOverrideTarget() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "name"));
        var parsed = Json.parse(resp.body());
        // overrideTarget is a nested JSON object
        Object otRaw = parsed.get("overrideTarget");
        assertNotNull(otRaw,
                "Dog#name should have overrideTarget");
        var ot = Json.parse(otRaw.toString());
        assertEquals("method", Json.getString(ot, "kind"));
        assertTrue(Json.getString(ot, "fqmn")
                .contains("Animal#name"),
                "Should override Animal#name: " + ot);
    }

    @Test
    public void sourceTypeHierarchyParsed() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Animal"));
        var parsed = Json.parse(resp.body());
        assertEquals("test.model.Animal",
                Json.getString(parsed, "fqmn"));

        // supertypes/subtypes are raw JSON arrays
        Object subsRaw = parsed.get("subtypes");
        assertNotNull(subsRaw, "Should have subtypes");
        String subsStr = subsRaw.toString();
        assertTrue(subsStr.contains("test.model.Dog"),
                "Dog should be subtype: " + subsStr);
        assertTrue(subsStr.contains("test.model.Cat"),
                "Cat should be subtype: " + subsStr);

        // No refs array for type-level
        assertNull(parsed.get("refs"),
                "Type-level should not have refs");
    }

    @Test
    public void sourcePreservesLeadingIndent() throws Exception {
        // Dog.bark() has 4-space indent in TestFixture source.
        // IMember.getSource() strips it. Our handler must not.
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog", "method", "bark"));
        var parsed = Json.parse(resp.body());
        String source = Json.getString(parsed, "source");
        assertNotNull(source);
        assertFalse(source.startsWith("public"),
                "Should NOT start with 'public' (indent stripped)."
                + " Starts with: [" + source.substring(0,
                        Math.min(30, source.length())) + "]");
        assertTrue(source.contains("    public void bark()"),
                "Should have 4-space indent before 'public'");
    }

    @Test
    public void sourceNotFound() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "no.such.Type"));
        assertTrue(resp.body().contains("error"),
                "Should be error JSON: " + resp.body());
    }

    // ---- /projects ----

    // ---- Helpers ----

    /** Parse refs array from source JSON response. */
    private static List<Map<String, Object>> parseRefs(
            String json) {
        var parsed = Json.parse(json);
        Object refsRaw = parsed.get("refs");
        if (refsRaw == null) return List.of();
        String refsStr = refsRaw.toString().trim();
        if (!refsStr.startsWith("[")) return List.of();

        var result = new ArrayList<Map<String, Object>>();
        // Split array of objects by },{
        refsStr = refsStr.substring(1, refsStr.length() - 1);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < refsStr.length(); i++) {
            char c = refsStr.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0 && (c == '}' || i == refsStr.length() - 1)) {
                String elem = refsStr.substring(start,
                        i + 1).trim();
                if (elem.startsWith(",")) elem = elem.substring(1).trim();
                if (elem.startsWith("{")) {
                    result.add(Json.parse(elem));
                }
                start = i + 1;
            }
        }
        return result;
    }

    @Test
    public void projectsIncludesTestProject() throws Exception {
        String json = handler.handleProjects();
        assertTrue(json.contains(TestFixture.PROJECT_NAME),
                "Should include test project: " + json);
    }
}
