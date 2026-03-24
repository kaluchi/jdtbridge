package io.github.kaluchi.jdtbridge;

import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;

/**
 * Handlers for launch management: list launches, read console output.
 */
class LaunchHandler {

    private final LaunchTracker tracker;

    LaunchHandler(LaunchTracker tracker) {
        this.tracker = tracker;
    }

    private ILaunchManager launchManager() {
        return DebugPlugin.getDefault().getLaunchManager();
    }

    String handleList(Map<String, String> params) {
        ILaunch[] launches = launchManager().getLaunches();
        Json arr = Json.array();
        var seen = new java.util.HashSet<String>();

        // Launches from the manager (newest first)
        for (int i = launches.length - 1; i >= 0; i--) {
            ILaunch launch = launches[i];
            String name = launchName(launch);
            seen.add(name);
            arr.add(launchEntry(launch, name));
        }

        // Tracked launches not in the manager (disappeared)
        for (var entry : tracker.all().entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            LaunchTracker.TrackedLaunch tl = entry.getValue();
            arr.add(launchEntry(tl.launch, entry.getKey()));
        }

        return arr.toString();
    }

    private Json launchEntry(ILaunch launch, String name) {
        String type = launchType(launch);
        String mode = launch.getLaunchMode();
        boolean terminated = launch.isTerminated();

        Json entry = Json.object()
                .put("name", name)
                .put("type", type)
                .put("mode", mode)
                .put("terminated", terminated);

        String startedAt = launch.getAttribute(
                DebugPlugin.ATTR_LAUNCH_TIMESTAMP);
        if (startedAt != null) {
            try {
                entry.put("started",
                        Long.parseLong(startedAt));
            } catch (NumberFormatException e) { /* skip */ }
        }

        IProcess[] processes = launch.getProcesses();
        if (processes.length > 0) {
            IProcess proc = processes[0];
            if (terminated) {
                try {
                    entry.put("exitCode", proc.getExitValue());
                } catch (Exception e) { /* ignored */ }
            }
            String pid = proc.getAttribute(
                    IProcess.ATTR_PROCESS_ID);
            if (pid != null) {
                entry.put("pid", pid);
            }
        }

        return entry;
    }

    String handleConfigs(Map<String, String> params) {
        try {
            var allConfigs =
                    launchManager().getLaunchConfigurations();
            ILaunchConfiguration[] recent = getRecentConfigs();

            // Recent first, then remaining alphabetically
            Json arr = Json.array();
            var seen = new java.util.HashSet<String>();

            if (recent != null) {
                for (var config : recent) {
                    arr.add(Json.object()
                            .put("name", config.getName())
                            .put("type",
                                    config.getType().getName()));
                    seen.add(config.getName());
                }
            }

            for (var config : allConfigs) {
                if (seen.contains(config.getName())) continue;
                arr.add(Json.object()
                        .put("name", config.getName())
                        .put("type", config.getType().getName()));
            }

            return arr.toString();
        } catch (Exception e) {
            return Json.error(e.getMessage());
        }
    }

    @SuppressWarnings("restriction")
    private ILaunchConfiguration[] getRecentConfigs() {
        try {
            var mgr = org.eclipse.debug.internal.ui.DebugUIPlugin
                    .getDefault().getLaunchConfigurationManager();
            var runHistory = mgr.getLaunchHistory(
                    "org.eclipse.debug.ui.launchGroup.run");
            var debugHistory = mgr.getLaunchHistory(
                    "org.eclipse.debug.ui.launchGroup.debug");

            // Merge: favorites first, then recent from both groups
            var result = new java.util.ArrayList<
                    ILaunchConfiguration>();
            var seen = new java.util.HashSet<String>();

            // Favorites from run group
            if (runHistory != null) {
                for (var c : runHistory.getFavorites()) {
                    if (seen.add(c.getName())) result.add(c);
                }
            }
            // Favorites from debug group
            if (debugHistory != null) {
                for (var c : debugHistory.getFavorites()) {
                    if (seen.add(c.getName())) result.add(c);
                }
            }
            // Recent history (run)
            if (runHistory != null) {
                for (var c : runHistory.getHistory()) {
                    if (seen.add(c.getName())) result.add(c);
                }
            }
            // Recent history (debug)
            if (debugHistory != null) {
                for (var c : debugHistory.getHistory()) {
                    if (seen.add(c.getName())) result.add(c);
                }
            }

            if (!result.isEmpty()) {
                return result.toArray(
                        new ILaunchConfiguration[0]);
            }
        } catch (Throwable e) {
            // UI plugin not available — fall through
        }
        return null;
    }

    String handleClear(Map<String, String> params) {
        String name = params.get("name");
        ILaunch[] launches = launchManager().getLaunches();
        int removed = 0;
        var cleared = new java.util.HashSet<String>();

        // Remove terminated launches from the manager
        for (ILaunch launch : launches) {
            if (!launch.isTerminated()) continue;
            String lName = launchName(launch);
            if (name != null && !name.isBlank()
                    && !name.equals(lName)) {
                continue;
            }
            launchManager().removeLaunch(launch);
            tracker.remove(lName);
            cleared.add(lName);
            removed++;
        }

        // Remove tracked entries not in the manager
        for (var entry : tracker.all().entrySet()) {
            if (cleared.contains(entry.getKey())) continue;
            if (name != null && !name.isBlank()
                    && !name.equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue().terminated) {
                tracker.remove(entry.getKey());
                removed++;
            }
        }

        return Json.object()
                .put("removed", removed)
                .toString();
    }

    String handleRun(Map<String, String> params) {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return Json.error("Missing 'name' parameter");
        }
        String mode = params.containsKey("debug")
                ? ILaunchManager.DEBUG_MODE
                : ILaunchManager.RUN_MODE;
        try {
            ILaunchConfiguration config = findConfig(name);
            if (config == null) {
                return Json.error(
                        "Launch configuration not found: " + name);
            }
            ILaunch launch = config.launch(mode, null, true);
            return Json.object()
                    .put("ok", true)
                    .put("name", launchName(launch))
                    .put("mode", mode)
                    .toString();
        } catch (Exception e) {
            return Json.error(e.getMessage());
        }
    }

    String handleStop(Map<String, String> params) {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return Json.error("Missing 'name' parameter");
        }
        ILaunch target = findLaunch(name);
        if (target == null) {
            return Json.error("Launch not found: " + name);
        }
        if (target.isTerminated()) {
            return Json.error("Already terminated: " + name);
        }
        try {
            target.terminate();
            return Json.object()
                    .put("ok", true)
                    .put("name", name)
                    .toString();
        } catch (Exception e) {
            return Json.error(
                    "Failed to terminate: " + e.getMessage());
        }
    }

    private ILaunchConfiguration findConfig(String name) {
        try {
            for (var config
                    : launchManager().getLaunchConfigurations()) {
                if (name.equals(config.getName())) {
                    return config;
                }
            }
        } catch (Exception e) { /* ignored */ }
        return null;
    }

    /**
     * Read console output for a launch. The tracker (IStreamMonitor
     * listeners) is the primary source — it survives ILaunch removal
     * from the manager and works even when IStreamsProxy.getContents()
     * is empty (ProcessConsole calls setBuffered(false)).
     */
    String handleConsole(Map<String, String> params) {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return Json.error("Missing 'name' parameter");
        }

        String tailStr = params.get("tail");
        String stream = params.get("stream");

        // Primary: tracker (survives ILaunch removal from manager)
        LaunchTracker.TrackedLaunch tl = tracker.get(name);
        if (tl == null) {
            // Launch exists in manager but tracker missed it?
            ILaunch target = findLaunch(name);
            if (target == null) {
                return Json.error("Launch not found: " + name);
            }
            return Json.object()
                    .put("name", name)
                    .put("terminated", target.isTerminated())
                    .put("output", "")
                    .toString();
        }

        String output = tl.getOutput(stream);
        String result = output;
        if (tailStr != null) {
            try {
                int tailLines = Integer.parseInt(tailStr);
                result = tail(result, tailLines);
            } catch (NumberFormatException e) { /* use full */ }
        }

        return Json.object()
                .put("name", name)
                .put("terminated", tl.terminated)
                .put("output", result)
                .toString();
    }

    private ILaunch findLaunch(String name) {
        ILaunch[] launches = launchManager().getLaunches();
        // Search newest first
        for (int i = launches.length - 1; i >= 0; i--) {
            if (name.equals(launchName(launches[i]))) {
                return launches[i];
            }
        }
        return null;
    }

    private String tail(String text, int lines) {
        if (lines <= 0) return text;
        int pos = text.length();
        for (int i = 0; i < lines && pos > 0; i++) {
            pos = text.lastIndexOf('\n', pos - 1);
        }
        return pos <= 0 ? text : text.substring(pos + 1);
    }

    static String launchName(ILaunch launch) {
        ILaunchConfiguration config =
                launch.getLaunchConfiguration();
        if (config != null) return config.getName();
        IProcess[] procs = launch.getProcesses();
        if (procs.length > 0) return procs[0].getLabel();
        return "(unknown)";
    }

    private static String launchType(ILaunch launch) {
        try {
            ILaunchConfiguration config =
                    launch.getLaunchConfiguration();
            if (config != null) {
                return config.getType().getName();
            }
        } catch (Exception e) { /* ignored */ }
        return "";
    }
}
