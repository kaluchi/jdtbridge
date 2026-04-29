package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        assertEquals("clean install",
                maven.get("goals").getAsString(),
                "Maven goals must round-trip: " + maven);
    }

    @Test
    void mavenSummaryHasGoalsAndProfiles() throws Exception {
        mavenCfg.delete();
        ILaunchManager mgr = DebugPlugin.getDefault()
                .getLaunchManager();
        ILaunchConfigurationType type = mgr
                .getLaunchConfigurationType(
                        "org.eclipse.m2e.Maven2LaunchConfigurationType");
        ILaunchConfigurationWorkingCopy wc =
                type.newInstance(null, "SumMaven");
        wc.setAttribute("M2_GOALS", "clean verify");
        wc.setAttribute("M2_PROFILES", "ci");
        mavenCfg = wc.doSave();

        JsonArray arr = JsonParser.parseString(
                handler.handleConfigs(Map.of(),
                        ProjectScope.ALL)).getAsJsonArray();
        JsonObject maven = null;
        for (var el : arr) {
            var obj = el.getAsJsonObject();
            if ("SumMaven".equals(
                    obj.get("configId").getAsString())) {
                maven = obj;
                break;
            }
        }
        assertNotNull(maven, "SumMaven must appear");
        assertEquals("clean verify",
                maven.get("goals").getAsString());
        assertEquals("ci",
                maven.get("profiles").getAsString());
    }

    @Test
    void debugModeSetsCorrectMode() throws Exception {
        String json = handler.handleRun(
                java.util.Map.of("configId",
                        "MavenConfigTest-Maven",
                        "debug", "true"));
        var obj = JsonParser.parseString(json).getAsJsonObject();
        assertNotNull(obj.get("mode"),
                "run must return mode: " + json);
        assertEquals("debug",
                obj.get("mode").getAsString());
    }

    @Test
    void agentConfigHasProviderAndAgent() throws Exception {
        ILaunchManager mgr = DebugPlugin.getDefault()
                .getLaunchManager();
        ILaunchConfigurationType agentType = mgr
                .getLaunchConfigurationType(
                        "io.github.kaluchi.jdtbridge.ui"
                        + ".agentLaunchType");
        assertNotNull(agentType,
                "agent launch type missing in UI runtime");
        ILaunchConfigurationWorkingCopy wc =
                agentType.newInstance(null, "AgentConfigTest");
        wc.setAttribute(
                "io.github.kaluchi.jdtbridge.ui.provider",
                "local");
        wc.setAttribute(
                "io.github.kaluchi.jdtbridge.ui.agent",
                "claude");
        wc.setAttribute(
                "io.github.kaluchi.jdtbridge.ui.agentArgs",
                "--continue");
        ILaunchConfiguration agentCfg = wc.doSave();
        try {
            String json = handler.handleConfigs(
                    Map.of(), ProjectScope.ALL);
            JsonArray arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            JsonObject agent = null;
            for (var el : arr) {
                var obj = el.getAsJsonObject();
                if ("AgentConfigTest".equals(
                        obj.get("configId").getAsString())) {
                    agent = obj;
                    break;
                }
            }
            assertNotNull(agent,
                    "Agent config must appear: " + json);
            assertEquals("local",
                    agent.get("provider").getAsString());
            assertEquals("claude",
                    agent.get("agent").getAsString());
            assertEquals("--continue",
                    agent.get("agentArgs").getAsString());
        } finally {
            agentCfg.delete();
        }
    }
}
