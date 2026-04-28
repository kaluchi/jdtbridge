package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReferenceCollector} — AST-based reference
 * resolution. Uses TestFixture classes (test.model, test.service).
 */
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

            // No java.* type FQNs as keys
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
                    "All refs should be unique by FQN");
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
                        + ref.fqn());
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
                        + ref.fqn());
            }
        }

        @Test
        void noRefHasNullFqn() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            for (IMethod m : type.getMethods()) {
                var refs = ReferenceCollector.collect(m);
                for (var ref : refs.values()) {
                    assertNotNull(ref.fqn(),
                            "Ref fqn should not be null");
                    assertFalse(ref.fqn().isEmpty(),
                            "Ref fqn should not be empty");
                }
            }
        }
    }

    @Nested
    class FieldTypeResolution {

        @Test
        void typeVariableFieldCarriesBound() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.GenericService");
            IMethod method = JdtUtils.findMethod(
                    type, "get", null);
            assertNotNull(method);

            var refs = ReferenceCollector.collect(method);
            var itemRef = refs.get(
                    "test.service.GenericService#item");
            assertNotNull(itemRef,
                    "get() reads field 'item': "
                    + refs.keySet());
            assertEquals(ReferenceCollector.RefKind.FIELD,
                    itemRef.kind());
            assertTrue(itemRef.isTypeVariable(),
                    "item is T → isTypeVariable");
            assertNotNull(itemRef.typeBound(),
                    "T extends Animal → typeBound");
            assertTrue(itemRef.typeBound().contains("Animal"),
                    "bound should be Animal: "
                    + itemRef.typeBound());
        }

        @Test
        void constantFieldHasConstantKind() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getStaticValue", null);
            assertNotNull(method);

            var refs = ReferenceCollector.collect(method);
            var valRef = refs.get(
                    "test.edge.Outer.StaticNested#VALUE");
            assertNotNull(valRef,
                    "getStaticValue() reads VALUE: "
                    + refs.keySet());
            assertEquals(ReferenceCollector.RefKind.CONSTANT,
                    valRef.kind());
            assertTrue(valRef.isStatic());
        }
    }

    @Nested
    class InheritedMethodDetection {

        @Test
        void methodCalledOnSubtypeIsInherited() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getParrotName", "Parrot");
            assertNotNull(method);

            var refs = ReferenceCollector.collect(method);
            var nameRef = refs.values().stream()
                    .filter(r -> r.fqn().contains("#name("))
                    .findFirst().orElse(null);
            assertNotNull(nameRef,
                    "getParrotName() calls name(): "
                    + refs.keySet());
            assertEquals(ReferenceCollector.RefKind.METHOD,
                    nameRef.kind());
        }
    }

    @Nested
    class PureHelpers {

        @Test
        void stripGenericsRemovesEverythingFromAngle() {
            assertEquals("List",
                    ReferenceCollector.stripGenerics(
                            "List<String>"));
            assertEquals("java.util.Map",
                    ReferenceCollector.stripGenerics(
                            "java.util.Map<K,V>"));
        }

        @Test
        void stripGenericsPassesThroughWhenNoAngle() {
            assertEquals("plain.Class",
                    ReferenceCollector.stripGenerics(
                            "plain.Class"));
            assertEquals("",
                    ReferenceCollector.stripGenerics(""));
        }

        @Test
        void paramSigEmptyForNoArgs() throws Exception {
            IType type = JdtUtils.findType(
                    "test.model.Animal");
            IMethod method = JdtUtils.findMethod(
                    type, "name", null);
            assertNotNull(method);
            assertEquals("",
                    ReferenceCollector.paramSig(method));
        }

        @Test
        void paramSigPrimitivePair() throws Exception {
            IType type = JdtUtils.findType(
                    "test.edge.Calculator");
            IMethod method = JdtUtils.findMethod(
                    type, "add", "int,int");
            assertNotNull(method);
            assertEquals("int,int",
                    ReferenceCollector.paramSig(method));
        }
    }
}
