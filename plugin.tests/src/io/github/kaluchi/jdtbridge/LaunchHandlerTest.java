package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for LaunchHandler — list and console commands.
 * Tests against the real Eclipse launch infrastructure.
 */
public class LaunchHandlerTest {

    private final LaunchTracker tracker = new LaunchTracker();
    private final LaunchHandler handler = new LaunchHandler(tracker);

    @BeforeEach
    void startTracker() {
        tracker.start();
    }

    @AfterEach
    void stopTracker() {
        tracker.stop();
    }

    // ---- Synthetic-config helpers ----
    //
    // PDE test runtime starts with an empty workspace. Tests that
    // need a launch config must create their own; tests that need a
    // running launch must add an ILaunch to the manager. Returned
    // handles are deleted in the matching @AfterEach to keep the
    // workspace clean between tests.

    private static ILaunchConfiguration createConfig(
            String typeId, String name,
            java.util.Map<String, String> attrs) throws Exception {
        ILaunchManager mgr =
                DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type =
                mgr.getLaunchConfigurationType(typeId);
        assertNotNull(type, "Launch type missing: " + typeId);
        ILaunchConfigurationWorkingCopy wc =
                type.newInstance(null, name);
        for (var e : attrs.entrySet()) {
            wc.setAttribute(e.getKey(), e.getValue());
        }
        return wc.doSave();
    }

    private static ILaunchConfiguration createJavaConfig(
            String name, String mainType) throws Exception {
        return createConfig(
                "org.eclipse.jdt.launching.localJavaApplication",
                name,
                Map.of("org.eclipse.jdt.launching.MAIN_TYPE",
                        mainType));
    }

    private static ILaunchConfiguration createJunitConfig(
            String name, String testClass) throws Exception {
        return createConfig(
                "org.eclipse.jdt.junit.launchconfig",
                name,
                Map.of("org.eclipse.jdt.launching.MAIN_TYPE",
                        testClass));
    }

    private static ILaunchConfiguration createMavenConfig(
            String name, String goals) throws Exception {
        return createConfig(
                "org.eclipse.m2e.Maven2LaunchConfigurationType",
                name,
                Map.of("M2_GOALS", goals));
    }

    private static void deleteIfPresent(ILaunchConfiguration cfg)
            throws Exception {
        if (cfg != null && cfg.exists()) cfg.delete();
    }

    private static ILaunch addSyntheticLaunch(String mode) {
        ILaunchManager mgr =
                DebugPlugin.getDefault().getLaunchManager();
        ILaunch launch = new org.eclipse.debug.core.Launch(
                null, mode, null);
        launch.setAttribute(
                DebugPlugin.ATTR_LAUNCH_TIMESTAMP,
                Long.toString(System.currentTimeMillis()));
        mgr.addLaunch(launch);
        return launch;
    }

    private static void removeIfPresent(ILaunch launch) {
        if (launch == null) return;
        DebugPlugin.getDefault().getLaunchManager().removeLaunch(launch);
    }

    @Nested
    class List {

        @Test
        void returnsArray() {
            String json = handler.handleList(Map.of(), ProjectScope.ALL);
            assertNotNull(json);
            assertTrue(json.startsWith("["),
                    "Should be JSON array: " + json);
        }

        @Test
        void emptyWhenNoLaunches() {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch[] existing = mgr.getLaunches();
            // Remove all for clean test
            if (existing.length > 0) {
                mgr.removeLaunches(existing);
            }
            String json = handler.handleList(Map.of(), ProjectScope.ALL);
            assertEquals("[]", json);
            // Restore
            for (ILaunch l : existing) {
                mgr.addLaunch(l);
            }
        }

        @Test
        void containsIdentityFields() {
            ILaunch launch = addSyntheticLaunch("run");
            try {
                String json = handler.handleList(Map.of(), ProjectScope.ALL);
                assertFalse(json.equals("[]"),
                        "Synthetic launch should appear: " + json);
                assertTrue(json.contains("\"configId\""),
                        "Should have configId: " + json);
                assertTrue(json.contains("\"launchId\""),
                        "Should have launchId: " + json);
                assertTrue(json.contains("\"terminated\""),
                        "Should have terminated: " + json);
            } finally {
                removeIfPresent(launch);
            }
        }

        @Test
        void containsModeAndType() {
            ILaunch launch = addSyntheticLaunch("debug");
            try {
                String json = handler.handleList(Map.of(), ProjectScope.ALL);
                assertFalse(json.equals("[]"),
                        "Synthetic launch should appear: " + json);
                assertTrue(json.contains("\"mode\""),
                        "Should have mode: " + json);
                assertTrue(json.contains("\"configType\""),
                        "Should have configType: " + json);
            } finally {
                removeIfPresent(launch);
            }
        }
    }

    @Nested
    class Console {

        @Test
        void missingNameReturnsError() {
            String json = handler.handleConsole(Map.of());
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("Missing"),
                    "Should say missing: " + json);
        }

        @Test
        void unknownNameReturnsError() {
            String json = handler.handleConsole(
                    Map.of("launchId", "no-such-launch-xyz"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("not found"),
                    "Should say not found: " + json);
        }

        @Test
        void tailParamIsRespected() {
            // Even for non-existent launch, tail param should not
            // cause crash
            String json = handler.handleConsole(
                    Map.of("launchId", "no-such-launch", "tail", "10"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void invalidTailIsIgnored() {
            String json = handler.handleConsole(
                    Map.of("launchId", "no-such-launch", "tail", "abc"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void streamFilterDoesNotCrash() {
            String json = handler.handleConsole(
                    Map.of("launchId", "no-such-launch",
                            "stream", "stderr"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }
    }

    @Nested
    class Configs {

        private ILaunchConfiguration javaCfg;
        private ILaunchConfiguration junitCfg;
        private ILaunchConfiguration mavenCfg;

        @BeforeEach
        void createConfigs() throws Exception {
            javaCfg = createJavaConfig(
                    "ConfigsTest-Java", "test.Main");
            junitCfg = createJunitConfig(
                    "ConfigsTest-JUnit", "test.SomeTest");
            mavenCfg = createMavenConfig(
                    "ConfigsTest-Maven", "clean install");
        }

        @AfterEach
        void deleteConfigs() throws Exception {
            deleteIfPresent(javaCfg);
            deleteIfPresent(junitCfg);
            deleteIfPresent(mavenCfg);
        }

        @Test
        void returnsArray() {
            String json = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            assertNotNull(json);
            assertTrue(json.startsWith("["),
                    "Should be JSON array: " + json);
        }

        @Test
        void containsNameAndType() {
            String json = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            assertFalse(json.equals("[]"),
                    "Created configs should appear: " + json);
            assertTrue(json.contains("\"configId\""),
                    "Should have configId: " + json);
            assertTrue(json.contains("\"configType\""),
                    "Should have configType: " + json);
        }

        @Test
        void junitConfigHasClassAndRunner() {
            String json = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            JsonObject junit = findByConfigId(arr, "ConfigsTest-JUnit");
            assertNotNull(junit,
                    "Created JUnit config must appear: " + json);
            assertTrue(junit.has("class") || junit.has("project"),
                    "JUnit config should have class or project: "
                    + junit);
        }

        @Test
        void mavenConfigHasGoals() {
            String json = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            JsonObject maven = findByConfigId(arr, "ConfigsTest-Maven");
            assertNotNull(maven,
                    "Created Maven config must appear: " + json);
            assertTrue(maven.has("goals"),
                    "Maven config should have goals: " + maven);
        }
    }

    private static JsonObject findByConfigId(
            com.google.gson.JsonArray arr, String configId) {
        for (var el : arr) {
            var obj = el.getAsJsonObject();
            if (configId.equals(obj.get("configId").getAsString())) {
                return obj;
            }
        }
        return null;
    }

    @Nested
    class Config {

        private ILaunchConfiguration javaCfg;
        private ILaunchConfiguration junitCfg;

        @BeforeEach
        void createConfigs() throws Exception {
            javaCfg = createJavaConfig(
                    "ConfigTest-Java", "test.Main");
            junitCfg = createJunitConfig(
                    "ConfigTest-JUnit", "test.SomeTest");
        }

        @AfterEach
        void deleteConfigs() throws Exception {
            deleteIfPresent(javaCfg);
            deleteIfPresent(junitCfg);
        }

        @Test
        void missingNameReturnsError() {
            String json = handler.handleConfig(Map.of());
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("Missing"),
                    "Should say missing: " + json);
        }

        @Test
        void unknownConfigReturnsError() {
            String json = handler.handleConfig(
                    Map.of("configId", "no-such-config-xyz-999"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("not found"),
                    "Should say not found: " + json);
        }

        @Test
        void knownConfigReturnsAttributes() {
            String json = handler.handleConfig(
                    Map.of("configId", "ConfigTest-Java"));
            assertFalse(json.contains("\"error\""),
                    "Should not error: " + json);
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals("ConfigTest-Java",
                    obj.get("configId").getAsString());
            assertTrue(obj.has("configType"),
                    "Should have configType: " + json);
            assertTrue(obj.has("configTypeId"),
                    "Should have configTypeId: " + json);
            assertTrue(obj.has("attributes"),
                    "Should have attributes: " + json);
            assertTrue(obj.get("attributes").isJsonObject(),
                    "Attributes should be object: " + json);
        }

        @Test
        void xmlFormatReturnsXmlContent() {
            String json = handler.handleConfig(
                    Map.of("configId", "ConfigTest-Java",
                            "format", "xml"));
            assertFalse(json.contains("\"error\""),
                    "Should not error: " + json);
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals("ConfigTest-Java",
                    obj.get("configId").getAsString());
            assertTrue(obj.has("xml"),
                    "Should have xml field: " + json);
            String xml = obj.get("xml").getAsString();
            assertTrue(xml.contains("<?xml")
                    || xml.contains("<launchConfiguration"),
                    "XML should contain launch config: "
                    + xml.substring(0,
                            Math.min(200, xml.length())));
        }

        @Test
        void attributesContainExpectedKeys() {
            String json = handler.handleConfig(
                    Map.of("configId", "ConfigTest-JUnit"));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            var attrs = obj.getAsJsonObject("attributes");
            assertTrue(
                    attrs.has(
                        "org.eclipse.jdt.launching.MAIN_TYPE")
                    || attrs.has(
                        "org.eclipse.jdt.junit.CONTAINER"),
                    "JUnit config should have test class or "
                    + "container in attributes: " + attrs);
        }

        @Test
        void attributeTypesPreserved() {
            String json = handler.handleConfig(
                    Map.of("configId", "ConfigTest-Java"));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            var attrs = obj.getAsJsonObject("attributes");
            assertNotNull(attrs);
            assertTrue(attrs.size() > 0,
                    "Config should have some attributes");
        }
    }

    @Nested
    class Clear {

        @Test
        void clearReturnsRemovedCount() {
            String json = handler.handleClear(Map.of());
            assertTrue(json.contains("\"removed\""),
                    "Should have removed count: " + json);
        }

        @Test
        void clearByNameDoesNotCrash() {
            String json = handler.handleClear(
                    Map.of("launchId", "no-such-launch"));
            assertTrue(json.contains("\"removed\":0"),
                    "Should remove 0: " + json);
        }

        @Test
        void clearRemovesTerminatedLaunch() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            // Attach a finished process so isTerminated() = true
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "clear-test-process");
            mgr.addLaunch(launch);

            assertTrue(launch.isTerminated(),
                    "Launch should be terminated");
            assertTrue(mgr.isRegistered(launch),
                    "Launch should be registered");

            handler.handleClear(Map.of());

            assertFalse(mgr.isRegistered(launch),
                    "Launch should be removed after clear");
        }

        @Test
        void clearByNameSelectiveRemoval() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch1 = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process p1 = new ProcessBuilder(
                    "java", "-version").start();
            p1.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch1, p1, "keep-this");

            ILaunch launch2 = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process p2 = new ProcessBuilder(
                    "java", "-version").start();
            p2.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch2, p2, "remove-this");

            mgr.addLaunch(launch1);
            mgr.addLaunch(launch2);

            // Clear only "remove-this"
            handler.handleClear(
                    Map.of("launchId", "remove-this"));

            assertTrue(mgr.isRegistered(launch1),
                    "keep-this should still be registered");
            assertFalse(mgr.isRegistered(launch2),
                    "remove-this should be removed");

            mgr.removeLaunch(launch1);
        }
    }

    // ---- Tests with a synthetic launch ----

    @Nested
    class WithSyntheticLaunch {

        @Test
        void syntheticLaunchAppearsInList() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            launch.setAttribute(
                    DebugPlugin.ATTR_LAUNCH_TIMESTAMP,
                    Long.toString(System.currentTimeMillis()));
            mgr.addLaunch(launch);
            try {
                String json = handler.handleList(Map.of(), ProjectScope.ALL);
                assertFalse(json.equals("[]"),
                        "Should have launch: " + json);
                assertTrue(json.contains("\"mode\":\"run\""),
                        "Should have run mode: " + json);
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void syntheticLaunchWithProcess() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);

            // Create a real process with output
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc, "java -version");

            mgr.addLaunch(launch);
            try {
                // Should appear in list
                String listJson = handler.handleList(Map.of(), ProjectScope.ALL);
                assertTrue(listJson.contains("\"terminated\""),
                        "Should have terminated: " + listJson);

                // Console should return valid JSON
                String consoleJson = handler.handleConsole(
                        Map.of("launchId", "java -version"));
                assertFalse(consoleJson.contains("error"),
                        "Should not error: " + consoleJson);
                assertTrue(
                        consoleJson.contains("\"output\""),
                        "Should have output: " + consoleJson);
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void consoleTailOnSyntheticLaunch() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);

            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc, "java -version");

            mgr.addLaunch(launch);
            try {
                String fullJson = handler.handleConsole(
                        Map.of("launchId", "java -version"));
                String tailJson = handler.handleConsole(
                        Map.of("launchId", "java -version",
                                "tail", "1"));

                var fullObj = JsonParser.parseString(fullJson)
                        .getAsJsonObject();
                var tailObj = JsonParser.parseString(tailJson)
                        .getAsJsonObject();
                String fullOut =
                        fullObj.get("output").getAsString();
                String tailOut =
                        tailObj.get("output").getAsString();

                assertNotNull(fullOut, "Should have full output");
                assertNotNull(tailOut, "Should have tail output");
                assertTrue(
                        tailOut.length() <= fullOut.length(),
                        "Tail should be <= full");
            } finally {
                mgr.removeLaunch(launch);
            }
        }
    }

    @Nested
    class ConsoleWithEmptyStreams {

        @Test
        void launchWithNoProcessReturnsEmptyOutput()
                throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            mgr.addLaunch(launch);
            try {
                String json = handler.handleConsole(
                        Map.of("launchId", "(unknown)"));
                assertFalse(json.contains("error"),
                        "Should not error: " + json);
                assertTrue(json.contains("\"output\":\"\""),
                        "Should have empty output: " + json);
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void processWithStreamsReturnsValidJson() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "streams-test");
            mgr.addLaunch(launch);
            try {
                String json = handler.handleConsole(
                        Map.of("launchId", "streams-test"));
                assertFalse(json.contains("error"),
                        "Should not be error: " + json);
                assertTrue(json.contains("\"output\""),
                        "Should have output field: " + json);
                assertTrue(json.contains("\"terminated\""),
                        "Should have terminated: " + json);
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void consoleOutputContainsTerminatedFlag() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "terminated-flag-test");
            mgr.addLaunch(launch);
            try {
                String json = handler.handleConsole(
                        Map.of("launchId", "terminated-flag-test"));
                assertTrue(
                        json.contains("\"terminated\":true"),
                        "Should have terminated flag: " + json);
                assertTrue(json.contains("\"configId\""),
                        "Should have configId: " + json);
            } finally {
                mgr.removeLaunch(launch);
            }
        }
    }

    @Nested
    class Run {

        @Test
        void missingNameReturnsError() {
            String json = handler.handleRun(Map.of());
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void unknownConfigReturnsError() {
            String json = handler.handleRun(
                    Map.of("configId", "no-such-config-xyz"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("not found"),
                    "Should say not found: " + json);
        }
    }

    @Nested
    class Stop {

        @Test
        void missingNameReturnsError() {
            String json = handler.handleStop(Map.of());
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void unknownLaunchReturnsError() {
            String json = handler.handleStop(
                    Map.of("launchId", "no-such-launch-xyz"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void terminatedLaunchReturnsError() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "stop-test-terminated");
            mgr.addLaunch(launch);
            try {
                String json = handler.handleStop(
                        Map.of("launchId", "stop-test-terminated"));
                assertTrue(json.contains("Already terminated"),
                        "Should say already terminated: " + json);
            } finally {
                mgr.removeLaunch(launch);
            }
        }
    }

    // ---- HTTP routing tests ----

    @Nested
    class HttpRouting {

        @Test
        void parseQueryWithLaunchParams() {
            var params = HttpServer.parseQuery(
                    "name=my-server&tail=50&stream=stderr");
            assertEquals("my-server", params.get("name"));
            assertEquals("50", params.get("tail"));
            assertEquals("stderr", params.get("stream"));
        }

        @Test
        void parseQueryNameEncoded() {
            var params = HttpServer.parseQuery(
                    "name=My%20Test%20Config");
            assertEquals("My Test Config", params.get("name"));
        }
    }

    @Nested
    class ArgsAttribute {

        @Test
        void externalToolsType() {
            assertEquals(
                    "org.eclipse.ui.externaltools.ATTR_TOOL_ARGUMENTS",
                    LaunchHandler.argsAttribute(
                            "org.eclipse.ui.externaltools"
                            + ".ProgramLaunchConfigurationType"));
        }

        @Test
        void javaAppType() {
            assertEquals(
                    "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS",
                    LaunchHandler.argsAttribute(
                            "org.eclipse.jdt.launching"
                            + ".localJavaApplication"));
        }

        @Test
        void mavenType() {
            assertEquals(
                    "org.eclipse.ui.externaltools.ATTR_TOOL_ARGUMENTS",
                    LaunchHandler.argsAttribute(
                            "org.eclipse.m2e"
                            + ".Maven2LaunchConfigurationType"));
        }

        @Test
        void junitType() {
            assertEquals(
                    "org.eclipse.jdt.launching.VM_ARGUMENTS",
                    LaunchHandler.argsAttribute(
                            "org.eclipse.jdt.junit.launchconfig"));
        }

        @Test
        void pdeJunitType() {
            assertEquals(
                    "org.eclipse.jdt.launching.VM_ARGUMENTS",
                    LaunchHandler.argsAttribute(
                            "org.eclipse.pde.ui.JunitLaunchConfig"));
        }

        @Test
        void agentType() {
            assertEquals(
                    "io.github.kaluchi.jdtbridge.ui.agentArgs",
                    LaunchHandler.argsAttribute(
                            "io.github.kaluchi.jdtbridge.ui"
                            + ".agentLaunchType"));
        }

        @Test
        void unknownTypeDefaultsToProgramArgs() {
            assertEquals(
                    "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS",
                    LaunchHandler.argsAttribute(
                            "some.unknown.type"));
        }
    }
}
