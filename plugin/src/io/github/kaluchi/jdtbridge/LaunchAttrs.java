package io.github.kaluchi.jdtbridge;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;

/**
 * Shared accessors for {@link ILaunch} attributes used across
 * the bridge plugin. Centralizes the bits of debug-core access
 * that were copy-pasted in launch / test / coverage handlers.
 */
public final class LaunchAttrs {

    private LaunchAttrs() {
    }

    public static ILaunchManager launchManager() {
        DebugPlugin debug = DebugPlugin.getDefault();
        return debug != null ? debug.getLaunchManager() : null;
    }

    /** First process PID under the launch, or {@code null} when no
     *  process is registered yet (race window between
     *  {@code launch()} and {@code DebugEvent.CREATE}). */
    public static String firstPid(ILaunch launch) {
        IProcess[] procs = launch.getProcesses();
        if (procs.length == 0) {
            return null;
        }
        return procs[0].getAttribute(IProcess.ATTR_PROCESS_ID);
    }

    /** {@code DebugPlugin.ATTR_LAUNCH_TIMESTAMP} parsed as
     *  {@link Long}. Eclipse stores it as
     *  {@code Long.toString(System.currentTimeMillis())}. Returns
     *  {@code null} on missing or malformed value. */
    public static Long launchTimestamp(ILaunch launch) {
        return parseTimestamp(launch.getAttribute(
                DebugPlugin.ATTR_LAUNCH_TIMESTAMP));
    }

    public static Long parseTimestamp(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Launch identifier as used across the bridge wire format —
     *  {@code configId:pid} when the process exists,
     *  {@code configId} otherwise. */
    public static String launchIdOf(String configId, ILaunch launch) {
        String pid = firstPid(launch);
        return pid != null ? configId + ":" + pid : configId;
    }

    /** Find a launch configuration by its display name. Returns
     *  {@code null} when none matches or when the launch manager
     *  fails to enumerate configurations. */
    public static ILaunchConfiguration findConfig(String name) {
        try {
            for (ILaunchConfiguration c
                    : launchManager().getLaunchConfigurations()) {
                if (name.equals(c.getName())) {
                    return c;
                }
            }
        } catch (CoreException e) {
            Log.warn("findConfig(" + name + ") failed", e);
        }
        return null;
    }
}
