package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for DiagnosticsHandler utility methods.
 */
public class DiagnosticsHandlerTest {

    @Test
    public void shortMarkerTypeJdt() {
        assertEquals("jdt", invokeShortMarkerType(
                "org.eclipse.jdt.core.problem"));
    }

    @Test
    public void shortMarkerTypeCheckstyle() {
        assertEquals("checkstyle", invokeShortMarkerType(
                "net.sf.eclipsecs.core.CheckstyleMarker"));
    }

    @Test
    public void shortMarkerTypeMaven() {
        assertEquals("maven", invokeShortMarkerType(
                "org.eclipse.m2e.core.maven2Problem"));
    }

    @Test
    public void shortMarkerTypeUnknown() {
        assertEquals("SomeProblem", invokeShortMarkerType(
                "com.vendor.SomeProblem"));
    }

    @Test
    public void shortMarkerTypeNull() {
        assertEquals("unknown", invokeShortMarkerType(null));
    }

    private String invokeShortMarkerType(String type) {
        try {
            var method = DiagnosticsHandler.class
                    .getDeclaredMethod("shortMarkerType", String.class);
            method.setAccessible(true);
            return (String) method.invoke(new DiagnosticsHandler(), type);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
