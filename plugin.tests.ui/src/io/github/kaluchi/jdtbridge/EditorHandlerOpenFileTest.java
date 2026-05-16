package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.kaluchi.jdtbridge.support.TestFixture;

/**
 * UI-runtime tests for {@link EditorHandler#handleOpenFile}.
 * Exercise both dispatch branches — workspace IFile vs external
 * IFileStore — and the parameter-validation paths. Uses .java and
 * .txt extensions exclusively: both have a single registered editor
 * (JDT and the default text editor), so no interactive "Select
 * Editor" dialog can pop up and stall the test under headless PDE.
 */
public class EditorHandlerOpenFileTest {

    private static final EditorHandler handler = new EditorHandler();

    private static Path externalDir;

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
        externalDir = Files.createTempDirectory("jdt-open-file-");
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
        if (externalDir != null) {
            try (var stream = Files.walk(externalDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    @BeforeEach
    void closeAllEditors() {
        Display.getDefault().syncExec(() ->
                PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                        .getActivePage().closeAllEditors(false));
    }

    @AfterEach
    void alsoCloseAllEditors() {
        Display.getDefault().syncExec(() ->
                PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                        .getActivePage().closeAllEditors(false));
    }

    @Test
    public void missingPathReturnsError() throws Exception {
        JsonObject obj = call(Map.of());
        assertTrue(obj.has("error"));
        assertTrue(obj.get("error").getAsString()
                .contains("Missing 'path' parameter"));
    }

    @Test
    public void blankPathReturnsError() throws Exception {
        JsonObject obj = call(Map.of("path", "   "));
        assertTrue(obj.has("error"));
    }

    @Test
    public void relativePathRejected() throws Exception {
        JsonObject obj = call(Map.of("path", "foo/bar.txt"));
        assertTrue(obj.get("error").getAsString()
                .contains("Path must be absolute"));
    }

    @Test
    public void missingFileReturnsError() throws Exception {
        Path ghost = externalDir.resolve("ghost.txt");
        JsonObject obj = call(Map.of(
                "path", ghost.toAbsolutePath().toString()));
        assertTrue(obj.get("error").getAsString()
                .contains("File not found"));
    }

    @Test
    public void directoryRejected() throws Exception {
        JsonObject obj = call(Map.of(
                "path", externalDir.toAbsolutePath().toString()));
        assertTrue(obj.get("error").getAsString()
                .contains("Path is a directory"));
    }

    @Test
    public void workspaceJavaFileOpensViaIDE() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(TestFixture.PROJECT_NAME);
        IFile dog = project.getFile("src/test/model/Dog.java");
        assertTrue(dog.exists(), "Dog.java must exist in fixture");
        String path = dog.getLocation().toOSString();

        JsonObject obj = call(Map.of("path", path));
        assertTrue(obj.get("ok").getAsBoolean(),
                "Expected ok=true: " + obj);
        assertEquals("org.eclipse.jdt.ui.CompilationUnitEditor",
                obj.get("editorId").getAsString(),
                "Java workspace file routes to JDT editor");
    }

    @Test
    public void externalTextFileOpensViaEFS() throws Exception {
        Path external = externalDir.resolve("scratch.txt");
        Files.writeString(external, "hello\n",
                StandardCharsets.UTF_8);
        JsonObject obj = call(Map.of(
                "path", external.toAbsolutePath().toString()));
        assertTrue(obj.get("ok").getAsBoolean(),
                "Expected ok=true for external file: " + obj);
        assertNotNull(obj.get("editorId").getAsString());
    }

    private JsonObject call(Map<String, String> params) throws Exception {
        return JsonParser.parseString(handler.handleOpenFile(params))
                .getAsJsonObject();
    }
}
