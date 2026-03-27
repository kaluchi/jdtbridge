package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for type-level jdt source output: hierarchy
 * (supertypes, subtypes), enclosing type, no outgoing refs.
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class TypeLevelHierarchyTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    private String sourceJson(String fqn) throws Exception {
        IType type = JdtUtils.findType(fqn);
        assertNotNull(type, fqn + " not found");
        var refs = ReferenceCollector.collect(type);
        return SourceReport.toJson(
                fqn, type, "D:/t.java",
                type.getSource(), 1, 100, refs, null);
    }

    private String handleSource(String fqn) throws Exception {
        var handler = new SearchHandler();
        return handler.handleSource(
                Map.of("class", fqn)).body();
    }

    @Nested
    class Supertypes {

        @Test
        void dogHasAnimalSupertype() throws Exception {
            String json = sourceJson("test.model.Dog");
            assertTrue(json.contains("\"supertypes\""),
                    "Should have supertypes: " + json);
            assertTrue(json.contains("test.model.Animal"),
                    "Dog should have Animal supertype: "
                    + json);
        }

        @Test
        void supertypeHasKind() throws Exception {
            String json = sourceJson("test.model.Dog");
            // Animal is an interface
            assertTrue(json.contains("\"kind\":\"interface\""),
                    "Animal should be interface: " + json);
        }

        @Test
        void abstractPetHasAnimalSupertype() throws Exception {
            String json = sourceJson("test.edge.AbstractPet");
            assertTrue(json.contains("test.model.Animal"),
                    "AbstractPet implements Animal: " + json);
        }

        @Test
        void parrotHasAbstractPetSupertype() throws Exception {
            String json = sourceJson("test.edge.Parrot");
            assertTrue(json.contains("test.edge.AbstractPet"),
                    "Parrot extends AbstractPet: " + json);
        }

        @Test
        void objectNotInSupertypes() throws Exception {
            String json = sourceJson("test.model.Dog");
            assertFalse(json.contains("java.lang.Object"),
                    "Object should be filtered: " + json);
        }
    }

    @Nested
    class Subtypes {

        @Test
        void animalHasSubtypes() throws Exception {
            String json = sourceJson("test.model.Animal");
            assertTrue(json.contains("\"subtypes\""),
                    "Should have subtypes: " + json);
            // Dog, Cat, AbstractPet all implement Animal
            assertTrue(json.contains("test.model.Dog"),
                    "Dog should be a subtype: " + json);
        }

        @Test
        void catIsAnimalSubtype() throws Exception {
            String json = sourceJson("test.model.Animal");
            assertTrue(json.contains("test.model.Cat"),
                    "Cat should be a subtype: " + json);
        }

        @Test
        void abstractPetIsAnimalSubtype() throws Exception {
            String json = sourceJson("test.model.Animal");
            assertTrue(json.contains("test.edge.AbstractPet"),
                    "AbstractPet should be a subtype: "
                    + json);
        }

        @Test
        void abstractPetHasParrotSubtype() throws Exception {
            String json = sourceJson("test.edge.AbstractPet");
            assertTrue(json.contains("test.edge.Parrot"),
                    "Parrot should be subtype of AbstractPet: "
                    + json);
        }

        @Test
        void subtypeHasKind() throws Exception {
            String json = sourceJson("test.model.Animal");
            // Dog is a class
            assertTrue(json.contains("\"kind\":\"class\""),
                    "Dog should be class kind: " + json);
        }

        @Test
        void leafClassHasNoSubtypes() throws Exception {
            String json = sourceJson("test.model.Cat");
            // Cat has no subclasses in fixture
            assertTrue(json.contains("\"subtypes\":[]"),
                    "Cat should have empty subtypes: "
                    + json);
        }
    }

    @Nested
    class NoRefsForTypeLevel {

        @Test
        void noRefsArrayForClass() throws Exception {
            String json = sourceJson("test.model.Dog");
            assertFalse(json.contains("\"refs\""),
                    "Type-level should not have refs: "
                    + json);
        }

        @Test
        void noRefsArrayForInterface() throws Exception {
            String json = sourceJson("test.model.Animal");
            assertFalse(json.contains("\"refs\""),
                    "Interface type should not have refs: "
                    + json);
        }

        @Test
        void noDirectionForTypeLevel() throws Exception {
            String json = sourceJson("test.model.Dog");
            assertFalse(json.contains("\"direction\""),
                    "Type-level should not have direction: "
                    + json);
        }
    }

    @Nested
    class SourceContent {

        @Test
        void classSourceIncludesFullBody() throws Exception {
            String json = handleSource("test.model.Dog");
            assertTrue(json.contains("public class Dog"),
                    "Should include class declaration: "
                    + json);
            assertTrue(json.contains("public void bark()"),
                    "Should include bark method: " + json);
        }

        @Test
        void interfaceSourceIncludesBody() throws Exception {
            String json = handleSource("test.model.Animal");
            assertTrue(json.contains("public interface Animal"),
                    "Should include interface declaration: "
                    + json);
            assertTrue(json.contains("String name()"),
                    "Should include name method: " + json);
        }

        @Test
        void enumSourceIncludesConstants() throws Exception {
            String json = handleSource("test.edge.Color");
            assertTrue(json.contains("RED"),
                    "Should include enum constants: " + json);
            assertTrue(json.contains("GREEN"),
                    "Should include GREEN: " + json);
        }
    }

    @Nested
    class EnclosingType {

        @Test
        void innerClassHasEnclosingType() throws Exception {
            String json = sourceJson("test.edge.Outer.Inner");
            assertTrue(json.contains("\"enclosingType\""),
                    "Inner should have enclosingType: "
                    + json);
            assertTrue(json.contains("test.edge.Outer"),
                    "Enclosing should be Outer: " + json);
            assertTrue(json.contains("\"kind\":\"class\""),
                    "Enclosing kind should be class: "
                    + json);
        }

        @Test
        void staticNestedHasEnclosingType() throws Exception {
            String json = sourceJson(
                    "test.edge.Outer.StaticNested");
            assertTrue(json.contains("\"enclosingType\""),
                    "StaticNested should have enclosingType: "
                    + json);
        }

        @Test
        void topLevelHasNoEnclosingType() throws Exception {
            String json = sourceJson("test.model.Dog");
            assertFalse(json.contains("\"enclosingType\""),
                    "Top-level should not have enclosingType: "
                    + json);
        }
    }
}
