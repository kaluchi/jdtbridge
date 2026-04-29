package io.github.kaluchi.jdtbridge.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CoverageTypes}. The 9 candidate IDs are static
 * (mirrored from EclEmma's {@code plugin.xml}); the runtime-resolved
 * subset depends on which optional host bundles (PDE, TestNG, RAP,
 * SWTBot, Scala) are installed alongside EclEmma.
 */
public class CoverageTypesTest {

    @Nested
    class Candidates {

        @Test
        void sizeIsNine() {
            assertEquals(9,
                    CoverageTypes.CANDIDATE_TYPE_IDS.size());
        }

        @Test
        void includesJavaApplication() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.eclipse.jdt.launching.localJavaApplication"));
        }

        @Test
        void includesPlainJUnit() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.eclipse.jdt.junit.launchconfig"));
        }

        @Test
        void includesPdeJUnit() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.eclipse.pde.ui.JunitLaunchConfig"));
        }

        @Test
        void includesEclipseApplication() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.eclipse.pde.ui.RuntimeWorkbench"));
        }

        @Test
        void includesEquinox() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.eclipse.pde.ui.EquinoxLauncher"));
        }

        @Test
        void includesTestNg() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.testng.eclipse.launchconfig"));
        }

        @Test
        void includesRap() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.eclipse.rap.ui.launch.RAPJUnitTestLauncher"));
        }

        @Test
        void includesSwtBot() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "org.eclipse.swtbot.eclipse.ui.launcher"
                            + ".JunitLaunchConfig"));
        }

        @Test
        void includesScala() {
            assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS.contains(
                    "scala.application"));
        }
    }

    @Nested
    class Resolution {

        @Test
        void supportedIsSubsetOfCandidates() {
            Set<String> supported = CoverageTypes.supported();
            assertNotNull(supported);
            for (String typeId : supported) {
                assertTrue(CoverageTypes.CANDIDATE_TYPE_IDS
                        .contains(typeId),
                        "Resolved type ID not in candidate list: "
                                + typeId);
            }
        }

        @Test
        void supportedIsCachedAcrossCalls() {
            Set<String> first = CoverageTypes.supported();
            Set<String> second = CoverageTypes.supported();
            // Cached set should be the same reference (or at least
            // equal contents — we accept equals here).
            assertEquals(first, second);
        }

        @Test
        void refreshRecomputes() {
            Set<String> first = CoverageTypes.supported();
            CoverageTypes.refresh();
            Set<String> after = CoverageTypes.supported();
            // Workspace state stable inside one test, so contents
            // must match — but the refresh path executed at least.
            assertEquals(first, after);
        }

        @Test
        void unknownTypeIdNotSupported() {
            assertFalse(CoverageTypes.isSupported(
                    "io.example.bogus.never-registered.type"));
        }

        @Test
        void launchModeConstant() {
            assertEquals("coverage", CoverageTypes.LAUNCH_MODE);
        }

        @Test
        void whenEclEmmaPresentJavaApplicationIsSupported() {
            assertTrue(CoverageTypes.isSupported(
                    "org.eclipse.jdt.launching.localJavaApplication"),
                    "Java App should have a coverage delegate when"
                            + " EclEmma is installed");
        }
    }
}
