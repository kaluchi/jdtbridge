package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class IncomingRefsTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    static JsonObject source(String fqn, String method)
            throws Exception {
        var handler = new SearchHandler();
        var params = method != null
                ? Map.of("class", fqn, "method", method)
                : Map.of("class", fqn);
        return JsonParser.parseString(
                handler.handleSource(params).body())
                .getAsJsonObject();
    }

    static JsonArray refsDir(JsonObject json, String dir) {
        var result = new JsonArray();
        if (!json.has("refs")) return result;
        for (JsonElement e : json.getAsJsonArray("refs")) {
            var ref = e.getAsJsonObject();
            if (ref.has("direction") && dir.equals(
                    ref.get("direction").getAsString())) {
                result.add(ref);
            }
        }
        return result;
    }

    static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }

    @Nested
    class DogBarkIncoming {

        @Test
        void barkHasIncomingCallers() throws Exception {
            var json = source("test.model.Dog", "bark");
            assertTrue(refsDir(json, "incoming").size() > 0);
        }

        @Test
        void barkCalledByCreateDog() throws Exception {
            var json = source("test.model.Dog", "bark");
            var inc = refsDir(json, "incoming");
            var fqmns = StreamSupport.stream(
                    inc.spliterator(), false)
                    .map(e -> str(e.getAsJsonObject(), "fqmn"))
                    .collect(Collectors.toSet());
            assertTrue(fqmns.stream()
                    .anyMatch(f -> f.contains(
                            "AnimalService#createDog")));
        }

        @Test
        void barkIncomingHasProjectScope() throws Exception {
            var json = source("test.model.Dog", "bark");
            for (JsonElement e : refsDir(json, "incoming")) {
                assertEquals("project",
                        str(e.getAsJsonObject(), "scope"));
            }
        }

        @Test
        void barkIncomingHasFile() throws Exception {
            var json = source("test.model.Dog", "bark");
            for (JsonElement e : refsDir(json, "incoming")) {
                assertNotNull(
                        str(e.getAsJsonObject(), "file"));
            }
        }

        @Test
        void barkIncomingHasLine() throws Exception {
            var json = source("test.model.Dog", "bark");
            for (JsonElement e : refsDir(json, "incoming")) {
                assertTrue(e.getAsJsonObject()
                        .get("line").getAsInt() > 0);
            }
        }

        @Test
        void barkIncomingFqmnUsesHash() throws Exception {
            var json = source("test.model.Dog", "bark");
            for (JsonElement e : refsDir(json, "incoming")) {
                String fqmn = str(e.getAsJsonObject(), "fqmn");
                assertTrue(fqmn.contains("#"));
            }
        }

        @Test
        void barkIncomingDeduped() throws Exception {
            var json = source("test.model.Dog", "bark");
            var fqmns = StreamSupport.stream(
                    refsDir(json, "incoming").spliterator(),
                    false)
                    .map(e -> str(e.getAsJsonObject(), "fqmn"))
                    .toList();
            assertEquals(fqmns.size(),
                    Set.copyOf(fqmns).size());
        }

        @Test
        void barkHasNoOutgoing() throws Exception {
            var json = source("test.model.Dog", "bark");
            assertEquals(0,
                    refsDir(json, "outgoing").size());
        }
    }

    @Nested
    class AnimalServiceProcessIncoming {

        @Test
        void processHasIncomingFromCaller() throws Exception {
            var json = source(
                    "test.service.AnimalService", "process");
            var inc = refsDir(json, "incoming");
            var fqmns = StreamSupport.stream(
                    inc.spliterator(), false)
                    .map(e -> str(e.getAsJsonObject(), "fqmn"))
                    .collect(Collectors.toSet());
            assertTrue(fqmns.stream()
                    .anyMatch(f -> f.contains(
                            "CallerService#callProcess")));
        }

        @Test
        void processHasBothDirections() throws Exception {
            var json = source(
                    "test.service.AnimalService", "process");
            assertTrue(refsDir(json, "outgoing").size() > 0);
            assertTrue(refsDir(json, "incoming").size() > 0);
        }

        @Test
        void everyRefHasDirection() throws Exception {
            var json = source(
                    "test.service.AnimalService", "process");
            for (JsonElement e : json.getAsJsonArray("refs")) {
                var ref = e.getAsJsonObject();
                String dir = str(ref, "direction");
                assertTrue("outgoing".equals(dir)
                        || "incoming".equals(dir));
            }
        }
    }

    @Nested
    class TypeLevelNoIncoming {

        @Test
        void classLevelHasNoRefs() throws Exception {
            var json = source("test.model.Dog", null);
            assertFalse(json.has("refs"));
            assertTrue(json.has("supertypes"));
        }
    }

    @Nested
    class UnusedMethodNoIncoming {

        @Test
        void getColorHasNoIncoming() throws Exception {
            var json = source(
                    "test.service.EnrichedRefService",
                    "getColor");
            assertEquals(0,
                    refsDir(json, "incoming").size());
        }
    }
}
