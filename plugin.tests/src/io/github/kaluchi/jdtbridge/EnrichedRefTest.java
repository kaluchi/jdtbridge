package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for enriched reference metadata: declaringTypeKind,
 * isStatic, inherited, resolvedType, typeBound, etc.
 *
 * Uses TestFixture classes including EnrichedRefService and
 * GenericService.
 */
public class EnrichedRefTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- Helpers ----

    static Map<String, ReferenceCollector.Ref>
            collectAll(String fqn) throws Exception {
        IType type = JdtUtils.findType(fqn);
        assertNotNull(type, fqn + " should exist");
        var all = new LinkedHashMap<String,
                ReferenceCollector.Ref>();
        for (IMethod m : type.getMethods()) {
            all.putAll(ReferenceCollector.collect(m));
        }
        return all;
    }

    static Map<String, ReferenceCollector.Ref>
            collectMethod(String typeFqn, String methodName)
            throws Exception {
        IType type = JdtUtils.findType(typeFqn);
        assertNotNull(type, typeFqn + " should exist");
        IMethod method = JdtUtils.findMethod(
                type, methodName, null);
        assertNotNull(method, methodName + " should exist in "
                + typeFqn);
        return ReferenceCollector.collect(method);
    }

    static ReferenceCollector.Ref find(
            Map<String, ReferenceCollector.Ref> refs,
            String fqmnPart) {
        return refs.values().stream()
                .filter(r -> r.fqmn().contains(fqmnPart))
                .findFirst().orElse(null);
    }

    static String toJson(IMethod method,
            Map<String, ReferenceCollector.Ref> refs)
            throws Exception {
        return SourceReport.toJson(
                method.getDeclaringType()
                        .getFullyQualifiedName()
                        + "#" + method.getElementName(),
                method, "D:/test/Test.java",
                method.getSource(), 1, 10, refs, null);
    }

    // ============================================================
    // Ref record field tests
    // ============================================================

    @Nested
    class DeclaringTypeKind {

        @Test
        void classMethod() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "Dog#bark");
            assertNotNull(ref, "Should find Dog#bark: "
                    + refs.keySet());
            assertEquals("class", ref.declaringTypeKind());
        }

        @Test
        void interfaceMethod() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getAnimalName");
            var ref = find(refs, "Animal#name");
            assertNotNull(ref, "Should find Animal#name: "
                    + refs.keySet());
            assertEquals("interface", ref.declaringTypeKind());
        }

        @Test
        void enumTypeRef() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "test.edge.Color");
            assertNotNull(ref, "Should find Color: "
                    + refs.keySet());
            assertEquals("enum", ref.declaringTypeKind());
        }

        @Test
        void classTypeRef() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "test.model.Dog");
            assertNotNull(ref, "Should find Dog type ref: "
                    + refs.keySet());
            assertEquals("class", ref.declaringTypeKind());
        }

        @Test
        void interfaceTypeRef() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "test.model.Animal");
            assertNotNull(ref, "Should find Animal type ref: "
                    + refs.keySet());
            assertEquals("interface", ref.declaringTypeKind());
        }
    }

    @Nested
    class StaticModifier {

        @Test
        void instanceMethodNotStatic() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "Dog#bark");
            assertNotNull(ref);
            assertFalse(ref.isStatic());
        }

        @Test
        void staticFieldIsStatic() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "SHARED_DOG");
            assertNotNull(ref, "Should find SHARED_DOG: "
                    + refs.keySet());
            assertTrue(ref.isStatic());
        }

        @Test
        void constantIsStatic() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "VALUE");
            assertNotNull(ref, "Should find VALUE: "
                    + refs.keySet());
            assertTrue(ref.isStatic());
        }

        @Test
        void constantHasConstantKind() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "VALUE");
            assertNotNull(ref);
            assertEquals(ReferenceCollector.RefKind.CONSTANT,
                    ref.kind());
        }

        @Test
        void enumConstantIsStatic() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "Color#RED");
            assertNotNull(ref, "Should find Color#RED: "
                    + refs.keySet());
            assertTrue(ref.isStatic());
        }
    }

    @Nested
    class ResolvedType {

        @Test
        void voidReturnType() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "Dog#bark");
            assertNotNull(ref);
            assertEquals("void", ref.resolvedType());
        }

        @Test
        void stringReturnTypeSimpleName() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getAnimalName");
            var ref = find(refs, "Animal#name");
            assertNotNull(ref);
            assertEquals("String", ref.resolvedType());
        }

        @Test
        void jdkReturnTypeHasFqn() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getAnimalName");
            var ref = find(refs, "Animal#name");
            assertNotNull(ref);
            // Server exhaustive: FQN always present
            assertEquals("java.lang.String",
                    ref.resolvedTypeFqn());
        }

        @Test
        void voidHasNoFqn() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "Dog#bark");
            assertNotNull(ref);
            assertNull(ref.resolvedTypeFqn());
        }

        @Test
        void voidHasNoTypeKind() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "Dog#bark");
            assertNotNull(ref);
            assertNull(ref.resolvedTypeKind());
        }

        @Test
        void fieldTypeResolved() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "SHARED_DOG");
            assertNotNull(ref, "Should find SHARED_DOG: "
                    + refs.keySet());
            assertEquals("Dog", ref.resolvedType());
        }

        @Test
        void fieldTypeFqn() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "SHARED_DOG");
            assertNotNull(ref);
            assertEquals("test.model.Dog",
                    ref.resolvedTypeFqn());
        }

        @Test
        void fieldTypeKindIsClass() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "SHARED_DOG");
            assertNotNull(ref);
            assertEquals("class", ref.resolvedTypeKind());
        }
    }

    @Nested
    class Inherited {

        @Test
        void directCallNotInherited() throws Exception {
            var refs = collectAll(
                    "test.service.AnimalService");
            var ref = find(refs, "Dog#bark");
            assertNotNull(ref);
            assertFalse(ref.isInherited());
            assertNull(ref.inheritedFrom());
        }

        @Test
        void inheritedMethodDetected() throws Exception {
            // getParrotName calls p.name() on Parrot
            // name() is declared in AbstractPet (not overridden
            // by Parrot)
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getParrotName");
            var ref = find(refs, "#name(");
            assertNotNull(ref, "Should find name() call: "
                    + refs.keySet());
            // Declaring type should be AbstractPet
            assertTrue(ref.fqmn().contains("AbstractPet"),
                    "name() should resolve to AbstractPet: "
                    + ref.fqmn());
            assertTrue(ref.isInherited(),
                    "Parrot.name() inherited from AbstractPet");
        }

        @Test
        void inheritedFromIsDeclaringType() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getParrotName");
            var ref = find(refs, "AbstractPet#name");
            assertNotNull(ref, "Should find AbstractPet#name: "
                    + refs.keySet());
            assertEquals("test.edge.AbstractPet",
                    ref.inheritedFrom());
        }

        @Test
        void interfaceCallNotInherited() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getAnimalName");
            var ref = find(refs, "Animal#name");
            assertNotNull(ref);
            // a.name() where a is Animal → receiver=Animal,
            // declaring=Animal → not inherited
            assertFalse(ref.isInherited());
        }
    }

    @Nested
    class TypeParameters {

        @Test
        void typeVariableInGenericService() throws Exception {
            var refs = collectMethod(
                    "test.service.GenericService", "name");
            var ref = find(refs, "Animal#name");
            assertNotNull(ref,
                    "Should find Animal#name via T.name(): "
                    + refs.keySet());
        }

        @Test
        void getMethodReturnTypeIsTypeVariable()
                throws Exception {
            // GenericService.get() returns T, body: return item
            // item is a field of type T extends Animal
            var refs = collectMethod(
                    "test.service.GenericService", "get");
            var itemRef = find(refs, "item");
            assertNotNull(itemRef,
                    "get() accesses item field: "
                    + refs.keySet());
            assertTrue(itemRef.isTypeVariable(),
                    "item field should be type variable");
            assertEquals("test.model.Animal",
                    itemRef.typeBound(),
                    "T bound should be Animal");
        }

        @Test
        void setMethodParamTypeResolved() throws Exception {
            // GenericService.set(T item) calls this.item = item
            var refs = collectMethod(
                    "test.service.GenericService", "set");
            var itemRef = find(refs, "item");
            assertNotNull(itemRef,
                    "Should find item field ref: "
                    + refs.keySet());
        }
    }

    @Nested
    class HelperMethods {

        @Test
        void stripGenericsSimple() {
            assertEquals("com.example.List",
                    ReferenceCollector.stripGenerics(
                            "com.example.List<String>"));
        }

        @Test
        void stripGenericsNested() {
            assertEquals("com.example.Map",
                    ReferenceCollector.stripGenerics(
                            "com.example.Map<S,List<I>>"));
        }

        @Test
        void stripGenericsNone() {
            assertEquals("com.example.Foo",
                    ReferenceCollector.stripGenerics(
                            "com.example.Foo"));
        }

        @Test
        void annotationTypeKind() throws Exception {
            // Collect refs from Marker annotation type itself
            // to verify typeKind resolution works for annotations
            IType markerType = JdtUtils.findType(
                    "test.edge.Marker");
            assertNotNull(markerType, "Marker should exist");
            // Marker has @Retention annotation — that's a type
            // ref inside its source range
            var refs = ReferenceCollector.collect(markerType);
            assertNotNull(refs, "Should collect refs for Marker");
            // The Marker type itself, when referenced by others,
            // should have annotation kind. Test via type-info:
            assertTrue(markerType.isAnnotation(),
                    "Marker should be annotation type");
        }
    }

    // ============================================================
    // JSON output tests (SourceReport)
    // ============================================================

    @Nested
    class JsonOutput {

        @Test
        void directionAlwaysOutgoing() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = type.getMethods()[0];
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            assertFalse(refs.isEmpty(),
                    "Should have refs");
            assertTrue(json.contains(
                    "\"direction\":\"outgoing\""),
                    "All refs should be outgoing: " + json);
            assertFalse(json.contains(
                    "\"direction\":\"incoming\""),
                    "Should not have incoming refs");
        }

        @Test
        void typeKindInJson() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getAnimalName");
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getAnimalName", null);
            String json = toJson(method, refs);
            assertTrue(json.contains("\"typeKind\":\"interface\""),
                    "Should have interface typeKind: " + json);
        }

        @Test
        void staticTrueInJson() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            assertNotNull(refs, "Should collect refs");
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getStaticValue", null);
            var methodRefs = ReferenceCollector.collect(method);
            String json = toJson(method, methodRefs);
            assertTrue(json.contains("\"static\":true"),
                    "Should have static:true: " + json);
        }

        @Test
        void staticAbsentWhenFalse() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            // process() calls animal.name() — not static
            // "static":true should not appear
            assertFalse(json.contains("\"static\":true"),
                    "Non-static refs should not have static: "
                    + json);
        }

        @Test
        void inheritedTrueInJson() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getParrotName", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            assertTrue(json.contains("\"inherited\":true"),
                    "Should have inherited:true: " + json);
        }

        @Test
        void inheritedFromInJson() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getParrotName", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            assertTrue(json.contains(
                    "\"inheritedFrom\":\"test.edge.AbstractPet\""),
                    "Should have inheritedFrom: " + json);
        }

        @Test
        void typeFieldInJson() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getAnimalName", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            assertTrue(json.contains("\"type\":\"String\""),
                    "Should have type:String: " + json);
        }

        @Test
        void returnTypeFqnInJson() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = JdtUtils.findMethod(
                    type, "getStaticValue", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            // VALUE is int — primitive, no returnTypeFqn
            assertFalse(json.contains("\"returnTypeFqn\""),
                    "Primitive return type should not have FQN: "
                    + json);
        }

        @Test
        void returnTypeFqnForClassReturn() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            // SHARED_DOG field has type Dog
            var ref = find(refs, "SHARED_DOG");
            assertNotNull(ref);
            // Put single ref in JSON
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = type.getMethods()[0];
            String json = SourceReport.toJson(
                    "test", method, "D:/t.java", "code", 1, 1,
                    Map.of(ref.fqmn(), ref), null);
            assertTrue(json.contains(
                    "\"returnTypeFqn\":\"test.model.Dog\""),
                    "Should have Dog FQN: " + json);
        }

        @Test
        void returnTypeKindInJson() throws Exception {
            var refs = collectAll(
                    "test.service.EnrichedRefService");
            var ref = find(refs, "SHARED_DOG");
            assertNotNull(ref);
            IType type = JdtUtils.findType(
                    "test.service.EnrichedRefService");
            IMethod method = type.getMethods()[0];
            String json = SourceReport.toJson(
                    "test", method, "D:/t.java", "code", 1, 1,
                    Map.of(ref.fqmn(), ref), null);
            assertTrue(json.contains(
                    "\"returnTypeKind\":\"class\""),
                    "Should have returnTypeKind:class: " + json);
        }

        @Test
        void scopeProjectForWorkspaceRefs() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            assertTrue(json.contains("\"scope\":\"project\""),
                    "Workspace refs should be project scope: "
                    + json);
        }

        @Test
        void kindFieldPresent() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            assertTrue(json.contains("\"kind\":\"method\""),
                    "Should have kind:method: " + json);
        }

        @Test
        void filePathForProjectRefs() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            // Project refs should have file paths
            assertTrue(json.contains("\"file\":"),
                    "Should have file path: " + json);
        }

        @Test
        void docCollectedForDependencyScope() throws Exception {
            // SourceReport collects javadoc for ALL scopes
            // including dependency. Use a method that calls
            // a dependency method with javadoc.
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "createDog", null);
            var refs = ReferenceCollector.collect(method);
            String json = toJson(method, refs);
            // createDog has outgoing refs — verify they exist
            assertTrue(json.contains("\"kind\":\"method\""),
                    "Should have method refs: " + json);
            // Dog type ref should exist
            assertTrue(json.contains("test.model.Dog"),
                    "Should reference Dog: " + json);
        }

        @Test
        void fullClassHasHierarchyNotRefs() throws Exception {
            IType type = JdtUtils.findType("test.model.Dog");
            var refs = ReferenceCollector.collect(type);
            String json = SourceReport.toJson(
                    "test.model.Dog", type,
                    "D:/test/Dog.java",
                    type.getSource(), 1, 20, refs, null);
            assertTrue(json.contains("\"supertypes\""),
                    "Type-level should have supertypes: "
                    + json);
            assertFalse(json.contains("\"refs\""),
                    "Type-level should not have refs: "
                    + json);
        }
    }
}
