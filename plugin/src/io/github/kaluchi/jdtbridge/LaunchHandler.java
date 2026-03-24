package io.github.kaluchi.jdtbridge;

import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

/**
 * Handlers for launch management: list launches, read console output.
 */
class LaunchHandler {

    private ILaunchManager launchManager() {
        return DebugPlugin.getDefault().getLaunchManager();
    }

    String handleList(Map<String, String> params) {
        ILaunch[] launches = launchManager().getLaunches();
        Json arr = Json.array();
        for (int i = launches.length - 1; i >= 0; i--) {
            ILaunch launch = launches[i];
            String name = launchName(launch);
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

            arr.add(entry);
        }
        return arr.toString();
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
        for (ILaunch launch : launches) {
            if (!launch.isTerminated()) continue;
            if (name != null && !name.isBlank()
                    && !name.equals(launchName(launch))) {
                continue;
            }
            launchManager().removeLaunch(launch);
            removed++;
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

    String handleConsole(Map<String, String> params) {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return Json.error("Missing 'name' parameter");
        }

        String tailStr = params.get("tail");
        String stream = params.get("stream");

        ILaunch target = findLaunch(name);
        if (target == null) {
            return Json.error("Launch not found: " + name);
        }

        IProcess[] processes = target.getProcesses();
        if (processes.length == 0) {
            return Json.error("No process for launch: " + name);
        }

        StringBuilder output = new StringBuilder();
        for (IProcess proc : processes) {
            IStreamsProxy proxy = proc.getStreamsProxy();
            if (proxy == null) continue;

            if (!"stderr".equals(stream)) {
                appendStream(proxy.getOutputStreamMonitor(),
                        output);
            }
            if (!"stdout".equals(stream)) {
                appendStream(proxy.getErrorStreamMonitor(),
                        output);
            }
        }

        String result = output.toString();
        if (tailStr != null) {
            try {
                int tailLines = Integer.parseInt(tailStr);
                result = tail(result, tailLines);
            } catch (NumberFormatException e) { /* use full */ }
        }

        return Json.object()
                .put("name", launchName(target))
                .put("terminated", target.isTerminated())
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

    private void appendStream(IStreamMonitor monitor,
            StringBuilder sb) {
        if (monitor == null) return;
        String contents = monitor.getContents();
        if (contents != null && !contents.isEmpty()) {
            sb.append(contents);
        }
    }

    private String tail(String text, int lines) {
        if (lines <= 0) return text;
        int pos = text.length();
        for (int i = 0; i < lines && pos > 0; i++) {
            pos = text.lastIndexOf('\n', pos - 1);
        }
        return pos <= 0 ? text : text.substring(pos + 1);
    }

    private static String launchName(ILaunch launch) {
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
