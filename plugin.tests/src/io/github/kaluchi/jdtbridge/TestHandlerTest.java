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
 */
public class TestHandlerTest {

    private static final TestHandler handler = new TestHandler();

    @Test
    public void detectTestKindJunit5FromPlatformCommons() {
        IJavaProject project = fakeProjectWithMarker(
                "org.junit.platform.commons.annotation.Testable",
                "junit-platform-commons-5.12.1.jar");

        String kind = handler.detectTestKind(project);

        assertEquals("org.eclipse.jdt.junit.loader.junit5", kind);
    }

    @Test
    public void detectTestKindJunit6FromPlatformCommons() {
        IJavaProject project = fakeProjectWithMarker(
                "org.junit.platform.commons.annotation.Testable",
                "junit-platform-commons-6.0.3.jar");

        String kind = handler.detectTestKind(project);

        assertEquals("org.eclipse.jdt.junit.loader.junit6", kind);
    }

    @Test
    public void detectTestKindJunit6FromSuiteApiFallback() {
        IJavaProject project = fakeProjectWithMarker(
                "org.junit.platform.suite.api.Suite",
                "junit-platform-suite-api-6.0.3.jar");

        String kind = handler.detectTestKind(project);

        assertEquals("org.eclipse.jdt.junit.loader.junit6", kind);
    }

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
