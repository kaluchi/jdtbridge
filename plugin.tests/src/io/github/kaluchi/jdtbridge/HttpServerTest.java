package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for utility methods in HttpServer and Json.
 * These run without a live Eclipse workspace.
 */
public class HttpServerTest {

    @Test
    public void escapeJsonPlainString() {
        assertEquals("hello", Json.escape("hello"));
    }

    @Test
    public void escapeJsonSpecialChars() {
        assertEquals("a\\\"b", Json.escape("a\"b"));
        assertEquals("a\\\\b", Json.escape("a\\b"));
        assertEquals("a\\nb", Json.escape("a\nb"));
        assertEquals("a\\rb", Json.escape("a\rb"));
        assertEquals("a\\tb", Json.escape("a\tb"));
    }

    @Test
    public void escapeJsonControlChars() {
        // Control char below 0x20 (e.g. 0x01) should be \\u0001
        String input = "a" + (char) 0x01 + "b";
        assertEquals("a\\u0001b", Json.escape(input));
    }

    @Test
    public void escapeJsonNull() {
        assertEquals("null", Json.escape(null));
    }

    @Test
    public void escapeJsonEmpty() {
        assertEquals("", Json.escape(""));
    }

    @Test
    public void escapeJsonUnicode() {
        // Non-ASCII chars should pass through unchanged
        assertEquals("Привет", Json.escape("Привет"));
        assertEquals("日本語", Json.escape("日本語"));
    }
}
