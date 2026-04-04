package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import java.util.ArrayList;
import java.util.Map;

/**
 * Handles Maven-related operations via m2e core API.
 */
@SuppressWarnings("restriction")
class MavenHandler {


    /**
     * Maven Update Project — equivalent to Alt+F5 in Eclipse.
     * Uses m2e core internal API (ProjectConfigurationManager)
     * directly — same logic as UpdateMavenProjectJob but without
     * UI bundle dependency.
     *
     * Params:
     *   project=<name>   specific project (default: all maven)
     *   offline          offline mode
     *   force            force update of snapshots/releases
     *   no-config        skip update project config from pom.xml
     *   no-clean         skip clean projects
     *   no-refresh       skip refresh workspace resources
     *   wait             wait for auto-build, count errors
     */
    String handleUpdate(Map<String, String> params)
            throws Exception {
        String projectName = params.get("project");
        boolean offline = params.containsKey("offline");
        boolean force = params.containsKey("force");
        boolean updateConfig =
                !params.containsKey("no-config");
        boolean clean = !params.containsKey("no-clean");
        boolean refresh = !params.containsKey("no-refresh");

        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();

        var projects = new ArrayList<IProject>();
        if (projectName != null && !projectName.isBlank()) {
            IProject project = root.getProject(projectName);
            if (!project.exists()) {
                return HttpServer.jsonError(
                        "Project not found: " + projectName);
            }
            if (!org.eclipse.m2e.core.MavenPlugin
                    .isMavenProject(project)) {
                return HttpServer.jsonError(
                        "Not a Maven project: "
                        + projectName);
            }
            projects.add(project);
        } else {
            for (IProject p : root.getProjects()) {
                if (p.isOpen()
                        && org.eclipse.m2e.core.MavenPlugin
                                .isMavenProject(p)) {
                    projects.add(p);
                }
            }
        }

        if (projects.isEmpty()) {
            return HttpServer.jsonError(
                    "No Maven projects found");
        }

        boolean wait = params.containsKey("wait");

        var configManager =
                (org.eclipse.m2e.core.internal.project
                        .ProjectConfigurationManager)
                org.eclipse.m2e.core.MavenPlugin
                        .getProjectConfigurationManager();
        var request =
                new org.eclipse.m2e.core.project
                        .MavenUpdateRequest(
                                projects, offline, force);
        var updateErrors = configManager
                .updateProjectConfiguration(
                        request, updateConfig, clean,
                        refresh, null);

        int errorCount = -1;
        if (wait) {
            JdtUtils.joinAutoBuild();
            errorCount = 0;
            for (IProject p : projects) {
                errorCount += JdtUtils.countErrors(p);
            }
        }

        var result = new JsonObject();
        result.addProperty("updated", projects.size());
        var names = new JsonArray();
        for (IProject p : projects) {
            names.add(p.getName());
        }
        result.add("projects", names);
        // Filter to actual errors (not OK statuses)
        boolean ok = true;
        var sb = new StringBuilder();
        if (updateErrors != null) {
            updateErrors.forEach((k, v) -> {
                if (!v.isOK()) {
                    sb.append(k).append(": ")
                            .append(v.getMessage())
                            .append("\n");
                }
            });
            ok = sb.isEmpty();
        }
        result.addProperty("ok", ok);
        if (errorCount >= 0) {
            result.addProperty("errors", errorCount);
        }
        if (!ok) {
            result.addProperty("message",
                    sb.toString().strip());
        }
        return result.toString();
    }
}
