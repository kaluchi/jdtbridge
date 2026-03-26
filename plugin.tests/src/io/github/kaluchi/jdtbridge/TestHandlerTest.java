package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TestHandler utility methods.
 * Uses dynamic proxies for JDT interfaces — no workspace needed.
 *
 * JUnit Platform versioning recap:
 * <ul>
 *   <li>JUnit 5 platform bundles use version 1.x (range [1.0, 2.0))
 *   <li>JUnit 6 platform bundles use version 6.x
 *   <li>The marker annotation is Testable (in commons) or Suite
 *       (in suite-api)
 *   <li>Eclipse uses both OSGi naming (underscore) and Maven naming
 *       (dash) for jar files
 * </ul>
 */
public class TestHandlerTest {

    private static final TestHandler handler = new TestHandler();

    private static final String TESTABLE =
            "org.junit.platform.commons.annotation.Testable";
    private static final String SUITE =
            "org.junit.platform.suite.api.Suite";
    private static final String JUNIT4_KIND =
            "org.eclipse.jdt.junit.loader.junit4";
    private static final String JUNIT5_KIND =
            "org.eclipse.jdt.junit.loader.junit5";
    private static final String JUNIT6_KIND =
            "org.eclipse.jdt.junit.loader.junit6";

    // ---- JUnit 5 via Testable marker (version 1.x) ----

    @Test
    public void junit5FromTestableOsgiNaming() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons_1.14.3.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit5FromTestableMavenNaming() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons-1.14.3.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit5FromTestableMinimumVersion() {
        // 1.0.0 is the earliest JUnit 5 platform release
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons_1.0.0.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit5FromTestableHighMinor() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons_1.99.0.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit5FromTestableWithQualifier() {
        // Eclipse/Tycho builds append qualifiers like .v20260101
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE,
                "junit-platform-commons_1.14.3.v20260315.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    // ---- JUnit 6 via Testable marker ----

    @Test
    public void junit6FromTestableOsgiNaming() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons_6.0.3.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit6FromTestableMavenNaming() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons-6.0.3.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit6FromTestableHighMinor() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons_6.5.0.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit6FromTestableWithQualifier() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE,
                "junit-platform-commons_6.0.3.v20260315.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }

    // ---- JUnit 5 via Suite marker (version 1.x) ----

    @Test
    public void junit5FromSuiteOsgiNaming() {
        IJavaProject project = fakeProjectWithMarker(
                SUITE, "junit-platform-suite-api_1.14.3.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit5FromSuiteMavenNaming() {
        IJavaProject project = fakeProjectWithMarker(
                SUITE, "junit-platform-suite-api-1.14.3.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit5FromSuiteMinimumVersion() {
        IJavaProject project = fakeProjectWithMarker(
                SUITE, "junit-platform-suite-api_1.0.0.jar");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    // ---- JUnit 6 via Suite marker ----

    @Test
    public void junit6FromSuiteOsgiNaming() {
        IJavaProject project = fakeProjectWithMarker(
                SUITE, "junit-platform-suite-api_6.0.3.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }

    @Test
    public void junit6FromSuiteMavenNaming() {
        IJavaProject project = fakeProjectWithMarker(
                SUITE, "junit-platform-suite-api-6.0.3.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }

    // ---- Unrecognized platform version → JUnit 4 ----

    @Test
    public void unknownPlatformVersion2FallsToJunit4() {
        // Version 2.x is neither JUnit 5 (1.x) nor JUnit 6 (6.x)
        // and no Jupiter API fallback → default JUnit 4
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons_2.0.0.jar");

        assertEquals(JUNIT4_KIND, handler.detectTestKind(project));
    }

    @Test
    public void unknownPlatformVersion3FallsToJunit4() {
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons_3.0.0.jar");

        assertEquals(JUNIT4_KIND, handler.detectTestKind(project));
    }

    // ---- JUnit 6 checked before JUnit 5 ----

    @Test
    public void junit6TakesPriorityOverJunit5() {
        // Version 6.x must be detected as JUnit 6, not JUnit 5
        // (both paths are checked; 6 first)
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "junit-platform-commons-6.0.0.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }

    // ---- Fallback: Jupiter API without platform markers ----

    @Test
    public void junit5FallbackFromJupiterApi() {
        // Platform markers not resolvable, but Jupiter API is
        // (common with M2Eclipse)
        IJavaProject project = fakeProjectWithFallbackOnly(
                "org.junit.jupiter.api.Test");

        assertEquals(JUNIT5_KIND, handler.detectTestKind(project));
    }

    // ---- Default: nothing found → JUnit 4 ----

    @Test
    public void fallsBackToJunit4WhenNothingFound() {
        IJavaProject project = fakeProjectWithFallbackOnly(null);

        assertEquals(JUNIT4_KIND, handler.detectTestKind(project));
    }

    // ---- parseTimeout ----

    @Test
    public void parseTimeoutDefault() {
        assertEquals(120, invokeParseTimeout(null, 120));
    }

    @Test
    public void parseTimeoutValid() {
        assertEquals(30, invokeParseTimeout("30", 120));
    }

    @Test
    public void parseTimeoutInvalid() {
        assertEquals(120, invokeParseTimeout("abc", 120));
    }

    // ---- Helpers ----

    private int invokeParseTimeout(String s, int defaultVal) {
        try {
            var method = TestHandler.class
                    .getDeclaredMethod("parseTimeout",
                            String.class, int.class);
            method.setAccessible(true);
            return (int) method.invoke(handler, s, defaultVal);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Project where platform markers are NOT resolvable,
     * but an optional fallback type IS (e.g. Jupiter API
     * present without junit-platform-commons).
     */
    private IJavaProject fakeProjectWithFallbackOnly(
            String fallbackFqn) {
        return (IJavaProject) Proxy.newProxyInstance(
                IJavaProject.class.getClassLoader(),
                new Class<?>[] { IJavaProject.class },
                (proxy, method, args) -> {
                    if ("findType".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && fallbackFqn != null
                            && fallbackFqn.equals(args[0])) {
                        return fakeType(fallbackFqn,
                                "junit-jupiter-api-5.12.1.jar",
                                new Path("/libs/junit-jupiter-api"
                                        + "-5.12.1.jar"));
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private IJavaProject fakeProjectWithMarker(String markerFqn,
            String jarName) {
        IPath rawClasspathPath = new Path("/libs/" + jarName);
        IType marker = fakeType(markerFqn, jarName, rawClasspathPath);

        return (IJavaProject) Proxy.newProxyInstance(
                IJavaProject.class.getClassLoader(),
                new Class<?>[] { IJavaProject.class },
                (proxy, method, args) -> {
                    if ("findType".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && markerFqn.equals(args[0])) {
                        return marker;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private IType fakeType(String fqn, String jarName,
            IPath rawClasspathPath) {
        IClasspathEntry entry = (IClasspathEntry)
                Proxy.newProxyInstance(
                        IClasspathEntry.class.getClassLoader(),
                        new Class<?>[] { IClasspathEntry.class },
                        (proxy, method, args) -> {
                            if ("getPath".equals(method.getName())) {
                                return rawClasspathPath;
                            }
                            return defaultValue(
                                    method.getReturnType());
                        });

        IPackageFragmentRoot root = (IPackageFragmentRoot)
                Proxy.newProxyInstance(
                        IPackageFragmentRoot.class.getClassLoader(),
                        new Class<?>[] { IPackageFragmentRoot.class },
                        (proxy, method, args) -> {
                            if ("getRawClasspathEntry".equals(
                                    method.getName())) {
                                return entry;
                            }
                            return defaultValue(
                                    method.getReturnType());
                        });

        IPath binaryPath = new Path("/repo/" + jarName);
        return (IType) Proxy.newProxyInstance(
                IType.class.getClassLoader(),
                new Class<?>[] { IType.class },
                (proxy, method, args) -> {
                    if ("getPath".equals(method.getName())) {
                        return binaryPath;
                    }
                    if ("getFullyQualifiedName".equals(
                            method.getName())) {
                        return fqn;
                    }
                    if ("getAncestor".equals(method.getName())
                            && args != null
                            && args.length == 1
                            && Integer.valueOf(
                                    IJavaElement
                                            .PACKAGE_FRAGMENT_ROOT)
                                    .equals(args[0])) {
                        return root;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0f;
        if (returnType == Double.TYPE) return 0d;
        if (returnType == Character.TYPE) return '\0';
        return null;
    }
}
