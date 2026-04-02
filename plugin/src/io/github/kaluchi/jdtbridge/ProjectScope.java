package io.github.kaluchi.jdtbridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;

/**
 * Project visibility scope for a request.
 * <p>
 * {@link #ALL} — default scope, no filtering (full workspace).
 * Filtered scope — only named projects are visible.
 * <p>
 * Domain-specific methods for each entity type:
 * {@link #containsProject(String)}, {@link #containsConfig(ILaunchConfiguration)},
 * {@link #containsLaunch(ILaunch)}.
 */
class ProjectScope {

    private static final String ATTR_PROJECT_NAME =
            "org.eclipse.jdt.launching.PROJECT_ATTR";
    private static final String ATTR_WORKING_DIR =
            "org.eclipse.jdt.launching.WORKING_DIRECTORY";

    /** Default scope — all projects visible, no filtering. */
    static final ProjectScope ALL = new ProjectScope(null);

    private final Set<String> projects;

    private ProjectScope(Set<String> projects) {
        this.projects = projects;
    }

    /** Create a filtered scope for the given project names. */
    static ProjectScope of(Set<String> projectNames) {
        if (projectNames == null || projectNames.isEmpty()) {
            return ALL;
        }
        return new ProjectScope(Set.copyOf(projectNames));
    }

    /**
     * All open, non-hidden workspace projects (unfiltered).
     * Used by {@link SessionScope} to resolve working directory
     * to project names before a scope is created.
     */
    static Stream<IProject> allOpenProjects() {
        return Arrays.stream(ResourcesPlugin.getWorkspace()
                        .getRoot().getProjects())
                .filter(IProject::isOpen)
                .filter(p -> !p.getName().startsWith("."));
    }

    /**
     * Stream of open workspace projects within this scope.
     * Hidden projects (name starts with ".") are excluded.
     */
    Stream<IProject> openProjects() {
        return allOpenProjects()
                .filter(p -> containsProject(p.getName()));
    }

    /** Check if a project name is within this scope. */
    boolean containsProject(String projectName) {
        return projects == null
                || projects.contains(projectName);
    }

    /**
     * Check if a launch configuration is in scope.
     * First checks PROJECT_ATTR, then falls back to
     * WORKING_DIRECTORY for configs without a project
     * (e.g. Maven builds, agent launches).
     */
    boolean containsConfig(ILaunchConfiguration config) {
        if (projects == null) return true;
        try {
            String project = config.getAttribute(
                    ATTR_PROJECT_NAME, (String) null);
            if (project != null) {
                return projects.contains(project);
            }
            if (isLaunchGroup(config)) {
                return launchGroupInScope(config);
            }
            return workingDirInScope(config);
        } catch (CoreException e) {
            return true;
        }
    }

    private boolean isLaunchGroup(ILaunchConfiguration config)
            throws CoreException {
        return "org.eclipse.debug.core.groups.GroupLaunchConfigurationType"
                .equals(config.getType().getIdentifier());
    }

    /**
     * Launch Group is in scope if any of its child configs
     * are in scope. Children referenced by configId in
     * attributes: org.eclipse.debug.core.launchGroup.N.name
     */
    private boolean launchGroupInScope(
            ILaunchConfiguration group) throws CoreException {
        var configsByName = Arrays.stream(DebugPlugin.getDefault()
                        .getLaunchManager()
                        .getLaunchConfigurations())
                .collect(java.util.stream.Collectors.toMap(
                        ILaunchConfiguration::getName,
                        c -> c, (a, b) -> a));
        for (int i = 0; ; i++) {
            String childConfigId = group.getAttribute(
                    "org.eclipse.debug.core.launchGroup."
                            + i + ".name",
                    (String) null);
            if (childConfigId == null) break;
            var child = configsByName.get(childConfigId);
            if (child != null && containsConfig(child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if config's WORKING_DIRECTORY overlaps with any
     * scoped project location. Match means either the working
     * dir contains a project, or a project contains the working dir.
     */
    private boolean workingDirInScope(
            ILaunchConfiguration config) {
        try {
            String rawWorkDir = config.getAttribute(
                    ATTR_WORKING_DIR, (String) null);
            if (rawWorkDir == null || rawWorkDir.isBlank()) {
                return true;
            }
            String resolved = VariablesPlugin.getDefault()
                    .getStringVariableManager()
                    .performStringSubstitution(rawWorkDir);
            Path configPath = normalize(Path.of(resolved));
            return openProjects()
                    .map(p -> p.getLocation())
                    .filter(loc -> loc != null)
                    .map(loc -> normalize(
                            Path.of(loc.toOSString())))
                    .anyMatch(projectPath ->
                            projectPath.startsWith(configPath)
                            || configPath.startsWith(projectPath));
        } catch (CoreException e) {
            return true;
        }
    }

    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** Check if a launch's configuration project is in scope. */
    boolean containsLaunch(ILaunch launch) {
        if (projects == null) return true;
        var config = launch.getLaunchConfiguration();
        return config == null || containsConfig(config);
    }

    /**
     * JDT search scope limited to projects in this scope.
     * For {@link #ALL}, returns workspace scope.
     */
    IJavaSearchScope searchScope() {
        if (projects == null) {
            return SearchEngine.createWorkspaceScope();
        }
        IJavaElement[] elements = openProjects()
                .map(JavaCore::create)
                .toArray(IJavaElement[]::new);
        return SearchEngine.createJavaSearchScope(elements);
    }
}
