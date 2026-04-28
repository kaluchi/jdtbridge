package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for {@link Parsing} — the lenient parser used
 * for HTTP query parameters across multiple handlers. Was previously
 * three copy-pasted try/catch blocks each tested only through their
 * end-to-end flow.
 */
class ParsingTest {

    @Test
    void validNumberRoundTrips() {
        assertEquals(42, Parsing.parseIntOr("42", 0));
    }

    @Test
    void negativeNumberRoundTrips() {
        assertEquals(-7, Parsing.parseIntOr("-7", 0));
    }

    @Test
    void nullFallsBackToDefault() {
        assertEquals(120, Parsing.parseIntOr(null, 120));
    }

    @Test
    void emptyFallsBackToDefault() {
        assertEquals(120, Parsing.parseIntOr("", 120));
    }

    @Test
    void nonNumericFallsBackToDefault() {
        assertEquals(120, Parsing.parseIntOr("abc", 120));
    }

    @Test
    void blankStringFallsBackToDefault() {
        assertEquals(120, Parsing.parseIntOr("   ", 120));
    }

    @Test
    void overflowFallsBackToDefault() {
        assertEquals(120, Parsing.parseIntOr(
                "999999999999999999", 120));
    }
}
