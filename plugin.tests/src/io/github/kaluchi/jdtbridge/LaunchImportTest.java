package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.debug.core.DebugPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for launch configuration import via LaunchHandler.handleImport().
 */
public class LaunchImportTest {

    private static final String MAVEN_LAUNCH_XML =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <launchConfiguration type="org.eclipse.m2e.Maven2LaunchConfigurationType">
                <booleanAttribute key="M2_DEBUG_OUTPUT" value="false"/>
                <stringAttribute key="M2_GOALS" value="clean verify"/>
                <booleanAttribute key="M2_NON_RECURSIVE" value="false"/>
                <booleanAttribute key="M2_OFFLINE" value="false"/>
                <stringAttribute key="M2_PROFILES" value=""/>
                <listAttribute key="M2_PROPERTIES"/>
                <stringAttribute key="M2_RUNTIME" value="EMBEDDED"/>
                <booleanAttribute key="M2_SKIP_TESTS" value="false"/>
                <booleanAttribute key="M2_UPDATE_SNAPSHOTS" value="false"/>
                <booleanAttribute key="M2_WORKSPACE_RESOLUTION" value="false"/>
                <intAttribute key="M2_THREADS" value="4"/>
            </launchConfiguration>
            """;

    private final LaunchHandler launchHandler = new LaunchHandler(
            new LaunchTracker());

    /** Configs created during test — cleaned up in @AfterEach. */
    private String importedConfigId;

    @AfterEach
    void cleanupImportedConfig() {
        if (importedConfigId != null) {
            launchHandler.handleConfigDelete(
                    Map.of("configId", importedConfigId));
            importedConfigId = null;
        }
    }

    @Nested
    class SuccessfulImport {
        @Test
        void importCreatesConfig() throws Exception {
            String configId = "test-import-" + System.currentTimeMillis();
            importedConfigId = configId;

            String responseJson = launchHandler.handleImport(
                    Map.of("configId", configId), MAVEN_LAUNCH_XML);
            JsonObject importResponse = JsonParser.parseString(responseJson)
                    .getAsJsonObject();

            assertTrue(importResponse.get("imported").getAsBoolean());
            assertEquals(configId,
                    importResponse.get("configId").getAsString());

            // Verify file exists on disk (LaunchManager cache may lag)
            Path launchFile = DebugPlugin.getDefault()
                    .getStateLocation().toPath()
                    .resolve(".launches")
                    .resolve(configId + ".launch");
            assertTrue(Files.exists(launchFile),
                    "Imported .launch file should exist on disk");
        }

        @Test
        void importPreservesAttributes() throws Exception {
            String configId = "test-import-attrs-"
                    + System.currentTimeMillis();
            importedConfigId = configId;

            launchHandler.handleImport(
                    Map.of("configId", configId), MAVEN_LAUNCH_XML);

            // Read the file directly — LaunchManager cache may lag
            Path launchFile = DebugPlugin.getDefault()
                    .getStateLocation().toPath()
                    .resolve(".launches")
                    .resolve(configId + ".launch");
            String launchXml = Files.readString(launchFile);

            assertTrue(launchXml.contains("clean verify"),
                    "Should contain M2_GOALS");
            assertTrue(launchXml.contains("M2_THREADS"),
                    "Should contain M2_THREADS");
            assertTrue(launchXml.contains("M2_DEBUG_OUTPUT"),
                    "Should contain M2_DEBUG_OUTPUT");
        }

        @Test
        void importWithCustomConfigId() throws Exception {
            String configId = "custom-name-"
                    + System.currentTimeMillis();
            importedConfigId = configId;

            String responseJson = launchHandler.handleImport(
                    Map.of("configId", configId), MAVEN_LAUNCH_XML);
            JsonObject response = JsonParser.parseString(responseJson)
                    .getAsJsonObject();

            assertEquals(configId,
                    response.get("configId").getAsString());
        }
    }

    @Nested
    class ImportErrors {
        @Test
        void rejectsMissingConfigId() {
            String responseJson = launchHandler.handleImport(
                    Map.of(), MAVEN_LAUNCH_XML);
            assertTrue(responseJson.contains("Missing"));
        }

        @Test
        void rejectsEmptyBody() {
            String responseJson = launchHandler.handleImport(
                    Map.of("configId", "test"), "");
            assertTrue(responseJson.contains("Missing"));
        }

        @Test
        void rejectsNullBody() {
            String responseJson = launchHandler.handleImport(
                    Map.of("configId", "test"), null);
            assertTrue(responseJson.contains("Missing"));
        }

        @Test
        void rejectsDuplicateConfigId() throws Exception {
            String configId = "test-dup-"
                    + System.currentTimeMillis();
            importedConfigId = configId;

            // First import succeeds
            launchHandler.handleImport(
                    Map.of("configId", configId), MAVEN_LAUNCH_XML);

            // Second import with same name fails
            String responseJson = launchHandler.handleImport(
                    Map.of("configId", configId), MAVEN_LAUNCH_XML);
            assertTrue(responseJson.contains("already exists"));
        }
    }
}
