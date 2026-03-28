package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

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
 * Generics erasure contract: all FQMNs in source output must be
 * generics-free. {@code Map<String,String>} → {@code Map}.
 * This ensures Zero-Modification Navigation (principle #3).
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class GenericErasureTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- Helpers ----

    static JsonObject sourceJson(String typeFqn,
            String methodName) throws Exception {
        var handler = new SearchHandler();
        String body = handler.handleSource(
                Map.of("class", typeFqn,
                        "method", methodName)).body();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    static JsonArray refs(JsonObject json) {
        return json.has("refs") ? json.getAsJsonArray("refs")
                : new JsonArray();
    }

    static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }

    static void assertNoGenericsInFqmns(JsonObject json,
            String context) {
        for (JsonElement e : refs(json)) {
            JsonObject ref = e.getAsJsonObject();
            String fqmn = str(ref, "fqmn");
            assertNotNull(fqmn, "ref missing fqmn: " + ref);
            assertFalse(fqmn.contains("<"),
                    context + " — FQMN contains generics: "
                    + fqmn);
        }
    }

    // ---- compactSignature ----

    @Nested
    class CompactSignature {

        @Test
        void stripsListGeneric() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.GenericCallerService");
            assertNotNull(type);
            IMethod m = JdtUtils.findMethod(
                    type, "saveItems", null);
            assertNotNull(m);
            String sig = JdtUtils.compactSignature(m);
            assertEquals("saveItems(List)", sig);
        }

        @Test
        void stripsMapGenericInReturnType() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.GenericCallerService");
            assertNotNull(type);
            IMethod m = JdtUtils.findMethod(
                    type, "lookup", null);
            assertNotNull(m);
            String sig = JdtUtils.compactSignature(m);
            // String[] stays as String[] — no generics
            assertEquals("lookup(String[])", sig);
        }

        @Test
        void repositorySaveListErased() throws Exception {
            IType type = JdtUtils.findType(
                    "test.edge.Repository");
            assertNotNull(type);
            // save(List<String>) should become save(List)
            for (IMethod m : type.getMethods()) {
                String sig = JdtUtils.compactSignature(m);
                assertFalse(sig.contains("<"),
                        "compactSignature should strip generics: "
                        + sig);
            }
        }

        @Test
        void repositoryFindByIdsErased() throws Exception {
            IType type = JdtUtils.findType(
                    "test.edge.Repository");
            assertNotNull(type);
            IMethod m = JdtUtils.findMethod(
                    type, "findByIds", null);
            assertNotNull(m);
            String sig = JdtUtils.compactSignature(m);
            assertEquals("findByIds(String[])", sig);
        }
    }

    // ---- Outgoing refs ----

    @Nested
    class OutgoingRefs {

        @Test
        void saveItemsRefsHaveNoGenerics() throws Exception {
            var json = sourceJson(
                    "test.service.GenericCallerService",
                    "saveItems");
            assertNoGenericsInFqmns(json, "saveItems outgoing");
        }

        @Test
        void lookupRefsHaveNoGenerics() throws Exception {
            var json = sourceJson(
                    "test.service.GenericCallerService",
                    "lookup");
            assertNoGenericsInFqmns(json, "lookup outgoing");
        }

        @Test
        void repositorySaveRefIsErased() throws Exception {
            var json = sourceJson(
                    "test.service.GenericCallerService",
                    "saveItems");
            boolean found = false;
            for (JsonElement e : refs(json)) {
                JsonObject ref = e.getAsJsonObject();
                String fqmn = str(ref, "fqmn");
                if (fqmn != null
                        && fqmn.contains("Repository#save")) {
                    assertEquals(
                            "test.edge.Repository#save(List)",
                            fqmn,
                            "Param should be erased to List");
                    found = true;
                }
            }
            assertTrue(found, "Should find Repository#save ref");
        }
    }

    // ---- Incoming refs ----

    @Nested
    class IncomingRefs {

        @Test
        void incomingRefsHaveNoGenerics() throws Exception {
            // Repository.save(List<String>) is called by
            // GenericCallerService.saveItems(List<String>)
            // The incoming ref FQMN must be generics-free.
            // Repository.save has overloads → server returns
            // JSON array. Use findByIds (no overloads) instead.
            var json = sourceJson(
                    "test.edge.Repository", "findByIds");
            for (JsonElement e : refs(json)) {
                JsonObject ref = e.getAsJsonObject();
                String fqmn = str(ref, "fqmn");
                assertNotNull(fqmn);
                assertFalse(fqmn.contains("<"),
                        "Incoming FQMN has generics: " + fqmn);
            }
        }
    }

    // ---- viewScope ----

    @Nested
    class ViewScope {

        @Test
        void projectSourceHasProjectScope() throws Exception {
            var json = sourceJson(
                    "test.service.GenericCallerService",
                    "saveItems");
            assertEquals("project", str(json, "viewScope"),
                    "Workspace source should have viewScope=project");
        }
    }

    // ---- Type subtypes + anonymous ----

    @Nested
    class TypeSubtypes {

        @Test
        void interfaceTypeRefHasImplementations()
                throws Exception {
            // AnonymousCallerService.createAnonymous() returns
            // Animal — an interface. Subtypes should be resolved.
            var json = sourceJson(
                    "test.service.AnonymousCallerService",
                    "createAnonymous");
            var impls = new java.util.ArrayList<JsonObject>();
            for (JsonElement e : refs(json)) {
                JsonObject ref = e.getAsJsonObject();
                if (ref.has("implementationOf")
                        && str(ref, "implementationOf")
                                .contains("Animal")) {
                    impls.add(ref);
                }
            }
            // Dog, Cat, AbstractPet + anonymous $1
            assertTrue(impls.size() >= 4,
                    "At least 4 impls of Animal (incl anonymous): "
                    + impls);
        }

        @Test
        void anonymousSubtypeHasFlag() throws Exception {
            var json = sourceJson(
                    "test.service.AnonymousCallerService",
                    "createAnonymous");
            boolean foundAnon = false;
            for (JsonElement e : refs(json)) {
                JsonObject ref = e.getAsJsonObject();
                if (ref.has("anonymous")
                        && ref.get("anonymous").getAsBoolean()) {
                    foundAnon = true;
                    assertTrue(
                            str(ref, "fqmn").contains("$"),
                            "Anonymous FQMN should contain $: "
                            + str(ref, "fqmn"));
                }
            }
            assertTrue(foundAnon,
                    "Should find anonymous subtype");
        }

        @Test
        void anonymousSubtypeHasEnclosingFqmn()
                throws Exception {
            var json = sourceJson(
                    "test.service.AnonymousCallerService",
                    "createAnonymous");
            for (JsonElement e : refs(json)) {
                JsonObject ref = e.getAsJsonObject();
                if (ref.has("anonymous")
                        && ref.get("anonymous").getAsBoolean()) {
                    String enc = str(ref, "enclosingFqmn");
                    assertNotNull(enc,
                            "Anonymous should have enclosingFqmn: "
                            + ref);
                    assertTrue(enc.contains(
                            "AnonymousCallerService"
                            + "#createAnonymous"),
                            "Enclosing should be createAnonymous: "
                            + enc);
                }
            }
        }

        @Test
        void namedSubtypesPresent() throws Exception {
            var json = sourceJson(
                    "test.service.AnonymousCallerService",
                    "createAnonymous");
            var fqmns = new java.util.ArrayList<String>();
            for (JsonElement e : refs(json)) {
                JsonObject ref = e.getAsJsonObject();
                if (ref.has("implementationOf")) {
                    fqmns.add(str(ref, "fqmn"));
                }
            }
            assertTrue(fqmns.stream()
                    .anyMatch(f -> f.contains("Dog")),
                    "Dog: " + fqmns);
            assertTrue(fqmns.stream()
                    .anyMatch(f -> f.contains("Cat")),
                    "Cat: " + fqmns);
        }
    }
}
