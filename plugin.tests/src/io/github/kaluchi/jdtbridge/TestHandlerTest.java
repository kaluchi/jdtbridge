package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
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

    // ---- Unparseable jar name → manifest + classpath fallback ----

    @Test
    public void jarWithoutVersionFallsThroughToClasspathCheck() {
        // jar name has no parseable version (extractVersion → null).
        // resolveJUnitMajor then calls readManifestVersion, which
        // reflectively probes PACKAGE_FRAGMENT_ROOT.getManifest()
        // — the mock has no such method, so the reflective lookup
        // throws and the version stays null. detectTestKind falls
        // back to the classpath-path exclusion check; the mock
        // entry path "/libs/stripped.jar" matches none of
        // JUnit 3/4/5 container paths, so JUnit 6 (newest) wins.
        // This mirrors Eclipse CoreTestSearchEngine's "unknown
        // version on a non-excluded classpath" handling.
        IJavaProject project = fakeProjectWithMarker(
                TESTABLE, "stripped.jar");

        assertEquals(JUNIT6_KIND, handler.detectTestKind(project));
    }


    // ---- parseTimeout ----

    @Test
    public void parseTimeoutDefault() {
        assertEquals(120, handler.parseTimeout(null, 120));
    }

    @Test
    public void parseTimeoutValid() {
        assertEquals(30, handler.parseTimeout("30", 120));
    }

    @Test
    public void parseTimeoutInvalid() {
        assertEquals(120, handler.parseTimeout("abc", 120));
    }

    // ---- launchPrefix ----

    @Test
    public void prefixFromFqn() {
        assertEquals("OrderServiceTest",
                TestHandler.launchPrefix(
                        "com.example.OrderServiceTest",
                        null, null));
    }

    @Test
    public void prefixFromFqnSimpleName() {
        assertEquals("MyTest",
                TestHandler.launchPrefix("MyTest", null, null));
    }

    @Test
    public void prefixFromPackage() {
        assertEquals("com.example.service",
                TestHandler.launchPrefix(
                        null, "com.example.service", "my-server"));
    }

    @Test
    public void prefixFromProject() {
        assertEquals("my-server",
                TestHandler.launchPrefix(null, null, "my-server"));
    }

    @Test
    public void prefixFallback() {
        assertEquals("test",
                TestHandler.launchPrefix(null, null, null));
    }

    @Test
    public void prefixFqnTakesPriority() {
        assertEquals("MyTest",
                TestHandler.launchPrefix(
                        "com.example.MyTest",
                        "com.example",
                        "my-server"));
    }

    @Test
    public void prefixPackageOverProject() {
        assertEquals("com.example",
                TestHandler.launchPrefix(
                        null, "com.example", "my-server"));
    }

    @Test
    public void prefixBlankFqnFallsToPackage() {
        assertEquals("com.example",
                TestHandler.launchPrefix(
                        "  ", "com.example", "my-server"));
    }

    private static final java.util.Map<Class<?>, Object>
            PRIMITIVE_DEFAULTS = java.util.Map.of(
            Boolean.TYPE, false, Byte.TYPE, (byte) 0,
            Short.TYPE, (short) 0, Integer.TYPE, 0,
            Long.TYPE, 0L, Float.TYPE, 0f,
            Double.TYPE, 0d, Character.TYPE, '\0');

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface,
            java.util.Map<String, Object> responses) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[] { iface },
                (p, method, args) -> responses.getOrDefault(
                        method.getName(),
                        PRIMITIVE_DEFAULTS.get(
                                method.getReturnType())));
    }

    private IJavaProject fakeProjectWithFallbackOnly(
            String fallbackFqn) {
        java.util.Map<String, IType> types = fallbackFqn != null
                ? java.util.Map.of(fallbackFqn,
                        fakeType(fallbackFqn,
                                "junit-jupiter-api-5.12.1.jar",
                                new Path("/libs/junit-jupiter-api"
                                        + "-5.12.1.jar")))
                : java.util.Map.of();
        return fakeProject(types);
    }

    private IJavaProject fakeProjectWithMarker(String markerFqn,
            String jarName) {
        IType marker = fakeType(markerFqn, jarName,
                new Path("/libs/" + jarName));
        return fakeProject(java.util.Map.of(markerFqn, marker));
    }

    @SuppressWarnings("unchecked")
    private IJavaProject fakeProject(
            java.util.Map<String, IType> types) {
        return (IJavaProject) Proxy.newProxyInstance(
                IJavaProject.class.getClassLoader(),
                new Class<?>[] { IJavaProject.class },
                (p, method, args) -> java.util.Optional
                        .ofNullable(args)
                        .map(a -> types.get((String) a[0]))
                        .orElse(null));
    }

    private IType fakeType(String fqn, String jarName,
            IPath rawClasspathPath) {
        IClasspathEntry entry = proxy(IClasspathEntry.class,
                java.util.Map.of("getPath", rawClasspathPath));
        IPackageFragmentRoot root = proxy(
                IPackageFragmentRoot.class,
                java.util.Map.of("getRawClasspathEntry", entry));
        return proxy(IType.class, java.util.Map.of(
                "getPath", new Path("/repo/" + jarName),
                "getFullyQualifiedName", fqn,
                "getAncestor", root));
    }
}
