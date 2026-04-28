package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.kaluchi.jdtbridge.support.TestFixture;

/**
 * UI-runtime integration tests for {@link EditorHandler} — every
 * path inside its two endpoints needs a live workbench, so the
 * tests live in plugin.tests.ui under the UI harness. Drives the
 * {@code /editors} and {@code /open} routes against the
 * TestFixture project so coverage reaches the editor-list
 * iteration, FQN lookup, and method-reveal branches.
 */
public class EditorHandlerTest {

    private static final EditorHandler handler = new EditorHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    @BeforeEach
    void closeAllEditors() {
        Display.getDefault().syncExec(() -> {
            IWorkbenchWindow w = PlatformUI.getWorkbench()
                    .getActiveWorkbenchWindow();
            if (w != null && w.getActivePage() != null) {
                w.getActivePage().closeAllEditors(false);
            }
        });
    }

    @Test
    public void handleEditorsEmptyWhenNothingOpen() throws Exception {
        String json = handler.handleEditors(
                Map.of(), ProjectScope.ALL);
        assertEquals("[]", json);
    }

    @Test
    public void handleOpenMissingClassReturnsError() throws Exception {
        String json = handler.handleOpen(Map.of());
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.has("error"),
                "Expected error field: " + json);
        assertTrue(obj.get("error").getAsString()
                        .contains("Missing 'class' parameter"),
                "Error mentions missing class: " + json);
    }

    @Test
    public void handleOpenBlankClassReturnsError() throws Exception {
        String json = handler.handleOpen(Map.of("class", "   "));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.has("error"),
                "Blank class must error: " + json);
    }

    @Test
    public void handleOpenTypeNotFoundReturnsError() throws Exception {
        String json = handler.handleOpen(
                Map.of("class", "no.such.Type"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.has("error"),
                "Missing type must error: " + json);
        assertTrue(obj.get("error").getAsString()
                        .contains("Type not found"),
                "Error mentions type not found: " + json);
    }

    @Test
    public void handleOpenExistingTypeReturnsOk() throws Exception {
        String json = handler.handleOpen(
                Map.of("class", "test.model.Dog"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.has("ok") && obj.get("ok").getAsBoolean(),
                "Expected ok=true: " + json);
    }

    @Test
    public void handleOpenWithMethodReveal() throws Exception {
        String json = handler.handleOpen(Map.of(
                "class", "test.model.Dog",
                "method", "bark"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.has("ok") && obj.get("ok").getAsBoolean(),
                "Expected ok=true: " + json);
    }

    @Test
    public void handleOpenUnknownMethodFallsBackToType()
            throws Exception {
        // method not found → reveal falls back to the type itself
        String json = handler.handleOpen(Map.of(
                "class", "test.model.Dog",
                "method", "noSuchMethod"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.has("ok") && obj.get("ok").getAsBoolean(),
                "Expected ok=true even with missing method: " + json);
    }

    @Test
    public void handleEditorsAfterOpenListsActiveAndScope()
            throws Exception {
        handler.handleOpen(Map.of("class", "test.model.Dog"));
        String json = handler.handleEditors(
                Map.of(), ProjectScope.ALL);
        assertTrue(json.contains("Dog"),
                "Editors list must include Dog after open: " + json);
        assertTrue(json.contains("\"active\":true"),
                "Opened editor should be active: " + json);
    }

    @Test
    public void handleEditorsRespectsScopeFilter() throws Exception {
        handler.handleOpen(Map.of("class", "test.model.Dog"));
        // Scope limited to a non-existent project drops Dog.java —
        // ProjectScope.of(emptySet) collapses to ALL, so a single
        // sentinel name is needed to make the filter active.
        String json = handler.handleEditors(Map.of(),
                ProjectScope.of(java.util.Set.of("no-such-project")));
        assertEquals("[]", json,
                "Out-of-scope editor must be dropped: " + json);
    }
}
