package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for LaunchTracker — stream output buffering via
 * IStreamMonitor listeners.
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class LaunchTrackerTest {

    @Nested
    class TrackedLaunchOutput {

        @Test
        void stdoutOnlyReturnsStdout() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            tl.appendOut("hello stdout");
            tl.appendErr("hello stderr");
            assertEquals("hello stdout",
                    tl.getOutput("stdout"));
        }

        @Test
        void stderrOnlyReturnsStderr() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            tl.appendOut("out");
            tl.appendErr("err");
            assertEquals("err", tl.getOutput("stderr"));
        }

        @Test
        void nullStreamReturnsBoth() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            tl.appendOut("OUT");
            tl.appendErr("ERR");
            assertEquals("OUTERR", tl.getOutput(null));
        }

        @Test
        void emptyBuffersReturnEmpty() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            assertEquals("", tl.getOutput(null));
            assertEquals("", tl.getOutput("stdout"));
            assertEquals("", tl.getOutput("stderr"));
        }

        @Test
        void terminatedFlagFromLaunch() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            assertFalse(tl.terminated);
        }

        @Test
        void outputListenerReceivesAppendOut() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            var received = new java.util.ArrayList<String>();
            tl.addOutputListener(
                    (text, stderr) -> received.add(text));
            tl.appendOut("hello");
            tl.appendErr("world");
            assertEquals(2, received.size());
            assertEquals("hello", received.get(0));
            assertEquals("world", received.get(1));
        }

        @Test
        void removeOutputListenerStopsNotifications() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            var received = new java.util.ArrayList<String>();
            LaunchTracker.OutputListener l =
                    (text, stderr) -> received.add(text);
            tl.addOutputListener(l);
            tl.appendOut("before");
            tl.removeOutputListener(l);
            tl.appendOut("after");
            assertEquals(1, received.size());
            assertEquals("before", received.get(0));
        }

        @Test
        void outputListenerDistinguishesStdoutStderr() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            var flags = new java.util.ArrayList<Boolean>();
            tl.addOutputListener(
                    (text, stderr) -> flags.add(stderr));
            tl.appendOut("out");
            tl.appendErr("err");
            assertFalse(flags.get(0));
            assertTrue(flags.get(1));
        }
    }

    @Nested
    class Lifecycle {

        private LaunchTracker tracker;

        @BeforeEach
        void setUp() {
            tracker = new LaunchTracker();
            tracker.start();
        }

        @AfterEach
        void tearDown() {
            tracker.stop();
        }

        @Test
        void launchesAddedTracksNewLaunch() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "tracker-add-test");
            mgr.addLaunch(launch);
            try {
                var tl = tracker.get("tracker-add-test");
                assertNotNull(tl,
                        "Tracker should have the launch");
                assertTrue(tl.terminated,
                        "Process should be terminated");
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void trackerAttachesStreamListeners() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "tracker-attach-test");
            mgr.addLaunch(launch);
            try {
                var tl = tracker.get("tracker-attach-test");
                assertNotNull(tl);
                // Stream monitors should be attached
                assertEquals(2, tl.attached.size(),
                        "Should attach stdout+stderr monitors");
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void launchesRemovedKeepsEntry() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "tracker-remove-test");
            mgr.addLaunch(launch);

            assertNotNull(tracker.get("tracker-remove-test"));

            // Remove from manager — tracker should keep entry
            mgr.removeLaunch(launch);

            assertNotNull(tracker.get("tracker-remove-test"),
                    "Should survive removal from manager");
        }

        @Test
        void removeByName() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            mgr.addLaunch(launch);
            try {
                assertNotNull(tracker.get("(unknown)"));
                tracker.remove("(unknown)");
                assertNull(tracker.get("(unknown)"));
            } finally {
                mgr.removeLaunch(launch);
            }
        }
    }
}
