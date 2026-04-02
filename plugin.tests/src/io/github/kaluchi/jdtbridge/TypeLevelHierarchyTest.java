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

import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for type-level jdt source output: hierarchy
 * (supertypes, subtypes), enclosing type, no outgoing refs.
 */
public class TypeLevelHierarchyTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    private JsonObject sourceJson(String fqn) throws Exception {
        IType type = JdtUtils.findType(fqn);
        assertNotNull(type, fqn + " not found");
        var refs = ReferenceCollector.collect(type);
        String json = SourceReport.toJson(
                fqn, type, "D:/t.java",
                type.getSource(), 1, 100, refs, null);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private JsonObject handleSource(String fqn) throws Exception {
        var handler = new SearchHandler();
        return JsonParser.parseString(
                handler.handleSource(
                        Map.of("class", fqn), ProjectScope.ALL).body())
                .getAsJsonObject();
    }

    private static boolean hasInArray(JsonArray arr, String fqn) {
        for (JsonElement e : arr) {
            if (e.getAsJsonObject().get("fqn").getAsString()
                    .equals(fqn)) return true;
        }
        return false;
    }

    @Nested
    class Supertypes {

        @Test
        void dogHasAnimalSupertype() throws Exception {
            JsonObject obj = sourceJson("test.model.Dog");
            JsonArray supers = obj.getAsJsonArray("supertypes");
            assertNotNull(supers, "Should have supertypes");
            assertTrue(hasInArray(supers, "test.model.Animal"),
                    "Dog should have Animal supertype");
        }

        @Test
        void supertypeHasKind() throws Exception {
            JsonObject obj = sourceJson("test.model.Dog");
            JsonArray supers = obj.getAsJsonArray("supertypes");
            for (JsonElement e : supers) {
                JsonObject s = e.getAsJsonObject();
                if ("test.model.Animal".equals(
                        s.get("fqn").getAsString())) {
                    assertEquals("interface",
                            s.get("kind").getAsString());
                    return;
                }
            }
            throw new AssertionError("Animal not found in supertypes");
        }

        @Test
        void abstractPetHasAnimalSupertype() throws Exception {
            JsonObject obj = sourceJson("test.edge.AbstractPet");
            JsonArray supers = obj.getAsJsonArray("supertypes");
            assertTrue(hasInArray(supers, "test.model.Animal"),
                    "AbstractPet implements Animal");
        }

        @Test
        void parrotHasAbstractPetSupertype() throws Exception {
            JsonObject obj = sourceJson("test.edge.Parrot");
            JsonArray supers = obj.getAsJsonArray("supertypes");
            assertTrue(hasInArray(supers, "test.edge.AbstractPet"),
                    "Parrot extends AbstractPet");
        }

        @Test
        void objectInSupertypes() throws Exception {
            JsonObject obj = sourceJson("test.model.Dog");
            JsonArray supers = obj.getAsJsonArray("supertypes");
            assertTrue(hasInArray(supers, "java.lang.Object"),
                    "Object should be in supertypes");
        }
    }

    @Nested
    class Subtypes {

        @Test
        void animalHasSubtypes() throws Exception {
            JsonObject obj = sourceJson("test.model.Animal");
            JsonArray subs = obj.getAsJsonArray("subtypes");
            assertNotNull(subs, "Should have subtypes");
            assertTrue(hasInArray(subs, "test.model.Dog"),
                    "Dog should be a subtype");
        }

        @Test
        void catIsAnimalSubtype() throws Exception {
            JsonObject obj = sourceJson("test.model.Animal");
            JsonArray subs = obj.getAsJsonArray("subtypes");
            assertTrue(hasInArray(subs, "test.model.Cat"),
                    "Cat should be a subtype");
        }

        @Test
        void abstractPetIsAnimalSubtype() throws Exception {
            JsonObject obj = sourceJson("test.model.Animal");
            JsonArray subs = obj.getAsJsonArray("subtypes");
            assertTrue(hasInArray(subs, "test.edge.AbstractPet"),
                    "AbstractPet should be a subtype");
        }

        @Test
        void abstractPetHasParrotSubtype() throws Exception {
            JsonObject obj = sourceJson("test.edge.AbstractPet");
            JsonArray subs = obj.getAsJsonArray("subtypes");
            assertTrue(hasInArray(subs, "test.edge.Parrot"),
                    "Parrot should be subtype of AbstractPet");
        }

        @Test
        void subtypeHasKind() throws Exception {
            JsonObject obj = sourceJson("test.model.Animal");
            JsonArray subs = obj.getAsJsonArray("subtypes");
            for (JsonElement e : subs) {
                JsonObject s = e.getAsJsonObject();
                if ("test.model.Dog".equals(
                        s.get("fqn").getAsString())) {
                    assertEquals("class",
                            s.get("kind").getAsString());
                    return;
                }
            }
            throw new AssertionError("Dog not found in subtypes");
        }

        @Test
        void leafClassHasNoSubtypes() throws Exception {
            JsonObject obj = sourceJson("test.model.Cat");
            JsonArray subs = obj.getAsJsonArray("subtypes");
            assertEquals(0, subs.size(),
                    "Cat should have empty subtypes");
        }
    }

    @Nested
    class NoRefsForTypeLevel {

        @Test
        void noRefsArrayForClass() throws Exception {
            JsonObject obj = sourceJson("test.model.Dog");
            assertFalse(obj.has("refs"),
                    "Type-level should not have refs");
        }

        @Test
        void noRefsArrayForInterface() throws Exception {
            JsonObject obj = sourceJson("test.model.Animal");
            assertFalse(obj.has("refs"),
                    "Interface type should not have refs");
        }

        @Test
        void noDirectionForTypeLevel() throws Exception {
            JsonObject obj = sourceJson("test.model.Dog");
            assertFalse(obj.toString().contains("\"direction\""),
                    "Type-level should not have direction");
        }
    }

    @Nested
    class SourceContent {

        @Test
        void classSourceIncludesFullBody() throws Exception {
            JsonObject obj = handleSource("test.model.Dog");
            String source = obj.get("source").getAsString();
            assertTrue(source.contains("public class Dog"),
                    "Should include class declaration");
            assertTrue(source.contains("public void bark()"),
                    "Should include bark method");
        }

        @Test
        void interfaceSourceIncludesBody() throws Exception {
            JsonObject obj = handleSource("test.model.Animal");
            String source = obj.get("source").getAsString();
            assertTrue(source.contains("public interface Animal"),
                    "Should include interface declaration");
            assertTrue(source.contains("String name()"),
                    "Should include name method");
        }

        @Test
        void enumSourceIncludesConstants() throws Exception {
            JsonObject obj = handleSource("test.edge.Color");
            String source = obj.get("source").getAsString();
            assertTrue(source.contains("RED"),
                    "Should include enum constants");
            assertTrue(source.contains("GREEN"),
                    "Should include GREEN");
        }
    }

    @Nested
    class EnclosingType {

        @Test
        void innerClassHasEnclosingType() throws Exception {
            JsonObject obj = sourceJson("test.edge.Outer.Inner");
            JsonObject enc = obj.getAsJsonObject("enclosingType");
            assertNotNull(enc, "Inner should have enclosingType");
            assertEquals("test.edge.Outer",
                    enc.get("fqn").getAsString());
            assertEquals("class",
                    enc.get("kind").getAsString());
        }

        @Test
        void staticNestedHasEnclosingType() throws Exception {
            JsonObject obj = sourceJson(
                    "test.edge.Outer.StaticNested");
            assertNotNull(obj.getAsJsonObject("enclosingType"),
                    "StaticNested should have enclosingType");
        }

        @Test
        void topLevelHasNoEnclosingType() throws Exception {
            JsonObject obj = sourceJson("test.model.Dog");
            assertFalse(obj.has("enclosingType"),
                    "Top-level should not have enclosingType");
        }
    }
}
