package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RefactoringHandler} — error paths and
 * parameter validation. Success paths are covered by
 * {@code RefactoringIntegrationTest} in plugin.tests.ui (needs
 * workbench for auto-build waits after rename/move).
 */
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
}
