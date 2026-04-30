package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GraphHandler} — point-lookup endpoints
 * against {@link TestFixture}. Each test asserts on the parsed JSON
 * response, treating the wire shape as the contract the CLI side
 * will read.
 */
public class GraphHandlerTest {

    private GraphHandler handler;

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    @BeforeEach
    void newHandler() {
        handler = new GraphHandler();
    }

    private static Map<String, String> params(String key, String value) {
        var m = new HashMap<String, String>();
        m.put(key, value);
        return m;
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static boolean isError(JsonObject obj) {
        return obj.has("_error");
    }

    private static JsonObject errorOf(JsonObject obj) {
        return obj.getAsJsonObject("_error");
    }

    // ── /type ───────────────────────────────────────────────────────

    @Test
    void typeReturnsDetailForExistingFqn() {
        JsonObject result = parse(
                handler.handleType(params("of", "test.model.Dog")));
        assertEquals("test.model.Dog",
                result.get("fqn").getAsString());
        assertEquals("type", result.get("kind").getAsString());
        assertEquals("class", result.get("typeKind").getAsString());
        assertEquals(1, result.getAsJsonArray("interfaces").size());
        assertEquals("test.model.Animal",
                result.getAsJsonArray("interfaces").get(0).getAsString());
    }

    @Test
    void typeReturnsErrorForUnknownFqn() {
        JsonObject result = parse(
                handler.handleType(params("of", "no.such.Class")));
        assertTrue(isError(result));
        assertEquals("type-not-found",
                errorOf(result).get("kind").getAsString());
        assertEquals("TypeNotFound",
                errorOf(result).get("thrown").getAsString());
        assertEquals("no.such.Class",
                errorOf(result).getAsJsonObject("context")
                .get("fqn").getAsString());
    }

    @Test
    void typeReturnsErrorWhenOfMissing() {
        JsonObject result = parse(handler.handleType(Map.of()));
        assertTrue(isError(result));
        assertEquals("missing-parameter",
                errorOf(result).get("kind").getAsString());
        assertEquals("of",
                errorOf(result).getAsJsonObject("context")
                .get("parameter").getAsString());
    }

    @Test
    void typeForInnerClassUsesDottedFqn() {
        JsonObject result = parse(handler.handleType(
                params("of", "test.edge.Outer.Inner")));
        assertEquals("test.edge.Outer.Inner",
                result.get("fqn").getAsString());
        assertEquals("test.edge.Outer",
                result.get("containingType").getAsString());
    }

    // ── /method ─────────────────────────────────────────────────────

    @Test
    void methodReturnsDetailForFqnWithSignature() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.edge.Calculator#add(int,int)")));
        assertEquals("test.edge.Calculator#add(int,int)",
                result.get("fqn").getAsString());
        assertEquals("method", result.get("kind").getAsString());
        assertEquals("add", result.get("name").getAsString());
        assertEquals(2, result.getAsJsonArray("parameters").size());
        assertEquals("int", result.get("returnType").getAsString());
    }

    @Test
    void methodResolvesParamTypesFromInlineParens() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.edge.Calculator#add(int,int,int)")));
        assertEquals("add(int,int,int)",
                result.get("signature").getAsString());
    }

    @Test
    void methodFailsAmbiguousWithoutSignature() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.edge.Calculator#add")));
        assertTrue(isError(result));
        var error = errorOf(result);
        var context = error.getAsJsonObject("context");
        assertEquals("ambiguous-match",
                error.get("kind").getAsString());
        assertEquals(3, context.get("matchCount").getAsInt());

        var candidates = context.getAsJsonArray("candidates");
        assertNotNull(candidates,
                "ambiguous-match carries :candidates so the caller "
                + "routes `!| /context/candidates` to pick a "
                + "disambiguated FQN without a second discovery "
                + "roundtrip");
        assertEquals(3, candidates.size());
        var candidateFqns = candidates.asList().stream()
                .map(JsonElement::getAsString).toList();
        assertTrue(candidateFqns.stream()
                .allMatch(fqn -> fqn.startsWith(
                        "test.edge.Calculator#add(")),
                "every candidate is a concrete FQN with parameters; "
                + "got " + candidateFqns);

        String message = error.get("message").getAsString();
        for (String candidate : candidateFqns) {
            assertTrue(message.contains(candidate),
                    "error message lists every candidate FQN so "
                    + "the user (and LLM) can see the alternatives "
                    + "without routing into :context; message=\""
                    + message + "\"");
        }
    }

    @Test
    void methodReturnsErrorForMissingMethod() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.model.Dog#noSuchMethod()")));
        assertTrue(isError(result));
        assertEquals("method-not-found",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void methodRejectsTypeSubject() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.model.Dog")));
        assertTrue(isError(result));
        assertEquals("wrong-subject-kind",
                errorOf(result).get("kind").getAsString());
        assertEquals("method",
                errorOf(result).getAsJsonObject("context")
                .get("expected").getAsString());
    }

    @Test
    void methodErrorTypeNotFoundCarriesOnlyTheUnresolvedFqn() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "no.such.Class#foo()")));
        assertTrue(isError(result));
        assertEquals("type-not-found",
                errorOf(result).get("kind").getAsString());
        var ctx = errorOf(result).getAsJsonObject("context");
        // :fqn names the identifier that could not be resolved —
        // just the type part, since that is what actually failed.
        // The caller already knows what it passed; the error
        // descriptor does not echo its input back.
        assertEquals("no.such.Class", ctx.get("fqn").getAsString());
        assertEquals(1, ctx.size(),
                ":fqn is the only context field — no echo of the "
                + "caller's full identifier");
    }

    // ── /field ──────────────────────────────────────────────────────

    @Test
    void fieldReturnsDetailForExistingField() {
        JsonObject result = parse(handler.handleField(
                params("of", "test.model.Dog#age")));
        assertEquals("test.model.Dog#age",
                result.get("fqn").getAsString());
        assertEquals("field", result.get("kind").getAsString());
        assertEquals("age", result.get("name").getAsString());
        assertEquals("int", result.get("type").getAsString());
    }

    @Test
    void fieldRejectsMethodFqn() {
        JsonObject result = parse(handler.handleField(
                params("of", "test.model.Dog#bark")));
        assertTrue(isError(result));
        assertEquals("wrong-subject-kind",
                errorOf(result).get("kind").getAsString());
        assertEquals("field",
                errorOf(result).getAsJsonObject("context")
                .get("expected").getAsString());
    }

    @Test
    void fieldRejectsParensInName() {
        JsonObject result = parse(handler.handleField(
                params("of", "test.model.Dog#age()")));
        assertTrue(isError(result));
        assertEquals("invalid-fqn",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void fieldErrorForUnknownField() {
        JsonObject result = parse(handler.handleField(
                params("of", "test.model.Dog#noSuchField")));
        assertTrue(isError(result));
        assertEquals("field-not-found",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void fieldDetailForStaticFinalCarriesIsConstant() {
        JsonObject result = parse(handler.handleField(
                params("of", "test.edge.Outer.StaticNested#VALUE")));
        assertNotNull(result.get("isConstant"),
                "static final field must carry :isConstant");
        assertTrue(result.get("isConstant").getAsBoolean());
    }

    // ── /members /methods /fields /innerTypes ───────────────────────

    @Test
    void membersReturnsMethodsFieldsInnerTypesOfDog() {
        var arr = JsonParser.parseString(handler.handleMembers(
                params("of", "test.model.Dog")))
                .getAsJsonArray();
        // Dog: bark(), name(), age — 2 methods + 1 field + 0 inner
        long methodCount = arr.asList().stream()
                .filter(e -> "method".equals(e.getAsJsonObject()
                        .get("kind").getAsString()))
                .count();
        long fieldCount = arr.asList().stream()
                .filter(e -> "field".equals(e.getAsJsonObject()
                        .get("kind").getAsString()))
                .count();
        assertEquals(2, methodCount, "Dog has bark() + name()");
        assertEquals(1, fieldCount, "Dog has :age");
    }

    @Test
    void membersIncludesInnerTypesForOuter() {
        var arr = JsonParser.parseString(handler.handleMembers(
                params("of", "test.edge.Outer")))
                .getAsJsonArray();
        long innerCount = arr.asList().stream()
                .filter(e -> "type".equals(e.getAsJsonObject()
                        .get("kind").getAsString()))
                .count();
        assertEquals(2, innerCount,
                "Outer has Inner + StaticNested");
    }

    @Test
    void methodsReturnsOnlyMethods() {
        var arr = JsonParser.parseString(handler.handleMethods(
                params("of", "test.model.Dog")))
                .getAsJsonArray();
        assertEquals(2, arr.size());
        for (var entry : arr) {
            assertEquals("method", entry.getAsJsonObject()
                    .get("kind").getAsString());
        }
    }

    @Test
    void fieldsReturnsOnlyFields() {
        var arr = JsonParser.parseString(handler.handleFields(
                params("of", "test.model.Dog")))
                .getAsJsonArray();
        assertEquals(1, arr.size());
        assertEquals("field", arr.get(0).getAsJsonObject()
                .get("kind").getAsString());
        assertEquals("test.model.Dog#age",
                arr.get(0).getAsJsonObject().get("fqn").getAsString());
    }

    @Test
    void innerTypesReturnsNestedTypes() {
        var arr = JsonParser.parseString(handler.handleInnerTypes(
                params("of", "test.edge.Outer")))
                .getAsJsonArray();
        assertEquals(2, arr.size());
        for (var entry : arr) {
            assertEquals("type", entry.getAsJsonObject()
                    .get("kind").getAsString());
        }
    }

    @Test
    void membersErrorForUnknownType() {
        JsonObject result = parse(handler.handleMembers(
                params("of", "no.such.X")));
        assertTrue(isError(result));
        assertEquals("type-not-found",
                errorOf(result).get("kind").getAsString());
    }

    // ── /supers /subtypes ───────────────────────────────────────────

    @Test
    void supersReturnsSuperclassAndInterfaces() {
        var arr = JsonParser.parseString(handler.handleSupers(
                params("of", "test.model.Dog")))
                .getAsJsonArray();
        // Dog: extends Object implements Animal — Object filtered? No — supers includes both
        var fqns = new java.util.HashSet<String>();
        for (var entry : arr) {
            fqns.add(entry.getAsJsonObject().get("fqn").getAsString());
        }
        assertTrue(fqns.contains("test.model.Animal"),
                "supers should include Animal interface, got: " + fqns);
        assertTrue(fqns.contains("java.lang.Object"),
                "supers should include Object superclass, got: " + fqns);
    }

    @Test
    void supersForParrotIncludesAbstractPet() {
        var arr = JsonParser.parseString(handler.handleSupers(
                params("of", "test.edge.Parrot")))
                .getAsJsonArray();
        var fqns = new java.util.HashSet<String>();
        for (var entry : arr) {
            fqns.add(entry.getAsJsonObject().get("fqn").getAsString());
        }
        assertTrue(fqns.contains("test.edge.AbstractPet"));
    }

    @Test
    void subtypesOfAnimalIncludesDogAndCat() {
        var arr = JsonParser.parseString(handler.handleSubtypes(
                params("of", "test.model.Animal")))
                .getAsJsonArray();
        var fqns = new java.util.HashSet<String>();
        for (var entry : arr) {
            fqns.add(entry.getAsJsonObject().get("fqn").getAsString());
        }
        assertTrue(fqns.contains("test.model.Dog"),
                "subtypes of Animal should include Dog, got: " + fqns);
        assertTrue(fqns.contains("test.model.Cat"));
    }

    @Test
    void subtypesEmptyForLeafType() {
        var arr = JsonParser.parseString(handler.handleSubtypes(
                params("of", "test.model.Cat")))
                .getAsJsonArray();
        assertEquals(0, arr.size());
    }

    // ── /overrides ──────────────────────────────────────────────────

    @Test
    void overridesReturnsAnimalNameForDogName() {
        String response = handler.handleOverrides(
                params("of", "test.model.Dog#name()"));
        JsonObject result = parse(response);
        assertEquals("method", result.get("kind").getAsString());
        assertEquals("test.model.Animal#name()",
                result.get("fqn").getAsString());
    }

    @Test
    void overridesReturnsJsonNullWhenNoOverride() {
        String response = handler.handleOverrides(
                params("of", "test.model.Dog#bark()"));
        assertEquals("null", response,
                "bark() does not override anything");
    }

    @Test
    void overridesRejectsNonMethodSubject() {
        JsonObject result = parse(handler.handleOverrides(
                params("of", "test.model.Dog")));
        assertTrue(isError(result));
        assertEquals("wrong-subject-kind",
                errorOf(result).get("kind").getAsString());
        assertEquals("method",
                errorOf(result).getAsJsonObject("context")
                .get("expected").getAsString());
    }

    // ── /overloads ──────────────────────────────────────────────────

    @Test
    void overloadsReturnsAllAddSiblings() {
        var arr = JsonParser.parseString(handler.handleOverloads(
                params("of", "test.edge.Calculator#add(int,int)")))
                .getAsJsonArray();
        assertEquals(3, arr.size(), "Calculator has 3 add overloads");
        for (var entry : arr) {
            assertEquals("method", entry.getAsJsonObject()
                    .get("kind").getAsString());
            assertEquals("test.edge.Calculator",
                    entry.getAsJsonObject().get("fqn").getAsString()
                            .substring(0, "test.edge.Calculator".length()));
        }
    }

    @Test
    void overloadsSingleEntryForUniqueMethodName() {
        var arr = JsonParser.parseString(handler.handleOverloads(
                params("of", "test.model.Dog#bark()")))
                .getAsJsonArray();
        assertEquals(1, arr.size());
        assertEquals("test.model.Dog#bark()",
                arr.get(0).getAsJsonObject().get("fqn").getAsString());
    }

    // ── /implementors ───────────────────────────────────────────────

    @Test
    void implementorsTypeModeReturnsAllSubtypes() {
        var arr = JsonParser.parseString(handler.handleImplementors(
                params("of", "test.model.Animal")))
                .getAsJsonArray();
        var fqns = new java.util.HashSet<String>();
        for (var entry : arr) {
            fqns.add(entry.getAsJsonObject().get("fqn").getAsString());
        }
        assertTrue(fqns.contains("test.model.Dog"),
                "Animal subtypes should include Dog, got: " + fqns);
        assertTrue(fqns.contains("test.model.Cat"));
        assertTrue(fqns.contains("test.edge.AbstractPet"),
                "transitive: AbstractPet implements Animal");
        assertTrue(fqns.contains("test.edge.Parrot"),
                "transitive: Parrot extends AbstractPet implements Animal");
    }

    @Test
    void implementorsMethodModeReturnsOverridingMethods() {
        var arr = JsonParser.parseString(handler.handleImplementors(
                params("of", "test.model.Animal#name()")))
                .getAsJsonArray();
        var fqns = new java.util.HashSet<String>();
        for (var entry : arr) {
            fqns.add(entry.getAsJsonObject().get("fqn").getAsString());
            assertEquals("method",
                    entry.getAsJsonObject().get("kind").getAsString());
        }
        assertTrue(fqns.contains("test.model.Dog#name()"));
        assertTrue(fqns.contains("test.model.Cat#name()"));
        assertTrue(fqns.contains("test.edge.AbstractPet#name()"));
    }

    // ── /refs?to= ───────────────────────────────────────────────────

    @Test
    void refsToFindsIncomingMethodCalls() {
        var arr = JsonParser.parseString(handler.handleRefsTo(
                params("of", "test.model.Dog#bark()"),
                ProjectScope.ALL)).getAsJsonArray();
        // bark() is called by AnimalService.createDog and CallerService.callCreateDog (transitively, but only direct here)
        assertFalse(arr.isEmpty(), "bark() has at least one caller");
        var first = arr.get(0).getAsJsonObject();
        assertEquals("reference", first.get("kind").getAsString());
        assertEquals("incoming", first.get("direction").getAsString(),
                "/refs (handleRefsTo) stamps every record with "
                + ":direction \"incoming\" so downstream renderers "
                + "know which side is the query subject");
        assertEquals("call", first.get("refKind").getAsString());
        assertEquals("test.model.Dog#bark()",
                first.getAsJsonObject("to").get("fqn").getAsString());
        assertNotNull(first.get("from"),
                "every ref must carry a :from skeleton");
        assertNotNull(first.get("location"),
                "every ref must carry a :location");
    }

    @Test
    void refsToOnTypeReturnsTypeUseRefs() {
        var arr = JsonParser.parseString(handler.handleRefsTo(
                params("of", "test.model.Animal"),
                ProjectScope.ALL)).getAsJsonArray();
        assertFalse(arr.isEmpty());
        for (var entry : arr) {
            JsonObject e = entry.getAsJsonObject();
            assertEquals("typeUse",
                    e.get("refKind").getAsString());
            assertEquals("test.model.Animal",
                    e.getAsJsonObject("to").get("fqn").getAsString());
        }
    }

    @Test
    void refsToReadsOnFieldUsesReadAccessesPattern() {
        var arr = JsonParser.parseString(handler.handleRefsTo(
                paramsMulti("of",
                        "test.edge.Outer.StaticNested#VALUE",
                        "refKind", "read"),
                ProjectScope.ALL)).getAsJsonArray();
        assertFalse(arr.isEmpty(),
                "VALUE is read in EnrichedRefService.getStaticValue");
        assertEquals("read",
                arr.get(0).getAsJsonObject().get("refKind").getAsString());
    }

    @Test
    void refsToErrorOnUnknownTarget() {
        JsonObject result = parse(handler.handleRefsTo(
                params("of", "no.such.Type"), ProjectScope.ALL));
        assertTrue(isError(result));
        assertEquals("type-not-found",
                errorOf(result).get("kind").getAsString());
    }

    private static Map<String, String> paramsMulti(String... pairs) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    // ── /types (bulk pattern search) ────────────────────────────────

    @Test
    void typesExactMatchReturnsSingleSkeleton() {
        var arr = JsonParser.parseString(handler.handleTypes(
                params("pattern", "Dog"),
                ProjectScope.ALL)).getAsJsonArray();
        // exact "Dog" — one match in fixture
        assertEquals(1, arr.size());
        assertEquals("test.model.Dog",
                arr.get(0).getAsJsonObject().get("fqn").getAsString());
        assertEquals("type",
                arr.get(0).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void typesWildcardMatchesMultiple() {
        var arr = JsonParser.parseString(handler.handleTypes(
                params("pattern", "*Service"),
                ProjectScope.ALL)).getAsJsonArray();
        assertEquals(2, Math.min(arr.size(), 2),
                "*Service should match multiple, got " + arr.size());
        for (var entry : arr) {
            String fqn = entry.getAsJsonObject().get("fqn").getAsString();
            assertTrue(fqn.endsWith("Service"),
                    "non-Service FQN matched: " + fqn);
        }
    }

    @Test
    void typesSourceOnlyExcludesBinary() {
        // Wildcard pattern matches both binary java.lang.Object and
        // the source TestFixture type test.edge.ObjectHolder. The
        // sourceOnly run must keep ObjectHolder and drop Object.
        var arrAll = JsonParser.parseString(handler.handleTypes(
                params("pattern", "*Object*"),
                ProjectScope.ALL)).getAsJsonArray();
        var arrSrc = JsonParser.parseString(handler.handleTypes(
                paramsMulti("pattern", "*Object*", "sourceOnly", ""),
                ProjectScope.ALL)).getAsJsonArray();
        boolean hasObjectAll = arrAll.asList().stream()
                .anyMatch(e -> "java.lang.Object".equals(
                        e.getAsJsonObject().get("fqn").getAsString()));
        boolean hasObjectSrc = arrSrc.asList().stream()
                .anyMatch(e -> "java.lang.Object".equals(
                        e.getAsJsonObject().get("fqn").getAsString()));
        boolean hasObjectHolderSrc = arrSrc.asList().stream()
                .anyMatch(e -> "test.edge.ObjectHolder".equals(
                        e.getAsJsonObject().get("fqn").getAsString()));
        assertTrue(hasObjectAll,
                "java.lang.Object must appear in unrestricted search");
        assertFalse(hasObjectSrc,
                "java.lang.Object must NOT appear with sourceOnly");
        assertTrue(hasObjectHolderSrc,
                "source type ObjectHolder must appear with sourceOnly");
    }

    @Test
    void typesMissingPatternReturnsError() {
        JsonObject result = parse(handler.handleTypes(
                Map.of(), ProjectScope.ALL));
        assertTrue(isError(result));
        assertEquals("missing-parameter",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void typesEmptyResultForUnknownPattern() {
        var arr = JsonParser.parseString(handler.handleTypes(
                params("pattern", "DefinitelyNonExistentTypeNameXYZ"),
                ProjectScope.ALL)).getAsJsonArray();
        assertEquals(0, arr.size());
    }

    // ── Workspace navigation ────────────────────────────────────────

    @Test
    void projectsListsFixtureProject() {
        var arr = JsonParser.parseString(handler.handleProjects(
                ProjectScope.ALL)).getAsJsonArray();
        boolean found = arr.asList().stream().anyMatch(e ->
                "jdtbridge-test".equals(e.getAsJsonObject()
                        .get("fqn").getAsString()));
        assertTrue(found, "fixture project must be in /projects");
    }

    // ── Outgoing references ────────────────────────────────────────

    @Test
    void outgoingRefsFromMethodLists_callsReadsAndTypeUses() {
        var arr = JsonParser.parseString(handler.handleOutgoingRefs(
                params("of",
                    "test.service.EnrichedRefService#getAnimalName(test.model.Animal)"),
                ProjectScope.ALL)).getAsJsonArray();

        assertFalse(arr.isEmpty(),
                "getAnimalName body calls at least one method, "
                + "references the Animal type — @outgoingRefs must "
                + "produce non-empty ref records");

        var refKinds = arr.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .map(n -> n.get("refKind").getAsString())
                .distinct().toList();
        assertTrue(refKinds.contains("call"),
                "expected at least one :call ref in outgoing; got "
                + refKinds);

        var calleeFqns = arr.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(n -> "call".equals(n.get("refKind").getAsString()))
                .map(n -> n.getAsJsonObject("to")
                        .get("fqn").getAsString())
                .toList();
        assertTrue(calleeFqns.contains("test.model.Animal#name()"),
                "Animal#name() is invoked inside the body; callee "
                + "fqn should land in /to/fqn; got " + calleeFqns);
    }

    @Test
    void outgoingRefsFromEveryNodeCarriesFromSubject() {
        var arr = JsonParser.parseString(handler.handleOutgoingRefs(
                params("of",
                    "test.service.EnrichedRefService#getAnimalName(test.model.Animal)"),
                ProjectScope.ALL)).getAsJsonArray();
        for (var node : arr) {
            var obj = node.getAsJsonObject();
            assertEquals("reference",
                    obj.get("kind").getAsString());
            assertEquals("outgoing",
                    obj.get("direction").getAsString(),
                    "@outgoingRefs stamps every record with "
                    + ":direction \"outgoing\" — symmetric with "
                    + "@incomingRefs \"incoming\"");
            var from = obj.getAsJsonObject("from");
            assertNotNull(from,
                    "every outgoing ref carries :from pointing back "
                    + "at the subject (the member whose body was "
                    + "scanned)");
            assertEquals(
                    "test.service.EnrichedRefService#getAnimalName(test.model.Animal)",
                    from.get("fqn").getAsString());
            assertNotNull(obj.getAsJsonObject("to"),
                    ":to carries the target skeleton — "
                    + "symmetric with incoming /refs");
        }
    }

    @Test
    void outgoingRefsErrorsOnUnknownTarget() {
        JsonObject result = parse(handler.handleOutgoingRefs(
                params("of", "no.such.Type#method()"),
                ProjectScope.ALL));
        assertTrue(isError(result),
                "unresolved target must surface as structured "
                + "error on the fail-track; got " + result);
    }

    // ── Annotations ─────────────────────────────────────────────────

    @Test
    void typeSkeletonCarriesFqnAnnotations() {
        JsonObject result = parse(handler.handleType(
                params("of", "test.service.EnrichedRefService")));
        var annotations = result.getAsJsonArray("annotations");
        assertNotNull(annotations,
                "type detail must carry :annotations resolved to FQN");
        assertTrue(annotations.asList().stream()
                .map(JsonElement::getAsString)
                .anyMatch("test.edge.Marker"::equals),
                "simple @Marker source reference promotes to "
                + "FQN test.edge.Marker via enclosing imports; "
                + "got " + annotations);
    }

    @Test
    void methodSkeletonCarriesTestAnnotation() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.edge.SimpleTest#onePlusOne()")));
        var annotations = result.getAsJsonArray("annotations");
        assertNotNull(annotations,
                "method detail must carry :annotations");
        assertTrue(annotations.asList().stream()
                .map(JsonElement::getAsString)
                .anyMatch(
                    "org.junit.jupiter.api.Test"::equals),
                "@Test on a JUnit 5 method resolves via the "
                + "compilation unit's import to FQN "
                + "org.junit.jupiter.api.Test; got " + annotations);
    }

    @Test
    void methodsAxisCarriesAnnotationsOnSkeletons() {
        var arr = JsonParser.parseString(handler.handleMethods(
                params("of", "test.edge.SimpleTest"))).getAsJsonArray();
        JsonObject testMethod = arr.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(m -> m.get("fqn").getAsString()
                        .endsWith("#onePlusOne()"))
                .findFirst().orElseThrow();
        var annotations = testMethod.getAsJsonArray("annotations");
        assertNotNull(annotations,
                ":annotations must appear on the method skeleton "
                + "returned by @methods so `@methods | filter("
                + "/annotations | any(eq(\"org.junit.jupiter.api.Test\")))` "
                + "runs without an @detail roundtrip per method");
        assertTrue(annotations.asList().stream()
                .map(JsonElement::getAsString)
                .anyMatch("org.junit.jupiter.api.Test"::equals));
    }

    @Test
    void typeCarryingTestAnnotatedMethodIsTestScope() {
        JsonObject result = parse(handler.handleType(
                params("of", "test.edge.SimpleTest")));
        assertTrue(result.get("isTestScope").getAsBoolean(),
                "SimpleTest hosts @Test-annotated methods; "
                + "annotation fallback tags the enclosing type as "
                + "test-scope even without an M2E src/test/java "
                + "classpath attribute");
    }

    @Test
    void productionTypeIsNotTestScope() {
        JsonObject result = parse(handler.handleType(
                params("of", "test.model.Dog")));
        assertFalse(result.get("isTestScope").getAsBoolean(),
                "Dog carries no test annotations on its methods "
                + "and sits under a production source root");
    }

    @Test
    void methodInheritsTestScopeFromDeclaringType() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.edge.SimpleTest#onePlusOne()")));
        assertTrue(result.get("isTestScope").getAsBoolean(),
                "a method resolves :isTestScope via its declaring "
                + "type so `filter(@containingType | /isTestScope)` "
                + "is unnecessary — the field is on the method "
                + "skeleton directly");
    }

    @Test
    void annotationsFieldIsEmptyArrayWhenNoDeclarations() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.model.Dog#bark()")));
        var annotations = result.getAsJsonArray("annotations");
        assertNotNull(annotations,
                ":annotations is always present (empty array for "
                + "un-annotated members) so filter predicates like "
                + "`filter(/annotations | any(eq(...)))` run against "
                + "every element without tripping on null");
        assertEquals(0, annotations.size());
    }

    @Test
    void projectSkeletonCarriesNatures() {
        var arr = JsonParser.parseString(handler.handleProjects(
                ProjectScope.ALL)).getAsJsonArray();
        JsonObject fixture = arr.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(e -> "jdtbridge-test".equals(
                        e.get("fqn").getAsString()))
                .findFirst().orElseThrow();
        var natures = fixture.getAsJsonArray("natures");
        assertNotNull(natures,
                ":natures must be on the project skeleton so "
                + "`@projects | filter(/natures | any(eq(\"java\")))` "
                + "avoids an N+1 @detail roundtrip");
        boolean hasJava = natures.asList().stream()
                .map(JsonElement::getAsString)
                .anyMatch("java"::equals);
        assertTrue(hasJava,
                "fixture project has the Java nature");
    }

    @Test
    void projectDetailCarriesNaturesAndDependencies() {
        JsonObject result = parse(handler.handleProject(
                params("of", "jdtbridge-test")));
        assertEquals("jdtbridge-test",
                result.get("fqn").getAsString());
        assertEquals("project", result.get("kind").getAsString());
        assertNotNull(result.get("rootPath"),
                "project detail carries :rootPath");
        assertNotNull(result.getAsJsonArray("natures"));
        assertNotNull(result.getAsJsonArray("classpathEntries"));
        assertNotNull(result.getAsJsonArray("dependencies"));
        assertNotNull(result.getAsJsonArray("sourceRoots"));
    }

    @Test
    void projectErrorForUnknownName() {
        JsonObject result = parse(handler.handleProject(
                params("of", "no-such-project")));
        assertTrue(isError(result));
        assertEquals("project-not-found",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void classpathReturnsAllEntries() {
        var arr = JsonParser.parseString(handler.handleClasspath(
                params("of", "jdtbridge-test"))).getAsJsonArray();
        assertEquals(3, Math.min(arr.size(), 3),
                "fixture project has at least src + JRE + JUnit");
        for (var entry : arr) {
            JsonObject e = entry.getAsJsonObject();
            assertEquals("classpathEntry", e.get("kind").getAsString());
            assertNotNull(e.get("entryKind"));
            assertNotNull(e.get("path"));
        }
    }

    @Test
    void classpathResolvedExpandsContainers() {
        // getResolvedClasspath(true) flattens JRE_CONTAINER / M2E
        // into their constituent library entries; container/variable
        // kinds must not reach the client.
        var arr = JsonParser.parseString(handler.handleClasspath(
                params("of", "jdtbridge-test"))).getAsJsonArray();
        for (var entry : arr) {
            String entryKind = entry.getAsJsonObject()
                    .get("entryKind").getAsString();
            assertTrue(java.util.Set.of("source", "library", "project")
                            .contains(entryKind),
                    "resolved classpath must only emit "
                    + "source/library/project; got " + entryKind);
        }
    }

    @Test
    void classpathEntryOriginMatchesKind() {
        var arr = JsonParser.parseString(handler.handleClasspath(
                params("of", "jdtbridge-test"))).getAsJsonArray();
        for (var entry : arr) {
            JsonObject e = entry.getAsJsonObject();
            String entryKind = e.get("entryKind").getAsString();
            String origin = e.get("origin").getAsString();
            String expected = "source".equals(entryKind)
                    ? "source" : "binary";
            assertEquals(expected, origin,
                    "entryKind=" + entryKind
                    + " must carry origin=" + expected);
        }
    }

    @Test
    void classpathPathsAreAbsoluteFilesystemPaths() {
        // No workspace-relative leaks. Every :path must be an
        // absolute OS filesystem path — File.isAbsolute() verifies
        // irrespective of path separator.
        var arr = JsonParser.parseString(handler.handleClasspath(
                params("of", "jdtbridge-test"))).getAsJsonArray();
        for (var entry : arr) {
            String path = entry.getAsJsonObject().get("path").getAsString();
            assertTrue(new java.io.File(path).isAbsolute(),
                    ":path must be absolute, got " + path);
        }
    }

    @Test
    void classpathSourceEntryHasAbsoluteOutputLocation() {
        var arr = JsonParser.parseString(handler.handleClasspath(
                params("of", "jdtbridge-test"))).getAsJsonArray();
        boolean sawSource = false;
        for (var entry : arr) {
            JsonObject e = entry.getAsJsonObject();
            if (!"source".equals(e.get("entryKind").getAsString())) continue;
            sawSource = true;
            // Source entries may OR may not carry their own
            // outputLocation override; if present it must be absolute.
            var outputLoc = e.get("outputLocation");
            if (outputLoc != null && !outputLoc.isJsonNull()) {
                String raw = outputLoc.getAsString();
                assertTrue(new java.io.File(raw).isAbsolute(),
                        ":outputLocation must be absolute, got " + raw);
            }
        }
        assertTrue(sawSource,
                "fixture project expected to have at least one "
                + "source entry");
    }

    @Test
    void packageReturnsDetailWithTypeCount() {
        JsonObject result = parse(handler.handlePackage(
                params("of", "test.model")));
        assertEquals("test.model", result.get("fqn").getAsString());
        assertEquals("package", result.get("kind").getAsString());
        assertEquals(3, result.get("typeCount").getAsInt(),
                "test.model has Animal + Dog + Cat");
    }

    @Test
    void packageErrorForUnknownName() {
        JsonObject result = parse(handler.handlePackage(
                params("of", "no.such.pkg")));
        assertTrue(isError(result));
        assertEquals("package-not-found",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void typesInPackageListsAllTypesInTestModel() {
        var arr = JsonParser.parseString(handler.handleTypesInPackage(
                params("of", "test.model"))).getAsJsonArray();
        var fqns = new java.util.HashSet<String>();
        for (var entry : arr) {
            fqns.add(entry.getAsJsonObject().get("fqn").getAsString());
        }
        assertEquals(3, arr.size());
        assertTrue(fqns.contains("test.model.Animal"));
        assertTrue(fqns.contains("test.model.Dog"));
        assertTrue(fqns.contains("test.model.Cat"));
    }

    @Test
    void typesInPackageEmptyForUnknownPackage() {
        var arr = JsonParser.parseString(handler.handleTypesInPackage(
                params("of", "no.such.pkg"))).getAsJsonArray();
        assertEquals(0, arr.size());
    }

    @Test
    void packagesInProjectListsAllPackages() {
        var arr = JsonParser.parseString(
                handler.handlePackagesInProject(
                        params("of", "jdtbridge-test")))
                .getAsJsonArray();
        var names = new java.util.HashSet<String>();
        for (var entry : arr) {
            names.add(entry.getAsJsonObject().get("fqn").getAsString());
        }
        assertTrue(names.contains("test.model"));
        assertTrue(names.contains("test.service"));
        assertTrue(names.contains("test.edge"));
        assertTrue(names.contains("test.refactor"));
    }

    // ── /file ────────────────────────────────────────────────────────

    @Test
    void fileReturnsDetailForAbsolutePath() throws Exception {
        var root = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot();
        var file = root.getProject("jdtbridge-test")
                .getFile("src/test/model/Dog.java");
        String absPath = file.getLocation().toOSString();

        JsonObject result = parse(handler.handleFile(
                params("of", absPath)));
        assertEquals("file", result.get("kind").getAsString());
        assertEquals("java", result.get("language").getAsString());
        assertEquals("jdtbridge-test",
                result.get("containingProject").getAsString());
    }

    @Test
    void fileErrorForUnknownPath() {
        JsonObject result = parse(handler.handleFile(
                params("of", "D:/no/such/file.java")));
        assertTrue(isError(result));
        assertEquals("file-not-found",
                errorOf(result).get("kind").getAsString());
    }

    // ── /typesInFile ────────────────────────────────────────────────

    @Test
    void typesInFileListsTypesFromAbsolutePath() throws Exception {
        var root = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot();
        var file = root.getProject("jdtbridge-test")
                .getFile("src/test/model/Dog.java");
        String absPath = file.getLocation().toOSString();

        var arr = JsonParser.parseString(handler.handleTypesInFile(
                params("of", absPath))).getAsJsonArray();
        assertEquals(1, arr.size(), "Dog.java has one top-level type");
        assertEquals("test.model.Dog",
                arr.get(0).getAsJsonObject().get("fqn").getAsString());
    }

    @Test
    void typesInFileErrorForUnknownPath() {
        JsonObject result = parse(handler.handleTypesInFile(
                params("of", "D:/no/such/file.java")));
        assertTrue(isError(result));
        assertEquals("file-not-found",
                errorOf(result).get("kind").getAsString());
    }

    // ── /source ──────────────────────────────────────────────────────

    @Test
    void sourceReturnsRawTextString() {
        String response = handler.handleSource(
                params("of", "test.model.Dog"));
        // Response is a JSON-quoted string, not an object
        String text = JsonParser.parseString(response).getAsString();
        assertTrue(text.contains("class Dog"),
                "source text must contain 'class Dog'");
    }

    @Test
    void sourceForMethodReturnsMethodBody() {
        String response = handler.handleSource(
                params("of", "test.model.Dog#bark()"));
        String text = JsonParser.parseString(response).getAsString();
        assertTrue(text.contains("bark"),
                "method source must contain 'bark'");
    }

    @Test
    void sourceErrorForUnknownMember() {
        JsonObject result = parse(handler.handleSource(
                params("of", "no.such.Type")));
        assertTrue(isError(result));
    }

    // ── /problems ───────────────────────────────────────────────────

    @Test
    void problemsReturnsCompilationErrors() {
        // test.broken.BrokenClass has an intentional compile error
        var arr = JsonParser.parseString(handler.handleProblems(
                params("of", "jdtbridge-test"),
                ProjectScope.ALL)).getAsJsonArray();
        assertFalse(arr.isEmpty(),
                "fixture has BrokenClass with compile error");
        var first = arr.get(0).getAsJsonObject();
        assertEquals("problem", first.get("kind").getAsString());
        assertEquals("error", first.get("severity").getAsString());
        assertNotNull(first.get("message"));
        assertNotNull(first.get("location"));
    }

    @Test
    void problemsSeverityFilterAcceptsWarnings() {
        // The filter was previously severity >= SEVERITY_ERROR which
        // silently dropped every warning and made the "warning" label
        // dead code. Filter is now severity >= SEVERITY_WARNING.
        var arr = JsonParser.parseString(handler.handleProblems(
                params("of", "jdtbridge-test"),
                ProjectScope.ALL)).getAsJsonArray();
        for (var entry : arr) {
            String sev = entry.getAsJsonObject()
                    .get("severity").getAsString();
            assertTrue(java.util.Set.of("error", "warning").contains(sev),
                    ":severity must be error or warning, got " + sev);
        }
    }

    @Test
    void problemsFileScopeRejectsWorkspaceRelativePath() {
        // /jdtbridge-test/src/... is workspace-relative — not a real
        // filesystem path. The handler must fail with file-not-found
        // rather than silently matching through findMember.
        // (The of=-param resolver sees the leading slash, classifies
        // as path-shape, and calls getFileForLocation on the IPath —
        // workspace-relative paths do not resolve there.)
        JsonObject result = parse(handler.handleProblems(
                params("of", "/jdtbridge-test/src/test/broken/BrokenClass.java"),
                ProjectScope.ALL));
        assertTrue(isError(result),
                "workspace-relative paths must not be accepted as :file");
        assertEquals("file-not-found",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void problemsOfUnknownFqnReturnsProjectNotFound() {
        // Non-path fqn that isn't a project name → primitive resolves
        // as IProject → getProject("no-such-project").exists() == false.
        JsonObject result = parse(handler.handleProblems(
                params("of", "no-such-project-xyz"),
                ProjectScope.ALL));
        assertTrue(isError(result));
        assertEquals("project-not-found",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void problemsNoOfParamReturnsWorkspace() {
        // Empty params → workspace scope (root.findMarkers).
        // Always a JSON array (possibly empty), never an error.
        var arr = JsonParser.parseString(handler.handleProblems(
                new java.util.HashMap<>(),
                ProjectScope.ALL)).getAsJsonArray();
        // The fixture has BrokenClass so workspace scope must
        // contain at least one marker.
        assertFalse(arr.isEmpty(),
                "workspace scope sees the fixture's BrokenClass");
    }

    @Test
    void problemsOfAbsoluteFilePathNarrowsToFile() throws Exception {
        // Compute the actual host path to BrokenClass.java and pass
        // it as of=<absolute-path>. Scope must narrow to that file —
        // the fixture has exactly one compile error in BrokenClass,
        // zero in the other test files.
        var root = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot();
        var brokenFile = root.getProject("jdtbridge-test")
                .getFile("src/test/broken/BrokenClass.java");
        String absPath = brokenFile.getLocation().toOSString();

        var arr = JsonParser.parseString(handler.handleProblems(
                params("of", absPath),
                ProjectScope.ALL)).getAsJsonArray();
        assertEquals(1, arr.size(),
                "file scope returns exactly BrokenClass's one error");
        assertEquals("error",
                arr.get(0).getAsJsonObject()
                    .get("severity").getAsString());
    }

    @Test
    void refsToRejectsUnknownRefKind() {
        // Unknown refKind was silently passed through, producing a
        // ref vec labelled with the bogus kind. Handler now rejects.
        JsonObject result = parse(handler.handleRefsTo(
                paramsMulti("of", "test.model.Dog#bark()",
                            "refKind", "bogus"),
                ProjectScope.ALL));
        assertTrue(isError(result),
                "unrecognised refKind must fail-track, not run search");
        JsonObject err = errorOf(result);
        assertEquals("invalid-modifier",
                err.get("kind").getAsString());
        JsonObject ctx = err.getAsJsonObject("context");
        assertEquals("refKind",
                ctx.get("parameter").getAsString());
        assertEquals("bogus",
                ctx.get("value").getAsString());
        var allowed = ctx.getAsJsonArray("allowed");
        assertNotNull(allowed, "error carries :allowed set");
        var allowedStrings = new java.util.HashSet<String>();
        for (var a : allowed) allowedStrings.add(a.getAsString());
        assertTrue(allowedStrings.containsAll(
                java.util.Set.of("all", "call", "read", "write", "typeUse")));
    }

    @Test
    void refsToAcceptsAllRecognisedRefKinds() {
        // Successful responses are JSON arrays; the invalid-modifier
        // guard returns an object with _error. A simple substring
        // check avoids tripping the Array/Object shape mismatch.
        for (String rk : new String[] {"all", "call", "typeUse"}) {
            String raw = handler.handleRefsTo(
                    paramsMulti("of", "test.model.Dog",
                                "refKind", rk),
                    ProjectScope.ALL);
            assertFalse(raw.contains("\"invalid-modifier\""),
                    "recognised refKind '" + rk
                    + "' must not trip invalid-modifier; got " + raw);
        }
    }

    // ── Composite FQN resolution (lambda / anonymous) ───────────

    @Test
    void typeResolvesAnonymousComposite() {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}";
        JsonObject result = parse(
                handler.handleType(params("of", fqn)));
        assertFalse(isError(result),
                "should resolve anonymous type: " + result);
        assertEquals("type", result.get("kind").getAsString());
    }

    @Test
    void methodResolvesAnonymousComposite() {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}#name()";
        JsonObject result = parse(
                handler.handleMethod(params("of", fqn)));
        assertFalse(isError(result),
                "should resolve anonymous method: " + result);
        assertEquals("method", result.get("kind").getAsString());
        assertEquals("name", result.get("name").getAsString());
    }

    @Test
    void typeResolvesLambdaComposite() {
        String fqn = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable";
        JsonObject result = parse(
                handler.handleType(params("of", fqn)));
        assertFalse(isError(result),
                "should resolve lambda type: " + result);
        assertEquals("type", result.get("kind").getAsString());
    }

    @Test
    void typeErrorForUnknownComposite() {
        String fqn = "test.service.AnonymousCallerService"
                + "#noSuchMethod().new Animal() {...}";
        JsonObject result = parse(
                handler.handleType(params("of", fqn)));
        assertTrue(isError(result),
                "should error for unknown composite: " + result);
    }

    @Test
    void sourceResolvesAnonymousComposite() {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}";
        String json = handler.handleSource(params("of", fqn));
        assertFalse(json.contains("_error"),
                "source should resolve anonymous: " + json);
        assertTrue(json.contains("Anonymous"),
                "source should contain method body: " + json);
    }

    // ── Missing parameter errors for workspace handlers ───────────

    @Test
    void projectErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handleProject(Map.of()));
        assertTrue(isError(result));
        assertEquals("missing-parameter",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void classpathErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handleClasspath(Map.of()));
        assertTrue(isError(result));
        assertEquals("missing-parameter",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void classpathErrorForNonJavaProject() throws Exception {
        TestFixture.createNonJavaProject();
        try {
            JsonObject result = parse(
                    handler.handleClasspath(
                            params("of", TestFixture.NON_JAVA_PROJECT_NAME)));
            assertTrue(isError(result));
            assertTrue(errorOf(result).toString().contains("not a Java"),
                    "should say not a Java project: " + result);
        } finally {
            TestFixture.destroy();
            TestFixture.create();
        }
    }

    @Test
    void packageErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handlePackage(Map.of()));
        assertTrue(isError(result));
    }

    @Test
    void fileErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handleFile(Map.of()));
        assertTrue(isError(result));
    }

    @Test
    void typesInFileErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handleTypesInFile(Map.of()));
        assertTrue(isError(result));
    }

    @Test
    void packagesInProjectErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handlePackagesInProject(Map.of()));
        assertTrue(isError(result));
    }

    @Test
    void typesInPackageErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handleTypesInPackage(Map.of()));
        assertTrue(isError(result));
    }

    // ── Additional validation paths ────────────────────────────────

    @Test
    void refsToWriteOnFieldUsesWriteAccessesPattern() {
        var arr = JsonParser.parseString(handler.handleRefsTo(
                paramsMulti("of",
                        "test.edge.Outer.StaticNested#VALUE",
                        "refKind", "write"),
                ProjectScope.ALL)).getAsJsonArray();
        // VALUE is final — write accesses may be empty or only
        // at the initializer. The important thing is it doesn't
        // error and uses the WRITE_ACCESSES pattern.
        assertNotNull(arr);
    }

    @Test
    void refsToMissingOfReturnsError() {
        JsonObject result = parse(handler.handleRefsTo(
                Map.of(), ProjectScope.ALL));
        assertTrue(isError(result));
    }

    @Test
    void packagesInProjectUnknownProjectReturnsError() {
        JsonObject result = parse(
                handler.handlePackagesInProject(
                        params("of", "no-such-project-xyz")));
        assertTrue(isError(result));
        assertEquals("project-not-found",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void sourceErrorWhenOfMissing() {
        JsonObject result = parse(
                handler.handleSource(Map.of()));
        assertTrue(isError(result));
    }

    @Test
    void outgoingRefsErrorWhenOfMissing() {
        JsonObject result = parse(handler.handleOutgoingRefs(
                Map.of(), ProjectScope.ALL));
        assertTrue(isError(result));
    }

    @Test
    void outgoingRefsErrorOnUnknownTarget() {
        JsonObject result = parse(handler.handleOutgoingRefs(
                params("of", "no.such.Type"),
                ProjectScope.ALL));
        assertTrue(isError(result));
    }

    @Test
    void typesInFileForNonJavaReturnsError() throws Exception {
        // Use a file that exists but isn't a Java source
        String pomPath = "D:/git/eclipse-jdt-search/pom.xml";
        JsonObject result = parse(
                handler.handleTypesInFile(params("of", pomPath)));
        assertTrue(isError(result));
    }

    // ── Cross-cutting: every error carries origin :jdt/plugin ───────

    @Test
    void everyErrorPathsCarryOriginJdtPlugin() {
        JsonElement[] errors = {
            JsonParser.parseString(
                handler.handleType(Map.of())),
            JsonParser.parseString(
                handler.handleType(params("of", "no.such"))),
            JsonParser.parseString(
                handler.handleMethod(Map.of())),
            JsonParser.parseString(
                handler.handleMethod(params("of", "no#hash()"))),
            JsonParser.parseString(
                handler.handleField(Map.of())),
            JsonParser.parseString(
                handler.handleField(params("of", "no.such#x"))),
        };
        for (var je : errors) {
            JsonObject obj = je.getAsJsonObject();
            assertTrue(isError(obj), "expected error: " + obj);
            assertEquals("jdt/plugin",
                    errorOf(obj).get("origin").getAsString());
        }
    }
}
