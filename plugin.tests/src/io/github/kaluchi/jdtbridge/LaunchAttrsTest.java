package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure helpers in {@link LaunchAttrs}.
 * The {@link org.eclipse.debug.core.ILaunch}-bound accessors
 * (firstPid / launchTimestamp / launchIdOf / launchManager) are
 * exercised end-to-end by LaunchTracker / TestHandler / coverage
 * integration tests.
 */
public class LaunchAttrsTest {

    @Nested
    class ParseTimestamp {

        @Test
        void nullReturnsNull() {
            assertNull(LaunchAttrs.parseTimestamp(null));
        }

        @Test
        void blankReturnsNull() {
            assertNull(LaunchAttrs.parseTimestamp(""));
        }

        @Test
        void numericStringParses() {
            assertEquals(1_234_567_890L,
                    LaunchAttrs.parseTimestamp("1234567890"));
        }

        @Test
        void zeroParses() {
            assertEquals(0L, LaunchAttrs.parseTimestamp("0"));
        }

        @Test
        void negativeParses() {
            assertEquals(-42L, LaunchAttrs.parseTimestamp("-42"));
        }

        @Test
        void longBoundsParse() {
            assertEquals(Long.MAX_VALUE,
                    LaunchAttrs.parseTimestamp(
                            Long.toString(Long.MAX_VALUE)));
            assertEquals(Long.MIN_VALUE,
                    LaunchAttrs.parseTimestamp(
                            Long.toString(Long.MIN_VALUE)));
        }

        @Test
        void overflowReturnsNull() {
            // Long.MAX_VALUE + 1
            assertNull(LaunchAttrs.parseTimestamp(
                    "9223372036854775808"));
        }

        @Test
        void nonNumericReturnsNull() {
            assertNull(LaunchAttrs.parseTimestamp("not-a-number"));
        }

        @Test
        void floatLiteralReturnsNull() {
            assertNull(LaunchAttrs.parseTimestamp("3.14"));
        }

        @Test
        void surroundingWhitespaceReturnsNull() {
            // Long.parseLong does not trim — strict format only.
            assertNull(LaunchAttrs.parseTimestamp(" 123 "));
        }
    }
}
