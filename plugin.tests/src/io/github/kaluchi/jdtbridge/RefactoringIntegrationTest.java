package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for RefactoringHandler: rename, move, format,
 * organize-imports. Uses test.refactor.* classes from TestFixture.
 *
 * Tests are ordered to avoid conflicts (rename changes names
 * that subsequent tests reference).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")
public class RefactoringIntegrationTest {

    private static final RefactoringHandler handler =
            new RefactoringHandler();
    private static final SearchHandler search = new SearchHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- Organize imports ----

    @Test
    public void a1_organizeImportsRemovesUnused() throws Exception {
        String filePath = "/" + TestFixture.PROJECT_NAME
                + "/src/test/refactor/ImportTarget.java";
        try {
            String json = handler.handleOrganizeImports(
                    Map.of("file", filePath));
            // ImportTarget imports Map and Set but only uses List
            assertTrue(json.contains("\"removed\":2"),
                    "Should remove unused imports: " + json);
        } catch (IllegalArgumentException e) {
            // ProjectScope.getNode() fails in headless PDE tests
            // (no project preferences area). Works in full Eclipse.
            assumeTrue(false, "organize-imports needs ProjectScope: " + e);
        }
    }

    @Test
    public void a2_organizeImportsNoChanges() throws Exception {
        String filePath = "/" + TestFixture.PROJECT_NAME
                + "/src/test/refactor/ImportTarget.java";
        try {
            // Run twice — first organize, then verify idempotent
            handler.handleOrganizeImports(Map.of("file", filePath));
            String json = handler.handleOrganizeImports(
                    Map.of("file", filePath));
            assertTrue(json.contains("\"added\":0"),
                    "Should have 0 added: " + json);
            assertTrue(json.contains("\"removed\":0"),
                    "Should have 0 removed: " + json);
        } catch (IllegalArgumentException e) {
            assumeTrue(false, "organize-imports needs ProjectScope: " + e);
        }
    }

    @Test
    public void a3_organizeImportsFileNotFound() throws Exception {
        String json = handler.handleOrganizeImports(
                Map.of("file", "/no/such/File.java"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    // ---- Format ----

    @Test
    public void b1_formatFixesMessyCode() throws Exception {
        String filePath = "/" + TestFixture.PROJECT_NAME
                + "/src/test/refactor/FormatTarget.java";
        String json = handler.handleFormat(
                Map.of("file", filePath));
        assertTrue(json.contains("\"modified\":true"),
                "Should be modified: " + json);
    }

    @Test
    public void b2_formatAlreadyFormatted() throws Exception {
        // After formatting, running again should find no changes
        String filePath = "/" + TestFixture.PROJECT_NAME
                + "/src/test/refactor/FormatTarget.java";
        String json = handler.handleFormat(
                Map.of("file", filePath));
        assertTrue(json.contains("\"modified\":false"),
                "Should not be modified: " + json);
    }

    @Test
    public void b3_formatFileNotFound() throws Exception {
        String json = handler.handleFormat(
                Map.of("file", "/no/such/File.java"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    // ---- Rename method ----

    @Test
    public void c1_renameMethod() throws Exception {
        String json = handler.handleRename(Map.of(
                "class", "test.refactor.RenameTarget",
                "method", "increment",
                "newName", "incrementCounter"));
        assertTrue(json.contains("\"ok\":true"),
                "Should succeed: " + json);

        // Wait for build
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);

        // Verify caller updated
        String source = search.handleSource(
                Map.of("class", "test.refactor.RenameCaller")).body();
        assertTrue(source.contains("incrementCounter"),
                "Caller should use new name: " + source);
    }

    // ---- Rename field ----

    @Test
    public void c2_renameField() throws Exception {
        try {
            String json = handler.handleRename(Map.of(
                    "class", "test.refactor.RenameTarget",
                    "field", "counter",
                    "newName", "count"));
            assertTrue(json.contains("\"ok\":true"),
                    "Should succeed: " + json);

            // Verify getter updated
            Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);
            String source = search.handleSource(
                    Map.of("class", "test.refactor.RenameTarget")).body();
            assertTrue(source.contains("count"),
                    "Should use new field name: " + source);
        } catch (IllegalArgumentException e) {
            // RenameFieldProcessor needs ProjectScope for getter/setter
            // detection. Fails in headless PDE tests.
            assumeTrue(false, "rename-field needs ProjectScope: " + e);
        }
    }

    // ---- Rename type ----

    @Test
    public void c3_renameType() throws Exception {
        String json = handler.handleRename(Map.of(
                "class", "test.refactor.RenameTarget",
                "newName", "RenamedTarget"));
        assertTrue(json.contains("\"ok\":true"),
                "Should succeed: " + json);

        // Wait for build
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);

        // Verify new type exists
        String findJson = search.handleFind(Map.of("name", "RenamedTarget"));
        assertTrue(findJson.contains("test.refactor.RenamedTarget"),
                "Should find renamed type: " + findJson);

        // Verify caller references updated
        String callerSrc = search.handleSource(
                Map.of("class", "test.refactor.RenameCaller")).body();
        assertTrue(callerSrc.contains("RenamedTarget"),
                "Caller should reference RenamedTarget: " + callerSrc);
    }

    // ---- Move ----

    @Test
    public void d1_moveType() throws Exception {
        try {
            String json = handler.handleMove(Map.of(
                    "class", "test.refactor.RenameCaller",
                    "target", "test.moved"));
            assertTrue(json.contains("\"ok\":true"),
                    "Should succeed: " + json);

            // Wait for build
            Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);

            // Verify type in new package
            String findJson = search.handleFind(
                    Map.of("name", "RenameCaller"));
            assertTrue(findJson.contains("test.moved.RenameCaller"),
                    "Should be in test.moved: " + findJson);
        } catch (IllegalArgumentException e) {
            // MoveCuUpdateCreator needs ProjectScope for import rewriting.
            // Fails in headless PDE tests.
            assumeTrue(false, "move needs ProjectScope: " + e);
        }
    }

    // ---- Error cases ----

    @Test
    public void e1_renameMissingClass() throws Exception {
        String json = handler.handleRename(
                Map.of("newName", "Foo"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void e2_renameMissingNewName() throws Exception {
        String json = handler.handleRename(
                Map.of("class", "test.model.Dog"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void e3_renameTypeNotFound() throws Exception {
        String json = handler.handleRename(
                Map.of("class", "no.such.Type", "newName", "X"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void e4_moveMissingTarget() throws Exception {
        String json = handler.handleMove(
                Map.of("class", "test.model.Dog"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }
}
