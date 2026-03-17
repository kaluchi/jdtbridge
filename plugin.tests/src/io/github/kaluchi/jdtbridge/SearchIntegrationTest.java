package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("text/plain", resp.contentType());
        assertTrue(resp.body().contains("public class Dog"),
                "Should contain class body: " + resp.body());
        assertTrue(resp.body().contains("public void bark()"),
                "Should contain bark method: " + resp.body());
    }

    @Test
    public void sourceMethod() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog", "method", "bark"));
        assertEquals("text/plain", resp.contentType());
        assertTrue(resp.body().contains("bark"),
                "Should contain bark: " + resp.body());
        assertTrue(resp.headers().containsKey("X-Start-Line"),
                "Should have line header");
    }

    @Test
    public void sourceNotFound() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "no.such.Type"));
        assertTrue(resp.body().contains("error"),
                "Should be error JSON: " + resp.body());
    }

    // ---- /projects ----

    @Test
    public void projectsIncludesTestProject() throws Exception {
        String json = handler.handleProjects();
        assertTrue(json.contains(TestFixture.PROJECT_NAME),
                "Should include test project: " + json);
    }
}
