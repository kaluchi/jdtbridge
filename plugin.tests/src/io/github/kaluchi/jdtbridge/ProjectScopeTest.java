package io.github.kaluchi.jdtbridge;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProjectScope}.
 */
public class ProjectScopeTest {

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
            // No PROJECT_ATTR, no WORKING_DIR → passes
            var scope = ProjectScope.of(Set.of("my-project"));
            ILaunchManager mgr = DebugPlugin.getDefault()
                    .getLaunchManager();
            ILaunchConfigurationType mavenType = mgr
                    .getLaunchConfigurationType(
                            "org.eclipse.m2e.Maven2LaunchConfigurationType");
            org.junit.jupiter.api.Assertions.assertNotNull(
                    mavenType,
                    "m2e launch type must be present in test runtime");
            var config = mavenType.newInstance(
                    null, "test-no-project");
            // No working dir set → passes (permissive)
            assertTrue(scope.containsConfig(config));
        }

        @Test
        void launchGroupTypeRoutesThroughGroupBranch()
                throws Exception {
            // Group type config — exercises isLaunchGroup() and
            // launchGroupInScope(); empty group has no children
            // so the result is false.
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
    }
}
