package io.github.kaluchi.jdtbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.launcher.JUnitLaunchConfigurationDelegate;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;
import org.eclipse.jdt.junit.model.ITestElementContainer;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.osgi.framework.Version;

/**
 * Handler for /test endpoint: run JUnit tests via Eclipse's
 * built-in runner.
 */
class TestHandler {

    private static final String JUNIT_LAUNCH_TYPE =
            "org.eclipse.jdt.junit.launchconfig";
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

    String handleTest(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");
        String projectName = params.get("project");
        String packageName = params.get("package");
        int timeoutSec = parseTimeout(params.get("timeout"), 120);

        boolean noRefresh = params.containsKey("no-refresh");

        // Refresh project from disk before running (default: on)
        if (!noRefresh) {
            refreshProject(fqn, projectName);
        }

        ILaunchManager manager =
                DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType launchType =
                manager.getLaunchConfigurationType(JUNIT_LAUNCH_TYPE);
        if (launchType == null) {
            return Json.error(
                    "JUnit launch type not available");
        }

        String configName = "jdtbridge-test-"
                + System.currentTimeMillis();
        ILaunchConfigurationWorkingCopy wc =
                launchType.newInstance(null, configName);

        // Determine what to run
        String configError = configureLaunch(
                wc, fqn, methodName, projectName, packageName);
        if (configError != null) {
            return configError;
        }

        // Register listener before launch
        CountDownLatch latch = new CountDownLatch(1);
        ResultCollector collector =
                new ResultCollector(configName, latch);
        JUnitCore.addTestRunListener(collector);

        ILaunch launch =
                new Launch(wc, ILaunchManager.RUN_MODE, null);
        manager.addLaunch(launch);

        try {
            JUnitLaunchConfigurationDelegate delegate =
                    new JUnitLaunchConfigurationDelegate();
            delegate.launch(wc, ILaunchManager.RUN_MODE,
                    launch, new NullProgressMonitor());

            if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
                return Json.error("Test run timed out after "
                        + timeoutSec + "s");
            }

            return collector.toJson();
        } finally {
            JUnitCore.removeTestRunListener(collector);
            if (!launch.isTerminated()) {
                try {
                    launch.terminate();
                } catch (Exception e) {
                    Log.warn("Failed to terminate launch", e);
                }
            }
        }
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
                return Json.error("Type not found: " + fqn);
            }

            IJavaProject jp = type.getJavaProject();
            wc.setAttribute(
                    IJavaLaunchConfigurationConstants
                            .ATTR_PROJECT_NAME,
                    jp.getElementName());
            wc.setAttribute(
                    IJavaLaunchConfigurationConstants
                            .ATTR_MAIN_TYPE_NAME,
                    fqn);
            wc.setAttribute(ATTR_TEST_KIND,
                    detectTestKind(type));

            if (methodName != null && !methodName.isBlank()) {
                wc.setAttribute(ATTR_TEST_NAME, methodName);
            }
        } else if (projectName != null && !projectName.isBlank()) {
            var model = JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot());
            IJavaProject jp = model.getJavaProject(projectName);
            if (jp == null || !jp.exists()) {
                return Json.error(
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
                    return Json.error(
                            "Package not found: " + packageName);
                }
                wc.setAttribute(ATTR_TEST_CONTAINER,
                        pkg.getHandleIdentifier());
            } else {
                wc.setAttribute(ATTR_TEST_CONTAINER,
                        jp.getHandleIdentifier());
            }
        } else {
            return Json.error(
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

    // ---- Result collector ----

    private static class ResultCollector extends TestRunListener {
        private final String configName;
        private final CountDownLatch latch;
        private final List<TestResult> results = new ArrayList<>();
        private double totalTime;
        private int total;
        private int passed;
        private int failed;
        private int errors;
        private int ignored;

        ResultCollector(String configName, CountDownLatch latch) {
            this.configName = configName;
            this.latch = latch;
        }

        @Override
        public void sessionFinished(ITestRunSession session) {
            if (!session.getTestRunName().equals(configName)) return;
            totalTime = session.getElapsedTimeInSeconds();
            collectResults(session);
            latch.countDown();
        }

        private void collectResults(ITestElementContainer container) {
            try {
                for (ITestElement child : container.getChildren()) {
                    if (child instanceof ITestCaseElement tc) {
                        recordTestCase(tc);
                    }
                    if (child instanceof ITestElementContainer c) {
                        collectResults(c);
                    }
                }
            } catch (Exception e) {
                Log.warn("collectResults failed", e);
            }
        }

        private void recordTestCase(ITestCaseElement tc) {
            var result = tc.getTestResult(false);
            total++;
            TestResult tr = new TestResult();
            tr.className = tc.getTestClassName();
            tr.method = tc.getTestMethodName();

            if (result == ITestElement.Result.OK) {
                tr.status = "PASS";
                passed++;
            } else if (result == ITestElement.Result.FAILURE) {
                tr.status = "FAIL";
                failed++;
                FailureTrace ft = tc.getFailureTrace();
                if (ft != null) tr.trace = ft.getTrace();
            } else if (result == ITestElement.Result.ERROR) {
                tr.status = "ERROR";
                errors++;
                FailureTrace ft = tc.getFailureTrace();
                if (ft != null) tr.trace = ft.getTrace();
            } else if (result == ITestElement.Result.IGNORED) {
                tr.status = "IGNORED";
                ignored++;
            } else {
                tr.status = "UNKNOWN";
            }
            results.add(tr);
        }

        String toJson() {
            Json failures = Json.array();
            for (TestResult r : results) {
                if ("PASS".equals(r.status)
                        || "IGNORED".equals(r.status)) continue;
                Json f = Json.object()
                        .put("class", r.className)
                        .put("method", r.method)
                        .put("status", r.status);
                if (r.trace != null) {
                    f.put("trace", r.trace);
                }
                failures.add(f);
            }

            return Json.object()
                    .put("total", total)
                    .put("passed", passed)
                    .put("failed", failed)
                    .put("errors", errors)
                    .put("ignored", ignored)
                    .put("time", totalTime)
                    .put("failures", failures)
                    .toString();
        }
    }

    private static class TestResult {
        String className;
        String method;
        String status;
        String trace;
    }

    // ---- Helpers ----

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
            if (hasJUnitJupiterMajor(project, 5,
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

    int parseTimeout(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
