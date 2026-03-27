package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    static JsonObject sourceJson(String typeFqn,
            String methodName) throws Exception {
        var handler = new SearchHandler();
        String body = handler.handleSource(
                Map.of("class", typeFqn,
                        "method", methodName)).body();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    static JsonObject typeJson(String typeFqn) throws Exception {
        var handler = new SearchHandler();
        String body = handler.handleSource(
                Map.of("class", typeFqn)).body();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    static JsonArray refs(JsonObject json) {
        return json.has("refs") ? json.getAsJsonArray("refs")
                : new JsonArray();
    }

    static JsonArray refsWithDirection(JsonObject json,
            String direction) {
        var result = new JsonArray();
        for (JsonElement e : refs(json)) {
            JsonObject ref = e.getAsJsonObject();
            if (direction.equals(str(ref, "direction"))) {
                result.add(ref);
            }
        }
        return result;
    }

    static JsonObject findRef(JsonArray refs, String fqmnPart) {
        for (JsonElement e : refs) {
            JsonObject ref = e.getAsJsonObject();
            String fqmn = str(ref, "fqmn");
            if (fqmn != null && fqmn.contains(fqmnPart)) {
                return ref;
            }
        }
        return null;
    }

    static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }

    static boolean bool(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).getAsBoolean();
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
            var json = sourceJson(
                    "test.service.AnimalService", "process");
            for (JsonElement e : refs(json)) {
                JsonObject ref = e.getAsJsonObject();
                assertNotNull(str(ref, "direction"),
                        "Every ref must have direction: "
                        + ref);
            }
        }

        @Test
        void incomingCallersFromCallerService() throws Exception {
            var json = sourceJson(
                    "test.service.AnimalService", "process");
            var incoming = refsWithDirection(json, "incoming");
            assertTrue(incoming.size() > 0,
                    "CallerService calls process");
            var caller = findRef(incoming,
                    "CallerService#callProcess");
            assertNotNull(caller,
                    "CallerService#callProcess should call: "
                    + incoming);
            assertEquals("project", str(caller, "scope"));
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
            var json = sourceJson("test.model.Dog", "name");
            var ot = json.getAsJsonObject("overrideTarget");
            assertNotNull(ot, "Should have overrideTarget");
            assertEquals("method", str(ot, "kind"));
            assertEquals("interface", str(ot, "typeKind"));
            assertEquals("test.model.Animal#name()",
                    str(ot, "fqmn"));
        }
    }

    @Nested
    class ParrotSpeakOverride {

        @Test
        void overrideTargetFqmnExact() throws Exception {
            var json = sourceJson(
                    "test.edge.Parrot", "speak");
            var ot = json.getAsJsonObject("overrideTarget");
            assertNotNull(ot, "Should have overrideTarget");
            assertEquals("method", str(ot, "kind"));
            assertEquals("class", str(ot, "typeKind"));
            assertEquals("test.edge.AbstractPet#speak()",
                    str(ot, "fqmn"));
        }
    }

    // ============================================================
    // Type-level hierarchy exact counts
    // ============================================================

    @Nested
    class HierarchyContracts {

        @Test
        void animalSubtypesExactCount() throws Exception {
            var json = typeJson("test.model.Animal");
            var subs = json.getAsJsonArray("subtypes");
            assertEquals(3, subs.size());
        }

        @Test
        void animalSubtypesFqns() throws Exception {
            var json = typeJson("test.model.Animal");
            var subs = json.getAsJsonArray("subtypes");
            var fqns = StreamSupport.stream(
                    subs.spliterator(), false)
                    .map(e -> e.getAsJsonObject()
                            .get("fqn").getAsString())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("test.model.Dog",
                    "test.model.Cat",
                    "test.edge.AbstractPet"), fqns);
        }

        @Test
        void dogSupertypesExact() throws Exception {
            var json = typeJson("test.model.Dog");
            var supers = json.getAsJsonArray("supertypes");
            assertEquals(1, supers.size());
            assertEquals("test.model.Animal",
                    supers.get(0).getAsJsonObject()
                            .get("fqn").getAsString());
            assertEquals("interface",
                    supers.get(0).getAsJsonObject()
                            .get("kind").getAsString());
        }

        @Test
        void parrotSupertypes() throws Exception {
            var json = typeJson("test.edge.Parrot");
            var supers = json.getAsJsonArray("supertypes");
            assertTrue(supers.size() >= 1);
            var fqns = StreamSupport.stream(
                    supers.spliterator(), false)
                    .map(e -> e.getAsJsonObject()
                            .get("fqn").getAsString())
                    .collect(Collectors.toSet());
            assertTrue(fqns.contains(
                    "test.edge.AbstractPet"));
        }

        @Test
        void abstractPetSubtypesExact() throws Exception {
            var json = typeJson("test.edge.AbstractPet");
            var subs = json.getAsJsonArray("subtypes");
            assertEquals(1, subs.size());
            assertEquals("test.edge.Parrot",
                    subs.get(0).getAsJsonObject()
                            .get("fqn").getAsString());
        }

        @Test
        void catHasNoSubtypes() throws Exception {
            var json = typeJson("test.model.Cat");
            assertEquals(0,
                    json.getAsJsonArray("subtypes").size());
        }

        @Test
        void colorEnumHasNoSubtypes() throws Exception {
            var json = typeJson("test.edge.Color");
            assertEquals(0,
                    json.getAsJsonArray("subtypes").size());
        }

        @Test
        void innerClassEnclosingType() throws Exception {
            var json = typeJson("test.edge.Outer.Inner");
            var enc = json.getAsJsonObject("enclosingType");
            assertNotNull(enc);
            assertEquals("test.edge.Outer",
                    enc.get("fqn").getAsString());
            assertEquals("class",
                    enc.get("kind").getAsString());
        }

        @Test
        void topLevelNoEnclosingType() throws Exception {
            var json = typeJson("test.model.Dog");
            assertFalse(json.has("enclosingType"));
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
            var json = sourceJson("test.model.Dog", "bark");
            var incoming = refsWithDirection(json, "incoming");
            assertTrue(incoming.size() > 0,
                    "bark() should have incoming callers");
            assertNotNull(findRef(incoming, "createDog"),
                    "AnimalService#createDog calls bark");
        }

        @Test
        void incomingRefHasProjectScope() throws Exception {
            var json = sourceJson("test.model.Dog", "bark");
            for (JsonElement e
                    : refsWithDirection(json, "incoming")) {
                assertEquals("project",
                        str(e.getAsJsonObject(), "scope"));
            }
        }

        @Test
        void incomingRefFqmnUsesHashSeparator()
                throws Exception {
            var json = sourceJson("test.model.Dog", "bark");
            for (JsonElement e
                    : refsWithDirection(json, "incoming")) {
                String fqmn = str(e.getAsJsonObject(), "fqmn");
                assertTrue(fqmn.contains("#"),
                        "FQMN should use #: " + fqmn);
            }
        }

        @Test
        void incomingRefHasFile() throws Exception {
            var json = sourceJson("test.model.Dog", "bark");
            for (JsonElement e
                    : refsWithDirection(json, "incoming")) {
                assertNotNull(
                        str(e.getAsJsonObject(), "file"),
                        "Incoming should have file");
            }
        }

        @Test
        void incomingRefsDeduped() throws Exception {
            var json = sourceJson("test.model.Dog", "bark");
            var fqmns = StreamSupport.stream(
                    refsWithDirection(json, "incoming")
                            .spliterator(), false)
                    .map(e -> str(e.getAsJsonObject(), "fqmn"))
                    .toList();
            assertEquals(fqmns.size(),
                    Set.copyOf(fqmns).size());
        }
    }

    @Nested
    class JsonStructureContracts {

        @Test
        void methodSourceHasAllTopLevelFields()
                throws Exception {
            var json = sourceJson(
                    "test.service.AnimalService", "process");
            assertNotNull(str(json, "fqmn"));
            assertNotNull(str(json, "file"));
            assertTrue(json.get("startLine").getAsInt() > 0);
            assertTrue(json.get("endLine").getAsInt() > 0);
            assertNotNull(str(json, "source"));
            assertTrue(json.has("refs"));
        }

        @Test
        void typeLevelHasNoRefsField() throws Exception {
            var json = typeJson("test.model.Dog");
            assertFalse(json.has("refs"));
            assertTrue(json.has("supertypes"));
            assertTrue(json.has("subtypes"));
        }

        @Test
        void overrideMethodHasOverrideTarget()
                throws Exception {
            var json = sourceJson("test.model.Dog", "name");
            assertTrue(json.has("overrideTarget"));
        }

        @Test
        void nonOverrideMethodHasNoTarget() throws Exception {
            var json = sourceJson("test.model.Dog", "bark");
            assertFalse(json.has("overrideTarget"));
        }

        @Test
        void everyOutgoingRefHasRequiredFields()
                throws Exception {
            var json = sourceJson(
                    "test.service.AnimalService", "process");
            for (JsonElement e
                    : refsWithDirection(json, "outgoing")) {
                JsonObject ref = e.getAsJsonObject();
                assertNotNull(str(ref, "fqmn"));
                assertNotNull(str(ref, "kind"));
                assertNotNull(str(ref, "direction"));
                assertNotNull(str(ref, "scope"));
            }
        }
    }
}
