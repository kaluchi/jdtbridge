package io.github.kaluchi.jdtbridge.coverage;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionManager;
import org.eclipse.eclemma.core.analysis.IJavaModelCoverage;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.jacoco.core.data.SessionInfo;

import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.addNullableLong;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.addNullableString;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.countersOf;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.error;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.optBool;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.optString;
import static io.github.kaluchi.jdtbridge.coverage.CoverageJson.parseObjectBody;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.kaluchi.jdtbridge.ProjectScope;

/**
 * HTTP handlers for the read / list / mutating-on-state
 * {@code /coverage/*} endpoints: {@code /runs}, {@code /session},
 * {@code /active}, {@code /activate}, {@code /merge},
 * {@code /remove}.
 * <p>
 * Mutation against a running launch (run / dump / refresh /
 * relaunch) lives in {@link CoverageHandler}; streaming lives in
 * {@link CoverageProgressStreamer}.
 */
class CoverageSessionHandler {

    /** Same template as EclEmma's
     *  {@code MergeSessionsDialogDescriptionDefault_value}
     *  ({@code uimessages.properties:59}). Mirrors what the GUI
     *  injects when the user clicks Merge Sessions… without
     *  editing the description field. */
    private static final String MERGE_DESC_TEMPLATE =
            "Merged ({0,date,medium} {0,time,medium})";

    private final CoverageTracker tracker;
    private final CoverageAnalyzer analyzer;

    CoverageSessionHandler(CoverageTracker tracker,
            CoverageAnalyzer analyzer) {
        this.tracker = tracker;
        this.analyzer = analyzer;
    }

    /** {@code GET /coverage/runs} — list every tracked run,
     *  filtered by {@link ProjectScope}. */
    String handleRuns(ProjectScope scope) {
        var arr = new JsonArray();
        String activeCoverageId = tracker.activeCoverageId();
        for (CoverageRun run : tracker.snapshot().values()) {
            if (!inScope(run, scope)) {
                continue;
            }
            JsonObject entry = runEntry(run);
            entry.addProperty("active",
                    run.coverageId.equals(activeCoverageId));
            arr.add(entry);
        }
        return arr.toString();
    }

    /** {@code GET /coverage/session?coverageId=...} — full entry
     *  including counters and JaCoCo session-info list. */
    String handleSession(Map<String, String> params) {
        String coverageId = params.get("coverageId");
        if (coverageId == null || coverageId.isBlank()) {
            return error("coverage-not-found",
                    "Missing 'coverageId' parameter");
        }
        CoverageRun run = tracker.byCoverageId(coverageId);
        if (run == null) {
            return error("coverage-not-found", coverageId);
        }
        Integer dumpIndex = parseDumpIndex(coverageId);
        ICoverageSession session = run.resolveSession(dumpIndex);
        if (session == null) {
            if (dumpIndex != null) {
                return error("coverage-dump-not-found", coverageId);
            }
            return runEntry(run).toString();
        }
        JsonObject entry = runEntry(run);
        try {
            CoverageAnalyzer.CachedAnalysis ca =
                    analyzer.ensureAnalyzed(session);
            entry.add("counters",
                    countersOf(ca.modelCoverage));
            entry.add("jacocoSessionInfos",
                    sessionInfosJson(ca.jacocoSessionInfos));
        } catch (CoreException e) {
            return error("coverage-analysis-failed",
                    e.getMessage());
        }
        return entry.toString();
    }

    /** {@code GET /coverage/active} — id of the active session,
     *  or {@code null}. */
    String handleActive() {
        var obj = new JsonObject();
        addNullableString(obj, "activeCoverageId",
                tracker.activeCoverageId());
        return obj.toString();
    }

    /** {@code POST /coverage/activate} body {@code {coverageId}}. */
    String handleActivate(String requestBody) {
        JsonObject body = parseObjectBody(requestBody);
        if (body == null) {
            return error("coverage-not-found",
                    "Missing or invalid request body");
        }
        String coverageId = optString(body, "coverageId");
        if (coverageId == null || coverageId.isBlank()) {
            return error("coverage-not-found",
                    "Missing 'coverageId' in body");
        }
        CoverageRun run = tracker.byCoverageId(coverageId);
        if (run == null) {
            return error("coverage-not-found", coverageId);
        }
        Integer dumpIndex = parseDumpIndex(coverageId);
        ICoverageSession target = run.resolveSession(dumpIndex);
        if (target == null) {
            return error("coverage-dump-not-found", coverageId);
        }
        String previous = tracker.activeCoverageId();
        CoverageTools.getSessionManager().activateSession(target);
        var obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("activeCoverageId", run.coverageId);
        addNullableString(obj, "previousActiveCoverageId", previous);
        return obj.toString();
    }

    /** {@code POST /coverage/merge} body
     *  {@code {coverageIds, description}}. */
    String handleMerge(String requestBody) {
        JsonObject body = parseObjectBody(requestBody);
        if (body == null) {
            return error("coverage-not-found",
                    "Missing or invalid request body");
        }
        JsonElement arrEl = body.get("coverageIds");
        if (arrEl == null || !arrEl.isJsonArray()) {
            return error("coverage-merge-too-few-inputs",
                    "Missing 'coverageIds' array in body");
        }
        JsonArray inputArr = arrEl.getAsJsonArray();
        if (inputArr.size() < 2) {
            return error("coverage-merge-too-few-inputs",
                    "Need at least 2 inputs, got " + inputArr.size());
        }
        List<ICoverageSession> inputs = new ArrayList<>();
        for (JsonElement el : inputArr) {
            String id = el.getAsString();
            CoverageRun run = tracker.byCoverageId(id);
            if (run == null) {
                JsonObject err = new JsonObject();
                err.addProperty("error", "coverage-not-found");
                err.addProperty("message",
                        "Unknown coverageId: " + id);
                JsonObject ctx = new JsonObject();
                ctx.addProperty("missing", id);
                err.add("context", ctx);
                return err.toString();
            }
            inputs.addAll(run.sessions);
        }
        String description = optString(body, "description");
        if (description == null || description.isBlank()) {
            description = MessageFormat.format(
                    MERGE_DESC_TEMPLATE,
                    new Object[] { new Date() });
        }
        ISessionManager sm = CoverageTools.getSessionManager();
        ICoverageSession merged;
        try {
            merged = sm.mergeSessions(inputs, description,
                    new NullProgressMonitor());
        } catch (CoreException e) {
            return error("coverage-launch-failed",
                    "Merge failed: " + e.getMessage());
        }
        try {
            org.eclipse.core.runtime.jobs.Job.getJobManager().join(
                    CoverageTracker.CLASSIFY_FAMILY,
                    new NullProgressMonitor());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String mergedCoverageId = findCoverageIdFor(merged);
        var obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("mergedCoverageId", mergedCoverageId);
        JsonArray consumed = new JsonArray();
        for (JsonElement el : inputArr) {
            consumed.add(el.getAsString());
        }
        obj.add("consumedCoverageIds", consumed);
        obj.addProperty("active", true);
        return obj.toString();
    }

    /** {@code POST /coverage/remove} — body {@code {}} removes
     *  the active session, body {@code {all: true}} removes all. */
    String handleRemove(String requestBody) {
        JsonObject body = parseObjectBody(requestBody);
        boolean all = body != null && optBool(body, "all", false);
        ISessionManager sm = CoverageTools.getSessionManager();
        List<String> removed = new ArrayList<>();
        if (all) {
            for (CoverageRun run : tracker.snapshot().values()) {
                removed.add(run.coverageId);
            }
            sm.removeAllSessions();
        } else {
            ICoverageSession active = sm.getActiveSession();
            if (active == null) {
                return error("coverage-no-active-session",
                        "No active coverage session");
            }
            String activeId = tracker.activeCoverageId();
            if (activeId != null) {
                removed.add(activeId);
            }
            sm.removeSession(active);
        }
        var obj = new JsonObject();
        obj.addProperty("ok", true);
        JsonArray arr = new JsonArray();
        for (String id : removed) {
            arr.add(id);
        }
        obj.add("removedCoverageIds", arr);
        return obj.toString();
    }

    // -- helpers --

    private boolean inScope(CoverageRun run, ProjectScope scope) {
        if (run.kind == CoverageRun.Kind.LIVE && run.launch != null) {
            return scope.containsLaunch(run.launch);
        }
        return scope.containsAnyOfRoots(run.coverageScope);
    }

    private static JsonObject runEntry(CoverageRun run) {
        var obj = new JsonObject();
        obj.addProperty("coverageId", run.coverageId);
        obj.addProperty("coverageSessionKind", run.kind.wireName());
        addNullableString(obj, "configId", run.configId);
        addNullableString(obj, "configType", run.configType);
        addNullableString(obj, "configTypeId", run.configTypeId);

        addNullableString(obj, "launchId",
                run.launch != null && run.kind == CoverageRun.Kind.LIVE
                        ? buildLaunchId(run) : null);

        obj.addProperty("description", run.description != null
                ? run.description : "");

        JsonArray scopeArr = new JsonArray();
        for (IPackageFragmentRoot root : run.coverageScope) {
            scopeArr.add(root.getHandleIdentifier());
        }
        obj.add("coverageScope", scopeArr);

        obj.addProperty("dumpCount", run.dumpCount());

        if (run.kind == CoverageRun.Kind.LIVE) {
            obj.addProperty("terminated",
                    run.launch != null
                            ? run.launch.isTerminated() : true);
        } else {
            obj.addProperty("terminated", true);
        }
        obj.addProperty("dataReceived", run.dataReceived);
        obj.addProperty("analysisLoading", run.analysisLoading);
        obj.addProperty("analysisReady", run.analysisReady);

        addNullableLong(obj, "launchTimestamp", run.launchTimestamp);
        addNullableLong(obj, "terminatedAt", run.terminatedAt);

        if (run.kind == CoverageRun.Kind.MERGED
                && !run.consumedCoverageIds.isEmpty()) {
            JsonArray consumed = new JsonArray();
            for (String id : run.consumedCoverageIds) {
                consumed.add(id);
            }
            obj.add("consumedCoverageIds", consumed);
        }

        return obj;
    }

    private static String buildLaunchId(CoverageRun run) {
        return io.github.kaluchi.jdtbridge.LaunchAttrs
                .launchIdOf(run.configId, run.launch);
    }

    private static JsonArray sessionInfosJson(
            List<SessionInfo> infos) {
        var arr = new JsonArray();
        for (SessionInfo info : infos) {
            var obj = new JsonObject();
            obj.addProperty("jacocoSessionId", info.getId());
            obj.addProperty("agentStartTimestamp",
                    info.getStartTimeStamp());
            obj.addProperty("dumpTimestamp",
                    info.getDumpTimeStamp());
            arr.add(obj);
        }
        return arr;
    }

    /** Find the {@code coverageId} the tracker assigned to a
     *  given session. Used after {@code mergeSessions} to surface
     *  the bridge's id in the response. */
    private String findCoverageIdFor(ICoverageSession session) {
        for (CoverageRun run : tracker.snapshot().values()) {
            if (run.sessions.contains(session)) {
                return run.coverageId;
            }
        }
        return null;
    }

    /** Package-private for unit tests of the colon-in-configId
     *  edge cases. */
    static Integer parseDumpIndex(String coverageId) {
        if (coverageId == null) return null;
        int colon = coverageId.lastIndexOf(':');
        if (colon < 0) return null;
        String tail = coverageId.substring(colon + 1);
        if (!tail.matches("\\d+")) return null;
        // Bare configId:launchTimestamp also matches digits — only
        // treat as dump index when the prefix already contains a
        // colon (i.e. there are at least two colons in the id).
        String prefix = coverageId.substring(0, colon);
        if (prefix.indexOf(':') < 0) {
            return null;
        }
        // Configurations with ':' in the name produce three-or-more
        // colon ids whose tail is the launchTimestamp (13-digit
        // millis), not a dump index. Parse via Long with explicit
        // range guard so a timestamp doesn't overflow Integer and
        // throw NumberFormatException — and is rejected outright as
        // not a real dump index. A real dump count is bounded by
        // the number of requestDump calls in one launch (single /
        // double digits in practice; cap at MAX_INT to be safe).
        long parsed;
        try {
            parsed = Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return null;
        }
        if (parsed < 1 || parsed > Integer.MAX_VALUE) {
            return null;
        }
        return (int) parsed;
    }

}
