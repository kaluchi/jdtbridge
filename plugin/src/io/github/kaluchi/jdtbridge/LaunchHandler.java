package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;

class LaunchHandler {

    // Attribute keys for launch configuration types
    private static final String ATTR_PROJECT_NAME =
            "org.eclipse.jdt.launching.PROJECT_ATTR";
    private static final String ATTR_MAIN_TYPE_NAME =
            "org.eclipse.jdt.launching.MAIN_TYPE";
    private static final String ATTR_TEST_KIND =
            "org.eclipse.jdt.junit.TEST_KIND";
    private static final String ATTR_TEST_NAME =
            "org.eclipse.jdt.junit.TESTNAME";
    private static final String ATTR_CONTAINER =
            "org.eclipse.jdt.junit.CONTAINER";
    private static final String JUNIT_LAUNCH_TYPE =
            "org.eclipse.jdt.junit.launchconfig";
    private static final String PDE_JUNIT_LAUNCH_TYPE =
            "org.eclipse.pde.ui.JunitLaunchConfig";
    private static final String JAVA_APP_LAUNCH_TYPE =
            "org.eclipse.jdt.launching.localJavaApplication";
    private static final String MAVEN_LAUNCH_TYPE =
            "org.eclipse.m2e.Maven2LaunchConfigurationType";
    private static final String MAVEN_GOALS =
            "M2_GOALS";
    private static final String MAVEN_PROFILES =
            "M2_PROFILES";
    private static final String AGENT_LAUNCH_TYPE =
            "io.github.kaluchi.jdtbridge.ui.agentLaunchType";
    private static final String AGENT_PROVIDER =
            "io.github.kaluchi.jdtbridge.ui.provider";
    private static final String AGENT_NAME =
            "io.github.kaluchi.jdtbridge.ui.agent";
    private static final String AGENT_ARGS =
            "io.github.kaluchi.jdtbridge.ui.agentArgs";

    private final LaunchTracker tracker;

    LaunchHandler(LaunchTracker tracker) {
        this.tracker = tracker;
    }

    private static String formatRunner(String testKind) {
        if (testKind == null) return null;
        return switch (testKind) {
        case "org.eclipse.jdt.junit.loader.junit6" ->
                "JUnit 6";
        case "org.eclipse.jdt.junit.loader.junit5" ->
                "JUnit 5";
        case "org.eclipse.jdt.junit.loader.junit4" ->
                "JUnit 4";
        default -> testKind;
        };
    }

    /**
     * Extract package name from JUnit CONTAINER attribute.
     * Format: "=project/src\/test\/java=...=/<package.name"
     * Returns null for project-level containers ("=project").
     */
    private static String parseContainerPackage(
            String container) {
        if (container == null || container.isBlank())
            return null;
        int lt = container.lastIndexOf('<');
        if (lt < 0) return null; // project-level
        return container.substring(lt + 1);
    }

    private ILaunchManager launchManager() {
        return DebugPlugin.getDefault().getLaunchManager();
    }

    String handleList(Map<String, String> params) {
        ILaunch[] launches = launchManager().getLaunches();
        var arr = new JsonArray();
        for (int i = launches.length - 1; i >= 0; i--) {
            arr.add(launchEntry(launches[i],
                    launchName(launches[i])));
        }
        return arr.toString();
    }

    private JsonObject launchEntry(ILaunch launch,
            String name) {
        String type = launchType(launch);
        String mode = launch.getLaunchMode();
        boolean terminated = launch.isTerminated();

        String pid = null;
        IProcess[] processes = launch.getProcesses();
        if (processes.length > 0) {
            IProcess proc = processes[0];
            pid = proc.getAttribute(
                    IProcess.ATTR_PROCESS_ID);
        }

        var entry = new JsonObject();
        String launchId = pid != null
                ? name + ":" + pid : name;
        entry.addProperty("launchId", launchId);
        entry.addProperty("configId", name);
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

        if (processes.length > 0) {
            IProcess proc = processes[0];
            if (terminated) {
                try {
                    entry.addProperty("exitCode",
                            proc.getExitValue());
                } catch (Exception e) { /* ignored */ }
            }
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
                    arr.add(configSummary(config));
                    seen.add(config.getName());
                }
            }

            for (var config : allConfigs) {
                if (seen.contains(config.getName())) continue;
                arr.add(configSummary(config));
            }

            return arr.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
        }
    }

    String handleConfig(Map<String, String> params) {
        String name = params.get("configId");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'configId' parameter");
        }
        try {
            ILaunchConfiguration config = findConfig(name);
            if (config == null) {
                return HttpServer.jsonError(
                        "Launch configuration not found: "
                        + name);
            }
            if ("xml".equals(params.get("format"))) {
                return configXml(config);
            }
            return configDetail(config);
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
        }
    }

    String handleConfigDelete(Map<String, String> params) {
        String configId = params.get("configId");
        if (configId == null || configId.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'configId' parameter");
        }
        ILaunchConfiguration config = findConfig(configId);
        if (config == null) {
            return HttpServer.jsonError(
                    "Launch configuration not found: "
                    + configId);
        }
        try {
            config.delete();
            var result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("configId", configId);
            return result.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
        }
    }

    // -- config summary (for /launch/configs list) --

    private JsonObject configSummary(
            ILaunchConfiguration config) throws CoreException {
        var obj = new JsonObject();
        obj.addProperty("configId", config.getName());
        String typeName = config.getType().getName();
        obj.addProperty("type", typeName);

        String project = config.getAttribute(
                ATTR_PROJECT_NAME, (String) null);
        if (project != null)
            obj.addProperty("project", project);

        String typeId = config.getType().getIdentifier();
        addTypeSummary(obj, config, typeId);
        return obj;
    }

    private static void addTypeSummary(JsonObject obj,
            ILaunchConfiguration config, String typeId)
            throws CoreException {
        switch (typeId) {
        case JUNIT_LAUNCH_TYPE, PDE_JUNIT_LAUNCH_TYPE -> {
            String mainType = config.getAttribute(
                    ATTR_MAIN_TYPE_NAME, (String) null);
            if (mainType != null && !mainType.isBlank())
                obj.addProperty("class", mainType);
            else {
                String pkg = parseContainerPackage(
                        config.getAttribute(
                                ATTR_CONTAINER,
                                (String) null));
                if (pkg != null)
                    obj.addProperty("package", pkg);
            }
            String method = config.getAttribute(
                    ATTR_TEST_NAME, (String) null);
            if (method != null && !method.isBlank())
                obj.addProperty("method", method);
            String runner = formatRunner(
                    config.getAttribute(
                            ATTR_TEST_KIND, (String) null));
            if (runner != null)
                obj.addProperty("runner", runner);
        }
        case JAVA_APP_LAUNCH_TYPE -> {
            String mainType = config.getAttribute(
                    ATTR_MAIN_TYPE_NAME, (String) null);
            if (mainType != null && !mainType.isBlank())
                obj.addProperty("mainClass", mainType);
        }
        case MAVEN_LAUNCH_TYPE -> {
            String goals = config.getAttribute(
                    MAVEN_GOALS, (String) null);
            if (goals != null)
                obj.addProperty("goals", goals);
            String profiles = config.getAttribute(
                    MAVEN_PROFILES, (String) null);
            if (profiles != null
                    && !profiles.isBlank())
                obj.addProperty("profiles", profiles);
        }
        case AGENT_LAUNCH_TYPE -> {
            String provider = config.getAttribute(
                    AGENT_PROVIDER, (String) null);
            if (provider != null && !provider.isBlank())
                obj.addProperty("provider", provider);
            String agent = config.getAttribute(
                    AGENT_NAME, (String) null);
            if (agent != null && !agent.isBlank())
                obj.addProperty("agent", agent);
            String agentArgs = config.getAttribute(
                    AGENT_ARGS, (String) null);
            if (agentArgs != null && !agentArgs.isBlank())
                obj.addProperty("agentArgs", agentArgs);
        }
        default -> { /* no extra fields */ }
        }
    }

    // -- config detail (for /launch/config?name=X) --

    private String configDetail(ILaunchConfiguration config)
            throws CoreException {
        var obj = new JsonObject();
        obj.addProperty("configId", config.getName());
        obj.addProperty("type", config.getType().getName());
        obj.addProperty("typeId",
                config.getType().getIdentifier());

        java.io.File launchFile = resolveLaunchFile(config);
        if (launchFile != null) {
            obj.addProperty("file",
                    launchFile.getAbsolutePath());
        }

        // All attributes as a nested object
        Map<String, Object> attrs = config.getAttributes();
        var attrsObj = new JsonObject();
        for (var entry : attrs.entrySet()) {
            attrsObj.add(entry.getKey(),
                    toJsonElement(entry.getValue()));
        }
        obj.add("attributes", attrsObj);
        return obj.toString();
    }

    private String configXml(ILaunchConfiguration config)
            throws CoreException {
        java.io.File launchFile = resolveLaunchFile(config);
        if (launchFile == null || !launchFile.exists()) {
            return HttpServer.jsonError(
                    "No .launch file for: "
                    + config.getName());
        }
        try {
            String xml = Files.readString(
                    launchFile.toPath());
            var obj = new JsonObject();
            obj.addProperty("configId", config.getName());
            obj.addProperty("file",
                    launchFile.getAbsolutePath());
            obj.addProperty("xml", xml);
            return obj.toString();
        } catch (IOException e) {
            return HttpServer.jsonError(
                    "Cannot read .launch file: "
                    + e.getMessage());
        }
    }

    private static java.io.File resolveLaunchFile(
            ILaunchConfiguration config) {
        java.io.File file = DebugPlugin.getDefault()
                .getStateLocation()
                .append(".launches")
                .append(config.getName() + ".launch")
                .toFile();
        return file.exists() ? file : null;
    }

    @SuppressWarnings("unchecked")
    private static com.google.gson.JsonElement toJsonElement(
            Object value) {
        if (value == null)
            return com.google.gson.JsonNull.INSTANCE;
        if (value instanceof String s)
            return new JsonPrimitive(s);
        if (value instanceof Boolean b)
            return new JsonPrimitive(b);
        if (value instanceof Number n)
            return new JsonPrimitive(n);
        if (value instanceof List<?> list) {
            var arr = new JsonArray();
            for (Object item : list)
                arr.add(toJsonElement(item));
            return arr;
        }
        if (value instanceof Set<?> set) {
            var arr = new JsonArray();
            for (Object item : set)
                arr.add(toJsonElement(item));
            return arr;
        }
        if (value instanceof Map<?, ?> map) {
            var obj = new JsonObject();
            for (var entry
                    : ((Map<String, Object>) map).entrySet())
                obj.add(entry.getKey(),
                        toJsonElement(entry.getValue()));
            return obj;
        }
        return new JsonPrimitive(value.toString());
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
        String nameOrId = params.get("launchId");
        ILaunch[] launches = launchManager().getLaunches();
        int removed = 0;

        for (ILaunch launch : launches) {
            if (!launch.isTerminated()) continue;
            String configId = launchName(launch);
            if (nameOrId != null && !nameOrId.isBlank()) {
                // Accept configId or launchId
                ILaunch found = findLaunch(nameOrId);
                if (found != launch) continue;
            }
            launchManager().removeLaunch(launch);
            removed++;
        }

        var result = new JsonObject();
        result.addProperty("removed", removed);
        return result.toString();
    }

    String handleRun(Map<String, String> params) {
        String name = params.get("configId");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'configId' parameter");
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
            String configId = launchName(launch);
            response.addProperty("configId", configId);
            response.addProperty("mode", mode);
            response.addProperty("type",
                    launchType(launch));
            addProcessMetadata(launch, response);
            // Add launchId after process metadata (has pid)
            String pid = response.has("pid")
                    ? response.get("pid").getAsString() : null;
            response.addProperty("launchId",
                    pid != null ? configId + ":" + pid
                            : configId);
            return response.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
        }
    }

    String handleStop(Map<String, String> params) {
        String name = params.get("launchId");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'launchId' parameter");
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
            result.addProperty("configId",
                    launchName(target));
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
        String name = params.get("launchId");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'launchId' parameter");
        }

        String tailStr = params.get("tail");
        String stream = params.get("stream");

        // LaunchTracker multi-key: accepts configId, launchId,
        // or testRunId — all resolve to the TrackedLaunch.
        LaunchTracker.TrackedLaunch tl = tracker.get(name);
        if (tl == null) {
            return HttpServer.jsonError(
                    "Launch not found: " + name);
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

        String cId = LaunchHandler.launchName(tl.launch);

        var obj = new JsonObject();
        obj.addProperty("configId", cId);
        addProcessMetadata(tl.launch, obj);
        // Compose launchId from configId + first PID
        if (obj.has("pid"))
            obj.addProperty("launchId",
                    cId + ":" + obj.get("pid").getAsString());
        obj.addProperty("terminated", tl.terminated);
        obj.addProperty("output", result);
        return obj.toString();
    }

    /**
     * Find launch by name or launchId (configId:pid).
     * LaunchId format allows disambiguation when multiple
     * launches share the same config name.
     */
    private ILaunch findLaunch(String nameOrId) {
        // Parse launchId format: configId:pid
        String configName = nameOrId;
        String targetPid = null;
        int colonIdx = nameOrId.lastIndexOf(':');
        if (colonIdx > 0) {
            String maybePid = nameOrId.substring(colonIdx + 1);
            // Only treat as pid if it's numeric
            if (maybePid.matches("\\d+")) {
                configName = nameOrId.substring(0, colonIdx);
                targetPid = maybePid;
            }
        }

        ILaunch[] launches = launchManager().getLaunches();
        ILaunch fallback = null;
        for (int i = launches.length - 1; i >= 0; i--) {
            if (!configName.equals(launchName(launches[i])))
                continue;
            if (targetPid != null) {
                IProcess[] procs = launches[i].getProcesses();
                if (procs.length > 0) {
                    String pid = procs[0].getAttribute(
                            IProcess.ATTR_PROCESS_ID);
                    if (targetPid.equals(pid))
                        return launches[i];
                }
            } else {
                if (fallback == null) fallback = launches[i];
            }
        }
        return fallback;
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
