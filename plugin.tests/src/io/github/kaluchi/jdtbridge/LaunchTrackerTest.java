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

/**
 * Tests for LaunchTracker — stream output buffering via
 * IStreamMonitor listeners.
 */
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
        void outLenReflectsAppendedContent() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            assertEquals(0, tl.outLen());
            tl.appendOut("hello");
            assertEquals(5, tl.outLen());
        }

        @Test
        void errLenReflectsAppendedContent() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            var tl = new LaunchTracker.TrackedLaunch(launch);
            assertEquals(0, tl.errLen());
            tl.appendErr("err");
            assertEquals(3, tl.errLen());
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
        void startTracksExistingLaunches() throws Exception {
            tracker.stop();
            LaunchTracker fresh = new LaunchTracker();
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            mgr.addLaunch(launch);
            try {
                fresh.start();
                assertNotNull(fresh.get("(unknown)"),
                        "Should retroactively track existing launch");
            } finally {
                fresh.stop();
                mgr.removeLaunch(launch);
                tracker.start();
            }
        }

        @Test
        void timestampAttributeCreatesSecondaryKey()
                throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            String ts = String.valueOf(System.currentTimeMillis());
            launch.setAttribute(
                    DebugPlugin.ATTR_LAUNCH_TIMESTAMP, ts);
            mgr.addLaunch(launch);
            try {
                var tl = tracker.get("(unknown):" + ts);
                assertNotNull(tl,
                        "Should be accessible by configId:timestamp");
            } finally {
                mgr.removeLaunch(launch);
            }
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
        void allReturnsTrackedMap() throws Exception {
            assertNotNull(tracker.all());
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            mgr.addLaunch(launch);
            try {
                assertFalse(tracker.all().isEmpty(),
                        "Map should contain tracked launch");
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void launchesTerminatedSetsFlag() throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            mgr.addLaunch(launch);
            try {
                var tl = tracker.get("(unknown)");
                assertNotNull(tl);
                assertFalse(tl.terminated);
                tracker.launchesTerminated(
                        new ILaunch[] { launch });
                assertTrue(tl.terminated);
            } finally {
                mgr.removeLaunch(launch);
            }
        }

        @Test
        void launchesTerminatedIgnoresUntrackedLaunch() {
            ILaunch untracked = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            tracker.launchesTerminated(
                    new ILaunch[] { untracked });
        }

        @Test
        void launchesTerminatedIgnoresDifferentInstance()
                throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch first = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            mgr.addLaunch(first);
            var tl = tracker.get("(unknown)");
            assertNotNull(tl);
            mgr.removeLaunch(first);

            ILaunch second = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            mgr.addLaunch(second);
            try {
                tracker.launchesTerminated(
                        new ILaunch[] { first });
                assertFalse(tl.terminated,
                        "Should not terminate for different instance");
            } finally {
                mgr.removeLaunch(second);
            }
        }

        @Test
        void launchesChangedReattachesIdempotently()
                throws Exception {
            ILaunchManager mgr =
                    DebugPlugin.getDefault().getLaunchManager();
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            Process proc = new ProcessBuilder(
                    "java", "-version").start();
            proc.waitFor(5,
                    java.util.concurrent.TimeUnit.SECONDS);
            DebugPlugin.newProcess(launch, proc,
                    "tracker-changed-test");
            mgr.addLaunch(launch);
            try {
                var tl = tracker.get("tracker-changed-test");
                assertNotNull(tl);
                int monitorsBefore = tl.attached.size();
                tracker.launchesChanged(
                        new ILaunch[] { launch });
                assertEquals(monitorsBefore, tl.attached.size(),
                        "Re-attach should not duplicate monitors");
            } finally {
                mgr.removeLaunch(launch);
            }
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
