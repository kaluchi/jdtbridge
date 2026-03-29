package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for ProjectHandler using a real JDT workspace.
 */
public class ProjectInfoIntegrationTest {

    private static final ProjectHandler handler = new ProjectHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    @Test
    public void projectInfoBasicFields() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertEquals(TestFixture.PROJECT_NAME,
                obj.get("name").getAsString());
        assertTrue(obj.get("totalTypes").getAsInt() > 0,
                "Should have types");
        assertTrue(obj.getAsJsonArray("sourceRoots").size() > 0,
                "Should have source roots");
    }

    @Test
    public void projectInfoIncludesPackages() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        // packages are nested inside sourceRoots[i]
        JsonArray roots = obj.getAsJsonArray("sourceRoots");
        assertNotNull(roots, "Should have sourceRoots");
        assertTrue(roots.size() > 0, "Should have at least one root");
        JsonArray packages = roots.get(0).getAsJsonObject()
                .getAsJsonArray("packages");
        assertNotNull(packages, "Should have packages array");
        assertTrue(hasPackage(packages, "test.model"),
                "Should have test.model");
        assertTrue(hasPackage(packages, "test.service"),
                "Should have test.service");
        assertTrue(hasPackage(packages, "test.broken"),
                "Should have test.broken");
    }

    @Test
    public void projectInfoIncludesTypes() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        JsonArray roots = obj.getAsJsonArray("sourceRoots");
        assertTrue(hasType(roots, "Animal"),
                "Should have Animal");
        assertTrue(hasType(roots, "Dog"),
                "Should have Dog");
        assertTrue(hasType(roots, "AnimalService"),
                "Should have AnimalService");
    }

    @Test
    public void projectInfoMembersIncluded() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertTrue(obj.get("membersIncluded").getAsBoolean(),
                "Small project should include members");
    }

    @Test
    public void projectInfoNotFound() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", "nonexistent-xyz"));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        assertNotNull(obj.get("error"), "Should have error field");
    }

    @Test
    public void projectInfoHasJavaNature() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        JsonObject obj = JsonParser.parseString(json)
                .getAsJsonObject();
        JsonArray natures = obj.getAsJsonArray("natures");
        assertNotNull(natures, "Should have natures array");
        boolean hasJava = false;
        for (JsonElement e : natures) {
            if (e.getAsString().contains("java")) {
                hasJava = true;
                break;
            }
        }
        assertTrue(hasJava, "Should have java nature");
    }

    private static boolean hasPackage(JsonArray packages,
            String name) {
        for (JsonElement e : packages) {
            JsonObject pkg = e.getAsJsonObject();
            if (name.equals(pkg.get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasType(JsonArray roots,
            String typeName) {
        for (JsonElement root : roots) {
            JsonArray pkgs = root.getAsJsonObject()
                    .getAsJsonArray("packages");
            for (JsonElement p : pkgs) {
                JsonArray types = p.getAsJsonObject()
                        .getAsJsonArray("types");
                for (JsonElement t : types) {
                    if (typeName.equals(t.getAsJsonObject()
                            .get("name").getAsString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
