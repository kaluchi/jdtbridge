package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProjectScope}.
 */
public class ProjectScopeTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    @Nested
    class AllScope {

        @Test
        void containsAnyProject() {
            assertTrue(ProjectScope.ALL.containsProject("foo"));
            assertTrue(ProjectScope.ALL.containsProject("bar"));
            assertTrue(ProjectScope.ALL.containsProject(""));
        }
    }

    @Nested
    class FilteredScope {

        private final ProjectScope scope =
                ProjectScope.of(Set.of("alpha", "beta"));

        @Test
        void containsIncludedProjects() {
            assertTrue(scope.containsProject("alpha"));
            assertTrue(scope.containsProject("beta"));
        }

        @Test
        void excludesUnknownProjects() {
            assertFalse(scope.containsProject("gamma"));
            assertFalse(scope.containsProject(""));
        }
    }

    @Nested
    class EmptySetReturnsAll {

        @Test
        void emptySetMeansNoFiltering() {
            ProjectScope scope = ProjectScope.of(Set.of());
            assertTrue(scope.containsProject("anything"));
        }

        @Test
        void nullSetMeansNoFiltering() {
            ProjectScope scope = ProjectScope.of(null);
            assertTrue(scope.containsProject("anything"));
        }
    }

    @Nested
    class ImmutableCopy {

        @Test
        void scopeIsNotAffectedByOriginalSetChanges() {
            var mutable = new java.util.HashSet<>(
                    Set.of("a", "b"));
            ProjectScope scope = ProjectScope.of(mutable);
            mutable.add("c");
            assertFalse(scope.containsProject("c"));
        }
    }

    @Nested
    class ContainsConfig {

        private ILaunchConfigurationWorkingCopy createConfig(
                String name) throws CoreException {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType type = mgr
                    .getLaunchConfigurationType(
                            "org.eclipse.jdt.junit.launchconfig");
            return type.newInstance(null, name);
        }

        @Test
        void allScopeAcceptsAnyConfig() throws Exception {
            var config = createConfig("test-any");
            config.setAttribute(
                    "org.eclipse.jdt.launching.PROJECT_ATTR",
                    "unknown-project");
            assertTrue(ProjectScope.ALL.containsConfig(config));
        }

        @Test
        void filteredScopeAcceptsMatchingProject()
                throws Exception {
            var scope = ProjectScope.of(Set.of("my-project"));
            var config = createConfig("test-match");
            config.setAttribute(
                    "org.eclipse.jdt.launching.PROJECT_ATTR",
                    "my-project");
            assertTrue(scope.containsConfig(config));
        }

        @Test
        void filteredScopeRejectsNonMatchingProject()
                throws Exception {
            var scope = ProjectScope.of(Set.of("my-project"));
            var config = createConfig("test-reject");
            config.setAttribute(
                    "org.eclipse.jdt.launching.PROJECT_ATTR",
                    "other-project");
            assertFalse(scope.containsConfig(config));
        }

        @Test
        void configWithoutProjectPassesViaWorkingDir()
                throws Exception {
            // localJavaApplication is part of org.eclipse.jdt.launching
            // (already a plugin dependency). With no PROJECT_ATTR and
            // no WORKING_DIR, containsConfig must fall through to the
            // permissive return-true branch.
            var scope = ProjectScope.of(Set.of("my-project"));
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType javaType = mgr
                    .getLaunchConfigurationType(
                            "org.eclipse.jdt.launching."
                                    + "localJavaApplication");
            org.junit.jupiter.api.Assertions.assertNotNull(
                    javaType);
            var config = javaType.newInstance(
                    null, "test-no-project");
            assertTrue(scope.containsConfig(config));
        }

        @Test
        void launchGroupTypeRoutesThroughGroupBranch()
                throws Exception {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType groupType = mgr
                    .getLaunchConfigurationType(
                            "org.eclipse.debug.core.groups."
                                    + "GroupLaunchConfigurationType");
            org.junit.jupiter.api.Assertions.assertNotNull(
                    groupType,
                    "Group launch type ships with debug.core "
                            + "and must be present");
            var scope = ProjectScope.of(Set.of("anything"));
            var wc = groupType.newInstance(
                    null, "test-empty-group");
            assertFalse(scope.containsConfig(wc),
                    "Empty group must be out of scope");
        }

        @Test
        void launchGroupWithMatchingChildInScope()
                throws Exception {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            var childCfg = createConfig("group-child");
            childCfg.setAttribute(
                    "org.eclipse.jdt.launching.PROJECT_ATTR",
                    "my-project");
            var saved = childCfg.doSave();
            try {
                ILaunchConfigurationType groupType = mgr
                        .getLaunchConfigurationType(
                                "org.eclipse.debug.core.groups."
                                        + "GroupLaunchConfigurationType");
                var group = groupType.newInstance(
                        null, "test-group-with-child");
                group.setAttribute(
                        "org.eclipse.debug.core.launchGroup.0.name",
                        "group-child");
                var scope = ProjectScope.of(Set.of("my-project"));
                assertTrue(scope.containsConfig(group),
                        "Group with matching child should be in scope");
            } finally {
                saved.delete();
            }
        }

        @Test
        void configWithWorkingDirMatchesProject()
                throws Exception {
            var project = org.eclipse.core.resources.ResourcesPlugin
                    .getWorkspace().getRoot()
                    .getProject(TestFixture.PROJECT_NAME);
            String projectPath = project.getLocation().toOSString();
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType javaType = mgr
                    .getLaunchConfigurationType(
                            "org.eclipse.jdt.launching."
                                    + "localJavaApplication");
            var config = javaType.newInstance(
                    null, "test-workdir-match");
            config.setAttribute(
                    "org.eclipse.jdt.launching.WORKING_DIRECTORY",
                    projectPath);
            var scope = ProjectScope.of(
                    Set.of(TestFixture.PROJECT_NAME));
            assertTrue(scope.containsConfig(config),
                    "Working dir matching project location "
                            + "should be in scope");
        }

        @Test
        void configWithWorkingDirNotMatchingProjectExcluded()
                throws Exception {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType javaType = mgr
                    .getLaunchConfigurationType(
                            "org.eclipse.jdt.launching."
                                    + "localJavaApplication");
            var config = javaType.newInstance(
                    null, "test-workdir-nomatch");
            config.setAttribute(
                    "org.eclipse.jdt.launching.WORKING_DIRECTORY",
                    "/some/completely/unrelated/path");
            var scope = ProjectScope.of(
                    Set.of(TestFixture.PROJECT_NAME));
            assertFalse(scope.containsConfig(config),
                    "Working dir not matching any project "
                            + "should be out of scope");
        }
    }

    @Nested
    class ContainsAnyOfRoots {

        @Test
        void allScopeAcceptsEvenNullSet() {
            assertTrue(ProjectScope.ALL.containsAnyOfRoots(null));
        }

        @Test
        void allScopeAcceptsEmptySet() {
            assertTrue(ProjectScope.ALL.containsAnyOfRoots(
                    Set.of()));
        }

        @Test
        void filteredScopeRejectsNullSet() {
            ProjectScope scope = ProjectScope.of(
                    Set.of("project-a"));
            assertFalse(scope.containsAnyOfRoots(null));
        }

        @Test
        void filteredScopeRejectsEmptySet() {
            ProjectScope scope = ProjectScope.of(
                    Set.of("project-a"));
            assertFalse(scope.containsAnyOfRoots(Set.of()));
        }

        @Test
        void filteredScopeAcceptsMatchingRoot() throws Exception {
            IJavaProject jp = JavaCore.create(
                    org.eclipse.core.resources.ResourcesPlugin
                            .getWorkspace().getRoot()
                            .getProject(TestFixture.PROJECT_NAME));
            var roots = new HashSet<IPackageFragmentRoot>();
            for (IPackageFragmentRoot r
                    : jp.getPackageFragmentRoots()) {
                if (r.getKind()
                        == IPackageFragmentRoot.K_SOURCE) {
                    roots.add(r);
                }
            }
            ProjectScope scope = ProjectScope.of(
                    Set.of(TestFixture.PROJECT_NAME));
            assertTrue(scope.containsAnyOfRoots(roots),
                    "fixture source roots must match fixture scope");
        }

        @Test
        void filteredScopeRejectsNonMatchingRoot() throws Exception {
            IJavaProject jp = JavaCore.create(
                    org.eclipse.core.resources.ResourcesPlugin
                            .getWorkspace().getRoot()
                            .getProject(TestFixture.PROJECT_NAME));
            var roots = new HashSet<IPackageFragmentRoot>();
            for (IPackageFragmentRoot r
                    : jp.getPackageFragmentRoots()) {
                if (r.getKind()
                        == IPackageFragmentRoot.K_SOURCE) {
                    roots.add(r);
                }
            }
            ProjectScope scope = ProjectScope.of(
                    Set.of("completely-different-project"));
            assertFalse(scope.containsAnyOfRoots(roots));
        }
    }

    @Nested
    class ContainsLaunch {

        @Test
        void allScopeAcceptsLaunchWithoutConfig() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            assertTrue(ProjectScope.ALL.containsLaunch(launch));
        }

        @Test
        void filteredScopeAcceptsLaunchWithNullConfig() {
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    null, "run", null);
            ProjectScope scope = ProjectScope.of(
                    Set.of("anything"));
            assertTrue(scope.containsLaunch(launch),
                    "null config → permissive");
        }

        @Test
        void filteredScopeAcceptsLaunchWithMatchingProject()
                throws Exception {
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType type = mgr
                    .getLaunchConfigurationType(
                            "org.eclipse.jdt.junit.launchconfig");
            var wc = type.newInstance(null, "scope-launch-test");
            wc.setAttribute(
                    "org.eclipse.jdt.launching.PROJECT_ATTR",
                    "my-project");
            ILaunch launch = new org.eclipse.debug.core.Launch(
                    wc, "run", null);
            ProjectScope scope = ProjectScope.of(
                    Set.of("my-project"));
            assertTrue(scope.containsLaunch(launch));
        }
    }

    @Nested
    class SearchScope {

        @Test
        void allScopeCreatesWorkspaceScope() {
            IJavaSearchScope scope =
                    ProjectScope.ALL.searchScope();
            assertNotNull(scope);
        }

        @Test
        void filteredScopeCreatesProjectScope() {
            ProjectScope scope = ProjectScope.of(
                    Set.of(TestFixture.PROJECT_NAME));
            IJavaSearchScope searchScope = scope.searchScope();
            assertNotNull(searchScope);
        }
    }
}
