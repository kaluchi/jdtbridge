package io.github.kaluchi.jdtbridge.coverage;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionManager;
import org.eclipse.eclemma.core.launching.ICoverageLaunch;
import org.eclipse.jdt.core.IPackageFragmentRoot;

import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.error;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.optBool;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.optString;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.parseObjectBody;

import io.github.kaluchi.jdtbridge.LaunchAttrs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        ILaunchConfiguration config = LaunchAttrs.findConfig(configId);
        if (config == null) {
            return error("coverage-config-not-found",
                    "Launch configuration not found: " + configId);
        }
        String typeId;
        try {
            typeId = config.getType().getIdentifier();
        } catch (CoreException e) {
            return error("coverage-launch-failed",
                    "Failed to read launch configuration type: "
                            + e.getMessage());
        }
        if (!CoverageTypes.isSupported(typeId)) {
            return CoverageTypes.modeNotSupportedJson(typeId);
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
        JsonObject body = parseObjectBody(requestBody);
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


    /** Build the response JSON for {@code /coverage/run} and
     *  {@code /coverage/relaunch}. */
    private static String runResponse(ILaunch launch) {
        var obj = new JsonObject();
        obj.addProperty("ok", true);

        ILaunchConfiguration config = launch.getLaunchConfiguration();
        String configId = config != null ? config.getName() : "";
        obj.addProperty("configId", configId);

        Long timestamp = LaunchAttrs.launchTimestamp(launch);
        String coverageId = timestamp != null
                ? configId + ":" + timestamp
                : configId;
        obj.addProperty("coverageId", coverageId);
        if (timestamp != null) {
            obj.addProperty("launchTimestamp", timestamp);
        }

        String pid = LaunchAttrs.firstPid(launch);
        if (pid != null) {
            obj.addProperty("processPid", pid);
        }
        obj.addProperty("launchId",
                LaunchAttrs.launchIdOf(configId, launch));

        IProcess[] procs = launch.getProcesses();
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
                arr.add(root.getPath().toString());
            }
            obj.add("coverageScope", arr);
        }

        return obj.toString();
    }


}
