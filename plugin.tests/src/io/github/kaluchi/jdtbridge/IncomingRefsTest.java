package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for incoming refs (callers) collected by
 * SearchHandler and emitted in SourceReport JSON
 * with direction:"incoming".
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class IncomingRefsTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    private static String sourceBody(String fqn, String method)
            throws Exception {
        var handler = new SearchHandler();
        var params = method != null
                ? Map.of("class", fqn, "method", method)
                : Map.of("class", fqn);
        return handler.handleSource(params).body();
    }

    private static List<Map<String, Object>> parseRefs(
            String json) {
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

    private static List<Map<String, Object>> incoming(
            String json) {
        return parseRefs(json).stream()
                .filter(r -> "incoming".equals(
                        Json.getString(r, "direction")))
                .toList();
    }

    private static List<Map<String, Object>> outgoing(
            String json) {
        return parseRefs(json).stream()
                .filter(r -> "outgoing".equals(
                        Json.getString(r, "direction")))
                .toList();
    }

    @Nested
    class DogBarkIncoming {

        @Test
        void barkHasIncomingCallers() throws Exception {
            String json = sourceBody("test.model.Dog", "bark");
            var inc = incoming(json);
            assertFalse(inc.isEmpty(),
                    "bark() should have incoming callers");
        }

        @Test
        void barkCalledByCreateDog() throws Exception {
            String json = sourceBody("test.model.Dog", "bark");
            var inc = incoming(json);
            var fqmns = inc.stream()
                    .map(r -> Json.getString(r, "fqmn"))
                    .collect(Collectors.toSet());
            assertTrue(fqmns.stream()
                    .anyMatch(f -> f.contains(
                            "AnimalService#createDog")),
                    "createDog calls bark: " + fqmns);
        }

        @Test
        void barkIncomingHasProjectScope() throws Exception {
            String json = sourceBody("test.model.Dog", "bark");
            for (var ref : incoming(json)) {
                assertEquals("project",
                        Json.getString(ref, "scope"),
                        "Fixture callers are project: " + ref);
            }
        }

        @Test
        void barkIncomingHasFile() throws Exception {
            String json = sourceBody("test.model.Dog", "bark");
            for (var ref : incoming(json)) {
                assertNotNull(Json.getString(ref, "file"),
                        "Incoming should have file: " + ref);
            }
        }

        @Test
        void barkIncomingHasLine() throws Exception {
            String json = sourceBody("test.model.Dog", "bark");
            for (var ref : incoming(json)) {
                assertTrue(
                        Json.getInt(ref, "line", -1) > 0,
                        "Incoming should have line: " + ref);
            }
        }

        @Test
        void barkIncomingFqmnUsesHash() throws Exception {
            String json = sourceBody("test.model.Dog", "bark");
            for (var ref : incoming(json)) {
                String fqmn = Json.getString(ref, "fqmn");
                assertTrue(fqmn.contains("#"),
                        "FQMN should use #: " + fqmn);
            }
        }

        @Test
        void barkIncomingDeduped() throws Exception {
            String json = sourceBody("test.model.Dog", "bark");
            var fqmns = incoming(json).stream()
                    .map(r -> Json.getString(r, "fqmn"))
                    .toList();
            assertEquals(fqmns.size(),
                    Set.copyOf(fqmns).size(),
                    "Incoming should be deduped: " + fqmns);
        }

        @Test
        void barkHasNoOutgoing() throws Exception {
            // bark() body: System.out.println — all java.* filtered
            String json = sourceBody("test.model.Dog", "bark");
            var out = outgoing(json);
            assertTrue(out.isEmpty(),
                    "bark() has no non-JDK outgoing: " + out);
        }
    }

    @Nested
    class AnimalServiceProcessIncoming {

        @Test
        void processHasIncomingFromCaller() throws Exception {
            String json = sourceBody(
                    "test.service.AnimalService", "process");
            var inc = incoming(json);
            var fqmns = inc.stream()
                    .map(r -> Json.getString(r, "fqmn"))
                    .collect(Collectors.toSet());
            assertTrue(fqmns.stream()
                    .anyMatch(f -> f.contains(
                            "CallerService#callProcess")),
                    "CallerService calls process: " + fqmns);
        }

        @Test
        void processHasBothDirections() throws Exception {
            String json = sourceBody(
                    "test.service.AnimalService", "process");
            assertFalse(outgoing(json).isEmpty(),
                    "Should have outgoing");
            assertFalse(incoming(json).isEmpty(),
                    "Should have incoming");
        }

        @Test
        void everyRefHasDirection() throws Exception {
            String json = sourceBody(
                    "test.service.AnimalService", "process");
            for (var ref : parseRefs(json)) {
                String dir = Json.getString(ref, "direction");
                assertTrue(
                        "outgoing".equals(dir)
                                || "incoming".equals(dir),
                        "Direction must be outgoing/incoming: "
                        + ref);
            }
        }
    }

    @Nested
    class TypeLevelNoIncoming {

        @Test
        void classLevelHasNoRefs() throws Exception {
            String json = sourceBody("test.model.Dog", null);
            var parsed = Json.parse(json);
            assertNull(parsed.get("refs"),
                    "Type-level should not have refs");
            assertNotNull(parsed.get("supertypes"),
                    "Type-level should have hierarchy");
        }
    }

    @Nested
    class UnusedMethodNoIncoming {

        @Test
        void getColorHasNoIncoming() throws Exception {
            String json = sourceBody(
                    "test.service.EnrichedRefService",
                    "getColor");
            var inc = incoming(json);
            assertTrue(inc.isEmpty(),
                    "getColor not called by anyone in fixture: "
                    + inc);
        }
    }
}
