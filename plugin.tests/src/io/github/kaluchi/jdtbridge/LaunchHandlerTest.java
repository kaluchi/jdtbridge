package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for LaunchHandler — list and console commands.
 * Tests against the real Eclipse launch infrastructure.
 */
@EnabledIfSystemProperty(named = "jdtbridge.integration-tests",
        matches = "true")
public class LaunchHandlerTest {

    private final LaunchHandler handler = new LaunchHandler();

    @Nested
    class List {

        @Test
        void returnsArray() {
            String json = handler.handleList(Map.of());
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
            String json = handler.handleList(Map.of());
            assertEquals("[]", json);
            // Restore
            for (ILaunch l : existing) {
                mgr.addLaunch(l);
            }
        }

        @Test
        void containsNameAndTerminated() {
            // Any launch from previous test runs may be present
            String json = handler.handleList(Map.of());
            // If there are launches, each should have name+terminated
            if (!json.equals("[]")) {
                assertTrue(json.contains("\"name\""),
                        "Should have name: " + json);
                assertTrue(json.contains("\"terminated\""),
                        "Should have terminated: " + json);
            }
        }

        @Test
        void containsModeAndType() {
            String json = handler.handleList(Map.of());
            if (!json.equals("[]")) {
                assertTrue(json.contains("\"mode\""),
                        "Should have mode: " + json);
                assertTrue(json.contains("\"type\""),
                        "Should have type: " + json);
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
                    Map.of("name", "no-such-launch-xyz"));
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
                    Map.of("name", "no-such-launch", "tail", "10"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void invalidTailIsIgnored() {
            String json = handler.handleConsole(
                    Map.of("name", "no-such-launch", "tail", "abc"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }

        @Test
        void streamFilterDoesNotCrash() {
            String json = handler.handleConsole(
                    Map.of("name", "no-such-launch",
                            "stream", "stderr"));
            assertTrue(json.contains("error"),
                    "Should return error: " + json);
        }
    }

    @Nested
    class Configs {

        @Test
        void returnsArray() {
            String json = handler.handleConfigs(Map.of());
            assertNotNull(json);
            assertTrue(json.startsWith("["),
                    "Should be JSON array: " + json);
        }

        @Test
        void containsNameAndType() {
            String json = handler.handleConfigs(Map.of());
            if (!json.equals("[]")) {
                assertTrue(json.contains("\"name\""),
                        "Should have name: " + json);
                assertTrue(json.contains("\"type\""),
                        "Should have type: " + json);
            }
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
                    Map.of("name", "no-such-launch"));
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
                    Map.of("name", "remove-this"));

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
                String json = handler.handleList(Map.of());
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
                String listJson = handler.handleList(Map.of());
                assertTrue(listJson.contains("\"terminated\""),
                        "Should have terminated: " + listJson);

                // Console should have output
                // Launch has no config name, so label is
                // process label
                String consoleJson = handler.handleConsole(
                        Map.of("name", "java -version"));
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
                        Map.of("name", "java -version"));
                String tailJson = handler.handleConsole(
                        Map.of("name", "java -version",
                                "tail", "1"));

                var fullMap = Json.parse(fullJson);
                var tailMap = Json.parse(tailJson);
                String fullOut =
                        Json.getString(fullMap, "output");
                String tailOut =
                        Json.getString(tailMap, "output");

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

    // ---- HTTP routing tests ----

    @Nested
    class HttpRouting {

        @Test
        void parseQueryWithLaunchParams() {
            var params = HttpServer.parseQuery(
                    "name=m8-server&tail=50&stream=stderr");
            assertEquals("m8-server", params.get("name"));
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
}
