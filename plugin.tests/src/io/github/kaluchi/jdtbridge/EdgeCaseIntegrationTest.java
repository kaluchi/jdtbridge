package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for edge cases: overloaded methods, inner classes,
 * enums, annotations, abstract classes.
 */
@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")
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
        // Should have 3 add methods
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"name\":\"add\"", idx)) >= 0) {
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
        assertTrue(json.contains("test.edge.Outer.Inner")
                        || json.contains("Outer$Inner"),
                "Should find Outer.Inner: " + json);
    }

    @Test
    public void findStaticNested() throws Exception {
        String json = search.handleFind(Map.of("name", "StaticNested"));
        assertTrue(json.contains("StaticNested"),
                "Should find StaticNested: " + json);
    }

    @Test
    public void typeInfoInnerClass() throws Exception {
        // Inner classes are found by $ separator in JDT
        String json = search.handleTypeInfo(
                Map.of("class", "test.edge.Outer"));
        assertTrue(json.contains("\"kind\":\"class\""),
                "Should be a class: " + json);
        assertTrue(json.contains("\"name\":\"name\""),
                "Should have name field: " + json);
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
        assertTrue(json.contains("\"kind\":\"enum\""),
                "Should be enum: " + json);
        assertTrue(json.contains("\"name\":\"lower\""),
                "Should have lower method: " + json);
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
        assertTrue(json.contains("test.edge.Color"),
                "Should find Color: " + json);
    }

    // ---- Annotation ----

    @Test
    public void typeInfoAnnotation() throws Exception {
        String json = search.handleTypeInfo(
                Map.of("class", "test.edge.Marker"));
        assertTrue(json.contains("\"kind\":\"annotation\""),
                "Should be annotation: " + json);
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
        assertTrue(json.contains("test.edge.Parrot"),
                "Should find Parrot: " + json);
    }

    @Test
    public void hierarchyOfParrot() throws Exception {
        String json = search.handleHierarchy(
                Map.of("class", "test.edge.Parrot"));
        // Parrot -> AbstractPet -> Object
        assertTrue(json.contains("test.edge.AbstractPet"),
                "Should have AbstractPet in supers: " + json);
        assertTrue(json.contains("java.lang.Object"),
                "Should have Object in supers: " + json);
        // Parrot implements Animal (through AbstractPet)
        assertTrue(json.contains("test.model.Animal"),
                "Should have Animal in interfaces: " + json);
    }

    @Test
    public void deepSubtypesOfAnimal() throws Exception {
        // Animal -> Dog, Cat, AbstractPet, Parrot
        String json = search.handleSubtypes(
                Map.of("class", "test.model.Animal"));
        assertTrue(json.contains("test.model.Dog"),
                "Should find Dog: " + json);
        assertTrue(json.contains("test.model.Cat"),
                "Should find Cat: " + json);
        assertTrue(json.contains("test.edge.AbstractPet"),
                "Should find AbstractPet: " + json);
        assertTrue(json.contains("test.edge.Parrot"),
                "Should find Parrot: " + json);
    }

    @Test
    public void implementorsIncludesDeepHierarchy() throws Exception {
        // name() is declared in Animal, implemented by Dog, Cat,
        // AbstractPet, and Parrot inherits it
        String json = search.handleImplementors(
                Map.of("class", "test.model.Animal", "method", "name"));
        assertTrue(json.contains("test.model.Dog"),
                "Should find Dog: " + json);
        assertTrue(json.contains("test.model.Cat"),
                "Should find Cat: " + json);
        assertTrue(json.contains("test.edge.AbstractPet"),
                "Should find AbstractPet: " + json);
    }

    // ---- Project info with edge types ----

    @Test
    public void projectInfoIncludesEdgePackage() throws Exception {
        ProjectHandler handler = new ProjectHandler();
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        assertTrue(json.contains("test.edge"),
                "Should have test.edge: " + json);
        assertTrue(json.contains("Calculator"),
                "Should have Calculator: " + json);
        assertTrue(json.contains("\"kind\":\"enum\""),
                "Should have Color as enum: " + json);
        assertTrue(json.contains("\"kind\":\"annotation\""),
                "Should have Marker as annotation: " + json);
    }
}
