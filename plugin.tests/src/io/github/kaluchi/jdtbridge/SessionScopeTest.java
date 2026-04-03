package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionScope}, specifically the
 * {@code projectScope} field in session JSON files.
 * <p>
 * Uses {@link TestFixture} to create a real project in the
 * PDE test workspace so that {@code resolveProjects()} can
 * match it against the session's {@code workingDir}.
 */
public class SessionScopeTest {

    private final SessionScope sessionScope = new SessionScope();
    private Path sessionsDir;

    /** Working directory that contains the test fixture project. */
    private static String fixtureWorkingDir;

    @BeforeAll
    static void setUpFixture() throws Exception {
        TestFixture.create();
        fixtureWorkingDir = ResourcesPlugin.getWorkspace()
                .getRoot().getProject(TestFixture.PROJECT_NAME)
                .getLocation().toOSString();
    }

    @AfterAll
    static void tearDownFixture() throws Exception {
        TestFixture.destroy();
    }

    @BeforeEach
    void setUp() throws IOException {
        sessionsDir = Activator.getHome().resolve("sessions");
        Files.createDirectories(sessionsDir);
    }

    @AfterEach
    void tearDown() {
        deleteQuietly("scope-disabled-test");
        deleteQuietly("scope-enabled-test");
        deleteQuietly("scope-missing-test");
        deleteQuietly("scope-no-workdir-test");
    }

    private void writeSession(String sessionId, String json)
            throws IOException {
        Files.writeString(
                sessionsDir.resolve(sessionId + ".json"), json);
    }

    private void deleteQuietly(String sessionId) {
        try {
            Files.deleteIfExists(
                    sessionsDir.resolve(sessionId + ".json"));
        } catch (IOException e) {
            // best effort
        }
    }

    /**
     * ALL scope accepts any project name including nonexistent.
     * Filtered scope rejects projects not in the set.
     */
    private boolean isAllScope(ProjectScope scope) {
        return scope.containsProject(
                "nonexistent-project-for-scope-test");
    }

    @Nested
    class ProjectScopeDisabled {

        @Test
        void returnAllWhenProjectScopeFalse() throws Exception {
            writeSession("scope-disabled-test",
                    "{\"provider\":\"local\","
                    + "\"agent\":\"claude\","
                    + "\"workingDir\":\""
                    + fixtureWorkingDir.replace("\\", "/") + "\","
                    + "\"projectScope\":false,"
                    + "\"bridgePort\":12345}");
            ProjectScope scope = sessionScope.resolve(
                    "scope-disabled-test");
            assertTrue(isAllScope(scope),
                    "projectScope:false should return ALL scope");
        }
    }

    @Nested
    class ProjectScopeEnabled {

        @Test
        void filtersWhenProjectScopeTrue() throws Exception {
            writeSession("scope-enabled-test",
                    "{\"provider\":\"local\","
                    + "\"agent\":\"claude\","
                    + "\"workingDir\":\""
                    + fixtureWorkingDir.replace("\\", "/") + "\","
                    + "\"projectScope\":true,"
                    + "\"bridgePort\":12345}");
            ProjectScope scope = sessionScope.resolve(
                    "scope-enabled-test");
            assertFalse(isAllScope(scope),
                    "projectScope:true should filter projects");
            assertTrue(scope.containsProject(
                    TestFixture.PROJECT_NAME),
                    "Filtered scope should include fixture project");
        }
    }

    @Nested
    class ProjectScopeMissing {

        @Test
        void defaultsToTrueWhenFieldMissing() throws Exception {
            writeSession("scope-missing-test",
                    "{\"provider\":\"local\","
                    + "\"agent\":\"claude\","
                    + "\"workingDir\":\""
                    + fixtureWorkingDir.replace("\\", "/") + "\","
                    + "\"bridgePort\":12345}");
            ProjectScope scope = sessionScope.resolve(
                    "scope-missing-test");
            assertFalse(isAllScope(scope),
                    "Missing projectScope should default to true"
                    + " (filtered)");
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void nullSessionIdReturnsAll() {
            ProjectScope scope = sessionScope.resolve(null);
            assertTrue(isAllScope(scope));
        }

        @Test
        void emptySessionIdReturnsAll() {
            ProjectScope scope = sessionScope.resolve("");
            assertTrue(isAllScope(scope));
        }

        @Test
        void nonexistentSessionReturnsAll() {
            ProjectScope scope = sessionScope.resolve(
                    "no-such-session-12345");
            assertTrue(isAllScope(scope));
        }

        @Test
        void scopeDisabledWithEmptyWorkingDir()
                throws Exception {
            writeSession("scope-no-workdir-test",
                    "{\"provider\":\"local\","
                    + "\"agent\":\"claude\","
                    + "\"workingDir\":\"\","
                    + "\"projectScope\":false,"
                    + "\"bridgePort\":12345}");
            ProjectScope scope = sessionScope.resolve(
                    "scope-no-workdir-test");
            assertTrue(isAllScope(scope),
                    "Empty workingDir + scope disabled → ALL");
        }
    }
}
