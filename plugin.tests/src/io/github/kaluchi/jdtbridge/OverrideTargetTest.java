package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class OverrideTargetTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    private JsonObject sourceJson(String typeFqn,
            String methodName) throws Exception {
        IType type = JdtUtils.findType(typeFqn);
        assertNotNull(type);
        IMethod method = JdtUtils.findMethod(
                type, methodName, null);
        assertNotNull(method);
        var refs = ReferenceCollector.collect(method);
        String json = SourceReport.toJson(
                typeFqn + "#" + methodName,
                method, "D:/t.java",
                method.getSource(), 1, 10, refs, null);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Nested
    class OverrideFromInterface {

        @Test
        void dogNameOverridesAnimal() throws Exception {
            var json = sourceJson("test.model.Dog", "name");
            var ot = json.getAsJsonObject("overrideTarget");
            assertNotNull(ot);
            assertEquals("test.model.Animal#name()",
                    ot.get("fqmn").getAsString());
        }

        @Test
        void catNameOverridesAnimal() throws Exception {
            var json = sourceJson("test.model.Cat", "name");
            var ot = json.getAsJsonObject("overrideTarget");
            assertNotNull(ot);
            assertEquals("test.model.Animal#name()",
                    ot.get("fqmn").getAsString());
        }

        @Test
        void overrideTargetKindIsMethod() throws Exception {
            var json = sourceJson("test.model.Dog", "name");
            var ot = json.getAsJsonObject("overrideTarget");
            assertEquals("method",
                    ot.get("kind").getAsString());
        }

        @Test
        void overrideTargetTypeKindIsInterface()
                throws Exception {
            var json = sourceJson("test.model.Dog", "name");
            var ot = json.getAsJsonObject("overrideTarget");
            assertEquals("interface",
                    ot.get("typeKind").getAsString());
        }

        @Test
        void abstractPetNameOverridesAnimal() throws Exception {
            var json = sourceJson(
                    "test.edge.AbstractPet", "name");
            var ot = json.getAsJsonObject("overrideTarget");
            assertNotNull(ot);
            assertEquals("test.model.Animal#name()",
                    ot.get("fqmn").getAsString());
            assertEquals("interface",
                    ot.get("typeKind").getAsString());
        }
    }

    @Nested
    class OverrideFromAbstractClass {

        @Test
        void parrotSpeakOverridesAbstractPet() throws Exception {
            var json = sourceJson("test.edge.Parrot", "speak");
            var ot = json.getAsJsonObject("overrideTarget");
            assertNotNull(ot);
            assertEquals("test.edge.AbstractPet#speak()",
                    ot.get("fqmn").getAsString());
        }

        @Test
        void parrotSpeakTargetTypeKindIsClass()
                throws Exception {
            var json = sourceJson("test.edge.Parrot", "speak");
            var ot = json.getAsJsonObject("overrideTarget");
            assertEquals("class",
                    ot.get("typeKind").getAsString());
        }
    }

    @Nested
    class NoOverride {

        @Test
        void barkHasNoTarget() throws Exception {
            var json = sourceJson("test.model.Dog", "bark");
            assertFalse(json.has("overrideTarget"));
        }

        @Test
        void staticMethodHasNoTarget() throws Exception {
            var json = sourceJson(
                    "test.service.EnrichedRefService",
                    "getSharedDog");
            assertFalse(json.has("overrideTarget"));
        }

        @Test
        void processHasNoTarget() throws Exception {
            var json = sourceJson(
                    "test.service.AnimalService", "process");
            assertFalse(json.has("overrideTarget"));
        }
    }

    @Nested
    class ParsedOverrideTarget {

        @Test
        void dogNameFqmnExact() throws Exception {
            var ot = sourceJson("test.model.Dog", "name")
                    .getAsJsonObject("overrideTarget");
            assertEquals("test.model.Animal#name()",
                    ot.get("fqmn").getAsString());
        }

        @Test
        void dogNameKindIsMethod() throws Exception {
            var ot = sourceJson("test.model.Dog", "name")
                    .getAsJsonObject("overrideTarget");
            assertEquals("method",
                    ot.get("kind").getAsString());
        }

        @Test
        void dogNameTypeKindIsInterface() throws Exception {
            var ot = sourceJson("test.model.Dog", "name")
                    .getAsJsonObject("overrideTarget");
            assertEquals("interface",
                    ot.get("typeKind").getAsString());
        }

        @Test
        void parrotSpeakFqmnExact() throws Exception {
            var ot = sourceJson("test.edge.Parrot", "speak")
                    .getAsJsonObject("overrideTarget");
            assertEquals("test.edge.AbstractPet#speak()",
                    ot.get("fqmn").getAsString());
        }

        @Test
        void parrotSpeakTypeKindIsClass() throws Exception {
            var ot = sourceJson("test.edge.Parrot", "speak")
                    .getAsJsonObject("overrideTarget");
            assertEquals("class",
                    ot.get("typeKind").getAsString());
        }

        @Test
        void catNameFqmnExact() throws Exception {
            var ot = sourceJson("test.model.Cat", "name")
                    .getAsJsonObject("overrideTarget");
            assertEquals("test.model.Animal#name()",
                    ot.get("fqmn").getAsString());
        }

        @Test
        void abstractPetNameFqmnExact() throws Exception {
            var ot = sourceJson(
                    "test.edge.AbstractPet", "name")
                    .getAsJsonObject("overrideTarget");
            assertEquals("test.model.Animal#name()",
                    ot.get("fqmn").getAsString());
        }

        @Test
        void abstractPetNameTypeKindIsInterface()
                throws Exception {
            var ot = sourceJson(
                    "test.edge.AbstractPet", "name")
                    .getAsJsonObject("overrideTarget");
            assertEquals("interface",
                    ot.get("typeKind").getAsString());
        }
    }
}
