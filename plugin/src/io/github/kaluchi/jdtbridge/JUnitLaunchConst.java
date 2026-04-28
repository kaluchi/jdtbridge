package io.github.kaluchi.jdtbridge;

/**
 * Eclipse JDT JUnit / PDE JUnit launch-configuration string keys
 * shared between {@link LaunchHandler} and {@link TestHandler}.
 * The Eclipse SDK does not export these as public constants —
 * they live inside {@code o.e.jdt.junit.JUnitLaunchConfigurationDelegate}
 * and friends, which are internal. Mirrored here as compile-time
 * constants so they are usable as switch case labels.
 */
final class JUnitLaunchConst {

    private JUnitLaunchConst() {
    }

    /** Plain JUnit launch configuration type. */
    static final String LAUNCH_TYPE =
            "org.eclipse.jdt.junit.launchconfig";

    /** PDE JUnit (plug-in test) launch configuration type. */
    static final String PDE_LAUNCH_TYPE =
            "org.eclipse.pde.ui.JunitLaunchConfig";

    /** Attribute: which test runner — see {@link #KIND_JUNIT4}
     *  / {@link #KIND_JUNIT5} / {@link #KIND_JUNIT6}. */
    static final String ATTR_TEST_KIND =
            "org.eclipse.jdt.junit.TEST_KIND";

    /** Attribute: single test method name within the chosen
     *  type, when targeting one method. */
    static final String ATTR_TEST_NAME =
            "org.eclipse.jdt.junit.TESTNAME";

    /** Attribute: container memento (project / source folder /
     *  package) for run-all-tests-in-X. */
    static final String ATTR_CONTAINER =
            "org.eclipse.jdt.junit.CONTAINER";

    static final String KIND_JUNIT4 =
            "org.eclipse.jdt.junit.loader.junit4";
    static final String KIND_JUNIT5 =
            "org.eclipse.jdt.junit.loader.junit5";
    static final String KIND_JUNIT6 =
            "org.eclipse.jdt.junit.loader.junit6";

    static String formatRunner(String testKind) {
        if (testKind == null) return null;
        return switch (testKind) {
            case KIND_JUNIT6 -> "JUnit 6";
            case KIND_JUNIT5 -> "JUnit 5";
            case KIND_JUNIT4 -> "JUnit 4";
            default -> testKind;
        };
    }
}
