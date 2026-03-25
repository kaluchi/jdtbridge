package io.github.kaluchi.jdtbridge;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

/**
 * Tracks launches and accumulates stream output via
 * {@link IStreamMonitor} listeners. Survives ILaunch removal
 * from the launch manager (which happens during Maven builds).
 */
class LaunchTracker implements ILaunchesListener2 {

    interface OutputListener {
        /** @param text chunk of output
         *  @param stderr true if stderr, false if stdout */
        void onOutput(String text, boolean stderr);
    }

    static class TrackedLaunch {
        final ILaunch launch;
        private final Object lock = new Object();
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        volatile boolean terminated;
        final Set<IStreamMonitor> attached =
                ConcurrentHashMap.newKeySet();
        private final List<OutputListener> listeners =
                new CopyOnWriteArrayList<>();

        TrackedLaunch(ILaunch launch) {
            this.launch = launch;
            this.terminated = launch.isTerminated();
        }

        void addOutputListener(OutputListener l) {
            listeners.add(l);
        }

        void removeOutputListener(OutputListener l) {
            listeners.remove(l);
        }

        void appendOut(String text) {
            synchronized (lock) { stdout.append(text); }
            for (var l : listeners) l.onOutput(text, false);
        }

        void appendErr(String text) {
            synchronized (lock) { stderr.append(text); }
            for (var l : listeners) l.onOutput(text, true);
        }

        String getOutput(String stream) {
            synchronized (lock) {
                if ("stderr".equals(stream)) {
                    return stderr.toString();
                }
                if ("stdout".equals(stream)) {
                    return stdout.toString();
                }
                if (stdout.isEmpty() && stderr.isEmpty()) {
                    return "";
                }
                return stdout.toString() + stderr.toString();
            }
        }

        int outLen() { synchronized (lock) { return stdout.length(); } }
        int errLen() { synchronized (lock) { return stderr.length(); } }
    }

    private final ConcurrentHashMap<String, TrackedLaunch> tracked =
            new ConcurrentHashMap<>();

    void start() {
        ILaunchManager mgr = launchManager();
        if (mgr == null) return;
        mgr.addLaunchListener(this);
        // Retroactively track existing launches
        for (ILaunch launch : mgr.getLaunches()) {
            track(launch);
        }
    }

    void stop() {
        ILaunchManager mgr = launchManager();
        if (mgr != null) {
            mgr.removeLaunchListener(this);
        }
        tracked.clear();
    }

    TrackedLaunch get(String name) {
        return tracked.get(name);
    }

    void remove(String name) {
        tracked.remove(name);
    }

    ConcurrentHashMap<String, TrackedLaunch> all() {
        return tracked;
    }

    // -- ILaunchesListener2 --

    @Override
    public void launchesAdded(ILaunch[] launches) {
        for (ILaunch launch : launches) {
            track(launch);
        }
    }

    @Override
    public void launchesChanged(ILaunch[] launches) {
        for (ILaunch launch : launches) {
            track(launch);
        }
    }

    @Override
    public void launchesTerminated(ILaunch[] launches) {
        for (ILaunch launch : launches) {
            String name = LaunchHandler.launchName(launch);
            TrackedLaunch tl = tracked.get(name);
            if (tl != null && tl.launch == launch) {
                tl.terminated = true;
            }
        }
    }

    @Override
    public void launchesRemoved(ILaunch[] launches) {
        // Intentionally keep tracked data — the whole point is
        // to survive removal from the launch manager.
    }

    // -- internals --

    private void track(ILaunch launch) {
        String name = LaunchHandler.launchName(launch);
        TrackedLaunch tl = tracked.compute(name, (k, existing) -> {
            if (existing != null && existing.launch == launch) {
                return existing;
            }
            // New launch or different launch with same name
            return new TrackedLaunch(launch);
        });
        attachStreams(launch, tl);
    }

    private void attachStreams(ILaunch launch, TrackedLaunch tl) {
        for (IProcess proc : launch.getProcesses()) {
            IStreamsProxy proxy = proc.getStreamsProxy();
            if (proxy == null) continue;

            IStreamMonitor outMon = proxy.getOutputStreamMonitor();
            if (outMon != null && tl.attached.add(outMon)) {
                // Listener first — so no content is lost between
                // getContents() and addListener().  Duplicate
                // delivery (listener re-delivers buffered content)
                // is harmless; a gap is not.
                outMon.addListener(
                        (text, monitor) -> tl.appendOut(text));
                String existing = outMon.getContents();
                if (existing != null && !existing.isEmpty()) {
                    tl.appendOut(existing);
                }
            }

            IStreamMonitor errMon = proxy.getErrorStreamMonitor();
            if (errMon != null && tl.attached.add(errMon)) {
                errMon.addListener(
                        (text, monitor) -> tl.appendErr(text));
                String existing = errMon.getContents();
                if (existing != null && !existing.isEmpty()) {
                    tl.appendErr(existing);
                }
            }
        }
    }

    private static ILaunchManager launchManager() {
        var debug = DebugPlugin.getDefault();
        return debug != null ? debug.getLaunchManager() : null;
    }
}
