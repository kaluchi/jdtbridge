package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

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

    private static void deleteIfPresent(ILaunchConfiguration cfg)
            throws Exception {
        cfg.delete();
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
        DebugPlugin.getDefault().getLaunchManager()
                .removeLaunch(launch);
    }

    @Nested
    class ListLaunches {

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

        @BeforeEach
        void createConfigs() throws Exception {
            javaCfg = createJavaConfig(
                    "ConfigsTest-Java", "test.Main");
            junitCfg = createJunitConfig(
                    "ConfigsTest-JUnit", "test.SomeTest");
        }

        @AfterEach
        void deleteConfigs() throws Exception {
            deleteIfPresent(javaCfg);
            deleteIfPresent(junitCfg);
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
    }

    private static JsonObject findByConfigId(
            com.google.gson.JsonArray arr, String configId) {
        return java.util.stream.StreamSupport
                .stream(arr.spliterator(), false)
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(o -> configId.equals(
                        o.get("configId").getAsString()))
                .findFirst()
                .orElseThrow();
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
        void missingNameReturnsError() throws Exception {
            String json = handler.handleConfig(Map.of());
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("Missing"),
                    "Should say missing: " + json);
        }

        @Test
        void unknownConfigReturnsError() throws Exception {
            String json = handler.handleConfig(
                    Map.of("configId", "no-such-config-xyz-999"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
            assertTrue(json.contains("not found"),
                    "Should say not found: " + json);
        }

        @Test
        void knownConfigReturnsAttributes() throws Exception {
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
        void xmlFormatReturnsXmlContent() throws Exception {
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
        void attributesContainExpectedKeys() throws Exception {
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
        void attributeTypesPreserved() throws Exception {
            String json = handler.handleConfig(
                    Map.of("configId", "ConfigTest-Java"));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            var attrs = obj.getAsJsonObject("attributes");
            assertNotNull(attrs);
            assertFalse(attrs.isEmpty(),
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
        void missingNameReturnsError() throws Exception {
            String json = handler.handleRun(Map.of());
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void unknownConfigReturnsError() throws Exception {
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
        void findLaunchByConfigIdPid() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfiguration cfg = createJavaConfig(
                    "FindByPid", "test.Main");
            try {
                ILaunch launch = new org.eclipse.debug.core.Launch(
                        cfg, "run", null);
                Process proc = new ProcessBuilder(
                        "java", "-version").start();
                proc.waitFor(5,
                        java.util.concurrent.TimeUnit.SECONDS);
                DebugPlugin.newProcess(launch, proc,
                        "find-pid-test");
                mgr.addLaunch(launch);
                try {
                    String pid = launch.getProcesses()[0]
                            .getAttribute(
                                    org.eclipse.debug.core.model
                                            .IProcess
                                            .ATTR_PROCESS_ID);
                    String launchId = "FindByPid:" + pid;
                    String json = handler.handleStop(
                            Map.of("launchId", launchId));
                    assertTrue(
                            json.contains("Already terminated"),
                            "terminated launch found by pid: "
                            + json);
                } finally {
                    mgr.removeLaunch(launch);
                }
            } finally {
                deleteIfPresent(cfg);
            }
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
    class ConfigDelete {

        @Test
        void missingConfigIdReturnsError() throws Exception {
            String json = handler.handleConfigDelete(Map.of());
            assertTrue(json.contains("Missing"));
        }

        @Test
        void unknownConfigReturnsError() throws Exception {
            String json = handler.handleConfigDelete(
                    Map.of("configId", "no-such-xyz"));
            assertTrue(json.contains("not found"));
        }

        @Test
        void deletesExistingLocalConfig() throws Exception {
            createJavaConfig("DeleteMe-Test", "test.Main");
            String json = handler.handleConfigDelete(
                    Map.of("configId", "DeleteMe-Test"));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.get("ok").getAsBoolean());
            assertEquals("DeleteMe-Test",
                    obj.get("configId").getAsString());
        }
    }

    @Nested
    class Import {

        @Test
        void missingConfigIdReturnsError() throws Exception {
            String json = handler.handleImport(Map.of(), "<xml/>");
            assertTrue(json.contains("Missing"));
        }

        @Test
        void missingBodyReturnsError() throws Exception {
            String json = handler.handleImport(
                    Map.of("configId", "test"), null);
            assertTrue(json.contains("Missing"));
        }

        @Test
        void pathTraversalRejected() throws Exception {
            String json = handler.handleImport(
                    Map.of("configId", "../evil"), "<xml/>");
            assertTrue(json.contains("Invalid"));
        }

        @Test
        void slashInConfigIdRejected() throws Exception {
            String json = handler.handleImport(
                    Map.of("configId", "foo/bar"), "<xml/>");
            assertTrue(json.contains("Invalid"));
        }

        @Test
        void duplicateConfigIdRejected() throws Exception {
            ILaunchConfiguration cfg = createJavaConfig(
                    "ImportDupe", "test.Main");
            try {
                String json = handler.handleImport(
                        Map.of("configId", "ImportDupe"),
                        "<xml/>");
                assertTrue(json.contains("already exists"));
            } finally {
                deleteIfPresent(cfg);
            }
        }
    }

    @Nested
    class RunSuccess {

        private ILaunchConfiguration javaCfg;

        @BeforeEach
        void create() throws Exception {
            javaCfg = createJavaConfig(
                    "RunSuccessTest", "test.Main");
        }

        @AfterEach
        void cleanup() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            for (ILaunch l : mgr.getLaunches()) {
                var cfg = l.getLaunchConfiguration();
                if (cfg != null && "RunSuccessTest".equals(
                        cfg.getName())) {
                    mgr.removeLaunch(l);
                }
            }
            deleteIfPresent(javaCfg);
        }

        @Test
        void launchesAndReturnsIdentityFields() throws Exception {
            String json = handler.handleRun(
                    Map.of("configId", "RunSuccessTest"));
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.get("ok").getAsBoolean(),
                    "run must succeed: " + json);
            assertEquals("RunSuccessTest",
                    obj.get("configId").getAsString());
            assertTrue(obj.has("launchId"));
            assertTrue(obj.has("mode"));
            assertEquals("run",
                    obj.get("mode").getAsString());
        }

        @Test
        void debugModeSetsCorrectMode() throws Exception {
            var params = new java.util.HashMap<String, String>();
            params.put("configId", "RunSuccessTest");
            params.put("debug", "true");
            String json = handler.handleRun(params);
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            if (obj.has("ok")) {
                assertEquals("debug",
                        obj.get("mode").getAsString());
            }
        }

        @Test
        void runWithExtraArgs() throws Exception {
            var params = new java.util.HashMap<String, String>();
            params.put("configId", "RunSuccessTest");
            params.put("args", "--port 9090");
            String json = handler.handleRun(params);
            var obj = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertTrue(obj.get("ok").getAsBoolean(),
                    "run with args must succeed: " + json);
        }
    }

    @Nested
    class ConfigSummaryFields {

        private ILaunchConfiguration junitCfg;
        private ILaunchConfiguration javaCfg;

        @BeforeEach
        void create() throws Exception {
            javaCfg = createJavaConfig(
                    "SumJava", "com.example.Main");
            junitCfg = createConfig(
                    "org.eclipse.jdt.junit.launchconfig",
                    "SumJUnit",
                    Map.of("org.eclipse.jdt.launching.MAIN_TYPE",
                            "com.example.FooTest",
                           "org.eclipse.jdt.junit.TEST_KIND",
                            "org.eclipse.jdt.junit.loader.junit5"));
        }

        @AfterEach
        void cleanup() throws Exception {
            deleteIfPresent(javaCfg);
            deleteIfPresent(junitCfg);
        }

        @Test
        void javaAppSummaryHasMainClass() {
            var arr = JsonParser.parseString(
                    handler.handleConfigs(Map.of(),
                            ProjectScope.ALL)).getAsJsonArray();
            JsonObject java = findByConfigId(arr, "SumJava");
            assertEquals("com.example.Main",
                    java.get("mainClass").getAsString());
        }

        @Test
        void junitSummaryHasClassAndRunner() {
            var arr = JsonParser.parseString(
                    handler.handleConfigs(Map.of(),
                            ProjectScope.ALL)).getAsJsonArray();
            JsonObject junit = findByConfigId(arr, "SumJUnit");
            assertEquals("com.example.FooTest",
                    junit.get("class").getAsString());
            assertEquals("JUnit 5",
                    junit.get("runner").getAsString());
        }

        @Test
        void junitContainerSummaryHasPackage() throws Exception {
            ILaunchConfiguration containerCfg = createConfig(
                    "org.eclipse.jdt.junit.launchconfig",
                    "SumContainerJUnit",
                    Map.of("org.eclipse.jdt.junit.CONTAINER",
                            "=my-project/src<com.example.service"));
            try {
                var arr = JsonParser.parseString(
                        handler.handleConfigs(Map.of(),
                                ProjectScope.ALL)).getAsJsonArray();
                JsonObject junit = findByConfigId(arr,
                        "SumContainerJUnit");
                assertFalse(junit.has("class"),
                        "container config has no mainType: " + junit);
                assertEquals("com.example.service",
                        junit.get("package").getAsString());
            } finally {
                deleteIfPresent(containerCfg);
            }
        }

        @Test
        void junitSummaryIncludesMethodName() throws Exception {
            ILaunchConfiguration methodCfg = createConfig(
                    "org.eclipse.jdt.junit.launchconfig",
                    "SumMethodJUnit",
                    Map.of("org.eclipse.jdt.launching.MAIN_TYPE",
                            "com.example.FooTest",
                           "org.eclipse.jdt.junit.TESTNAME",
                            "testSomething"));
            try {
                var arr = JsonParser.parseString(
                        handler.handleConfigs(Map.of(),
                                ProjectScope.ALL)).getAsJsonArray();
                JsonObject junit = findByConfigId(arr,
                        "SumMethodJUnit");
                assertEquals("testSomething",
                        junit.get("method").getAsString());
            } finally {
                deleteIfPresent(methodCfg);
            }
        }

        @Test
        void mavenSummaryHasGoalsAndProfiles() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            if (mgr.getLaunchConfigurationType(
                    "org.eclipse.m2e.Maven2LaunchConfigurationType")
                    == null) {
                return;
            }
            ILaunchConfiguration mavenCfg = createConfig(
                    "org.eclipse.m2e.Maven2LaunchConfigurationType",
                    "SumMaven",
                    Map.of("M2_GOALS", "clean verify",
                           "M2_PROFILES", "ci"));
            try {
                var arr = JsonParser.parseString(
                        handler.handleConfigs(Map.of(),
                                ProjectScope.ALL)).getAsJsonArray();
                JsonObject maven = findByConfigId(arr, "SumMaven");
                assertEquals("clean verify",
                        maven.get("goals").getAsString());
                assertEquals("ci",
                        maven.get("profiles").getAsString());
            } finally {
                deleteIfPresent(mavenCfg);
            }
        }
    }

    @Nested
    class AppendArgs {

        private ILaunchConfiguration javaCfg;

        @BeforeEach
        void create() throws Exception {
            javaCfg = createJavaConfig(
                    "AppendArgsTest", "test.Main");
        }

        @AfterEach
        void cleanup() throws Exception {
            deleteIfPresent(javaCfg);
        }

        @Test
        void appendsToEmptyAttribute() throws Exception {
            var wc = handler.appendArgs(javaCfg, "--port 8080");
            String value = wc.getAttribute(
                    "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS",
                    "");
            assertEquals("--port 8080", value);
        }

        @Test
        void appendsToExistingAttribute() throws Exception {
            var original = javaCfg.getWorkingCopy();
            original.setAttribute(
                    "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS",
                    "--host localhost");
            var saved = original.doSave();
            try {
                var wc = handler.appendArgs(saved, "--port 8080");
                String value = wc.getAttribute(
                        "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS",
                        "");
                assertEquals("--host localhost --port 8080", value);
            } finally {
                deleteIfPresent(saved);
            }
        }
    }

    @Nested
    class FormatRunner {

        @Test
        void junit6Kind() {
            assertEquals("JUnit 6",
                    JUnitLaunchConst.formatRunner(
                            "org.eclipse.jdt.junit.loader.junit6"));
        }

        @Test
        void junit5Kind() {
            assertEquals("JUnit 5",
                    JUnitLaunchConst.formatRunner(
                            "org.eclipse.jdt.junit.loader.junit5"));
        }

        @Test
        void junit4Kind() {
            assertEquals("JUnit 4",
                    JUnitLaunchConst.formatRunner(
                            "org.eclipse.jdt.junit.loader.junit4"));
        }

        @Test
        void nullReturnsNull() {
            assertEquals(null,
                    JUnitLaunchConst.formatRunner(null));
        }

        @Test
        void unknownReturnsVerbatim() {
            assertEquals("some.custom.loader",
                    JUnitLaunchConst.formatRunner(
                            "some.custom.loader"));
        }
    }

    @Nested
    class ParseContainerPackage {

        @Test
        void nullReturnsNull() {
            assertEquals(null,
                    LaunchHandler.parseContainerPackage(null));
        }

        @Test
        void blankReturnsNull() {
            assertEquals(null,
                    LaunchHandler.parseContainerPackage("  "));
        }

        @Test
        void projectLevelReturnsNull() {
            assertEquals(null,
                    LaunchHandler.parseContainerPackage(
                            "=my-project"));
        }

        @Test
        void extractsPackageName() {
            assertEquals("com.example.service",
                    LaunchHandler.parseContainerPackage(
                            "=my-project/src\\/test\\/java"
                            + "<com.example.service"));
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

    // ---- XML history parsing helpers ----

    private static Document newDocument() throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().newDocument();
    }

    private static Document buildHistoryDoc(
            String groupId, String section,
            String... mementos) throws Exception {
        Document doc = newDocument();
        Element root = doc.createElement("root");
        doc.appendChild(root);
        Element group = doc.createElement("launchGroup");
        group.setAttribute("id", groupId);
        root.appendChild(group);
        Element sec = doc.createElement(section);
        group.appendChild(sec);
        for (String m : mementos) {
            Element launch = doc.createElement("launch");
            launch.setAttribute("memento", m);
            sec.appendChild(launch);
        }
        return doc;
    }

    @Nested
    class XmlHistory {

        @Test
        void findLaunchGroupReturnsMatchingGroup() throws Exception {
            Document doc = buildHistoryDoc(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "favorites");
            Element group = LaunchHandler.findLaunchGroup(doc,
                    "org.eclipse.debug.ui.launchGroup.run");
            assertNotNull(group);
            assertEquals("org.eclipse.debug.ui.launchGroup.run",
                    group.getAttribute("id"));
        }

        @Test
        void findLaunchGroupReturnsNullForMissing() throws Exception {
            Document doc = buildHistoryDoc(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "favorites");
            assertNull(LaunchHandler.findLaunchGroup(doc,
                    "no-such-group"));
        }

        @Test
        void findLaunchGroupWithMultipleGroups() throws Exception {
            Document doc = newDocument();
            Element root = doc.createElement("root");
            doc.appendChild(root);
            for (String id : java.util.List.of(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "org.eclipse.debug.ui.launchGroup.debug")) {
                Element g = doc.createElement("launchGroup");
                g.setAttribute("id", id);
                root.appendChild(g);
            }
            Element debug = LaunchHandler.findLaunchGroup(doc,
                    "org.eclipse.debug.ui.launchGroup.debug");
            assertNotNull(debug);
            assertEquals("org.eclipse.debug.ui.launchGroup.debug",
                    debug.getAttribute("id"));
        }

        @Test
        void childElementReturnsMatchingChild() throws Exception {
            Document doc = buildHistoryDoc(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "favorites");
            Element group = LaunchHandler.findLaunchGroup(doc,
                    "org.eclipse.debug.ui.launchGroup.run");
            Element fav = LaunchHandler.childElement(
                    group, "favorites");
            assertNotNull(fav);
            assertEquals("favorites", fav.getTagName());
        }

        @Test
        void childElementReturnsNullForMissing() throws Exception {
            Document doc = buildHistoryDoc(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "favorites");
            Element group = LaunchHandler.findLaunchGroup(doc,
                    "org.eclipse.debug.ui.launchGroup.run");
            assertNull(LaunchHandler.childElement(
                    group, "mruHistory"));
        }

        @Test
        void childElementSkipsTextNodes() throws Exception {
            Document doc = buildHistoryDoc(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "favorites");
            Element group = LaunchHandler.findLaunchGroup(doc,
                    "org.eclipse.debug.ui.launchGroup.run");
            group.insertBefore(
                    doc.createTextNode("  "),
                    group.getFirstChild());
            Element fav = LaunchHandler.childElement(
                    group, "favorites");
            assertNotNull(fav,
                    "Should find element despite text nodes");
        }

        @Test
        void collectSectionResolvesConfigMemento()
                throws Exception {
            ILaunchConfiguration cfg = createJavaConfig(
                    "HistTest", "test.Main");
            try {
                String memento = cfg.getMemento();
                Document doc = buildHistoryDoc(
                        "org.eclipse.debug.ui.launchGroup.run",
                        "favorites", memento);

                List<ILaunchConfiguration> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                LaunchHandler.collectSection(doc,
                        "org.eclipse.debug.ui.launchGroup.run",
                        "favorites",
                        LaunchAttrs.launchManager(), out, seen);

                assertEquals(1, out.size());
                assertEquals("HistTest", out.get(0).getName());
            } finally {
                deleteIfPresent(cfg);
            }
        }

        @Test
        void collectSectionDeduplicatesByName() throws Exception {
            ILaunchConfiguration cfg = createJavaConfig(
                    "DedupTest", "test.Main");
            try {
                String memento = cfg.getMemento();
                Document doc = buildHistoryDoc(
                        "org.eclipse.debug.ui.launchGroup.run",
                        "favorites", memento, memento);

                List<ILaunchConfiguration> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                LaunchHandler.collectSection(doc,
                        "org.eclipse.debug.ui.launchGroup.run",
                        "favorites",
                        LaunchAttrs.launchManager(), out, seen);

                assertEquals(1, out.size(),
                        "Duplicate memento should be deduped");
            } finally {
                deleteIfPresent(cfg);
            }
        }

        @Test
        void collectSectionSkipsMissingGroup() throws Exception {
            Document doc = buildHistoryDoc(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "favorites");
            List<ILaunchConfiguration> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            LaunchHandler.collectSection(doc,
                    "no.such.group", "favorites",
                    LaunchAttrs.launchManager(), out, seen);
            assertTrue(out.isEmpty());
        }

        @Test
        void collectSectionSkipsMissingSection() throws Exception {
            Document doc = buildHistoryDoc(
                    "org.eclipse.debug.ui.launchGroup.run",
                    "favorites");
            List<ILaunchConfiguration> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            LaunchHandler.collectSection(doc,
                    "org.eclipse.debug.ui.launchGroup.run",
                    "mruHistory",
                    LaunchAttrs.launchManager(), out, seen);
            assertTrue(out.isEmpty());
        }

        @Test
        void collectSectionThrowsOnInvalidMemento() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.eclipse.core.runtime.CoreException.class,
                    () -> {
                Document doc = buildHistoryDoc(
                        "org.eclipse.debug.ui.launchGroup.run",
                        "favorites", "invalid-memento-xyz");
                List<ILaunchConfiguration> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                LaunchHandler.collectSection(doc,
                        "org.eclipse.debug.ui.launchGroup.run",
                        "favorites",
                        LaunchAttrs.launchManager(), out, seen);
            });
        }
    }

    @Nested
    class StopRunning {

        @Test
        void stopNonTerminatedLaunchReturnsOk() throws Exception {
            ILaunchConfiguration cfg = createJavaConfig(
                    "StopRunningTest", "test.Main");
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    cfg, "run", null);
            mgr.addLaunch(launch);
            try {
                assertFalse(launch.isTerminated(),
                        "Empty launch should not be terminated");
                String json = handler.handleStop(
                        Map.of("launchId", "StopRunningTest"));
                var obj = JsonParser.parseString(json)
                        .getAsJsonObject();
                assertTrue(obj.get("ok").getAsBoolean(),
                        "stop must succeed: " + json);
                assertEquals("StopRunningTest",
                        obj.get("configId").getAsString());
            } finally {
                mgr.removeLaunch(launch);
                deleteIfPresent(cfg);
            }
        }
    }

    @Nested
    class ResolveLaunchFile {

        @Test
        void returnsNullForNonExistentConfig() {
            assertNull(
                    LaunchHandler.resolveLaunchFile(
                            "no-such-config-file-xyz-999"));
        }

        @Test
        void returnsFileForExistingLocalConfig() throws Exception {
            ILaunchConfiguration cfg = createJavaConfig(
                    "ResolveFileTest", "test.Main");
            try {
                java.io.File file =
                        LaunchHandler.resolveLaunchFile(
                                "ResolveFileTest");
                assertNotNull(file,
                        "Local config should have .launch file");
                assertTrue(file.getName().endsWith(".launch"));
                assertTrue(file.exists());
            } finally {
                deleteIfPresent(cfg);
            }
        }
    }

    @Nested
    class ConfigXmlErrors {

        @Test
        void xmlFormatWithNoLaunchFileReturnsError()
                throws Exception {
            ILaunchConfiguration cfg = createJavaConfig(
                    "XmlNoFile", "test.Main");
            try {
                java.io.File file =
                        LaunchHandler.resolveLaunchFile("XmlNoFile");
                assertNotNull(file);
                assertTrue(file.delete(),
                        "Should delete .launch file");

                String json = handler.handleConfig(
                        Map.of("configId", "XmlNoFile",
                                "format", "xml"));
                assertTrue(json.contains("error"),
                        "Should error: " + json);
                assertTrue(json.contains("No .launch file"),
                        "Should say no launch file: " + json);
            } finally {
                deleteIfPresent(cfg);
            }
        }
    }

    @Nested
    class NonLocalConfigDelete {

        @Test
        void deleteNonLocalConfigReturnsError() throws Exception {
            io.github.kaluchi.jdtbridge.support
                    .TestFixture.create();
            var root = org.eclipse.core.resources.ResourcesPlugin
                    .getWorkspace().getRoot();
            var project = root.getProject(
                    io.github.kaluchi.jdtbridge.support
                            .TestFixture.PROJECT_NAME);
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfigurationType type =
                    mgr.getLaunchConfigurationType(
                            "org.eclipse.jdt.launching"
                            + ".localJavaApplication");
            assertNotNull(type);
            ILaunchConfigurationWorkingCopy wc =
                    type.newInstance(project, "NonLocalDel");
            wc.setAttribute(
                    "org.eclipse.jdt.launching.MAIN_TYPE",
                    "test.Main");
            ILaunchConfiguration cfg = wc.doSave();
            try {
                assertFalse(cfg.isLocal(),
                        "Project-stored config should not be local");
                String json = handler.handleConfigDelete(
                        Map.of("configId", "NonLocalDel"));
                assertTrue(json.contains("error"),
                        "Should error: " + json);
                assertTrue(
                        json.contains("Not found in workspace metadata"),
                        "Should say not in metadata: " + json);
            } finally {
                cfg.delete();
            }
        }
    }
}
