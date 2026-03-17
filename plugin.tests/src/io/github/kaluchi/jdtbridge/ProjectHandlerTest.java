package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for ProjectHandler utility methods.
 * Uses package-private access via Fragment-Host.
 */
public class ProjectHandlerTest {

    @Test
    public void shortNatureJava() {
        assertEquals("java",
                invokeShortNature("org.eclipse.jdt.core.javanature"));
    }

    @Test
    public void shortNatureMaven() {
        assertEquals("maven",
                invokeShortNature("org.eclipse.m2e.core.maven2Nature"));
    }

    @Test
    public void shortNaturePde() {
        assertEquals("pde",
                invokeShortNature("org.eclipse.pde.PluginNature"));
    }

    @Test
    public void shortNatureGradle() {
        assertEquals("gradle",
                invokeShortNature(
                        "org.eclipse.buildship.core.gradleprojectnature"));
    }

    @Test
    public void shortNatureUnknown() {
        assertEquals("SomeNature",
                invokeShortNature("com.example.SomeNature"));
    }

    @Test
    public void shortNatureNoPackage() {
        assertEquals("standalone",
                invokeShortNature("standalone"));
    }

    // shortNature is private static — access via reflection
    // since we are a fragment, we share the classloader
    private String invokeShortNature(String natureId) {
        try {
            var method = ProjectHandler.class
                    .getDeclaredMethod("shortNature", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, natureId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
