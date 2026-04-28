package io.github.kaluchi.jdtbridge;

/**
 * Lenient string-to-primitive parsing helpers — every callsite that
 * accepts an HTTP query parameter falls back to a default rather
 * than failing the request, so the swallow lives in one place.
 */
final class Parsing {

    private Parsing() {
    }

    /** Parse {@code s} as base-10 int. Returns {@code defaultValue}
     *  for {@code null}, blank, or malformed input. */
    static int parseIntOr(String s, int defaultValue) {
        if (s == null) return defaultValue;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
