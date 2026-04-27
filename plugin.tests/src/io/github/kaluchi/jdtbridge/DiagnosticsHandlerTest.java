package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for DiagnosticsHandler utility methods.
 */
public class DiagnosticsHandlerTest {

    private final DiagnosticsHandler handler = new DiagnosticsHandler();

    @Test
    public void shortMarkerTypeJdt() {
        assertEquals("jdt", handler.shortMarkerType(
                "org.eclipse.jdt.core.problem"));
    }

    @Test
    public void shortMarkerTypeCheckstyle() {
        assertEquals("checkstyle", handler.shortMarkerType(
                "net.sf.eclipsecs.core.CheckstyleMarker"));
    }

    @Test
    public void shortMarkerTypeMaven() {
        assertEquals("maven", handler.shortMarkerType(
                "org.eclipse.m2e.core.maven2Problem"));
    }

    @Test
    public void shortMarkerTypeUnknown() {
        assertEquals("SomeProblem", handler.shortMarkerType(
                "com.vendor.SomeProblem"));
    }

    @Test
    public void shortMarkerTypeNull() {
        assertEquals("unknown", handler.shortMarkerType(null));
    }
}
