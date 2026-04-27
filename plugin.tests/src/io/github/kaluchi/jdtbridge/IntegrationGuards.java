package io.github.kaluchi.jdtbridge;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.ui.PlatformUI;

/**
 * Capability probes for {@code @EnabledIf} test gating. Each method
 * returns {@code true} when the corresponding platform capability is
 * available in the current PDE runtime, {@code false} otherwise.
 * JUnit Jupiter then SKIPS the test with a "Disabled because X
 * returned false" entry in the report — explicit skip, not silent
 * pass.
 *
 * <p>Use over {@code try/catch + assumeTrue(false, ...)} which makes
 * the test pass without asserting anything when the condition is
 * unmet.
 */
public final class IntegrationGuards {

    private IntegrationGuards() { }

    /**
     * True iff a UI workbench is up. CI Tycho headless tests run
     * without one; calls into {@code org.eclipse.core.resources
     * .ProjectScope#getNode} (used by JDT manipulation APIs:
     * organize-imports, rename-field, move-cu) throw
     * {@link IllegalArgumentException} without it.
     */
    public static boolean isWorkbenchRunning() {
        try {
            PlatformUI.getWorkbench();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * True iff the m2e launch configuration type is registered. CI
     * Tycho target platform omits org.eclipse.m2e bundles by default,
     * so Maven-launch tests must gate themselves.
     */
    public static boolean hasMavenLaunchType() {
        return DebugPlugin.getDefault().getLaunchManager()
                .getLaunchConfigurationType(
                        "org.eclipse.m2e.Maven2LaunchConfigurationType")
                != null;
    }
}
