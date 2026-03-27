package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for {@link ReferenceCollector} — AST-based reference
 * resolution. Uses TestFixture classes (test.model, test.service).
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class ReferenceCollectorTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception { TestFixture.destroy(); }

    @Nested
    class CollectFromMethod {

        @Test
        void processMethodFindsAnimalName() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            assertNotNull(method);

            var refs = ReferenceCollector.collect(method);
            assertFalse(refs.isEmpty(),
                    "Should find references");
            // process(Animal) calls animal.name()
            assertTrue(refs.containsKey(
                    "test.model.Animal#name()"),
                    "Should find Animal#name(): "
                    + refs.keySet());
        }

        @Test
        void createDogFindsDogAndBark() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "createDog", null);
            assertNotNull(method);

            var refs = ReferenceCollector.collect(method);
            assertTrue(refs.containsKey("test.model.Dog#bark()"),
                    "Should find Dog#bark: " + refs.keySet());
            assertTrue(refs.containsKey("test.model.Dog"),
                    "Should find Dog type: " + refs.keySet());
        }

        @Test
        void skipsJavaLangTypes() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            var allRefs = new java.util.LinkedHashMap<
                    String, ReferenceCollector.Ref>();
            for (IMethod m : type.getMethods()) {
                allRefs.putAll(ReferenceCollector.collect(m));
            }

            // No java.* type FQMNs as keys
            for (String key : allRefs.keySet()) {
                assertFalse(key.startsWith("java."),
                        "Should skip java.* types: " + key);
            }
        }

        @Test
        void skipsJavaLangMethods() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            var allRefs = new java.util.LinkedHashMap<
                    String, ReferenceCollector.Ref>();
            for (IMethod m : type.getMethods()) {
                allRefs.putAll(ReferenceCollector.collect(m));
            }

            // System.out.println is java.* — should be skipped
            assertFalse(allRefs.containsKey(
                    "java.io.PrintStream#println(String)"),
                    "Should skip java.io refs");
        }

        @Test
        void deduplicatesReferences() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            var refs = new java.util.LinkedHashMap<
                    String, ReferenceCollector.Ref>();
            for (IMethod m : type.getMethods()) {
                refs.putAll(ReferenceCollector.collect(m));
            }
            assertEquals(refs.size(),
                    refs.keySet().stream().distinct().count(),
                    "All refs should be unique by FQMN");
        }
    }

    @Nested
    class CollectFromType {

        @Test
        void dogReferencesAnimalExactly() throws Exception {
            IType type = JdtUtils.findType("test.model.Dog");
            assertNotNull(type);

            var refs = ReferenceCollector.collect(type);
            assertTrue(refs.containsKey("test.model.Animal"),
                    "Dog should reference Animal: "
                    + refs.keySet());
            var animalRef = refs.get("test.model.Animal");
            assertEquals(ReferenceCollector.RefKind.TYPE,
                    animalRef.kind());
            assertEquals("interface",
                    animalRef.declaringTypeKind());
        }

        @Test
        void dogDoesNotReferenceSelf() throws Exception {
            IType type = JdtUtils.findType("test.model.Dog");
            var refs = ReferenceCollector.collect(type);
            assertFalse(refs.containsKey("test.model.Dog"),
                    "Dog should not reference itself: "
                    + refs.keySet());
        }

        @Test
        void interfaceHasNoSelfRef() throws Exception {
            IType type = JdtUtils.findType(
                    "test.model.Animal");
            var refs = ReferenceCollector.collect(type);
            assertFalse(refs.containsKey("test.model.Animal"),
                    "Animal should not reference itself");
        }
    }

    @Nested
    class RefMetadata {

        @Test
        void methodRefHasCorrectKind() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            var ref = refs.get("test.model.Animal#name()");
            assertNotNull(ref);
            assertEquals(ReferenceCollector.RefKind.METHOD,
                    ref.kind());
        }

        @Test
        void typeRefHasCorrectKind() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "createDog", null);
            var refs = ReferenceCollector.collect(method);
            var ref = refs.get("test.model.Dog");
            assertNotNull(ref, "Dog type ref: " + refs.keySet());
            assertEquals(ReferenceCollector.RefKind.TYPE,
                    ref.kind());
        }

        @Test
        void everyRefHasElement() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            for (var ref : refs.values()) {
                assertNotNull(ref.element(),
                        "Ref should have element: "
                        + ref.fqmn());
            }
        }

        @Test
        void everyRefHasDeclaringTypeKind() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            for (var ref : refs.values()) {
                assertNotNull(ref.declaringTypeKind(),
                        "Ref should have declaringTypeKind: "
                        + ref.fqmn());
            }
        }

        @Test
        void noRefHasNullFqmn() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            for (IMethod m : type.getMethods()) {
                var refs = ReferenceCollector.collect(m);
                for (var ref : refs.values()) {
                    assertNotNull(ref.fqmn(),
                            "Ref fqmn should not be null");
                    assertFalse(ref.fqmn().isEmpty(),
                            "Ref fqmn should not be empty");
                }
            }
        }
    }
}
