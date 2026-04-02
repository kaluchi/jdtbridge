package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;

import java.util.Map;
import java.util.jar.Manifest;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.osgi.framework.Version;

/**
 * Handler for /test endpoint: run JUnit tests via Eclipse's
 * built-in runner.
 */
class TestHandler {

    TestHandler() {
    }

    private static final String JUNIT_LAUNCH_TYPE =
            "org.eclipse.jdt.junit.launchconfig";
    private static final String PDE_JUNIT_LAUNCH_TYPE =
            "org.eclipse.pde.ui.JunitLaunchConfig";
    private static final String PDE_PLUGIN_NATURE =
            "org.eclipse.pde.PluginNature";
    private static final String PDE_RUN_IN_UI_THREAD =
            "run_in_ui_thread";
    private static final String PDE_APPLICATION =
            "application";
    private static final String PDE_CORE_TEST_APP =
            "org.eclipse.pde.junit.runtime"
                    + ".coretestapplication";
    private static final String PDE_USE_DEFAULT = "default";
    private static final String PDE_AUTOMATIC_ADD =
            "automaticAdd";
    private static final String PDE_DEFAULT_AUTO_START =
            "default_auto_start";
    private static final String PDE_DEFAULT_START_LEVEL =
            "default_start_level";
    private static final String PDE_INCLUDE_OPTIONAL =
            "includeOptional";
    private static final String ATTR_TEST_KIND =
            "org.eclipse.jdt.junit.TEST_KIND";
    private static final String ATTR_TEST_NAME =
            "org.eclipse.jdt.junit.TESTNAME";
    private static final String ATTR_TEST_CONTAINER =
            "org.eclipse.jdt.junit.CONTAINER";
    private static final String JUNIT6_KIND =
            "org.eclipse.jdt.junit.loader.junit6";
    private static final String JUNIT5_KIND =
            "org.eclipse.jdt.junit.loader.junit5";
    private static final String JUNIT4_KIND =
            "org.eclipse.jdt.junit.loader.junit4";
    private static final String JUNIT_PLATFORM_COMMONS_PREFIX =
            "junit-platform-commons";
    private static final String JUNIT_PLATFORM_SUITE_API_PREFIX =
            "junit-platform-suite-api";
    private static final String JUNIT_PLATFORM_TESTABLE =
            "org.junit.platform.commons.annotation.Testable";
    private static final String JUNIT_PLATFORM_SUITE =
            "org.junit.platform.suite.api.Suite";
    private static final String JAR_EXTENSION = ".jar";
    private static final String SPECIFICATION_VERSION =
            "Specification-Version";

    /** Prepared launch — shared between run modes. */
    private record PreparedLaunch(
            String configName,
            ILaunchConfiguration config,
            boolean reused,
            String project,
            String runner) {}

    /**
     * Common launch preparation: refresh, create config,
     * configure, resolve metadata. Returns null on error
     * (error JSON written to errorOut[0]).
     */
    private PreparedLaunch prepareLaunch(
            Map<String, String> params, String[] errorOut)
            throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");
        String projectName = params.get("project");
        String packageName = params.get("package");

        boolean noRefresh = params.containsKey("no-refresh");
        if (!noRefresh) {
            refreshProject(fqn, projectName);
        }

        ILaunchManager manager =
                DebugPlugin.getDefault().getLaunchManager();
        String launchTypeId = resolveJUnitLaunchType(
                manager, fqn, projectName);
        ILaunchConfigurationType launchType =
                manager.getLaunchConfigurationType(launchTypeId);
        if (launchType == null) {
            errorOut[0] = HttpServer.jsonError(
                    "JUnit launch type not available: "
                            + launchTypeId);
            return null;
        }

        // Stable name — no timestamp. Matches Eclipse GUI convention.
        String configName = launchPrefix(
                fqn, packageName, projectName);
        ILaunchConfigurationWorkingCopy wc =
                launchType.newInstance(null,
                        manager.generateLaunchConfigurationName(
                                configName));

        if (PDE_JUNIT_LAUNCH_TYPE.equals(launchTypeId)) {
            // Headless: no workbench, no UI thread
            wc.setAttribute(PDE_RUN_IN_UI_THREAD, false);
            wc.setAttribute(PDE_APPLICATION, PDE_CORE_TEST_APP);
            // Target platform bundles only — do not add workspace
            // plugins automatically to avoid validation errors from
            // non-OSGi projects (e.g. javax.persistence missing)
            wc.setAttribute(PDE_USE_DEFAULT, true);
            wc.setAttribute(PDE_AUTOMATIC_ADD, false);
            wc.setAttribute(PDE_DEFAULT_AUTO_START, true);
            wc.setAttribute(PDE_DEFAULT_START_LEVEL, 4);
            wc.setAttribute(PDE_INCLUDE_OPTIONAL, false);
            // Fresh temp workspace per run — clearws ensures
            // clean state regardless of location path
            wc.setAttribute("location",
                    System.getProperty("java.io.tmpdir")
                            + "/jdtbridge-test-ws");
            wc.setAttribute("askclear", false);
            wc.setAttribute("clearws", true);
        }

        String configError = configureLaunch(
                wc, fqn, methodName, projectName, packageName);
        if (configError != null) {
            errorOut[0] = configError;
            return null;
        }

        // Reuse existing config if one matches by attributes.
        // Same algorithm as Eclipse JUnitLaunchShortcut.
        boolean reused = false;
        ILaunchConfiguration config;
        ILaunchConfiguration existing =
                findExistingConfig(wc, manager);
        if (existing != null) {
            config = existing;
            configName = existing.getName();
            reused = true;
        } else {
            config = wc.doSave();
            configName = config.getName();
        }

        // Resolve metadata
        String resolvedProject = null;
        String testKind = null;
        if (fqn != null && !fqn.isBlank()) {
            IType type = JdtUtils.findType(fqn);
            if (type != null) {
                if (projectName != null
                        && !projectName.isBlank()) {
                    resolvedProject = projectName;
                    var model = JavaCore.create(
                            ResourcesPlugin.getWorkspace()
                                    .getRoot());
                    IJavaProject jp =
                            model.getJavaProject(projectName);
                    testKind = detectTestKind(jp);
                } else {
                    resolvedProject =
                            type.getJavaProject()
                                    .getElementName();
                    testKind = detectTestKind(type);
                }
            }
        } else if (projectName != null
                && !projectName.isBlank()) {
            resolvedProject = projectName;
            var model = JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot());
            IJavaProject jp = model.getJavaProject(projectName);
            if (jp != null && jp.exists()) {
                testKind = detectTestKind(jp);
            }
        }

        return new PreparedLaunch(configName, config, reused,
                resolvedProject, formatRunner(testKind));
    }

    /**
     * Non-blocking test launch. Returns immediately with session
     * info. Progress tracked by {@link TestSessionTracker}.
     */
    String handleTestRun(Map<String, String> params)
            throws Exception {
        String[] errorOut = { null };
        PreparedLaunch pl = prepareLaunch(params, errorOut);
        if (pl == null) return errorOut[0];

        ILaunch launch = pl.config().launch(
                ILaunchManager.RUN_MODE,
                new NullProgressMonitor(), true);

        String configId = pl.configName();
        String pid = null;
        var processes = launch.getProcesses();
        if (processes.length > 0) {
            pid = processes[0].getAttribute(
                    org.eclipse.debug.core.model.IProcess
                            .ATTR_PROCESS_ID);
        }

        String launchTimestamp = launch.getAttribute(
                DebugPlugin.ATTR_LAUNCH_TIMESTAMP);

        String launchId = pid != null
                ? configId + ":" + pid : configId;
        String testRunId = configId + ":"
                + launchTimestamp;

        var response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("configId", configId);
        response.addProperty("launchId", launchId);
        response.addProperty("testRunId", testRunId);
        response.addProperty("reused", pl.reused());
        response.addProperty("project", pl.project());
        response.addProperty("runner", pl.runner());
        if (pid != null)
            response.addProperty("pid", pid);

        return response.toString();
    }

    private String formatRunner(String testKind) {
        if (testKind == null) return null;
        if (JUNIT6_KIND.equals(testKind)) return "JUnit 6";
        if (JUNIT5_KIND.equals(testKind)) return "JUnit 5";
        if (JUNIT4_KIND.equals(testKind)) return "JUnit 4";
        return "JUnit";
    }

    // ---- Launch configuration ----

    private String configureLaunch(
            ILaunchConfigurationWorkingCopy wc,
            String fqn, String methodName,
            String projectName, String packageName)
            throws Exception {
        if (fqn != null && !fqn.isBlank()) {
            IType type = JdtUtils.findType(fqn);
            if (type == null) {
                return HttpServer.jsonError("Type not found: " + fqn);
            }

            // Use explicit project if provided, otherwise
            // fall back to the project that owns the type.
            // This is needed when the test class lives in
            // project A but must run from project B's classpath
            // (e.g. test in Grinbel, classpath from Build).
            String effectiveProject = projectName;
            if (effectiveProject == null
                    || effectiveProject.isBlank()) {
                effectiveProject =
                        type.getJavaProject().getElementName();
            }

            IJavaProject jp = JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot())
                    .getJavaProject(effectiveProject);
            if (jp == null || !jp.exists()) {
                return HttpServer.jsonError(
                        "Project not found: "
                                + effectiveProject);
            }
            wc.setAttribute(
                    IJavaLaunchConfigurationConstants
                            .ATTR_PROJECT_NAME,
                    effectiveProject);
            wc.setAttribute(
                    IJavaLaunchConfigurationConstants
                            .ATTR_MAIN_TYPE_NAME,
                    fqn);
            wc.setAttribute(ATTR_TEST_KIND,
                    detectTestKind(jp));

            if (methodName != null && !methodName.isBlank()) {
                wc.setAttribute(ATTR_TEST_NAME, methodName);
            }
        } else if (projectName != null && !projectName.isBlank()) {
            var model = JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot());
            IJavaProject jp = model.getJavaProject(projectName);
            if (jp == null || !jp.exists()) {
                // Check if project exists but isn't Java
                var project = ResourcesPlugin.getWorkspace()
                        .getRoot().getProject(projectName);
                if (project != null && project.exists()) {
                    return HttpServer.jsonError(
                            "Not a Java project: "
                                    + projectName);
                }
                return HttpServer.jsonError(
                        "Project not found: " + projectName);
            }

            wc.setAttribute(
                    IJavaLaunchConfigurationConstants
                            .ATTR_PROJECT_NAME,
                    projectName);
            wc.setAttribute(ATTR_TEST_KIND, detectTestKind(jp));

            if (packageName != null && !packageName.isBlank()) {
                IPackageFragment pkg =
                        findPackage(jp, packageName);
                if (pkg == null) {
                    return HttpServer.jsonError(
                            "Package not found: " + packageName);
                }
                wc.setAttribute(ATTR_TEST_CONTAINER,
                        pkg.getHandleIdentifier());
            } else {
                wc.setAttribute(ATTR_TEST_CONTAINER,
                        jp.getHandleIdentifier());
            }
        } else {
            return HttpServer.jsonError(
                    "Missing 'class' or 'project' parameter");
        }
        return null;
    }

    private void refreshProject(String fqn, String projectName)
            throws Exception {
        IJavaProject refreshProject = null;
        if (fqn != null && !fqn.isBlank()) {
            IType t = JdtUtils.findType(fqn);
            if (t != null) refreshProject = t.getJavaProject();
        } else if (projectName != null && !projectName.isBlank()) {
            var model = JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot());
            refreshProject = model.getJavaProject(projectName);
        }
        if (refreshProject != null && refreshProject.exists()) {
            refreshProject.getProject().refreshLocal(
                    org.eclipse.core.resources.IResource
                            .DEPTH_INFINITE,
                    new NullProgressMonitor());
            JdtUtils.joinAutoBuild();
        }
    }

    // ---- Helpers ----

    /**
     * If the target project has PDE PluginNature and the PDE
     * JUnit launch type is installed, use it — this gives the
     * test a full OSGi runtime with workspace. Otherwise fall
     * back to plain JUnit.
     */
    private String resolveJUnitLaunchType(
            ILaunchManager manager, String fqn,
            String projectName) {
        try {
            org.eclipse.core.resources.IProject project = null;
            if (fqn != null && !fqn.isBlank()) {
                IType type = JdtUtils.findType(fqn);
                if (type != null) {
                    project = type.getJavaProject().getProject();
                }
            } else if (projectName != null
                    && !projectName.isBlank()) {
                project = ResourcesPlugin.getWorkspace()
                        .getRoot().getProject(projectName);
            }
            if (project != null && project.exists()
                    && project.hasNature(PDE_PLUGIN_NATURE)) {
                if (manager.getLaunchConfigurationType(
                        PDE_JUNIT_LAUNCH_TYPE) != null) {
                    return PDE_JUNIT_LAUNCH_TYPE;
                }
            }
        } catch (Exception e) {
            Log.warn("Failed to detect PDE nature", e);
        }
        return JUNIT_LAUNCH_TYPE;
    }

    String detectTestKind(IType type) {
        return detectTestKind(type.getJavaProject());
    }

    String detectTestKind(IJavaProject project) {
        try {
            if (hasJUnitJupiterMajor(project, 6,
                    JUnitCore.JUNIT3_CONTAINER_PATH,
                    JUnitCore.JUNIT4_CONTAINER_PATH,
                    JUnitCore.JUNIT5_CONTAINER_PATH)) {
                return JUNIT6_KIND;
            }
            // JUnit 5 platform bundles use version 1.x (range [1.0,2.0))
            if (hasJUnitJupiterMajor(project, 1,
                    JUnitCore.JUNIT3_CONTAINER_PATH,
                    JUnitCore.JUNIT4_CONTAINER_PATH,
                    JUnitCore.JUNIT6_CONTAINER_PATH)) {
                return JUNIT5_KIND;
            }
            // Fallback: Jupiter API on classpath but platform
            // marker not resolvable (common with M2Eclipse)
            if (project.findType(
                    "org.junit.jupiter.api.Test") != null) {
                return JUNIT5_KIND;
            }
        } catch (JavaModelException e) {
            Log.warn("detectTestKind failed", e);
        }
        return JUNIT4_KIND;
    }

    private boolean hasJUnitJupiterMajor(IJavaProject project,
            int expectedMajor, IPath... excludedPaths)
            throws JavaModelException {
        if (project == null) return false;

        IType marker = findJUnitPlatformMarker(project);
        if (marker == null) return false;

        Integer major = resolveJUnitMajor(marker);
        if (major != null) {
            return major == expectedMajor;
        }

        IPath classpath = getRawClasspathPath(marker);
        return classpath != null
                && !matchesAny(classpath, excludedPaths);
    }

    private IType findJUnitPlatformMarker(IJavaProject project)
            throws JavaModelException {
        IType marker = project.findType(JUNIT_PLATFORM_TESTABLE);
        if (marker != null) return marker;
        return project.findType(JUNIT_PLATFORM_SUITE);
    }

    private Integer resolveJUnitMajor(IType marker) {
        IPath path = marker.getPath();
        if (path == null) return null;

        String jarName = path.lastSegment();
        if (jarName == null || !jarName.endsWith(JAR_EXTENSION)) {
            return null;
        }

        String prefix = marker.getFullyQualifiedName('.')
                .equals(JUNIT_PLATFORM_TESTABLE)
                        ? JUNIT_PLATFORM_COMMONS_PREFIX
                        : JUNIT_PLATFORM_SUITE_API_PREFIX;

        String version = extractVersion(jarName, prefix);
        if (version == null) {
            version = readManifestVersion(marker);
        }
        if (version == null) return null;

        try {
            return Version.parseVersion(version).getMajor();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String readManifestVersion(IType marker) {
        IJavaElement root =
                marker.getAncestor(
                        IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (root == null) return null;

        try {
            Object manifestObj = root.getClass()
                    .getMethod("getManifest")
                    .invoke(root);
            if (!(manifestObj instanceof Manifest manifest)) {
                return null;
            }
            return manifest.getMainAttributes()
                    .getValue(SPECIFICATION_VERSION);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private String extractVersion(String jarName, String prefix) {
        String dashPrefix = prefix + "-";
        String underscorePrefix = prefix + "_";
        if (jarName.startsWith(dashPrefix)) {
            return jarName.substring(dashPrefix.length(),
                    jarName.length() - JAR_EXTENSION.length());
        }
        if (jarName.startsWith(underscorePrefix)) {
            return jarName.substring(underscorePrefix.length(),
                    jarName.length() - JAR_EXTENSION.length());
        }
        return null;
    }

    private IPath getRawClasspathPath(IType marker)
            throws JavaModelException {
        IJavaElement rootElement =
                marker.getAncestor(
                        IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (!(rootElement instanceof IPackageFragmentRoot root)) {
            return null;
        }

        IClasspathEntry entry = root.getRawClasspathEntry();
        return entry != null ? entry.getPath() : null;
    }

    private boolean matchesAny(IPath path, IPath... candidates) {
        for (IPath candidate : candidates) {
            if (candidate != null && candidate.equals(path)) {
                return true;
            }
        }
        return false;
    }

    private IPackageFragment findPackage(IJavaProject project,
            String packageName) throws JavaModelException {
        for (var root : project.getPackageFragmentRoots()) {
            if (root.getKind()
                    == org.eclipse.jdt.core.IPackageFragmentRoot
                            .K_SOURCE) {
                IPackageFragment pkg =
                        root.getPackageFragment(packageName);
                if (pkg != null && pkg.exists()) return pkg;
            }
        }
        return null;
    }

    /**
     * Find an existing launch configuration with the same key
     * attributes as the working copy. Port of Eclipse's
     * JUnitLaunchShortcut#findExistingLaunchConfigurations.
     */
    private static ILaunchConfiguration findExistingConfig(
            ILaunchConfigurationWorkingCopy wc,
            ILaunchManager manager) throws CoreException {
        String[] keys = {
            IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
            ATTR_TEST_CONTAINER,
            IJavaLaunchConfigurationConstants
                    .ATTR_MAIN_TYPE_NAME,
            ATTR_TEST_NAME,
        };
        for (ILaunchConfiguration config
                : manager.getLaunchConfigurations(wc.getType())) {
            if (hasSameAttributes(config, wc, keys))
                return config;
        }
        return null;
    }

    private static boolean hasSameAttributes(
            ILaunchConfiguration a, ILaunchConfiguration b,
            String[] keys) {
        try {
            for (String key : keys) {
                String va = a.getAttribute(key, "");
                String vb = b.getAttribute(key, "");
                if (!va.equals(vb)) return false;
            }
            return true;
        } catch (CoreException e) {
            return false;
        }
    }

    static String launchPrefix(String fqn, String packageName,
            String projectName) {
        if (fqn != null && !fqn.isBlank())
            return fqn.substring(fqn.lastIndexOf('.') + 1);
        if (packageName != null && !packageName.isBlank())
            return packageName;
        if (projectName != null && !projectName.isBlank())
            return projectName;
        return "test";
    }

    int parseTimeout(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
