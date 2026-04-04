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
            if (mavenType == null) return; // m2e not available
            var config = mavenType.newInstance(
                    null, "test-no-project");
            // No working dir set → passes (permissive)
            assertTrue(scope.containsConfig(config));
        }
    }
}
