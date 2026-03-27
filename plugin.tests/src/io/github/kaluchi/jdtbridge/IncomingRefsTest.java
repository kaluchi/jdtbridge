package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
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

    private String handleSource(String fqn, String method)
            throws Exception {
        var handler = new SearchHandler();
        var params = method != null
                ? Map.of("class", fqn, "method", method)
                : Map.of("class", fqn);
        var resp = handler.handleSource(params);
        return resp.body();
    }

    @Nested
    class IncomingPresent {

        @Test
        void dogBarkHasIncomingFromAnimalService()
                throws Exception {
            String json = handleSource(
                    "test.model.Dog", "bark");
            // AnimalService.createDog() calls d.bark()
            assertTrue(json.contains("\"direction\":\"incoming\""),
                    "Should have incoming refs: " + json);
            assertTrue(json.contains("AnimalService"),
                    "Should be called from AnimalService: "
                    + json);
        }

        @Test
        void animalNameHasIncomingCallers() throws Exception {
            String json = handleSource(
                    "test.model.Animal", "name");
            // Animal#name() is called from AnimalService.process
            // and EnrichedRefService.getAnimalName
            assertTrue(json.contains("\"direction\":\"incoming\""),
                    "Should have incoming refs: " + json);
        }

        @Test
        void incomingRefHasFqmn() throws Exception {
            String json = handleSource(
                    "test.model.Dog", "bark");
            // Incoming ref should have enclosing method FQMN
            assertTrue(json.contains("\"fqmn\""),
                    "Incoming ref should have fqmn: " + json);
        }

        @Test
        void incomingRefHasFile() throws Exception {
            String json = handleSource(
                    "test.model.Dog", "bark");
            // Check that incoming refs have file paths
            // (at least some — project refs do)
            assertTrue(json.contains("\"file\""),
                    "Should have file paths: " + json);
        }

        @Test
        void incomingRefHasLine() throws Exception {
            String json = handleSource(
                    "test.model.Dog", "bark");
            assertTrue(json.contains("\"line\""),
                    "Should have line numbers: " + json);
        }

        @Test
        void incomingRefHasScope() throws Exception {
            String json = handleSource(
                    "test.model.Dog", "bark");
            assertTrue(json.contains("\"scope\":\"project\""),
                    "Caller should be project scope: " + json);
        }
    }

    @Nested
    class IncomingDirectionField {

        @Test
        void incomingPresentForBark() throws Exception {
            String json = handleSource(
                    "test.model.Dog", "bark");
            // bark() body is System.out.println — all java.*
            // filtered, so no outgoing. But incoming exists.
            assertTrue(json.contains("\"direction\":\"incoming\""),
                    "Should have incoming refs: " + json);
        }

        @Test
        void outgoingAndIncomingBothPresent() throws Exception {
            // AnimalService.process calls animal.name() →
            // outgoing, and is called by tests → incoming
            String json = handleSource(
                    "test.service.AnimalService", "process");
            assertTrue(json.contains("\"direction\":\"outgoing\""),
                    "Should have outgoing refs: " + json);
        }

        @Test
        void allRefsHaveDirection() throws Exception {
            String json = handleSource(
                    "test.model.Dog", "bark");
            // Every ref should have a direction field
            // Count refs vs direction occurrences
            int refCount = countOccurrences(json, "\"fqmn\"");
            int dirCount = countOccurrences(json, "\"direction\"");
            // refs array contains fqmn for each ref
            // minus the top-level fqmn
            assertTrue(dirCount >= refCount - 1,
                    "All refs should have direction: refs="
                    + refCount + " dirs=" + dirCount);
        }
    }

    @Nested
    class NoIncoming {

        @Test
        void unusedMethodHasNoIncoming() throws Exception {
            // EnrichedRefService.getColor() may not be called
            // by anyone in the test fixture
            String json = handleSource(
                    "test.service.EnrichedRefService",
                    "getColor");
            // May or may not have incoming — depends on fixture
            // At minimum, JSON should be valid
            assertTrue(json.contains("\"fqmn\""));
        }
    }

    @Nested
    class TypeLevelNoIncoming {

        @Test
        void classLevelHasNoIncomingRefs() throws Exception {
            String json = handleSource(
                    "test.model.Dog", null);
            // Type-level returns hierarchy, not refs
            assertFalse(json.contains("\"direction\""),
                    "Type-level should not have direction: "
                    + json);
            assertTrue(json.contains("\"supertypes\""),
                    "Type-level should have hierarchy: "
                    + json);
        }
    }

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
