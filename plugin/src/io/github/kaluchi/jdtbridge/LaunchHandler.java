package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;

class LaunchHandler {

    // Literal copies — switch case labels require compile-time
    // constants, and SDK-side ATTR_* / ID_* are computed at class
    // init via plugin id concatenation, so we cannot alias them
    // directly. Values are pinned to the SDK by
    // LaunchAttrKeysTest, which fails if a future SDK upgrade
    // shifts any of them.
    private static final String ATTR_PROJECT_NAME =
            "org.eclipse.jdt.launching.PROJECT_ATTR";
    private static final String ATTR_MAIN_TYPE_NAME =
            "org.eclipse.jdt.launching.MAIN_TYPE";
    private static final String ATTR_PROGRAM_ARGUMENTS =
            "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS";
    private static final String ATTR_VM_ARGUMENTS =
            "org.eclipse.jdt.launching.VM_ARGUMENTS";
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
    private static final String EXTERNAL_TOOLS_TYPE =
            "org.eclipse.ui.externaltools"
            + ".ProgramLaunchConfigurationType";
    private static final String ATTR_TOOL_ARGUMENTS =
            "org.eclipse.ui.externaltools.ATTR_TOOL_ARGUMENTS";

    private final LaunchTracker tracker;

    LaunchHandler(LaunchTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Extract package name from JUnit CONTAINER attribute.
     * Format: "=project/src\/test\/java=...=/<package.name"
     * Returns null for project-level containers ("=project").
     */
    static String parseContainerPackage(
            String container) {
        if (container == null || container.isBlank())
            return null;
        int lt = container.lastIndexOf('<');
        if (lt < 0) return null; // project-level
        return container.substring(lt + 1);
    }

    String handleList(Map<String, String> params,
            ProjectScope scope) {
        ILaunch[] launches = LaunchAttrs.launchManager().getLaunches();
        var arr = new JsonArray();
        // Reverse order: newest first
        reversedStream(launches)
                .filter(scope::containsLaunch)
                .map(l -> launchEntry(l, launchName(l)))
                .forEach(arr::add);
        return arr.toString();
    }

    private static <T> Stream<T> reversedStream(T[] array) {
        var list = Arrays.asList(array);
        return list.reversed().stream();
    }

    private JsonObject launchEntry(ILaunch launch,
            String name) {
        String type = launchType(launch);
        String mode = launch.getLaunchMode();
        boolean terminated = launch.isTerminated();

        String pid = LaunchAttrs.firstPid(launch);
        IProcess[] processes = launch.getProcesses();

        var entry = new JsonObject();
        entry.addProperty("launchId",
                LaunchAttrs.launchIdOf(name, launch));
        entry.addProperty("configId", name);
        entry.addProperty("configType", type);
        entry.addProperty("mode", mode);
        entry.addProperty("terminated", terminated);

        Long startedAt = LaunchAttrs.launchTimestamp(launch);
        if (startedAt != null) {
            entry.addProperty("started", startedAt);
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

    String handleConfigs(Map<String, String> params,
            ProjectScope scope) {
        try {
            var allConfigs =
                    LaunchAttrs.launchManager().getLaunchConfigurations();
            ILaunchConfiguration[] recent = getRecentConfigs();

            // Recent first, then remaining — deduplicated
            var seen = new LinkedHashSet<String>();
            var arr = new JsonArray();

            Stream.concat(
                    Arrays.stream(recent),
                    Arrays.stream(allConfigs))
                    .filter(scope::containsConfig)
                    .filter(c -> seen.add(c.getName()))
                    .map(this::configSummary)
                    .forEach(arr::add);

            return arr.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
        }
    }


    String handleConfig(Map<String, String> params) {
        String name = params.get("configId");
        if (name == null || name.isBlank()) {
            return HttpServer.missingParamError("configId");
        }
        try {
            ILaunchConfiguration config = LaunchAttrs.findConfig(name);
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

    @SuppressWarnings("restriction")
    String handleImport(Map<String, String> params,
            String launchXmlContent) {
        String configId = params.get("configId");
        if (configId == null || configId.isBlank()) {
            return HttpServer.missingParamError("configId");
        }
        if (launchXmlContent == null || launchXmlContent.isBlank()) {
            return HttpServer.jsonError(
                    "Missing launch configuration XML in request body");
        }
        // Reject path separators in configId (prevent path traversal)
        if (configId.contains("/") || configId.contains("\\")
                || configId.contains("..")) {
            return HttpServer.jsonError(
                    "Invalid configId: must not contain "
                    + "path separators or '..'");
        }
        // Check if configId already exists (API cache + file on disk)
        if (LaunchAttrs.findConfig(configId) != null
                || launchFileExists(configId)) {
            return HttpServer.jsonError(
                    "Launch configuration \"" + configId
                    + "\" already exists. "
                    + "Use --configid to import with a different name.");
        }
        java.nio.file.Path tempDir = null;
        java.nio.file.Path tempLaunchFile = null;
        try {
            tempDir = Files.createTempDirectory("jdtbridge-import");
            tempLaunchFile = tempDir.resolve(
                    configId + ".launch");
            Files.writeString(tempLaunchFile, launchXmlContent);

            // Eclipse's built-in import API — handles file copy,
            // LaunchManager registration, and change notification
            var launchManager =
                    (org.eclipse.debug.internal.core.LaunchManager)
                    DebugPlugin.getDefault().getLaunchManager();
            launchManager.importConfigurations(
                    new java.io.File[] { tempLaunchFile.toFile() },
                    null);

            var importResult = new JsonObject();
            importResult.addProperty("configId", configId);
            importResult.addProperty("imported", true);
            return importResult.toString();
        } catch (Exception importException) {
            return HttpServer.jsonError(
                    "Import failed: " + importException.getMessage());
        } finally {
            try {
                if (tempLaunchFile != null)
                    Files.deleteIfExists(tempLaunchFile);
                if (tempDir != null)
                    Files.deleteIfExists(tempDir);
            } catch (IOException cleanupException) {
                Log.warn("Failed to clean temp import files",
                        cleanupException);
            }
        }
    }

    String handleConfigDelete(Map<String, String> params) {
        String configId = params.get("configId");
        if (configId == null || configId.isBlank()) {
            return HttpServer.missingParamError("configId");
        }
        ILaunchConfiguration config = LaunchAttrs.findConfig(configId);
        if (config == null) {
            return HttpServer.jsonError(
                    "Launch configuration not found: "
                    + configId);
        }
        try {
            if (!config.isLocal()) {
                return HttpServer.jsonError(
                        "Not found in workspace metadata: "
                        + configId);
            }
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
            ILaunchConfiguration config) {
        var obj = new JsonObject();
        obj.addProperty("configId", config.getName());
        try {
            String typeName = config.getType().getName();
            obj.addProperty("configType", typeName);

            String project = config.getAttribute(
                    ATTR_PROJECT_NAME, (String) null);
            if (project != null)
                obj.addProperty("project", project);

            String typeId = config.getType().getIdentifier();
            addTypeSummary(obj, config, typeId);
        } catch (CoreException e) {
            obj.addProperty("error", e.getMessage());
        }
        return obj;
    }

    private static void addTypeSummary(JsonObject obj,
            ILaunchConfiguration config, String typeId)
            throws CoreException {
        switch (typeId) {
        case JUnitLaunchConst.LAUNCH_TYPE,
             JUnitLaunchConst.PDE_LAUNCH_TYPE -> {
            String mainType = config.getAttribute(
                    ATTR_MAIN_TYPE_NAME, (String) null);
            if (mainType != null && !mainType.isBlank())
                obj.addProperty("class", mainType);
            else {
                String pkg = parseContainerPackage(
                        config.getAttribute(
                                JUnitLaunchConst.ATTR_CONTAINER,
                                (String) null));
                if (pkg != null)
                    obj.addProperty("package", pkg);
            }
            String method = config.getAttribute(
                    JUnitLaunchConst.ATTR_TEST_NAME, (String) null);
            if (method != null && !method.isBlank())
                obj.addProperty("method", method);
            String runner = JUnitLaunchConst.formatRunner(
                    config.getAttribute(
                            JUnitLaunchConst.ATTR_TEST_KIND,
                            (String) null));
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
        obj.addProperty("configType", config.getType().getName());
        obj.addProperty("configTypeId",
                config.getType().getIdentifier());

        java.io.File launchFile = resolveLaunchFile(config.getName());
        if (launchFile != null) {
            obj.addProperty("file",
                    launchFile.getAbsolutePath());
        }

        // All attributes as a nested object
        Map<String, Object> attrs = config.getAttributes();
        var attrsObj = new JsonObject();
        for (var entry : attrs.entrySet()) {
            attrsObj.add(entry.getKey(),
                    JsonValues.toElement(entry.getValue()));
        }
        obj.add("attributes", attrsObj);
        return obj.toString();
    }

    private String configXml(ILaunchConfiguration config)
            throws CoreException {
        java.io.File launchFile = resolveLaunchFile(config.getName());
        if (launchFile == null) {
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

    static java.io.File resolveLaunchFile(String configName) {
        java.io.File file = DebugPlugin.getDefault()
                .getStateLocation()
                .append(".launches")
                .append(configName + ".launch")
                .toFile();
        return file.exists() ? file : null;
    }

    /** Launch groups queried for recency, in fixed priority order.
     *  Coverage group belongs to EclEmma — absent from history XML
     *  when the plugin is not installed (section simply missing). */
    private static final List<String> LAUNCH_GROUPS = List.of(
            "org.eclipse.debug.ui.launchGroup.run",
            "org.eclipse.debug.ui.launchGroup.debug",
            "org.eclipse.eclemma.ui.launchGroup.coverage");

    /**
     * Recent-used launch configurations, ordered:
     *   1. favorites (by LAUNCH_GROUPS priority)
     *   2. mruHistory (by LAUNCH_GROUPS priority, most-recent first)
     * dedup by name across the whole sequence.
     *
     * Source: workspace-local <code>launchConfigurationHistory.xml</code>,
     * persisted by Eclipse on workspace save (not on each launch — so
     * this lags in-memory state by up to a workspace save interval).
     * Read directly instead of via DebugUIPlugin so the code works in
     * headless PDE test runtimes where UI bundles can't activate.
     *
     * Returns empty array when history file is absent (fresh workspace).
     */
    private ILaunchConfiguration[] getRecentConfigs()
            throws IOException, ParserConfigurationException,
                   SAXException, CoreException {
        File historyFile = historyFile();
        if (!historyFile.exists()) return new ILaunchConfiguration[0];

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(historyFile);
        ILaunchManager lm = LaunchAttrs.launchManager();
        List<ILaunchConfiguration> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String section : List.of("favorites", "mruHistory"))
            for (String groupId : LAUNCH_GROUPS)
                collectSection(doc, groupId, section, lm, out, seen);
        return out.toArray(new ILaunchConfiguration[0]);
    }

    private static File historyFile() {
        return ResourcesPlugin.getWorkspace().getRoot().getLocation()
                .append(".metadata/.plugins/org.eclipse.debug.ui"
                        + "/launchConfigurationHistory.xml")
                .toFile();
    }

    static void collectSection(Document doc, String groupId,
            String sectionName, ILaunchManager lm,
            List<ILaunchConfiguration> out, Set<String> seen)
            throws CoreException {
        Element group = findLaunchGroup(doc, groupId);
        if (group == null) return;
        Element section = childElement(group, sectionName);
        if (section == null) return;
        var launches = section.getElementsByTagName("launch");
        for (int i = 0; i < launches.getLength(); i++) {
            String memento = ((Element) launches.item(i))
                    .getAttribute("memento");
            ILaunchConfiguration c = lm.getLaunchConfiguration(memento);
            if (c != null && c.exists() && seen.add(c.getName()))
                out.add(c);
        }
    }

    static Element findLaunchGroup(Document doc, String id) {
        var groups = doc.getElementsByTagName("launchGroup");
        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            if (id.equals(g.getAttribute("id"))) return g;
        }
        return null;
    }

    static Element childElement(Element parent, String name) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && name.equals(e.getTagName()))
                return e;
        }
        return null;
    }

    String handleClear(Map<String, String> params) {
        String nameOrId = params.get("launchId");
        ILaunch[] launches = LaunchAttrs.launchManager().getLaunches();
        int removed = 0;

        for (ILaunch launch : launches) {
            if (!launch.isTerminated()) continue;
            if (nameOrId != null && !nameOrId.isBlank()) {
                // Accept configId or launchId
                ILaunch found = findLaunch(nameOrId);
                if (found != launch) continue;
            }
            LaunchAttrs.launchManager().removeLaunch(launch);
            removed++;
        }

        var result = new JsonObject();
        result.addProperty("removed", removed);
        return result.toString();
    }

    /**
     * Create a WorkingCopy with extra arguments appended to the
     * appropriate attribute for the launch type. Not saved —
     * one-time use for this launch only.
     */
    ILaunchConfiguration appendArgs(
            ILaunchConfiguration config, String extraArgs)
            throws CoreException {
        var wc = config.getWorkingCopy();
        String typeId = config.getType().getIdentifier();
        String attrKey = argsAttribute(typeId);
        String existing = wc.getAttribute(attrKey, "");
        String combined = existing.isBlank()
                ? extraArgs
                : existing + " " + extraArgs;
        wc.setAttribute(attrKey, combined);
        return wc;
    }

    /**
     * Map launch type to the attribute key for appending arguments.
     */
    static String argsAttribute(String typeId) {
        return switch (typeId) {
            case EXTERNAL_TOOLS_TYPE -> ATTR_TOOL_ARGUMENTS;
            case JAVA_APP_LAUNCH_TYPE -> ATTR_PROGRAM_ARGUMENTS;
            case MAVEN_LAUNCH_TYPE -> ATTR_TOOL_ARGUMENTS;
            case JUnitLaunchConst.LAUNCH_TYPE,
                 JUnitLaunchConst.PDE_LAUNCH_TYPE
                    -> ATTR_VM_ARGUMENTS;
            case AGENT_LAUNCH_TYPE -> AGENT_ARGS;
            default -> ATTR_PROGRAM_ARGUMENTS;
        };
    }

    String handleRun(Map<String, String> params) {
        String name = params.get("configId");
        if (name == null || name.isBlank()) {
            return HttpServer.missingParamError("configId");
        }
        String mode = params.containsKey("debug")
                ? ILaunchManager.DEBUG_MODE
                : ILaunchManager.RUN_MODE;
        try {
            ILaunchConfiguration config = LaunchAttrs.findConfig(name);
            if (config == null) {
                return HttpServer.jsonError(
                        "Launch configuration not found: "
                        + name);
            }
            String extraArgs = params.get("args");
            ILaunchConfiguration toRun = config;
            if (extraArgs != null && !extraArgs.isBlank()) {
                toRun = appendArgs(config, extraArgs);
            }
            ILaunch launch = toRun.launch(mode, null, true);
            var response = new JsonObject();
            response.addProperty("ok", true);
            String configId = launchName(launch);
            response.addProperty("configId", configId);
            response.addProperty("mode", mode);
            response.addProperty("configType",
                    launchType(launch));
            addProcessMetadata(launch, response);
            response.addProperty("launchId",
                    LaunchAttrs.launchIdOf(configId, launch));
            return response.toString();
        } catch (Exception e) {
            return HttpServer.jsonError(e.getMessage());
        }
    }

    String handleStop(Map<String, String> params) {
        String name = params.get("launchId");
        if (name == null || name.isBlank()) {
            return HttpServer.missingParamError("launchId");
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


    private boolean launchFileExists(String configId) {
        return resolveLaunchFile(configId) != null;
    }

    String handleConsole(Map<String, String> params) {
        String name = params.get("launchId");
        if (name == null || name.isBlank()) {
            return HttpServer.missingParamError("launchId");
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
        obj.addProperty("launchId",
                LaunchAttrs.launchIdOf(cId, tl.launch));
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

        ILaunch[] launches = LaunchAttrs.launchManager().getLaunches();
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
