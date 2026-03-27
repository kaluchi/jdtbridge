package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for @Override target resolution in SourceReport.
 * Verifies that overrideTarget FQMN appears in JSON when
 * a method has @Override annotation.
 */
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

    private String sourceJson(String typeFqn, String methodName)
            throws Exception {
        IType type = JdtUtils.findType(typeFqn);
        assertNotNull(type, typeFqn + " not found");
        IMethod method = JdtUtils.findMethod(
                type, methodName, null);
        assertNotNull(method,
                methodName + " not found in " + typeFqn);
        var refs = ReferenceCollector.collect(method);
        return SourceReport.toJson(
                typeFqn + "#" + methodName,
                method, "D:/t.java",
                method.getSource(), 1, 10, refs, null);
    }

    @Nested
    class OverrideFromInterface {

        @Test
        void dogNameOverridesAnimalName() throws Exception {
            String json = sourceJson("test.model.Dog", "name");
            assertTrue(json.contains("\"overrideTarget\""),
                    "Dog#name should have overrideTarget: "
                    + json);
            assertTrue(json.contains("Animal#name"),
                    "overrideTarget should point to Animal: "
                    + json);
        }

        @Test
        void catNameOverridesAnimalName() throws Exception {
            String json = sourceJson("test.model.Cat", "name");
            assertTrue(json.contains("\"overrideTarget\""),
                    "Cat#name should have overrideTarget: "
                    + json);
            assertTrue(json.contains("Animal#name"),
                    "Should override Animal#name: " + json);
        }

        @Test
        void overrideTargetIsStructuredRef()
                throws Exception {
            String json = sourceJson("test.model.Dog", "name");
            // overrideTarget is {fqmn, kind, typeKind}
            assertTrue(json.contains("\"kind\":\"method\""),
                    "overrideTarget kind should be method: "
                    + json);
            assertTrue(json.contains(
                    "\"typeKind\":\"interface\""),
                    "declaring type should be interface: "
                    + json);
        }

        @Test
        void abstractPetNameOverridesAnimalName()
                throws Exception {
            String json = sourceJson(
                    "test.edge.AbstractPet", "name");
            assertTrue(json.contains("\"overrideTarget\""),
                    "AbstractPet#name should override: "
                    + json);
            assertTrue(json.contains("Animal#name"),
                    "Should override Animal#name: " + json);
        }
    }

    @Nested
    class OverrideFromAbstractClass {

        @Test
        void parrotSpeakOverridesAbstractPetSpeak()
                throws Exception {
            String json = sourceJson(
                    "test.edge.Parrot", "speak");
            assertTrue(json.contains("\"overrideTarget\""),
                    "Parrot#speak should override: " + json);
            assertTrue(json.contains("AbstractPet#speak"),
                    "Should override AbstractPet#speak: "
                    + json);
        }

        @Test
        void overrideTargetContainsClassTypeKind()
                throws Exception {
            String json = sourceJson(
                    "test.edge.Parrot", "speak");
            // AbstractPet is a class
            assertTrue(json.contains(
                    "\"typeKind\":\"class\""),
                    "overrideTarget typeKind should be class: "
                    + json);
        }
    }

    @Nested
    class NoOverride {

        @Test
        void nonOverrideMethodHasNoTarget() throws Exception {
            String json = sourceJson("test.model.Dog", "bark");
            assertFalse(json.contains("\"overrideTarget\""),
                    "bark() is not @Override: " + json);
        }

        @Test
        void staticMethodHasNoTarget() throws Exception {
            String json = sourceJson(
                    "test.service.EnrichedRefService",
                    "getSharedDog");
            assertFalse(json.contains("\"overrideTarget\""),
                    "static method has no override: " + json);
        }

        @Test
        void serviceProcessHasNoTarget() throws Exception {
            String json = sourceJson(
                    "test.service.AnimalService", "process");
            assertFalse(json.contains("\"overrideTarget\""),
                    "process() is not @Override: " + json);
        }
    }
}
