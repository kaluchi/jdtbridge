package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.JavaCore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public class RefactoringHandlerTest {

    private static final RefactoringHandler handler =
            new RefactoringHandler();

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    private static JsonObject parseJson(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static void assertError(String json, String fragment) {
        JsonObject obj = parseJson(json);
        assertTrue(obj.has("error"),
                "expected error field: " + json);
        assertTrue(obj.get("error").getAsString().contains(fragment),
                "expected '" + fragment + "' in: " + json);
    }

    // ── organize-imports errors ────────────────────────────────────

    @Test
    void organizeImportsMissingFileParamReturnsError() throws Exception {
        assertError(
                handler.handleOrganizeImports(Map.of()),
                "Missing 'file' parameter");
    }

    @Test
    void organizeImportsBlankFileParamReturnsError() throws Exception {
        assertError(
                handler.handleOrganizeImports(Map.of("file", "  ")),
                "Missing 'file' parameter");
    }

    // ── format errors ─────────────────────────────────────────────

    @Test
    void formatMissingFileParamReturnsError() throws Exception {
        assertError(
                handler.handleFormat(Map.of()),
                "Missing 'file' parameter");
    }

    @Test
    void formatBlankFileParamReturnsError() throws Exception {
        assertError(
                handler.handleFormat(Map.of("file", "")),
                "Missing 'file' parameter");
    }

    // ── rename errors ─────────────────────────────────────────────

    @Test
    void renameFieldNotFoundReturnsError() throws Exception {
        assertError(
                handler.handleRename(Map.of(
                        "class", "test.model.Dog",
                        "field", "nonExistentField",
                        "newName", "x")),
                "Field not found");
    }

    @Test
    void renameMethodNotFoundReturnsError() throws Exception {
        assertError(
                handler.handleRename(Map.of(
                        "class", "test.model.Dog",
                        "method", "nonExistentMethod",
                        "newName", "x")),
                "Method not found");
    }

    // ── move errors ───────────────────────────────────────────────

    @Test
    void moveMissingClassParamReturnsError() throws Exception {
        assertError(
                handler.handleMove(Map.of("target", "some.pkg")),
                "Missing 'class' parameter");
    }

    @Test
    void moveTypeNotFoundReturnsError() throws Exception {
        assertError(
                handler.handleMove(Map.of(
                        "class", "no.such.Type",
                        "target", "some.pkg")),
                "Type not found");
    }

    @Test
    void moveBinaryTypeReturnsError() throws Exception {
        assertError(
                handler.handleMove(Map.of(
                        "class", "java.lang.String",
                        "target", "some.pkg")),
                "Cannot move binary type");
    }

    // ── performRefactoring fatal error paths ─────────────────────

    @Test
    void renameToJavaKeywordReturnsFatalError() throws Exception {
        String json = handler.handleRename(Map.of(
                "class", "test.model.Dog",
                "newName", "class"));
        assertError(json, "");
    }

    @Test
    void renameToSameNameReturnsFatalError() throws Exception {
        String json = handler.handleRename(Map.of(
                "class", "test.model.Dog",
                "newName", "Dog"));
        assertError(json, "");
    }

    // ── findCompilationUnit edge cases ────────────────────────────

    @Test
    void organizeImportsNonExistentPathReturnsError() throws Exception {
        assertError(
                handler.handleOrganizeImports(
                        Map.of("file", "/no/such/File.java")),
                "Java file not found");
    }

    @Test
    void formatNonExistentPathReturnsError() throws Exception {
        assertError(
                handler.handleFormat(
                        Map.of("file", "/no/such/File.java")),
                "Java file not found");
    }

    // ── ensurePreferencesInitialized ──────────────────────────────

    @Test
    void ensurePreferencesInitializedSetsNodeId() {
        RefactoringHandler.ensurePreferencesInitialized();
        assertNotNull(
                org.eclipse.jdt.core.manipulation
                        .JavaManipulation.getPreferenceNodeId());
    }

    // ── success paths (integration with TestFixture) ─────────────

    private static String fixturePath(String pkg, String file) {
        return "/" + TestFixture.PROJECT_NAME
                + "/src/" + pkg.replace('.', '/') + "/" + file;
    }

    @Nested
    class OrganizeImportsSuccess {

        @Test
        void removesUnusedImports() throws Exception {
            String path = fixturePath(
                    "test.refactor", "ImportTarget.java");
            String json = handler.handleOrganizeImports(
                    Map.of("file", path));
            var obj = parseJson(json);
            assertFalse(obj.has("error"), "unexpected: " + json);
            assertTrue(obj.has("added"), "should have added: " + json);
            assertTrue(obj.has("removed"),
                    "should have removed: " + json);
            int removed = obj.get("removed").getAsInt();
            assertTrue(removed >= 2,
                    "Map + Set should be removed: removed=" + removed);
        }

        @Test
        void noOpOnCleanFile() throws Exception {
            String path = fixturePath(
                    "test.model", "Dog.java");
            String json = handler.handleOrganizeImports(
                    Map.of("file", path));
            var obj = parseJson(json);
            assertFalse(obj.has("error"), "unexpected: " + json);
            assertEquals(0, obj.get("added").getAsInt());
            assertEquals(0, obj.get("removed").getAsInt());
        }
    }

    @Nested
    class FormatSuccess {

        @Test
        void formatsMessyCode() throws Exception {
            String path = fixturePath(
                    "test.refactor", "FormatTarget.java");
            String json = handler.handleFormat(
                    Map.of("file", path));
            var obj = parseJson(json);
            assertFalse(obj.has("error"), "unexpected: " + json);
            assertTrue(obj.get("modified").getAsBoolean(),
                    "messy code should be reformatted: " + json);
        }

        @Test
        void doubleFormatIsNoOp() throws Exception {
            var root = ResourcesPlugin.getWorkspace().getRoot();
            var project = JavaCore.create(
                    root.getProject(TestFixture.PROJECT_NAME));
            var srcRoot = project.getPackageFragmentRoot(
                    root.getProject(TestFixture.PROJECT_NAME)
                            .getFolder("src"));
            var pkg = srcRoot.getPackageFragment("test.refactor");
            pkg.createCompilationUnit("FormatOnce.java", """
                    package test.refactor;
                    public class FormatOnce {
                    public    void   messy()  {   int x=1;  }
                    }
                    """, true, null);
            org.eclipse.core.runtime.jobs.Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);
            String path = fixturePath(
                    "test.refactor", "FormatOnce.java");
            try {
                handler.handleFormat(Map.of("file", path));
                String json2 = handler.handleFormat(
                        Map.of("file", path));
                var obj = parseJson(json2);
                assertFalse(obj.has("error"),
                        "second format must not error: " + json2);
                assertFalse(obj.get("modified").getAsBoolean(),
                        "second format should be no-op: " + json2);
            } finally {
                var cu = pkg.getCompilationUnit("FormatOnce.java");
                if (cu.exists()) cu.delete(true, null);
            }
        }

        @Test
        void noOpOnAlreadyFormatted() throws Exception {
            String path = fixturePath(
                    "test.model", "Animal.java");
            String json = handler.handleFormat(
                    Map.of("file", path));
            var obj = parseJson(json);
            assertFalse(obj.has("error"), "unexpected: " + json);
        }
    }

}
