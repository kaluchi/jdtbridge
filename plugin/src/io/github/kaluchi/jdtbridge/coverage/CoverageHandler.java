package io.github.kaluchi.jdtbridge.coverage;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionManager;
import org.eclipse.eclemma.core.launching.ICoverageLaunch;
import org.eclipse.jdt.core.IPackageFragmentRoot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * HTTP handlers for the four mutating {@code /coverage/*}
 * endpoints: {@code /run}, {@code /dump}, {@code /refresh},
 * {@code /relaunch}.
 * <p>
 * Read-only / list endpoints live in
 * {@link CoverageSessionHandler}; the streaming variant is
 * {@link CoverageProgressStreamer}.
 */
class CoverageHandler {

    static final String ATTR_CMDLINE =
            "org.eclipse.debug.core.ATTR_CMDLINE";

    private final CoverageTracker tracker;

    CoverageHandler(CoverageTracker tracker) {
        this.tracker = tracker;
    }

    /** {@code GET /coverage/run?configId=...} — start a new
     *  coverage launch. */
    String handleRun(Map<String, String> params) {
        String configId = params.get("configId");
        if (configId == null || configId.isBlank()) {
            return error("coverage-config-not-found",
                    "Missing 'configId' parameter");
        }
        ILaunchConfiguration config = findConfig(configId);
        if (config == null) {
            return error("coverage-config-not-found",
                    "Launch configuration not found: " + configId);
        }
        String typeId;
        try {
            typeId = config.getType().getIdentifier();
        } catch (CoreException e) {
            return error("coverage-config-not-found",
                    e.getMessage());
        }
        if (!CoverageTypes.isSupported(typeId)) {
            return modeNotSupported(typeId);
        }
        try {
            ILaunch launch = config.launch(
                    CoverageTypes.LAUNCH_MODE, null, true);
            return runResponse(launch);
        } catch (CoreException e) {
            return error("coverage-launch-failed",
                    "Launch failed: " + e.getMessage());
        }
    }

    /** {@code POST /coverage/dump} body {@code {coverageId, reset}}
     *  — request a dump from the running JaCoCo agent. */
    String handleDump(String requestBody) {
        JsonObject body = parseBody(requestBody);
        if (body == null) {
            return error("coverage-not-found",
                    "Missing or invalid request body");
        }
        String coverageId = optString(body, "coverageId");
        boolean reset = optBool(body, "reset", false);
        if (coverageId == null || coverageId.isBlank()) {
            return error("coverage-not-found",
                    "Missing 'coverageId' in body");
        }
        CoverageRun run = tracker.byCoverageId(coverageId);
        if (run == null) {
            return error("coverage-not-found", coverageId);
        }
        if (run.kind != CoverageRun.Kind.LIVE) {
            return error("coverage-launch-not-live",
                    "Session has no live launch: " + coverageId);
        }
        if (run.terminated) {
            return error("coverage-launch-terminated",
                    "Launch already terminated: " + coverageId);
        }
        if (!(run.launch instanceof ICoverageLaunch)) {
            return error("coverage-launch-not-live",
                    "Launch is not a coverage launch: "
                            + coverageId);
        }
        try {
            ((ICoverageLaunch) run.launch).requestDump(reset);
            var ok = new JsonObject();
            ok.addProperty("ok", true);
            return ok.toString();
        } catch (CoreException e) {
            return error("coverage-launch-failed",
                    "Dump failed: " + e.getMessage());
        }
    }

    /** {@code POST /coverage/refresh} — re-fire
     *  {@code sessionActivated} for the active session, which
     *  causes {@link org.eclipse.eclemma.internal.core.JavaCoverageLoader}
     *  to cancel any in-flight job and re-analyze. */
    String handleRefresh() {
        ISessionManager sm = CoverageTools.getSessionManager();
        ICoverageSession active = sm.getActiveSession();
        if (active == null) {
            return error("coverage-no-active-session",
                    "No active coverage session");
        }
        sm.refreshActiveSession();
        var obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("activeCoverageId",
                tracker.activeCoverageId());
        return obj.toString();
    }

    /** {@code POST /coverage/relaunch} — relaunch the active
     *  session's source config in coverage mode. The bridge runs
     *  headless and calls {@code config.launch(LAUNCH_MODE, ...)}
     *  directly instead of Eclipse's {@code DebugUITools.launch}. */
    String handleRelaunch() {
        ISessionManager sm = CoverageTools.getSessionManager();
        ICoverageSession active = sm.getActiveSession();
        if (active == null) {
            return error("coverage-no-active-session",
                    "No active coverage session");
        }
        ILaunchConfiguration config = active.getLaunchConfiguration();
        if (config == null) {
            return error("coverage-launch-not-live",
                    "Active session has no associated launch "
                            + "configuration");
        }
        try {
            ILaunch launch = config.launch(
                    CoverageTypes.LAUNCH_MODE, null, true);
            return runResponse(launch);
        } catch (CoreException e) {
            return error("coverage-launch-failed",
                    "Relaunch failed: " + e.getMessage());
        }
    }

    // -- helpers --

    private static ILaunchManager launchManager() {
        return DebugPlugin.getDefault().getLaunchManager();
    }

    private static ILaunchConfiguration findConfig(String name) {
        try {
            for (ILaunchConfiguration c
                    : launchManager().getLaunchConfigurations()) {
                if (name.equals(c.getName())) {
                    return c;
                }
            }
        } catch (Exception e) {
            // fall through to null
        }
        return null;
    }

    /** Build the response JSON for {@code /coverage/run} and
     *  {@code /coverage/relaunch}. */
    private static String runResponse(ILaunch launch) {
        var obj = new JsonObject();
        obj.addProperty("ok", true);

        ILaunchConfiguration config = launch.getLaunchConfiguration();
        String configId = config != null ? config.getName() : "";
        obj.addProperty("configId", configId);

        Long timestamp = parseLong(launch.getAttribute(
                DebugPlugin.ATTR_LAUNCH_TIMESTAMP));
        String coverageId = timestamp != null
                ? configId + ":" + timestamp
                : configId;
        obj.addProperty("coverageId", coverageId);
        if (timestamp != null) {
            obj.addProperty("launchTimestamp", timestamp);
        }

        IProcess[] procs = launch.getProcesses();
        String pid = procs.length > 0
                ? procs[0].getAttribute(IProcess.ATTR_PROCESS_ID)
                : null;
        if (pid != null) {
            obj.addProperty("processPid", pid);
            obj.addProperty("launchId", configId + ":" + pid);
        } else {
            obj.addProperty("launchId", configId);
        }

        if (procs.length > 0) {
            String cmdline = procs[0].getAttribute(ATTR_CMDLINE);
            if (cmdline != null) {
                obj.addProperty("cmdline", cmdline);
            }
        }

        if (config != null) {
            try {
                ILaunchConfigurationType type = config.getType();
                if (type != null) {
                    obj.addProperty("configType", type.getName());
                    obj.addProperty("configTypeId",
                            type.getIdentifier());
                }
            } catch (CoreException e) {
                // skip type info on failure
            }
        }

        if (launch instanceof ICoverageLaunch coverageLaunch) {
            var arr = new JsonArray();
            for (IPackageFragmentRoot root
                    : coverageLaunch.getScope()) {
                arr.add(root.getHandleIdentifier());
            }
            obj.add("coverageScope", arr);
        }

        return obj.toString();
    }

    private String modeNotSupported(String typeId) {
        var obj = new JsonObject();
        obj.addProperty("error", "coverage-mode-not-supported");
        obj.addProperty("message",
                "Launch type does not support coverage mode: "
                        + typeId);
        var arr = new JsonArray();
        for (String supported : CoverageTypes.supported()) {
            arr.add(supported);
        }
        obj.add("supportedTypeIds", arr);
        return obj.toString();
    }

    private static String error(String kind, String message) {
        var obj = new JsonObject();
        obj.addProperty("error", kind);
        if (message != null && !message.isEmpty()) {
            obj.addProperty("message", message);
        }
        return obj.toString();
    }

    private static JsonObject parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            // fall through to null
        }
        return null;
    }

    private static String optString(JsonObject body, String key) {
        JsonElement el = body.get(key);
        return el != null && !el.isJsonNull()
                ? el.getAsString() : null;
    }

    private static boolean optBool(JsonObject body, String key,
            boolean defaultValue) {
        JsonElement el = body.get(key);
        return el != null && !el.isJsonNull()
                ? el.getAsBoolean() : defaultValue;
    }

    private static Long parseLong(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
