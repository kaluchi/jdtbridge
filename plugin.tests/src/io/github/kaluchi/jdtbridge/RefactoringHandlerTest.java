package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kaluchi.jdtbridge.support.TestFixture;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void organizeImportsMissingClassParamReturnsError() throws Exception {
        assertError(
                handler.handleOrganizeImports(Map.of()),
                "Missing 'class' parameter");
    }

    @Test
    void organizeImportsBlankClassParamReturnsError() throws Exception {
        assertError(
                handler.handleOrganizeImports(Map.of("class", "  ")),
                "Missing 'class' parameter");
    }

    // ── format errors ─────────────────────────────────────────────

    @Test
    void formatMissingClassParamReturnsError() throws Exception {
        assertError(
                handler.handleFormat(Map.of()),
                "Missing 'class' parameter");
    }

    @Test
    void formatBlankClassParamReturnsError() throws Exception {
        assertError(
                handler.handleFormat(Map.of("class", "")),
                "Missing 'class' parameter");
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

    // ── type resolution edge cases ──────────────────────────────

    @Test
    void organizeImportsNonExistentTypeReturnsError() throws Exception {
        assertError(
                handler.handleOrganizeImports(
                        Map.of("class", "no.such.Type")),
                "Type not found");
    }

    @Test
    void formatNonExistentTypeReturnsError() throws Exception {
        assertError(
                handler.handleFormat(
                        Map.of("class", "no.such.Type")),
                "Type not found");
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

    @Nested
    class OrganizeImportsSuccess {

        @Test
        void removesUnusedImports() throws Exception {
            String json = handler.handleOrganizeImports(
                    Map.of("class", "test.refactor.ImportTarget"));
            var obj = parseJson(json);
            assertFalse(obj.has("error"), "unexpected: " + json);
            assertTrue(obj.has("added"), "should have added: " + json);
            assertTrue(obj.has("removed"),
                    "should have removed: " + json);
            int removed = obj.get("removed").getAsInt();
            assertEquals(2, removed,
                    "Map + Set should be removed");
        }

        @Test
        void noOpOnCleanFile() throws Exception {
            String json = handler.handleOrganizeImports(
                    Map.of("class", "test.model.Dog"));
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
            String json = handler.handleFormat(
                    Map.of("class", "test.refactor.FormatTarget"));
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
            try {
                handler.handleFormat(
                        Map.of("class", "test.refactor.FormatOnce"));
                String json2 = handler.handleFormat(
                        Map.of("class", "test.refactor.FormatOnce"));
                var obj = parseJson(json2);
                assertFalse(obj.has("error"),
                        "second format must not error: " + json2);
                assertFalse(obj.get("modified").getAsBoolean(),
                        "second format should be no-op: " + json2);
            } finally {
                var cu = pkg.getCompilationUnit("FormatOnce.java");
                cu.delete(true, null);
            }
        }

        @Test
        void noOpOnAlreadyFormatted() throws Exception {
            String json = handler.handleFormat(
                    Map.of("class", "test.model.Animal"));
            var obj = parseJson(json);
            assertFalse(obj.has("error"), "unexpected: " + json);
        }
    }

}
