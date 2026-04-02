package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.core.resources.IFolder;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for --project override in jdt test run.
 * Creates own test projects — does not touch TestFixture.
 */
public class ProjectOverrideTest {

    private static final String SOURCE_PROJECT = "override-source";
    private static final String LAUNCHER_PROJECT = "override-launcher";
    private static final TestHandler handler =
            new TestHandler();

    private static final String SIMPLE_TEST_SRC = """
            package test.override;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            public class SimpleTest {
                @Test
                public void alwaysPass() {
                    assertTrue(true);
                }
            }
            """;

    @BeforeAll
    static void setUp() throws Exception {
        var root = ResourcesPlugin.getWorkspace().getRoot();

        // Source project — has the test class
        createJavaProject(root, SOURCE_PROJECT, SIMPLE_TEST_SRC);

        // Launcher project — has JUnit on classpath
        // but no test classes of its own
        IProject lp = root.getProject(LAUNCHER_PROJECT);
        lp.create(null);
        lp.open(null);
        IProjectDescription desc = lp.getDescription();
        desc.setNatureIds(new String[]{JavaCore.NATURE_ID});
        lp.setDescription(desc, null);

        IJavaProject ljp = JavaCore.create(lp);
        IFolder src = lp.getFolder("src");
        src.create(true, true, null);
        var lcp = new java.util.ArrayList<IClasspathEntry>();
        lcp.add(JavaCore.newSourceEntry(src.getFullPath()));
        lcp.add(JavaCore.newContainerEntry(new Path(
                "org.eclipse.jdt.launching.JRE_CONTAINER")));
        addJUnitBundles(lcp);
        lcp.add(JavaCore.newProjectEntry(
                new Path("/" + SOURCE_PROJECT)));
        ljp.setRawClasspath(
                lcp.toArray(IClasspathEntry[]::new), null);

        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);
    }

    @AfterAll
    static void tearDown() throws Exception {
        // Terminate launches — they hold file locks on projects
        var mgr = DebugPlugin.getDefault().getLaunchManager();
        for (ILaunch launch : mgr.getLaunches()) {
            if (!launch.isTerminated()) {
                launch.terminate();
            }
        }

        var root = ResourcesPlugin.getWorkspace().getRoot();
        for (String name : new String[]{
                SOURCE_PROJECT, LAUNCHER_PROJECT}) {
            IProject p = root.getProject(name);
            if (p.exists()) p.delete(true, true, null);
        }
    }

    @Test
    void nonexistentProjectOverrideReturnsError()
            throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("class", "test.override.SimpleTest");
        params.put("project", "nonexistent-xyz");
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("error"),
                "Should error for bad project: " + json);
    }

    @Test
    void projectOverrideUsedInLaunch() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("class", "test.override.SimpleTest");
        params.put("project", LAUNCHER_PROJECT);
        params.put("no-refresh", "");
        String json = handler.handleTestRun(params);
        assertTrue(json.contains("\"ok\":true"),
                "Should launch: " + json);
        assertTrue(json.contains(
                "\"project\":\"" + LAUNCHER_PROJECT + "\""),
                "Should use launcher project: " + json);
    }

    // ---- helpers ----

    private static void createJavaProject(
            org.eclipse.core.resources.IWorkspaceRoot root,
            String name, String testSource) throws Exception {
        IProject project = root.getProject(name);
        if (project.exists()) project.delete(true, true, null);
        project.create(null);
        project.open(null);

        IProjectDescription desc = project.getDescription();
        desc.setNatureIds(new String[]{JavaCore.NATURE_ID});
        project.setDescription(desc, null);

        IJavaProject jp = JavaCore.create(project);
        IFolder src = project.getFolder("src");
        src.create(true, true, null);

        // Use bundle JARs for JUnit — JUNIT_CONTAINER doesn't
        // resolve in Tycho headless for dynamic projects
        var cp = new java.util.ArrayList<IClasspathEntry>();
        cp.add(JavaCore.newSourceEntry(src.getFullPath()));
        cp.add(JavaCore.newContainerEntry(new Path(
                "org.eclipse.jdt.launching.JRE_CONTAINER")));
        addJUnitBundles(cp);
        jp.setRawClasspath(
                cp.toArray(IClasspathEntry[]::new), null);

        IPackageFragmentRoot srcRoot =
                jp.getPackageFragmentRoot(src);
        IPackageFragment pkg =
                srcRoot.createPackageFragment(
                        "test.override", true, null);
        pkg.createCompilationUnit(
                "SimpleTest.java", testSource, true, null);

        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);
    }

    private static void addJUnitBundles(
            java.util.List<IClasspathEntry> cp) {
        for (String id : new String[]{
                "junit-jupiter-api",
                "junit-jupiter-engine",
                "junit-platform-launcher",
                "junit-platform-engine",
                "junit-platform-commons",
                "org.opentest4j",
                "org.eclipse.jdt.junit5.runtime",
                "org.eclipse.jdt.junit.runtime"}) {
            addBundleEntry(cp, id);
        }
    }

    private static void addBundleEntry(
            java.util.List<IClasspathEntry> cp,
            String bundleId) {
        Bundle bundle = Platform.getBundle(bundleId);
        if (bundle == null) return;
        java.io.File file = FileLocator
                .getBundleFileLocation(bundle).orElse(null);
        if (file == null) return;
        cp.add(JavaCore.newLibraryEntry(
                new Path(file.getAbsolutePath()),
                null, null));
    }
}
