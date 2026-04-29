package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.kaluchi.jdtbridge.support.TestFixture;

/**
 * Tests for {@link HttpServer#dispatch} and
 * {@link HttpServer#dispatchStatus} — routing layer coverage.
 * Calls dispatch directly without HTTP sockets.
 */
public class HttpServerDispatchTest {

    private static HttpServer server;

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
        server = new HttpServer();
    }

    @Nested
    class GraphRoutes {

        @Test
        void typeRouteReturnsJson() {
            var resp = server.dispatch("/type",
                    Map.of("of", "test.model.Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
            JsonParser.parseString(resp.body());
            assertFalse(resp.body().contains("\"error\""),
                    "Valid type should not error: " + resp.body());
        }

        @Test
        void membersRouteReturnsJson() {
            var resp = server.dispatch("/members",
                    Map.of("of", "test.model.Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().startsWith("["));
        }

        @Test
        void methodsRouteReturnsJson() {
            var resp = server.dispatch("/methods",
                    Map.of("of", "test.model.Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().startsWith("["));
        }

        @Test
        void fieldsRouteReturnsJson() {
            var resp = server.dispatch("/fields",
                    Map.of("of", "test.model.Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void supersRouteReturnsJson() {
            var resp = server.dispatch("/supers",
                    Map.of("of", "test.model.Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void subtypesRouteReturnsJson() {
            var resp = server.dispatch("/subtypes",
                    Map.of("of", "test.model.Animal"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void sourceRouteReturnsJson() {
            var resp = server.dispatch("/source",
                    Map.of("of", "test.model.Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void problemsRouteReturnsJson() {
            var resp = server.dispatch("/problems",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void projectsRouteReturnsJson() {
            var resp = server.dispatch("/projects",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().startsWith("["));
        }

        @Test
        void typesRouteReturnsJson() {
            var resp = server.dispatch("/types",
                    Map.of("pattern", "Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void refsRouteReturnsJson() {
            var resp = server.dispatch("/refs",
                    Map.of("of", "test.model.Dog"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void innerTypesRouteReturnsJson() {
            var resp = server.dispatch("/innerTypes",
                    Map.of("of", "test.edge.Outer"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void implementorsRouteReturnsJson() {
            var resp = server.dispatch("/implementors",
                    Map.of("of", "test.model.Animal"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void overridesRouteReturnsJson() {
            var resp = server.dispatch("/overrides",
                    Map.of("of", "test.model.Dog#name()"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void packageRouteReturnsJson() {
            var resp = server.dispatch("/package",
                    Map.of("of", "test.model"),
                    null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }
    }

    @Nested
    class LaunchRoutes {

        @Test
        void launchListReturnsJson() {
            var resp = server.dispatch("/launch/list",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().startsWith("["));
        }

        @Test
        void launchConfigsReturnsJson() {
            var resp = server.dispatch("/launch/configs",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().startsWith("["));
        }

        @Test
        void launchClearReturnsJson() {
            var resp = server.dispatch("/launch/clear",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().contains("\"removed\""));
        }
    }

    @Nested
    class TestRoutes {

        @Test
        void testSessionsReturnsJson() {
            var resp = server.dispatch("/test/sessions",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }
    }

    @Nested
    class RefactoringRoutes {

        @Test
        void organizeImportsMissingFileErrors() {
            var resp = server.dispatch("/organize-imports",
                    Map.of(), null, ProjectScope.ALL);
            assertTrue(resp.body().contains("Missing"));
        }

        @Test
        void formatMissingFileErrors() {
            var resp = server.dispatch("/format",
                    Map.of(), null, ProjectScope.ALL);
            assertTrue(resp.body().contains("Missing"));
        }

        @Test
        void renameMissingClassErrors() {
            var resp = server.dispatch("/rename",
                    Map.of(), null, ProjectScope.ALL);
            assertTrue(resp.body().contains("Missing"));
        }

        @Test
        void moveMissingClassErrors() {
            var resp = server.dispatch("/move",
                    Map.of(), null, ProjectScope.ALL);
            assertTrue(resp.body().contains("Missing"));
        }
    }

    @Nested
    class MiscRoutes {

        @Test
        void logRouteReturnsJson() {
            var resp = server.dispatch("/log",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void buildRouteReturnsJson() {
            var resp = server.dispatch("/build",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void coverageRouteReturnsJson() {
            var resp = server.dispatch("/coverage/runs",
                    Map.of(), null, ProjectScope.ALL);
            assertEquals("application/json", resp.contentType());
        }

        @Test
        void unknownPathReturnsError() {
            var resp = server.dispatch("/no-such-path",
                    Map.of(), null, ProjectScope.ALL);
            assertTrue(resp.body().contains("Unknown path"));
        }
    }

    @Nested
    class StatusRoutes {

        @Test
        void statusGetReturnsHtml() {
            var resp = server.dispatchStatus(
                    "/status", "GET", null);
            assertEquals("text/html", resp.contentType());
            assertFalse(resp.body().contains("{{version}}"));
        }

        @Test
        void dismissPostReturnsOk() {
            var resp = server.dispatchStatus(
                    "/status/dismiss", "POST", null);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().contains("\"ok\":true"));
        }

        @Test
        void undismissPostReturnsOk() {
            var resp = server.dispatchStatus(
                    "/status/undismiss", "POST", null);
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().contains("\"ok\":true"));
        }

        @Test
        void unknownStatusPathReturnsError() {
            var resp = server.dispatchStatus(
                    "/status/unknown", "GET", null);
            assertTrue(resp.body().contains("Not found"));
        }

        @Test
        void dismissWithGetIsNotMatched() {
            var resp = server.dispatchStatus(
                    "/status/dismiss", "GET", null);
            assertTrue(resp.body().contains("Not found"));
        }
    }
}
