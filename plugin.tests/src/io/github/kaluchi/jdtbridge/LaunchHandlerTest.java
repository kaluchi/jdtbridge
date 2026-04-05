package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.gson.JsonParser;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
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
            String json = handler.handleList(Map.of(), ProjectScope.ALL);
            if (!json.equals("[]")) {
                assertTrue(json.contains("\"configId\""),
                        "Should have configId: " + json);
                assertTrue(json.contains("\"launchId\""),
                        "Should have launchId: " + json);
                assertTrue(json.contains("\"terminated\""),
                        "Should have terminated: " + json);
            }
        }

        @Test
        void containsModeAndType() {
            String json = handler.handleList(Map.of(), ProjectScope.ALL);
            if (!json.equals("[]")) {
                assertTrue(json.contains("\"mode\""),
                        "Should have mode: " + json);
                assertTrue(json.contains("\"configType\""),
                        "Should have configType: " + json);
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
            if (!json.equals("[]")) {
                assertTrue(json.contains("\"configId\""),
                        "Should have configId: " + json);
                assertTrue(json.contains("\"configType\""),
                        "Should have configType: " + json);
            }
        }

        @Test
        void junitConfigHasClassAndRunner() {
            String json = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            for (var el : arr) {
                var obj = el.getAsJsonObject();
                String type = obj.get("configType").getAsString();
                if ("JUnit".equals(type)
                        || "JUnit Plug-in Test".equals(type)) {
                    assertTrue(obj.has("class")
                            || obj.has("project"),
                            "JUnit config should have class "
                            + "or project: " + obj);
                    return;
                }
            }
            // No JUnit configs — skip
        }

        @Test
        void mavenConfigHasGoals() {
            String json = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(json)
                    .getAsJsonArray();
            for (var el : arr) {
                var obj = el.getAsJsonObject();
                String type = obj.get("configType").getAsString();
                if ("Maven Build".equals(type)) {
                    assertTrue(obj.has("goals"),
                            "Maven config should have goals: "
                            + obj);
                    return;
                }
            }
            // No Maven configs — skip
        }
    }

    @Nested
    class Config {

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
            // Find any existing config name
            String listJson = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(listJson)
                    .getAsJsonArray();
            if (arr.isEmpty()) return; // no configs to test
            String configId = arr.get(0).getAsJsonObject()
                    .get("configId").getAsString();

            String json = handler.handleConfig(
                    Map.of("configId", configId));
            assertFalse(json.contains("\"error\""),
                    "Should not error: " + json);
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals(configId, obj.get("configId").getAsString());
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
            String listJson = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(listJson)
                    .getAsJsonArray();
            if (arr.isEmpty()) return;
            String configId = arr.get(0).getAsJsonObject()
                    .get("configId").getAsString();

            String json = handler.handleConfig(
                    Map.of("configId", configId, "format", "xml"));
            assertFalse(json.contains("\"error\""),
                    "Should not error: " + json);
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals(configId, obj.get("configId").getAsString());
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
            // Find a JUnit config specifically
            String listJson = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(listJson)
                    .getAsJsonArray();
            String configId = null;
            for (var el : arr) {
                var obj = el.getAsJsonObject();
                String type = obj.get("configType").getAsString();
                if ("JUnit".equals(type)
                        || "JUnit Plug-in Test".equals(type)) {
                    configId = obj.get("configId").getAsString();
                    break;
                }
            }
            if (configId == null) return;

            String json = handler.handleConfig(
                    Map.of("configId", configId));
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
            // Get any config and verify attribute value types
            String listJson = handler.handleConfigs(Map.of(), ProjectScope.ALL);
            var arr = JsonParser.parseString(listJson)
                    .getAsJsonArray();
            if (arr.isEmpty()) return;
            String configId = arr.get(0).getAsJsonObject()
                    .get("configId").getAsString();

            String json = handler.handleConfig(
                    Map.of("configId", configId));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            var attrs = obj.getAsJsonObject("attributes");
            // Attributes should be valid JSON — no crashes
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
