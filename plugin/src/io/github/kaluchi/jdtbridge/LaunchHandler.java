package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;

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
        var arr = new JsonArray();
        var seen = new java.util.HashSet<String>();

        for (int i = launches.length - 1; i >= 0; i--) {
            ILaunch launch = launches[i];
            String name = launchName(launch);
            seen.add(name);
            arr.add(launchEntry(launch, name));
        }

        for (var entry : tracker.all().entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            LaunchTracker.TrackedLaunch tl = entry.getValue();
            arr.add(launchEntry(tl.launch, entry.getKey()));
        }

        return arr.toString();
    }

    private JsonObject launchEntry(ILaunch launch,
            String name) {
        String type = launchType(launch);
        String mode = launch.getLaunchMode();
        boolean terminated = launch.isTerminated();

        var entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("type", type);
        entry.addProperty("mode", mode);
        entry.addProperty("terminated", terminated);

        String startedAt = launch.getAttribute(
                DebugPlugin.ATTR_LAUNCH_TIMESTAMP);
        if (startedAt != null) {
            try {
                entry.addProperty("started",
                        Long.parseLong(startedAt));
            } catch (NumberFormatException e) { /* skip */ }
        }

        IProcess[] processes = launch.getProcesses();
        if (processes.length > 0) {
            IProcess proc = processes[0];
            if (terminated) {
                try {
                    entry.addProperty("exitCode",
                            proc.getExitValue());
                } catch (Exception e) { /* ignored */ }
            }
            String pid = proc.getAttribute(
                    IProcess.ATTR_PROCESS_ID);
            if (pid != null) {
                entry.addProperty("pid", pid);
            }
        }

        return entry;
    }

    String handleConfigs(Map<String, String> params) {
        try {
            var allConfigs =
                    launchManager().getLaunchConfigurations();
            ILaunchConfiguration[] recent =
                    getRecentConfigs();

            var arr = new JsonArray();
            var seen = new java.util.HashSet<String>();

            if (recent != null) {
                for (var config : recent) {
                    var obj = new JsonObject();
                    obj.addProperty("name",
                            config.getName());
                    obj.addProperty("type",
                            config.getType().getName());
                    arr.add(obj);
                    seen.add(config.getName());
                }
            }

            for (var config : allConfigs) {
                if (seen.contains(config.getName())) continue;
                var obj = new JsonObject();
                obj.addProperty("name", config.getName());
                obj.addProperty("type",
                        config.getType().getName());
                arr.add(obj);
            }

            return arr.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
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

            var result = new java.util.ArrayList<
                    ILaunchConfiguration>();
            var seen = new java.util.HashSet<String>();

            if (runHistory != null) {
                for (var c : runHistory.getFavorites()) {
                    if (seen.add(c.getName())) result.add(c);
                }
            }
            if (debugHistory != null) {
                for (var c : debugHistory.getFavorites()) {
                    if (seen.add(c.getName())) result.add(c);
                }
            }
            if (runHistory != null) {
                for (var c : runHistory.getHistory()) {
                    if (seen.add(c.getName())) result.add(c);
                }
            }
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
        }
        return null;
    }

    String handleClear(Map<String, String> params) {
        String name = params.get("name");
        ILaunch[] launches = launchManager().getLaunches();
        int removed = 0;
        var cleared = new java.util.HashSet<String>();

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

        var result = new JsonObject();
        result.addProperty("removed", removed);
        return result.toString();
    }

    String handleRun(Map<String, String> params) {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'name' parameter");
        }
        String mode = params.containsKey("debug")
                ? ILaunchManager.DEBUG_MODE
                : ILaunchManager.RUN_MODE;
        try {
            ILaunchConfiguration config = findConfig(name);
            if (config == null) {
                return HttpServer.jsonError(
                        "Launch configuration not found: "
                        + name);
            }
            ILaunch launch = config.launch(mode, null, true);
            var response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("name",
                    launchName(launch));
            response.addProperty("mode", mode);
            response.addProperty("type",
                    launchType(launch));
            addProcessMetadata(launch, response);
            return response.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
        }
    }

    String handleStop(Map<String, String> params) {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'name' parameter");
        }
        ILaunch target = findLaunch(name);
        if (target == null) {
            return HttpServer.jsonError(
                    "Launch not found: " + name);
        }
        if (target.isTerminated()) {
            return HttpServer.jsonError(
                    "Already terminated: " + name);
        }
        try {
            target.terminate();
            var result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("name", name);
            return result.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(
                    "Failed to terminate: "
                    + e.getMessage());
        }
    }

    private ILaunchConfiguration findConfig(String name) {
        try {
            for (var config
                    : launchManager()
                            .getLaunchConfigurations()) {
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
            return HttpServer.jsonError(
                    "Missing 'name' parameter");
        }

        String tailStr = params.get("tail");
        String stream = params.get("stream");

        LaunchTracker.TrackedLaunch tl = tracker.get(name);
        if (tl == null) {
            ILaunch target = findLaunch(name);
            if (target == null) {
                return HttpServer.jsonError(
                        "Launch not found: " + name);
            }
            var obj = new JsonObject();
            obj.addProperty("name", name);
            obj.addProperty("terminated",
                    target.isTerminated());
            obj.addProperty("output", "");
            return obj.toString();
        }

        String output = tl.getOutput(stream);
        String result = output;
        if (tailStr != null) {
            try {
                int tailLines = Integer.parseInt(tailStr);
                result = ConsoleStreamer.tail(
                        result, tailLines);
            } catch (NumberFormatException e) { /* full */ }
        }

        var obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("terminated", tl.terminated);
        obj.addProperty("output", result);
        return obj.toString();
    }

    private ILaunch findLaunch(String name) {
        ILaunch[] launches = launchManager().getLaunches();
        for (int i = launches.length - 1; i >= 0; i--) {
            if (name.equals(launchName(launches[i]))) {
                return launches[i];
            }
        }
        return null;
    }

    private static void addProcessMetadata(ILaunch launch,
            JsonObject response) {
        IProcess[] processes = launch.getProcesses();
        if (processes.length > 0) {
            IProcess proc = processes[0];
            String pid = proc.getAttribute(
                    IProcess.ATTR_PROCESS_ID);
            if (pid != null)
                response.addProperty("pid", pid);
            String cmdline = proc.getAttribute(
                    "org.eclipse.debug.core"
                    + ".ATTR_CMDLINE");
            if (cmdline != null)
                response.addProperty("cmdline", cmdline);
        }
        try {
            ILaunchConfiguration config =
                    launch.getLaunchConfiguration();
            if (config != null) {
                String workDir = config.getAttribute(
                        "org.eclipse.debug.core"
                        + ".ATTR_WORKING_DIRECTORY",
                        (String) null);
                if (workDir != null) {
                    response.addProperty(
                            "workingDir", workDir);
                }
            }
        } catch (Exception e) { /* ignored */ }
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
