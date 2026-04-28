package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for RefactoringHandler: rename, move, format,
 * organize-imports. Uses test.refactor.* classes from TestFixture.
 *
 * Tests are ordered to avoid conflicts (rename changes names
 * that subsequent tests reference).
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
public class RefactoringIntegrationTest {

    private static final RefactoringHandler handler =
            new RefactoringHandler();
    private static final GraphHandler graph = new GraphHandler();

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
        String json = handler.handleOrganizeImports(
                Map.of("file", filePath));
        // ImportTarget imports Map and Set but only uses List
        assertTrue(json.contains("\"removed\":2"),
                "Should remove unused imports: " + json);
    }

    @Test
    public void a2_organizeImportsNoChanges() throws Exception {
        String filePath = "/" + TestFixture.PROJECT_NAME
                + "/src/test/refactor/ImportTarget.java";
        // Run twice — first organize, then verify idempotent
        handler.handleOrganizeImports(Map.of("file", filePath));
        String json = handler.handleOrganizeImports(
                Map.of("file", filePath));
        assertTrue(json.contains("\"added\":0"),
                "Should have 0 added: " + json);
        assertTrue(json.contains("\"removed\":0"),
                "Should have 0 removed: " + json);
    }

    @Test
    public void a3_organizeImportsFileNotFound() throws Exception {
        String json = handler.handleOrganizeImports(
                Map.of("file", "/no/such/File.java"));
        assertJsonError(json, "Java file not found");
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
        assertJsonError(json, "Java file not found");
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
        String source = graph.handleSource(
                Map.of("of", "test.refactor.RenameCaller"));
        assertTrue(source.contains("incrementCounter"),
                "Caller should use new name: " + source);
    }

    // ---- Rename field ----

    @Test
    public void c2_renameField() throws Exception {
        String json = handler.handleRename(Map.of(
                "class", "test.refactor.RenameTarget",
                "field", "counter",
                "newName", "count"));
        assertTrue(json.contains("\"ok\":true"),
                "Should succeed: " + json);

        // Verify getter updated
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);
        String source = graph.handleSource(
                Map.of("of", "test.refactor.RenameTarget"));
        assertTrue(source.contains("count"),
                "Should use new field name: " + source);
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
        String findJson = graph.handleTypes(
                Map.of("pattern", "RenamedTarget"), ProjectScope.ALL);
        assertTrue(findJson.contains("test.refactor.RenamedTarget"),
                "Should find renamed type: " + findJson);

        // Verify caller references updated
        String callerSrc = graph.handleSource(
                Map.of("of", "test.refactor.RenameCaller"));
        assertTrue(callerSrc.contains("RenamedTarget"),
                "Caller should reference RenamedTarget: " + callerSrc);
    }

    // ---- Move ----

    @Test
    public void d1_moveType() throws Exception {
        String json = handler.handleMove(Map.of(
                "class", "test.refactor.RenameCaller",
                "target", "test.moved"));
        assertTrue(json.contains("\"ok\":true"),
                "Should succeed: " + json);

        // Wait for build
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);

        // Verify type in new package
        String findJson = graph.handleTypes(
                Map.of("pattern", "RenameCaller"), ProjectScope.ALL);
        assertTrue(findJson.contains("test.moved.RenameCaller"),
                "Should be in test.moved: " + findJson);
    }

    // ---- Error cases ----

    @Test
    public void e1_renameMissingClass() throws Exception {
        String json = handler.handleRename(
                Map.of("newName", "Foo"));
        assertJsonError(json, "Missing 'class' parameter");
    }

    @Test
    public void e2_renameMissingNewName() throws Exception {
        String json = handler.handleRename(
                Map.of("class", "test.model.Dog"));
        assertJsonError(json, "Missing 'newName' parameter");
    }

    @Test
    public void e3_renameTypeNotFound() throws Exception {
        String json = handler.handleRename(
                Map.of("class", "no.such.Type", "newName", "X"));
        assertJsonError(json, "Type not found");
    }

    @Test
    public void e4_moveMissingTarget() throws Exception {
        String json = handler.handleMove(
                Map.of("class", "test.model.Dog"));
        assertJsonError(json, "Missing 'target' parameter");
    }

    private static void assertJsonError(
            String json, String expectedFragment) {
        var obj = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject();
        org.junit.jupiter.api.Assertions.assertTrue(
                obj.has("error"),
                "Expected error field: " + json);
        String error = obj.get("error").getAsString();
        org.junit.jupiter.api.Assertions.assertTrue(
                error.contains(expectedFragment),
                "Expected '" + expectedFragment + "' in error: "
                        + error);
    }
}
