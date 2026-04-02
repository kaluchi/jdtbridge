package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
}
