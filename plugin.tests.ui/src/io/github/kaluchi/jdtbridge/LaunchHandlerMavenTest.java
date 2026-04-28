package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * UI-runtime LaunchHandler tests — Maven launch type only registers
 * when m2e is on the platform, which the headless tycho-surefire
 * harness in plugin.tests does not provide.
 */
public class LaunchHandlerMavenTest {

    private final LaunchTracker tracker = new LaunchTracker();
    private final LaunchHandler handler = new LaunchHandler(tracker);
    private ILaunchConfiguration mavenCfg;

    @BeforeEach
    void setUp() throws Exception {
        tracker.start();
        ILaunchManager mgr = DebugPlugin.getDefault()
                .getLaunchManager();
        ILaunchConfigurationType type = mgr
                .getLaunchConfigurationType(
                        "org.eclipse.m2e.Maven2LaunchConfigurationType");
        assertNotNull(type, "m2e launch type missing in runtime");
        ILaunchConfigurationWorkingCopy wc = type.newInstance(
                null, "MavenConfigTest-Maven");
        wc.setAttribute("M2_GOALS", "clean install");
        mavenCfg = wc.doSave();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mavenCfg != null && mavenCfg.exists()) {
            mavenCfg.delete();
        }
        tracker.stop();
    }

    @Test
    void mavenConfigHasGoals() {
        String json = handler.handleConfigs(
                Map.of(), ProjectScope.ALL);
        JsonArray arr = JsonParser.parseString(json)
                .getAsJsonArray();
        JsonObject maven = null;
        for (var el : arr) {
            var obj = el.getAsJsonObject();
            if ("MavenConfigTest-Maven".equals(
                    obj.get("configId").getAsString())) {
                maven = obj;
                break;
            }
        }
        assertNotNull(maven,
                "Created Maven config must appear: " + json);
        assertTrue(maven.has("goals"),
                "Maven config should have goals: " + maven);
    }
}
