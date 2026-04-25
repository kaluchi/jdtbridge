package io.github.kaluchi.jdtbridge.coverage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;

import io.github.kaluchi.jdtbridge.LaunchAttrs;

/**
 * The set of launch configuration type IDs for which EclEmma
 * registers a {@code "coverage"} mode delegate. Mirrors the 9
 * {@code launchDelegate} entries in EclEmma's
 * {@code org.eclipse.eclemma.core/plugin.xml}.
 * <p>
 * The set is computed lazily on first access by querying the
 * Eclipse debug framework — types whose
 * {@link ILaunchConfigurationType#getDelegates(Set)} returns at
 * least one delegate for {@code "coverage"} mode. The 9 IDs below
 * are the candidates probed; the resolved set may be smaller in an
 * Eclipse instance that lacks one of the optional host bundles
 * (PDE, TestNG, RAP, SWTBot, Scala).
 */
public final class CoverageTypes {

    /** Mode name used by EclEmma's launch delegates and by
     *  {@code DebugPlugin.LAUNCH_MODE_COVERAGE}. */
    public static final String LAUNCH_MODE = "coverage";

    /** All launch type IDs probed for coverage support — same
     *  9 entries listed in EclEmma's {@code plugin.xml}. */
    public static final List<String> CANDIDATE_TYPE_IDS = List.of(
            "org.eclipse.jdt.launching.localJavaApplication",
            "org.eclipse.jdt.junit.launchconfig",
            "org.eclipse.pde.ui.RuntimeWorkbench",
            "org.eclipse.pde.ui.JunitLaunchConfig",
            "org.eclipse.pde.ui.EquinoxLauncher",
            "org.testng.eclipse.launchconfig",
            "org.eclipse.rap.ui.launch.RAPJUnitTestLauncher",
            "org.eclipse.swtbot.eclipse.ui.launcher.JunitLaunchConfig",
            "scala.application");

    private static volatile Set<String> resolved;

    private CoverageTypes() {
    }

    /** Type IDs for which Eclipse currently has a coverage-mode
     *  launch delegate registered. Result is cached after first
     *  computation. */
    public static Set<String> supported() {
        Set<String> snapshot = resolved;
        if (snapshot == null) {
            synchronized (CoverageTypes.class) {
                if (resolved == null) {
                    resolved = compute();
                }
                snapshot = resolved;
            }
        }
        return snapshot;
    }

    /** True when {@code typeId} has a registered coverage delegate. */
    public static boolean isSupported(String typeId) {
        return supported().contains(typeId);
    }

    /** Recompute and replace the cached set — for tests, or after
     *  EclEmma install/uninstall via p2. */
    public static synchronized void refresh() {
        resolved = compute();
    }

    private static Set<String> compute() {
        Set<String> modes = Set.of(LAUNCH_MODE);
        ILaunchManager mgr = LaunchAttrs.launchManager();
        Set<String> out = new LinkedHashSet<>();
        for (String typeId : CANDIDATE_TYPE_IDS) {
            ILaunchConfigurationType type =
                    mgr.getLaunchConfigurationType(typeId);
            if (type == null) {
                continue;
            }
            try {
                if (type.getDelegates(modes).length > 0) {
                    out.add(typeId);
                }
            } catch (Exception e) {
                // type registered without delegates — skip
            }
        }
        return Set.copyOf(out);
    }
}
