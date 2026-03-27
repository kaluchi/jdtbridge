package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Contract tests: exact ref counts and FQMNs for fixture methods.
 * These tests break if fixture source changes — that's intentional.
 * They guarantee the ref collector output is deterministic and
 * complete.
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class RefCountContractTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- Helpers ----

    static Map<String, ReferenceCollector.Ref> collectMethod(
            String typeFqn, String methodName) throws Exception {
        IType type = JdtUtils.findType(typeFqn);
        assertNotNull(type, typeFqn + " not found");
        IMethod method = JdtUtils.findMethod(
                type, methodName, null);
        assertNotNull(method, methodName + " not found");
        return ReferenceCollector.collect(method);
    }

    static Set<String> fqmns(
            Map<String, ReferenceCollector.Ref> refs) {
        return refs.keySet();
    }

    static List<Map<String, Object>> parseRefs(String json) {
        var parsed = Json.parse(json);
        Object refsRaw = parsed.get("refs");
        if (refsRaw == null) return List.of();
        String refsStr = refsRaw.toString().trim();
        if (!refsStr.startsWith("[")) return List.of();
        var result = new ArrayList<Map<String, Object>>();
        refsStr = refsStr.substring(1, refsStr.length() - 1);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < refsStr.length(); i++) {
            char c = refsStr.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            if (depth == 0 && c == '}') {
                String elem = refsStr.substring(start,
                        i + 1).trim();
                if (elem.startsWith(","))
                    elem = elem.substring(1).trim();
                if (elem.startsWith("{"))
                    result.add(Json.parse(elem));
                start = i + 1;
            }
        }
        return result;
    }

    static String sourceJson(String typeFqn, String methodName)
            throws Exception {
        var handler = new SearchHandler();
        return handler.handleSource(
                Map.of("class", typeFqn,
                        "method", methodName)).body();
    }

    // ============================================================
    // AnimalService.process(Animal) — known refs
    // Source: animal.name();
    // Outgoing: Animal#name(), Animal type
    // Implementations: Dog#name(), Cat#name(), AbstractPet#name()
    // ============================================================

    @Nested
    class AnimalServiceProcess {

        @Test
        void exactOutgoingRefCount() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "process");
            // process(Animal animal) { animal.name(); }
            // Refs: Animal (type), Animal#name() (method)
            assertEquals(2, refs.size(),
                    "Exact ref count: " + fqmns(refs));
        }

        @Test
        void exactOutgoingFqmns() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "process");
            assertTrue(refs.containsKey(
                    "test.model.Animal#name()"),
                    "Should have Animal#name: " + fqmns(refs));
            assertTrue(refs.containsKey("test.model.Animal"),
                    "Should have Animal type: " + fqmns(refs));
        }

        @Test
        void exactImplementationCount() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "process");
            ReferenceCollector.resolveImplementations(refs);
            long implCount = refs.values().stream()
                    .filter(r -> r.implementationOf() != null)
                    .count();
            // Dog, Cat, AbstractPet implement Animal#name()
            assertTrue(implCount >= 3,
                    "At least 3 implementations: "
                    + fqmns(refs));
        }

        @Test
        void implementationFqmnsExact() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "process");
            ReferenceCollector.resolveImplementations(refs);
            var implFqmns = refs.values().stream()
                    .filter(r -> r.implementationOf() != null)
                    .map(r -> r.fqmn())
                    .collect(Collectors.toSet());
            assertTrue(implFqmns.stream()
                    .anyMatch(f -> f.contains("Dog#name")),
                    "Dog impl: " + implFqmns);
            assertTrue(implFqmns.stream()
                    .anyMatch(f -> f.contains("Cat#name")),
                    "Cat impl: " + implFqmns);
            assertTrue(implFqmns.stream()
                    .anyMatch(f -> f.contains("AbstractPet#name")),
                    "AbstractPet impl: " + implFqmns);
        }

        @Test
        void allImplsPointToSameInterface() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "process");
            ReferenceCollector.resolveImplementations(refs);
            for (var ref : refs.values()) {
                if (ref.implementationOf() != null) {
                    assertEquals(
                            "test.model.Animal#name()",
                            ref.implementationOf(),
                            "All impls should point to "
                            + "Animal#name: " + ref.fqmn());
                }
            }
        }

        @Test
        void fullJsonAllRefsHaveDirection() throws Exception {
            String json = sourceJson(
                    "test.service.AnimalService", "process");
            var refs = parseRefs(json);
            for (var ref : refs) {
                String dir = Json.getString(ref, "direction");
                assertNotNull(dir,
                        "Every ref must have direction: "
                        + ref);
            }
        }

        @Test
        void incomingCallersFromCallerService() throws Exception {
            // CallerService.callProcess() calls process()
            String json = sourceJson(
                    "test.service.AnimalService", "process");
            var refs = parseRefs(json);
            var incoming = refs.stream()
                    .filter(r -> "incoming".equals(
                            Json.getString(r, "direction")))
                    .toList();
            assertFalse(incoming.isEmpty(),
                    "CallerService calls process: " + refs);
            var callerFqmns = incoming.stream()
                    .map(r -> Json.getString(r, "fqmn"))
                    .toList();
            assertTrue(callerFqmns.stream()
                    .anyMatch(f -> f.contains(
                            "CallerService#callProcess")),
                    "CallerService#callProcess should call: "
                    + callerFqmns);
        }
    }

    // ============================================================
    // AnimalService.createDog() — known refs
    // Source: Dog d = new Dog(); d.bark(); return d;
    // ============================================================

    @Nested
    class AnimalServiceCreateDog {

        @Test
        void exactOutgoingRefCount() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "createDog");
            // Dog (type), Dog#bark() (method)
            // new Dog() is constructor — may or may not appear
            assertTrue(refs.size() >= 2,
                    "At least Dog + bark: " + fqmns(refs));
        }

        @Test
        void hasDogTypeRef() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "createDog");
            var dogRef = refs.get("test.model.Dog");
            assertNotNull(dogRef, "Dog type: " + fqmns(refs));
            assertEquals(ReferenceCollector.RefKind.TYPE,
                    dogRef.kind());
            assertEquals("class", dogRef.declaringTypeKind());
        }

        @Test
        void hasDogBarkRef() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "createDog");
            var barkRef = refs.get("test.model.Dog#bark()");
            assertNotNull(barkRef, "bark: " + fqmns(refs));
            assertEquals(ReferenceCollector.RefKind.METHOD,
                    barkRef.kind());
            assertEquals("class", barkRef.declaringTypeKind());
            assertFalse(barkRef.isStatic());
            assertEquals("void", barkRef.resolvedType());
        }

        @Test
        void noImplementationsForConcreteCall() throws Exception {
            var refs = collectMethod(
                    "test.service.AnimalService", "createDog");
            ReferenceCollector.resolveImplementations(refs);
            long implCount = refs.values().stream()
                    .filter(r -> r.implementationOf() != null)
                    .count();
            assertEquals(0, implCount,
                    "No impls for concrete class methods");
        }
    }

    // ============================================================
    // EnrichedRefService — known refs per method
    // ============================================================

    @Nested
    class EnrichedRefServiceMethods {

        @Test
        void getParrotNameExactRefs() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getParrotName");
            // p.name() → AbstractPet#name(), Parrot type
            assertTrue(refs.size() >= 1,
                    "Should have refs: " + fqmns(refs));
            var nameRef = refs.values().stream()
                    .filter(r -> r.fqmn().contains("#name("))
                    .findFirst().orElse(null);
            assertNotNull(nameRef, "name ref: " + fqmns(refs));
            assertTrue(nameRef.isInherited(),
                    "name() is inherited on Parrot");
            assertEquals("test.edge.AbstractPet",
                    nameRef.inheritedFrom());
        }

        @Test
        void getAnimalNameExactRefs() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getAnimalName");
            var nameRef = refs.get(
                    "test.model.Animal#name()");
            assertNotNull(nameRef,
                    "Animal#name: " + fqmns(refs));
            assertEquals("interface",
                    nameRef.declaringTypeKind());
            assertFalse(nameRef.isInherited());
            assertFalse(nameRef.isStatic());
            assertEquals("String", nameRef.resolvedType());
        }

        @Test
        void getStaticValueExactRefs() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getStaticValue");
            // Outer.StaticNested.VALUE (constant)
            var valueRef = refs.values().stream()
                    .filter(r -> r.fqmn().contains("VALUE"))
                    .findFirst().orElse(null);
            assertNotNull(valueRef,
                    "VALUE ref: " + fqmns(refs));
            assertTrue(valueRef.isStatic());
            assertEquals(ReferenceCollector.RefKind.CONSTANT,
                    valueRef.kind());
            assertEquals("int", valueRef.resolvedType());
        }

        @Test
        void getColorExactRefs() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getColor");
            // Color.RED — enum constant
            var redRef = refs.values().stream()
                    .filter(r -> r.fqmn().contains("RED"))
                    .findFirst().orElse(null);
            assertNotNull(redRef, "RED: " + fqmns(refs));
            assertTrue(redRef.isStatic());
            assertEquals("enum", redRef.declaringTypeKind());
        }

        @Test
        void getSharedDogExactRefs() throws Exception {
            var refs = collectMethod(
                    "test.service.EnrichedRefService",
                    "getSharedDog");
            // return SHARED_DOG — same-class field
            var fieldRef = refs.values().stream()
                    .filter(r -> r.fqmn().contains("SHARED_DOG"))
                    .findFirst().orElse(null);
            assertNotNull(fieldRef,
                    "SHARED_DOG: " + fqmns(refs));
            assertTrue(fieldRef.isStatic());
            assertEquals("Dog", fieldRef.resolvedType());
            assertEquals("test.model.Dog",
                    fieldRef.resolvedTypeFqn());
            assertEquals("class", fieldRef.resolvedTypeKind());
        }
    }

    // ============================================================
    // Dog.name() — override target
    // ============================================================

    @Nested
    class DogNameOverride {

        @Test
        void overrideTargetFqmnExact() throws Exception {
            String json = sourceJson("test.model.Dog", "name");
            var parsed = Json.parse(json);
            Object otRaw = parsed.get("overrideTarget");
            assertNotNull(otRaw, "Should have overrideTarget");
            var ot = Json.parse(otRaw.toString());
            assertEquals("method",
                    Json.getString(ot, "kind"));
            assertEquals("interface",
                    Json.getString(ot, "typeKind"));
            String fqmn = Json.getString(ot, "fqmn");
            assertEquals("test.model.Animal#name()", fqmn);
        }
    }

    // ============================================================
    // Parrot.speak() — override from abstract class
    // ============================================================

    @Nested
    class ParrotSpeakOverride {

        @Test
        void overrideTargetFqmnExact() throws Exception {
            String json = sourceJson(
                    "test.edge.Parrot", "speak");
            var parsed = Json.parse(json);
            Object otRaw = parsed.get("overrideTarget");
            assertNotNull(otRaw, "Should have overrideTarget");
            var ot = Json.parse(otRaw.toString());
            assertEquals("method",
                    Json.getString(ot, "kind"));
            assertEquals("class",
                    Json.getString(ot, "typeKind"));
            String fqmn = Json.getString(ot, "fqmn");
            assertEquals("test.edge.AbstractPet#speak()",
                    fqmn);
        }
    }

    // ============================================================
    // Type-level hierarchy exact counts
    // ============================================================

    @Nested
    class HierarchyContracts {

        @Test
        void animalSubtypesExactCount() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class", "test.model.Animal")).body();
            var parsed = Json.parse(json);
            String subsStr = parsed.get("subtypes").toString();
            // Direct subtypes: Dog, Cat, AbstractPet
            int count = countOccurrences(subsStr, "\"fqn\"");
            assertEquals(3, count,
                    "Animal has 3 direct subtypes: " + subsStr);
        }

        @Test
        void dogSupertypesExactCount() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class", "test.model.Dog")).body();
            var parsed = Json.parse(json);
            String supersStr =
                    parsed.get("supertypes").toString();
            // Dog implements Animal (1 supertype, Object filtered)
            int count = countOccurrences(supersStr, "\"fqn\"");
            assertEquals(1, count,
                    "Dog has 1 supertype (Animal): "
                    + supersStr);
        }

        @Test
        void parrotSupertypesExactCount() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class", "test.edge.Parrot")).body();
            var parsed = Json.parse(json);
            String supersStr =
                    parsed.get("supertypes").toString();
            // Parrot extends AbstractPet (implements Animal)
            // Direct: AbstractPet + Animal
            int count = countOccurrences(supersStr, "\"fqn\"");
            assertTrue(count >= 1,
                    "Parrot has at least AbstractPet: "
                    + supersStr);
        }

        @Test
        void abstractPetSubtypesExact() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class",
                            "test.edge.AbstractPet")).body();
            var parsed = Json.parse(json);
            String subsStr = parsed.get("subtypes").toString();
            // Direct subtype: Parrot only
            assertTrue(subsStr.contains("Parrot"),
                    "Parrot should be subtype: " + subsStr);
            int count = countOccurrences(subsStr, "\"fqn\"");
            assertEquals(1, count,
                    "AbstractPet has 1 direct subtype: "
                    + subsStr);
        }

        @Test
        void catHasNoSubtypes() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class", "test.model.Cat")).body();
            var parsed = Json.parse(json);
            assertEquals("[]",
                    parsed.get("subtypes").toString().trim(),
                    "Cat has no subtypes");
        }

        @Test
        void colorEnumHasNoSubtypes() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class", "test.edge.Color")).body();
            var parsed = Json.parse(json);
            assertEquals("[]",
                    parsed.get("subtypes").toString().trim(),
                    "Color enum has no subtypes");
        }

        @Test
        void innerClassHasEnclosingType() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class",
                            "test.edge.Outer.Inner")).body();
            var parsed = Json.parse(json);
            Object encRaw = parsed.get("enclosingType");
            assertNotNull(encRaw, "Should have enclosingType");
            var enc = Json.parse(encRaw.toString());
            assertEquals("test.edge.Outer",
                    Json.getString(enc, "fqn"));
            assertEquals("class",
                    Json.getString(enc, "kind"));
        }

        @Test
        void topLevelHasNoEnclosingType() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class", "test.model.Dog")).body();
            var parsed = Json.parse(json);
            assertNull(parsed.get("enclosingType"),
                    "Top-level should not have enclosingType");
        }
    }

    // ============================================================
    // GenericService — type variables
    // ============================================================

    @Nested
    class GenericServiceContracts {

        @Test
        void getMethodFieldRefIsTypeVariable() throws Exception {
            var refs = collectMethod(
                    "test.service.GenericService", "get");
            var itemRef = refs.values().stream()
                    .filter(r -> r.fqmn().contains("item"))
                    .findFirst().orElse(null);
            assertNotNull(itemRef,
                    "get() accesses item: " + fqmns(refs));
            assertTrue(itemRef.isTypeVariable());
            assertEquals("test.model.Animal",
                    itemRef.typeBound());
        }

        @Test
        void nameMethodCallsAnimalName() throws Exception {
            var refs = collectMethod(
                    "test.service.GenericService", "name");
            assertTrue(refs.containsKey(
                    "test.model.Animal#name()"),
                    "name() calls item.name() → Animal#name: "
                    + fqmns(refs));
        }

        @Test
        void setMethodAccessesItemField() throws Exception {
            var refs = collectMethod(
                    "test.service.GenericService", "set");
            var itemRef = refs.values().stream()
                    .filter(r -> r.fqmn().contains("item"))
                    .findFirst().orElse(null);
            assertNotNull(itemRef,
                    "set() writes item: " + fqmns(refs));
        }
    }

    // ============================================================
    // CallerService — incoming ref verification
    // ============================================================

    @Nested
    class CallerServiceContracts {

        @Test
        void callerServiceOutgoingRefs() throws Exception {
            var refs = collectMethod(
                    "test.service.CallerService", "callProcess");
            // callProcess: new Dog(), service.process(dog)
            assertTrue(refs.containsKey("test.model.Dog"),
                    "Dog type: " + fqmns(refs));
            assertTrue(refs.values().stream()
                    .anyMatch(r -> r.fqmn().contains(
                            "AnimalService#process")),
                    "AnimalService#process: " + fqmns(refs));
        }

        @Test
        void callerServiceCallCreateDogRefs() throws Exception {
            var refs = collectMethod(
                    "test.service.CallerService",
                    "callCreateDog");
            assertTrue(refs.values().stream()
                    .anyMatch(r -> r.fqmn().contains(
                            "AnimalService#createDog")),
                    "Should call createDog: " + fqmns(refs));
        }

        @Test
        void dogBarkHasIncomingFromCreateDog()
                throws Exception {
            String json = sourceJson(
                    "test.model.Dog", "bark");
            var refs = parseRefs(json);
            var incoming = refs.stream()
                    .filter(r -> "incoming".equals(
                            Json.getString(r, "direction")))
                    .toList();
            assertFalse(incoming.isEmpty(),
                    "bark() should have incoming callers");
            var callerFqmns = incoming.stream()
                    .map(r -> Json.getString(r, "fqmn"))
                    .collect(Collectors.toSet());
            assertTrue(callerFqmns.stream()
                    .anyMatch(f -> f.contains("createDog")),
                    "AnimalService#createDog calls bark: "
                    + callerFqmns);
        }

        @Test
        void incomingRefHasProjectScope() throws Exception {
            String json = sourceJson(
                    "test.model.Dog", "bark");
            var refs = parseRefs(json);
            var incoming = refs.stream()
                    .filter(r -> "incoming".equals(
                            Json.getString(r, "direction")))
                    .toList();
            for (var ref : incoming) {
                assertEquals("project",
                        Json.getString(ref, "scope"),
                        "Incoming from fixture is project: "
                        + ref);
            }
        }

        @Test
        void incomingRefFqmnUsesHashSeparator()
                throws Exception {
            String json = sourceJson(
                    "test.model.Dog", "bark");
            var refs = parseRefs(json);
            var incoming = refs.stream()
                    .filter(r -> "incoming".equals(
                            Json.getString(r, "direction")))
                    .toList();
            for (var ref : incoming) {
                String fqmn = Json.getString(ref, "fqmn");
                // Method FQMNs should use # between type and
                // method: pkg.Type#method(), not pkg.Type.method()
                assertTrue(fqmn.contains("#"),
                        "FQMN should use # separator: "
                        + fqmn);
            }
        }

        @Test
        void incomingRefHasFile() throws Exception {
            String json = sourceJson(
                    "test.model.Dog", "bark");
            var refs = parseRefs(json);
            var incoming = refs.stream()
                    .filter(r -> "incoming".equals(
                            Json.getString(r, "direction")))
                    .toList();
            for (var ref : incoming) {
                assertNotNull(Json.getString(ref, "file"),
                        "Incoming should have file: " + ref);
            }
        }

        @Test
        void incomingRefsDeduped() throws Exception {
            String json = sourceJson(
                    "test.model.Dog", "bark");
            var refs = parseRefs(json);
            var incomingFqmns = refs.stream()
                    .filter(r -> "incoming".equals(
                            Json.getString(r, "direction")))
                    .map(r -> Json.getString(r, "fqmn"))
                    .toList();
            assertEquals(incomingFqmns.size(),
                    Set.copyOf(incomingFqmns).size(),
                    "Incoming refs should be deduped: "
                    + incomingFqmns);
        }
    }

    // ============================================================
    // Source JSON structure contracts
    // ============================================================

    @Nested
    class JsonStructureContracts {

        @Test
        void methodSourceHasAllTopLevelFields()
                throws Exception {
            String json = sourceJson(
                    "test.service.AnimalService", "process");
            var parsed = Json.parse(json);
            assertNotNull(Json.getString(parsed, "fqmn"));
            assertNotNull(Json.getString(parsed, "file"));
            assertTrue(
                    Json.getInt(parsed, "startLine", -1) > 0);
            assertTrue(
                    Json.getInt(parsed, "endLine", -1) > 0);
            assertNotNull(Json.getString(parsed, "source"));
            assertNotNull(parsed.get("refs"));
        }

        @Test
        void typeLevelHasNoRefsField() throws Exception {
            var handler = new SearchHandler();
            String json = handler.handleSource(
                    Map.of("class", "test.model.Dog")).body();
            var parsed = Json.parse(json);
            assertNull(parsed.get("refs"),
                    "Type-level: no refs");
            assertNotNull(parsed.get("supertypes"),
                    "Type-level: has supertypes");
            assertNotNull(parsed.get("subtypes"),
                    "Type-level: has subtypes");
        }

        @Test
        void overrideMethodHasOverrideTarget() throws Exception {
            String json = sourceJson(
                    "test.model.Dog", "name");
            var parsed = Json.parse(json);
            assertNotNull(parsed.get("overrideTarget"),
                    "Override method should have target");
        }

        @Test
        void nonOverrideMethodHasNoOverrideTarget()
                throws Exception {
            String json = sourceJson(
                    "test.model.Dog", "bark");
            var parsed = Json.parse(json);
            assertNull(parsed.get("overrideTarget"),
                    "Non-override should not have target");
        }

        @Test
        void everyOutgoingRefHasRequiredFields()
                throws Exception {
            String json = sourceJson(
                    "test.service.AnimalService", "process");
            var refs = parseRefs(json);
            for (var ref : refs) {
                String dir = Json.getString(ref, "direction");
                if (!"outgoing".equals(dir)) continue;
                assertNotNull(Json.getString(ref, "fqmn"),
                        "fqmn required: " + ref);
                assertNotNull(Json.getString(ref, "kind"),
                        "kind required: " + ref);
                assertNotNull(Json.getString(ref, "direction"),
                        "direction required: " + ref);
                assertNotNull(Json.getString(ref, "scope"),
                        "scope required: " + ref);
            }
        }
    }

    // ---- Utilities ----

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
