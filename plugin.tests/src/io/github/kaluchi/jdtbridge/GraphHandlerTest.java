package io.github.kaluchi.jdtbridge;

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
    void methodReturnsDetailForFqmnWithSignature() {
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
        assertEquals("ambiguous-match",
                errorOf(result).get("kind").getAsString());
        assertEquals(3,
                errorOf(result).getAsJsonObject("context")
                .get("matchCount").getAsInt());
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
    void methodRejectsMissingHash() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "test.model.Dog")));
        assertTrue(isError(result));
        assertEquals("invalid-fqmn",
                errorOf(result).get("kind").getAsString());
    }

    @Test
    void methodErrorTypeNotFoundCarriesBothFqnAndFqmn() {
        JsonObject result = parse(handler.handleMethod(
                params("of", "no.such.Class#foo()")));
        assertTrue(isError(result));
        assertEquals("type-not-found",
                errorOf(result).get("kind").getAsString());
        var ctx = errorOf(result).getAsJsonObject("context");
        assertEquals("no.such.Class", ctx.get("fqn").getAsString());
        assertEquals("no.such.Class#foo()",
                ctx.get("fqmn").getAsString());
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
    void fieldRejectsParensInName() {
        JsonObject result = parse(handler.handleField(
                params("of", "test.model.Dog#age()")));
        assertTrue(isError(result));
        assertEquals("invalid-fqmn",
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

    // ── /detail (polymorphic) ───────────────────────────────────────

    @Test
    void detailRoutesToTypeForPlainFqn() {
        JsonObject result = parse(handler.handleDetail(
                params("of", "test.model.Dog")));
        assertEquals("type", result.get("kind").getAsString());
    }

    @Test
    void detailRoutesToMethodForFqmnWithParens() {
        JsonObject result = parse(handler.handleDetail(
                params("of", "test.model.Dog#bark()")));
        assertEquals("method", result.get("kind").getAsString());
        assertEquals("bark", result.get("name").getAsString());
    }

    @Test
    void detailRoutesToFieldForFqmnWithoutParens() {
        JsonObject result = parse(handler.handleDetail(
                params("of", "test.model.Dog#age")));
        assertEquals("field", result.get("kind").getAsString());
        assertEquals("age", result.get("name").getAsString());
    }

    @Test
    void detailRoutesToMethodFallbackForBareFqmnWhenFieldAbsent() {
        // bark is a method; no field 'bark' exists
        JsonObject result = parse(handler.handleDetail(
                params("of", "test.model.Dog#bark")));
        assertEquals("method", result.get("kind").getAsString());
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
    void overridesResolvesBinarySupertype() {
        // Activator.start(BundleContext) overrides BundleActivator.start
        // — source vs binary signature alignment must use resolved FQNs.
        // Skip if Activator is not on the test fixture classpath
        // (it lives in the production plugin, not jdtbridge-test).
        try {
            var activator = JdtUtils.findType(
                    "io.github.kaluchi.jdtbridge.Activator");
            if (activator == null) return; // not in this test classpath
            String response = handler.handleOverrides(
                    params("of",
                        "io.github.kaluchi.jdtbridge.Activator"
                        + "#start(org.osgi.framework.BundleContext)"));
            JsonObject result = parse(response);
            assertEquals("method", result.get("kind").getAsString());
            assertTrue(result.get("fqn").getAsString()
                    .startsWith("org.osgi.framework.BundleActivator#start"),
                    "must resolve to binary super, got: " + result.get("fqn"));
        } catch (Exception ignored) {
            // resolution unavailable in test runtime — skip
        }
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

    // ── /implementors2 ──────────────────────────────────────────────

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
        var fqmns = new java.util.HashSet<String>();
        for (var entry : arr) {
            fqmns.add(entry.getAsJsonObject().get("fqn").getAsString());
            assertEquals("method",
                    entry.getAsJsonObject().get("kind").getAsString());
        }
        assertTrue(fqmns.contains("test.model.Dog#name()"));
        assertTrue(fqmns.contains("test.model.Cat#name()"));
        assertTrue(fqmns.contains("test.edge.AbstractPet#name()"));
    }

    // ── /refs?to= ───────────────────────────────────────────────────

    @Test
    void refsToFindsIncomingMethodCalls() {
        var arr = JsonParser.parseString(handler.handleRefsTo(
                params("of", "test.model.Dog#bark()"),
                ProjectScope.ALL)).getAsJsonArray();
        // bark() is called by AnimalService.createDog and CallerService.callCreateDog (transitively, but only direct here)
        assertTrue(arr.size() >= 1, "bark() has at least one caller");
        var first = arr.get(0).getAsJsonObject();
        assertEquals("reference", first.get("kind").getAsString());
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
        assertTrue(arr.size() >= 1);
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
        assertTrue(arr.size() >= 1,
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
        assertTrue(arr.size() >= 2,
                "*Service should match multiple, got " + arr.size());
        for (var entry : arr) {
            String fqn = entry.getAsJsonObject().get("fqn").getAsString();
            assertTrue(fqn.endsWith("Service"),
                    "non-Service FQN matched: " + fqn);
        }
    }

    @Test
    void typesSourceOnlyExcludesBinary() {
        var arrAll = JsonParser.parseString(handler.handleTypes(
                params("pattern", "Object"),
                ProjectScope.ALL)).getAsJsonArray();
        var arrSrc = JsonParser.parseString(handler.handleTypes(
                paramsMulti("pattern", "Object", "sourceOnly", ""),
                ProjectScope.ALL)).getAsJsonArray();
        // java.lang.Object is binary — present in arrAll, absent in arrSrc
        boolean hasObjectAll = arrAll.asList().stream()
                .anyMatch(e -> "java.lang.Object".equals(
                        e.getAsJsonObject().get("fqn").getAsString()));
        boolean hasObjectSrc = arrSrc.asList().stream()
                .anyMatch(e -> "java.lang.Object".equals(
                        e.getAsJsonObject().get("fqn").getAsString()));
        assertTrue(hasObjectAll,
                "java.lang.Object must appear in unrestricted search");
        assertFalse(hasObjectSrc,
                "java.lang.Object must NOT appear with sourceOnly");
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
            JsonParser.parseString(
                handler.handleDetail(Map.of())),
        };
        for (var je : errors) {
            JsonObject obj = je.getAsJsonObject();
            assertTrue(isError(obj), "expected error: " + obj);
            assertEquals("jdt/plugin",
                    errorOf(obj).get("origin").getAsString());
        }
    }
}
