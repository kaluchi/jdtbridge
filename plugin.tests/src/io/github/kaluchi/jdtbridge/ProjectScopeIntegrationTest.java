package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for session scope filtering with real
 * JDT workspace projects. Creates the test fixture, then
 * verifies that a filtered ProjectScope only returns data
 * from the scoped project.
 */
public class ProjectScopeIntegrationTest {

    private static final SearchHandler search =
            new SearchHandler();
    private static final DiagnosticsHandler diagnostics =
            new DiagnosticsHandler();

    /** Scope limited to the test project only. */
    private static final ProjectScope SCOPED =
            ProjectScope.of(Set.of(TestFixture.PROJECT_NAME));

    /** A scope that excludes the test project. */
    private static final ProjectScope EXCLUDED =
            ProjectScope.of(Set.of("nonexistent-project"));

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    @Nested
    class Projects {

        @Test
        void scopedProjectsContainsTestProject()
                throws Exception {
            String json = search.handleProjects(SCOPED);
            JsonArray arr = parseArray(json);
            assertTrue(arr.size() > 0,
                    "Should have at least one project");
            assertEquals(TestFixture.PROJECT_NAME,
                    arr.get(0).getAsJsonObject()
                            .get("name").getAsString());
        }

        @Test
        void scopedProjectsExcludesOtherProjects()
                throws Exception {
            String json = search.handleProjects(SCOPED);
            JsonArray arr = parseArray(json);
            for (JsonElement e : arr) {
                assertEquals(TestFixture.PROJECT_NAME,
                        e.getAsJsonObject()
                                .get("name").getAsString(),
                        "Only test project should be visible");
            }
        }

        @Test
        void allScopeReturnsAtLeastScopedProjects()
                throws Exception {
            String scopedJson = search.handleProjects(SCOPED);
            String allJson = search.handleProjects(
                    ProjectScope.ALL);
            int scopedCount = parseArray(scopedJson).size();
            int allCount = parseArray(allJson).size();
            assertTrue(allCount >= scopedCount,
                    "ALL scope should return >= scoped projects"
                    + " (" + allCount + " vs " + scopedCount
                    + ")");
        }

        @Test
        void excludedScopeReturnsEmpty() throws Exception {
            String json = search.handleProjects(EXCLUDED);
            assertEquals(0, parseArray(json).size());
        }
    }

    @Nested
    class Find {

        @Test
        void scopedFindReturnsTestTypes() throws Exception {
            String json = search.handleFind(
                    java.util.Map.of("name", "Dog"), SCOPED);
            JsonArray arr = parseArray(json);
            assertNotNull(findByFqn(arr, "test.model.Dog"),
                    "Should find Dog in scoped project");
        }

        @Test
        void excludedFindReturnsEmpty() throws Exception {
            String json = search.handleFind(
                    java.util.Map.of("name", "Dog"), EXCLUDED);
            JsonArray arr = parseArray(json);
            // Dog only exists in test project — excluded scope
            // should not find it (unless JDT index leaks)
            assertFalse(arr.size() > 0
                    && findByFqn(arr, "test.model.Dog") != null,
                    "Should NOT find Dog in excluded scope");
        }
    }

    @Nested
    class Errors {

        @Test
        void scopedErrorsFiltersMarkers() throws Exception {
            // Test project has deliberate compile errors
            var params = new java.util.HashMap<String, String>();
            params.put("project", TestFixture.PROJECT_NAME);
            String scopedJson = diagnostics.handleErrors(
                    params, SCOPED);
            String allJson = diagnostics.handleErrors(
                    params, ProjectScope.ALL);
            // Both should return same result since we explicitly
            // specified the project — scope only adds extra filter
            assertEquals(parseArray(allJson).size(),
                    parseArray(scopedJson).size(),
                    "Explicit project + scope should match");
        }

        @Test
        void excludedScopeFiltersOutMarkers() throws Exception {
            // Workspace-wide errors filtered by excluded scope
            String json = diagnostics.handleErrors(
                    java.util.Map.of(), EXCLUDED);
            assertEquals(0, parseArray(json).size(),
                    "Excluded scope should have no errors");
        }
    }

    @Nested
    class Source {

        @Test
        void scopedSourceWorks() throws Exception {
            var resp = search.handleSource(
                    java.util.Map.of("class", "test.model.Dog"),
                    SCOPED);
            var json = JsonParser.parseString(resp.body())
                    .getAsJsonObject();
            assertEquals("test.model.Dog",
                    json.get("fqmn").getAsString());
        }

        @Test
        void scopedReferencesWork() throws Exception {
            String json = search.handleReferences(
                    java.util.Map.of("class", "test.model.Dog"),
                    SCOPED);
            JsonArray arr = parseArray(json);
            assertTrue(arr.size() > 0,
                    "Should find references in scoped project");
        }
    }

    // ---- Helpers ----

    private static JsonArray parseArray(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static com.google.gson.JsonObject findByFqn(
            JsonArray arr, String fqn) {
        for (JsonElement e : arr) {
            var obj = e.getAsJsonObject();
            if (obj.has("fqn") && fqn.equals(
                    obj.get("fqn").getAsString())) {
                return obj;
            }
        }
        return null;
    }
}
