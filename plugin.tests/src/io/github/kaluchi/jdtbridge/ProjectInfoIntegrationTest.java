package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for ProjectHandler using a real JDT workspace.
 */
@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")
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
        assertTrue(json.contains("\"name\":\"" + TestFixture.PROJECT_NAME + "\""),
                "Should have name: " + json);
        assertTrue(json.contains("\"totalTypes\":"),
                "Should have totalTypes: " + json);
        assertTrue(json.contains("\"sourceRoots\":["),
                "Should have sourceRoots: " + json);
    }

    @Test
    public void projectInfoIncludesPackages() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        assertTrue(json.contains("test.model"),
                "Should have test.model: " + json);
        assertTrue(json.contains("test.service"),
                "Should have test.service: " + json);
        assertTrue(json.contains("test.broken"),
                "Should have test.broken: " + json);
    }

    @Test
    public void projectInfoIncludesTypes() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        assertTrue(json.contains("Animal"),
                "Should have Animal: " + json);
        assertTrue(json.contains("Dog"),
                "Should have Dog: " + json);
        assertTrue(json.contains("AnimalService"),
                "Should have AnimalService: " + json);
    }

    @Test
    public void projectInfoMembersIncluded() throws Exception {
        // Small project — members should be included
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        assertTrue(json.contains("\"membersIncluded\":true"),
                "Should include members: " + json);
        // With members, methods should be an object with visibility groups
        assertTrue(json.contains("\"public\":["),
                "Should have public methods: " + json);
    }

    @Test
    public void projectInfoNotFound() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", "nonexistent-xyz"));
        assertTrue(json.contains("error"),
                "Should return error: " + json);
    }

    @Test
    public void projectInfoHasJavaNature() throws Exception {
        String json = handler.handleProjectInfo(
                Map.of("project", TestFixture.PROJECT_NAME));
        assertTrue(json.contains("\"java\""),
                "Should have java nature: " + json);
    }
}
