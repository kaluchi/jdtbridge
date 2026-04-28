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
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
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
        void noOpOnAlreadyFormatted() throws Exception {
            String path = fixturePath(
                    "test.model", "Animal.java");
            String json = handler.handleFormat(
                    Map.of("file", path));
            var obj = parseJson(json);
            assertFalse(obj.has("error"), "unexpected: " + json);
        }
    }

    @Nested
    class RenameSuccess {

        private ICompilationUnit createTempType(
                String name, String src) throws Exception {
            var root = ResourcesPlugin.getWorkspace().getRoot();
            var project = JavaCore.create(
                    root.getProject(TestFixture.PROJECT_NAME));
            IPackageFragmentRoot srcRoot =
                    project.getPackageFragmentRoot(
                            root.getProject(TestFixture.PROJECT_NAME)
                                    .getFolder("src"));
            IPackageFragment pkg =
                    srcRoot.getPackageFragment("test.refactor");
            return pkg.createCompilationUnit(
                    name + ".java", src, true, null);
        }

        @Test
        void renameType() throws Exception {
            createTempType("RenameMe", """
                    package test.refactor;
                    public class RenameMe {
                    }
                    """);
            Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);
            try {
                String json = handler.handleRename(Map.of(
                        "class", "test.refactor.RenameMe",
                        "newName", "Renamed"));
                var obj = parseJson(json);
                assertTrue(obj.get("ok").getAsBoolean(),
                        "rename must succeed: " + json);
            } finally {
                var root = ResourcesPlugin.getWorkspace().getRoot();
                var project = JavaCore.create(
                        root.getProject(TestFixture.PROJECT_NAME));
                var srcRoot = project.getPackageFragmentRoot(
                        root.getProject(TestFixture.PROJECT_NAME)
                                .getFolder("src"));
                var pkg = srcRoot.getPackageFragment("test.refactor");
                var cu = pkg.getCompilationUnit("Renamed.java");
                if (cu.exists()) cu.delete(true, null);
                var old = pkg.getCompilationUnit("RenameMe.java");
                if (old.exists()) old.delete(true, null);
            }
        }

        @Test
        void renameField() throws Exception {
            createTempType("FieldRenameTarget", """
                    package test.refactor;
                    public class FieldRenameTarget {
                        int oldName = 0;
                    }
                    """);
            Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);
            try {
                String json = handler.handleRename(Map.of(
                        "class", "test.refactor.FieldRenameTarget",
                        "field", "oldName",
                        "newName", "newFieldName"));
                var obj = parseJson(json);
                assertTrue(obj.get("ok").getAsBoolean(),
                        "field rename must succeed: " + json);
            } finally {
                var root = ResourcesPlugin.getWorkspace().getRoot();
                var project = JavaCore.create(
                        root.getProject(TestFixture.PROJECT_NAME));
                var pkg = project.getPackageFragmentRoot(
                        root.getProject(TestFixture.PROJECT_NAME)
                                .getFolder("src"))
                        .getPackageFragment("test.refactor");
                var cu = pkg.getCompilationUnit(
                        "FieldRenameTarget.java");
                if (cu.exists()) cu.delete(true, null);
            }
        }

        @Test
        void renameMethod() throws Exception {
            createTempType("MethodRenameTarget", """
                    package test.refactor;
                    public class MethodRenameTarget {
                        public void oldMethod() {}
                    }
                    """);
            Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);
            try {
                String json = handler.handleRename(Map.of(
                        "class", "test.refactor.MethodRenameTarget",
                        "method", "oldMethod",
                        "newName", "newMethodName"));
                var obj = parseJson(json);
                assertTrue(obj.get("ok").getAsBoolean(),
                        "method rename must succeed: " + json);
            } finally {
                var root = ResourcesPlugin.getWorkspace().getRoot();
                var project = JavaCore.create(
                        root.getProject(TestFixture.PROJECT_NAME));
                var pkg = project.getPackageFragmentRoot(
                        root.getProject(TestFixture.PROJECT_NAME)
                                .getFolder("src"))
                        .getPackageFragment("test.refactor");
                var cu = pkg.getCompilationUnit(
                        "MethodRenameTarget.java");
                if (cu.exists()) cu.delete(true, null);
            }
        }
    }

    @Nested
    class MoveSuccess {

        @Test
        void moveTypeToNewPackage() throws Exception {
            var root = ResourcesPlugin.getWorkspace().getRoot();
            var project = JavaCore.create(
                    root.getProject(TestFixture.PROJECT_NAME));
            var srcRoot = project.getPackageFragmentRoot(
                    root.getProject(TestFixture.PROJECT_NAME)
                            .getFolder("src"));
            var pkg = srcRoot.getPackageFragment("test.refactor");
            pkg.createCompilationUnit("MoveMe.java", """
                    package test.refactor;
                    public class MoveMe {
                    }
                    """, true, null);
            Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);
            try {
                String json = handler.handleMove(Map.of(
                        "class", "test.refactor.MoveMe",
                        "target", "test.moved"));
                var obj = parseJson(json);
                assertTrue(obj.get("ok").getAsBoolean(),
                        "move must succeed: " + json);
            } finally {
                var targetPkg =
                        srcRoot.getPackageFragment("test.moved");
                if (targetPkg.exists()) {
                    var cu = targetPkg.getCompilationUnit(
                            "MoveMe.java");
                    if (cu.exists()) cu.delete(true, null);
                    targetPkg.delete(true, null);
                }
                var oldCu = pkg.getCompilationUnit("MoveMe.java");
                if (oldCu.exists()) oldCu.delete(true, null);
            }
        }
    }
}
