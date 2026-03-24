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
            // Try to get MRU-ordered history from UI layer
            ILaunchConfiguration[] recent = getRecentConfigs();
            if (recent != null && recent.length > 0) {
                Json arr = Json.array();
                for (var config : recent) {
                    arr.add(Json.object()
                            .put("name", config.getName())
                            .put("type",
                                    config.getType().getName()));
                }
                return arr.toString();
            }

            // Fallback: all configs alphabetically
            var configs =
                    launchManager().getLaunchConfigurations();
            Json arr = Json.array();
            for (var config : configs) {
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
            // "org.eclipse.debug.ui.launchGroup.run" is the
            // standard Run group
            var history = mgr.getLaunchHistory(
                    "org.eclipse.debug.ui.launchGroup.run");
            if (history != null) {
                return history.getHistory();
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
