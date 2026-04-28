package io.github.kaluchi.jdtbridge;

import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.osgi.framework.Bundle;

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
     * True iff a UI workbench is up. Probe via OSGi bundle state —
     * referencing PlatformUI directly would force the UI bundle to
     * link in headless Tycho and crash with NoClassDefFoundError
     * before any try/catch could catch it.
     */
    public static boolean isWorkbenchRunning() {
        Bundle bundle = Platform.getBundle(
                "org.eclipse.ui.workbench");
        return bundle != null
                && bundle.getState() == Bundle.ACTIVE;
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

    /**
     * True iff the EclEmma UI bundle is loaded AND active. End-to-end
     * coverage-launch tests need it because LaunchHandler routes
     * through UIPreferences for default scope; without it the JUnit
     * coverage delegate cannot resolve. CI Tycho target platform
     * may include org.eclipse.eclemma.core but not the UI bundle.
     */
    public static boolean isEclemmaUiActive() {
        var bundle = org.eclipse.core.runtime.Platform.getBundle(
                "org.eclipse.eclemma.ui");
        return bundle != null
                && bundle.getState()
                        >= org.osgi.framework.Bundle.ACTIVE;
    }

    /**
     * True iff a coverage delegate is registered for the JUnit launch
     * type AND the EclEmma UI bundle is active — both preconditions
     * required to drive {@code TestHandler#handleTestRun} with
     * {@code coverage=true} end-to-end.
     */
    public static boolean canRunJunitCoverageLaunch() {
        return io.github.kaluchi.jdtbridge.coverage.CoverageTypes
                        .isSupported(
                                "org.eclipse.jdt.junit.launchconfig")
                && isEclemmaUiActive();
    }
}
