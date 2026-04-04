package io.github.kaluchi.jdtbridge;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for implementation resolution — interface method calls
 * should have implementationOf refs added by
 * {@link ReferenceCollector#resolveImplementations}.
 */
public class ImplementationResolutionTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    static Map<String, ReferenceCollector.Ref>
            collectWithImpls(String typeFqn, String methodName)
            throws Exception {
        IType type = JdtUtils.findType(typeFqn);
        assertNotNull(type);
        IMethod method = JdtUtils.findMethod(
                type, methodName, null);
        assertNotNull(method, methodName + " not found in "
                + typeFqn);
        var refs = ReferenceCollector.collect(method);
        ReferenceCollector.resolveImplementations(refs);
        return refs;
    }

    static ReferenceCollector.Ref find(
            Map<String, ReferenceCollector.Ref> refs,
            String fqmnPart) {
        return refs.values().stream()
                .filter(r -> r.fqmn().contains(fqmnPart))
                .findFirst().orElse(null);
    }

    static long countImpls(
            Map<String, ReferenceCollector.Ref> refs) {
        return refs.values().stream()
                .filter(r -> r.implementationOf() != null)
                .count();
    }

    @Nested
    class InterfaceMethodImpls {

        @Test
        void interfaceCallGetsImplementations() throws Exception {
            // AnimalService.process(Animal) calls animal.name()
            // Animal is an interface — Dog and Cat implement it
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            var animalName = find(refs, "Animal#name");
            assertNotNull(animalName,
                    "Should find Animal#name: " + refs.keySet());
            assertEquals("interface",
                    animalName.declaringTypeKind());

            // Should have implementations
            long implCount = refs.values().stream()
                    .filter(r -> r.implementationOf() != null
                            && r.implementationOf()
                                    .contains("Animal#name"))
                    .count();
            assertTrue(implCount >= 2,
                    "Should have at least Dog + Cat impls: "
                    + refs.keySet());
        }

        @Test
        void dogImplementsAnimalName() throws Exception {
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            var dogImpl = find(refs, "Dog#name");
            assertNotNull(dogImpl,
                    "Dog#name should be an implementation: "
                    + refs.keySet());
            assertNotNull(dogImpl.implementationOf());
            assertTrue(dogImpl.implementationOf()
                    .contains("Animal#name"));
        }

        @Test
        void catImplementsAnimalName() throws Exception {
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            var catImpl = find(refs, "Cat#name");
            assertNotNull(catImpl,
                    "Cat#name should be an implementation: "
                    + refs.keySet());
            assertNotNull(catImpl.implementationOf());
        }

        @Test
        void implRefHasClassTypeKind() throws Exception {
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            var dogImpl = find(refs, "Dog#name");
            assertNotNull(dogImpl);
            assertEquals("class", dogImpl.declaringTypeKind());
        }

        @Test
        void implRefIsMethodKind() throws Exception {
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            var dogImpl = find(refs, "Dog#name");
            assertNotNull(dogImpl);
            assertEquals(ReferenceCollector.RefKind.METHOD,
                    dogImpl.kind());
        }

        @Test
        void abstractPetImplementsAnimalName() throws Exception {
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            // AbstractPet also implements Animal#name()
            var absPet = find(refs, "AbstractPet#name");
            assertNotNull(absPet,
                    "AbstractPet#name should be impl: "
                    + refs.keySet());
            assertNotNull(absPet.implementationOf());
        }
    }

    @Nested
    class ClassMethodNoImpls {

        @Test
        void concreteClassMethodHasNoImpls() throws Exception {
            // AnimalService.createDog() calls Dog.bark()
            // Dog is a class, bark() not an interface method
            var refs = collectWithImpls(
                    "test.service.AnimalService", "createDog");
            var bark = find(refs, "Dog#bark");
            assertNotNull(bark);
            assertNull(bark.implementationOf(),
                    "Dog#bark is not an implementation");

            // No impl refs pointing to Dog#bark
            long implCount = refs.values().stream()
                    .filter(r -> r.implementationOf() != null
                            && r.implementationOf()
                                    .contains("bark"))
                    .count();
            assertEquals(0, implCount,
                    "No impls for concrete class method");
        }

        @Test
        void noImplsForStaticMethod() throws Exception {
            var refs = collectWithImpls(
                    "test.service.EnrichedRefService",
                    "getStaticValue");
            long implCount = countImpls(refs);
            // VALUE is a constant, not an interface method
            assertEquals(0, implCount,
                    "Static constant access has no impls: "
                    + refs.keySet());
        }
    }

    @Nested
    class JsonOutput {

        @Test
        void implementationOfInJson() throws Exception {
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            String json = SourceReport.toJson(
                    "test.service.AnimalService#process",
                    method, "D:/t.java",
                    method.getSource(), 1, 10, refs, null);
            assertTrue(json.contains("\"implementationOf\""),
                    "JSON should have implementationOf: "
                    + json);
        }

        @Test
        void implementationOfPointsToInterfaceMethod()
                throws Exception {
            var refs = collectWithImpls(
                    "test.service.AnimalService", "process");
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            String json = SourceReport.toJson(
                    "test.service.AnimalService#process",
                    method, "D:/t.java",
                    method.getSource(), 1, 10, refs, null);
            assertTrue(json.contains(
                    "\"implementationOf\":\"test.model.Animal#name"),
                    "implementationOf should reference "
                    + "Animal#name: " + json);
        }

        @Test
        void noImplementationOfForConcreteMethod()
                throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "createDog", null);
            var refs = ReferenceCollector.collect(method);
            ReferenceCollector.resolveImplementations(refs);
            String json = SourceReport.toJson(
                    "test.service.AnimalService#createDog",
                    method, "D:/t.java",
                    method.getSource(), 1, 10, refs, null);
            assertFalse(json.contains("\"implementationOf\""),
                    "No implementationOf for concrete: "
                    + json);
        }
    }
}
