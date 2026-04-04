package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
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
            JsonObject response = JsonParser.parseString(responseJson)
                    .getAsJsonObject();

            assertTrue(response.get("imported").getAsBoolean());
            assertEquals(configId,
                    response.get("configId").getAsString());

            // Verify config is visible in LaunchManager
            ILaunchManager launchManager = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfiguration found = Arrays.stream(
                    launchManager.getLaunchConfigurations())
                    .filter(c -> configId.equals(c.getName()))
                    .findFirst().orElse(null);
            assertNotNull(found,
                    "Imported config should be in LaunchManager");
        }

        @Test
        void importPreservesAttributes() throws Exception {
            String configId = "test-import-attrs-"
                    + System.currentTimeMillis();
            importedConfigId = configId;

            launchHandler.handleImport(
                    Map.of("configId", configId), MAVEN_LAUNCH_XML);

            ILaunchManager launchManager = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfiguration config = Arrays.stream(
                    launchManager.getLaunchConfigurations())
                    .filter(c -> configId.equals(c.getName()))
                    .findFirst().orElse(null);
            assertNotNull(config);

            assertEquals("clean verify",
                    config.getAttribute("M2_GOALS", ""));
            assertEquals(4,
                    config.getAttribute("M2_THREADS", 0));
            assertEquals(false,
                    config.getAttribute("M2_DEBUG_OUTPUT", true));
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
