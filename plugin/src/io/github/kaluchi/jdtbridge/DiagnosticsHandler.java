package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;

class DiagnosticsHandler {

    private static final String JDT_PROBLEM_MARKER =
            "org.eclipse.jdt.core.problem";

    String handleErrors(Map<String, String> params,
            ProjectScope projectScope) throws Exception {
        String filePath = params.get("file");
        String projectName = params.get("project");
        boolean includeWarnings =
                params.containsKey("warnings");
        boolean all = params.containsKey("all");

        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();

        IResource resource;
        if (filePath != null && !filePath.isBlank()) {
            resource = root.findMember(filePath);
            if (resource == null) {
                return HttpServer.jsonError(
                        "Resource not found: " + filePath);
            }
        } else if (projectName != null
                && !projectName.isBlank()) {
            IProject project = root.getProject(projectName);
            if (!project.exists()) {
                return HttpServer.jsonError(
                        "Project not found: " + projectName);
            }
            resource = project;
        } else {
            resource = root;
        }

        int depth = (resource instanceof IFile)
                ? IResource.DEPTH_ZERO
                : IResource.DEPTH_INFINITE;
        resource.refreshLocal(depth, null);
        JdtUtils.joinAutoBuild();

        String markerType = all ? IMarker.PROBLEM
                : JDT_PROBLEM_MARKER;
        IMarker[] markers = resource.findMarkers(
                markerType, true, IResource.DEPTH_INFINITE);

        int minSeverity = includeWarnings
                ? IMarker.SEVERITY_WARNING
                : IMarker.SEVERITY_ERROR;

        var arr = new JsonArray();
        Arrays.stream(markers)
                .filter(m -> m.getAttribute(
                        IMarker.SEVERITY, -1) >= minSeverity)
                .filter(m -> projectScope.containsProject(
                        m.getResource().getProject().getName()))
                .map(m -> toMarkerJson(m, all))
                .forEach(arr::add);
        return arr.toString();
    }

    String handleBuild(Map<String, String> params)
            throws Exception {
        String projectName = params.get("project");
        boolean clean = params.containsKey("clean");

        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();

        IResource scope;
        IProject buildProject = null;
        if (projectName != null && !projectName.isBlank()) {
            IProject project = root.getProject(projectName);
            if (!project.exists()) {
                return HttpServer.jsonError(
                        "Project not found: " + projectName);
            }
            scope = project;
            buildProject = project;
        } else {
            scope = root;
        }

        if (clean && buildProject == null) {
            return HttpServer.jsonError(
                    "clean requires a specific project"
                    + " (use 'project' param)");
        }

        scope.refreshLocal(IResource.DEPTH_INFINITE, null);
        JdtUtils.joinAutoBuild();

        if (clean) {
            buildProject.build(
                    IncrementalProjectBuilder.CLEAN_BUILD,
                    null);
            buildProject.build(
                    IncrementalProjectBuilder.FULL_BUILD,
                    null);
        } else if (buildProject != null) {
            buildProject.build(
                    IncrementalProjectBuilder
                            .INCREMENTAL_BUILD,
                    null);
        } else {
            ResourcesPlugin.getWorkspace().build(
                    IncrementalProjectBuilder
                            .INCREMENTAL_BUILD,
                    null);
        }

        IMarker[] markers = scope.findMarkers(
                JDT_PROBLEM_MARKER, true,
                IResource.DEPTH_INFINITE);
        int errorCount = 0;
        for (IMarker marker : markers) {
            if (marker.getAttribute(IMarker.SEVERITY, -1)
                    == IMarker.SEVERITY_ERROR) {
                errorCount++;
            }
        }

        var result = new JsonObject();
        result.addProperty("errors", errorCount);
        return result.toString();
    }

    /**
     * Lightweight refresh: notify Eclipse that files changed on disk.
     * No build wait, no markers — just refreshLocal.
     *
     * Scope (pick one or omit for workspace):
     *   file=<absolute-path>   single file (DEPTH_ZERO)
     *   project=<name>         entire project (DEPTH_INFINITE)
     *   (none)                 entire workspace (DEPTH_INFINITE)
     */
    String handleRefresh(Map<String, String> params)
            throws Exception {
        String filePath = params.get("file");
        String projectName = params.get("project");

        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();

        if (filePath != null && !filePath.isBlank()) {
            // Absolute path → workspace file
            var path = org.eclipse.core.runtime.Path
                    .fromOSString(filePath);
            IFile[] files = root.findFilesForLocationURI(
                    path.toFile().toURI());

            if (files.length == 0) {
                var result = new JsonObject();
                result.addProperty("refreshed", false);
                result.addProperty("reason",
                        "not in workspace");
                return result.toString();
            }

            for (IFile file : files) {
                file.refreshLocal(
                        IResource.DEPTH_ZERO, null);
            }

            var result = new JsonObject();
            result.addProperty("refreshed", true);
            result.addProperty("files", files.length);
            return result.toString();

        } else if (projectName != null
                && !projectName.isBlank()) {
            IProject project = root.getProject(projectName);
            if (!project.exists()) {
                return HttpServer.jsonError(
                        "Project not found: " + projectName);
            }
            project.refreshLocal(
                    IResource.DEPTH_INFINITE, null);

            var result = new JsonObject();
            result.addProperty("refreshed", true);
            result.addProperty("project", projectName);
            return result.toString();

        } else {
            // Workspace-wide
            root.refreshLocal(
                    IResource.DEPTH_INFINITE, null);

            var result = new JsonObject();
            result.addProperty("refreshed", true);
            result.addProperty("scope", "workspace");
            return result.toString();
        }
    }

    private JsonObject toMarkerJson(IMarker marker,
            boolean includeSource) {
        int severity = marker.getAttribute(
                IMarker.SEVERITY, -1);
        String sevStr = switch (severity) {
            case IMarker.SEVERITY_ERROR -> "ERROR";
            case IMarker.SEVERITY_WARNING -> "WARNING";
            default -> "INFO";
        };
        var entry = new JsonObject();
        var loc = marker.getResource().getLocation();
        entry.addProperty("file", loc != null
                ? loc.toOSString()
                : marker.getResource()
                        .getFullPath().toString());
        entry.addProperty("line", marker.getAttribute(
                IMarker.LINE_NUMBER, -1));
        entry.addProperty("severity", sevStr);
        entry.addProperty("message", marker.getAttribute(
                IMarker.MESSAGE, ""));
        if (includeSource) {
            try {
                entry.addProperty("source",
                        shortMarkerType(marker.getType()));
            } catch (Exception ignored) { }
        }
        return entry;
    }

    String shortMarkerType(String type) {
        if (type == null) return "unknown";
        if (type.contains("jdt")) return "jdt";
        if (type.contains("eclipsecs")
                || type.contains("checkstyle"))
            return "checkstyle";
        if (type.contains("m2e") || type.contains("maven"))
            return "maven";
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }
}
