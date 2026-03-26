package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for SearchHandler — package vs type name detection
 * and package name normalization.
 */
public class SearchHandlerTest {

    @Nested
    class IsPackageSearch {

        @Test
        void dottedLowercaseName() {
            assertTrue(SearchHandler.isPackageSearch(
                    "com.example.core.user"));
        }

        @Test
        void trailingDot() {
            assertTrue(SearchHandler.isPackageSearch(
                    "com.example.core.user."));
        }

        @Test
        void trailingDotStar() {
            assertTrue(SearchHandler.isPackageSearch(
                    "com.example.core.user.*"));
        }

        @Test
        void trailingDotQuestion() {
            assertTrue(SearchHandler.isPackageSearch(
                    "com.example.core.user.?"));
        }

        @Test
        void twoSegments() {
            assertTrue(SearchHandler.isPackageSearch(
                    "com.example"));
        }

        @Test
        void deepPackage() {
            assertTrue(SearchHandler.isPackageSearch(
                    "com.example.service.impl"));
        }

        @Test
        void fqnUppercaseLastSegment() {
            assertFalse(SearchHandler.isPackageSearch(
                    "com.example.MyClass"));
        }

        @Test
        void simpleNameNoDots() {
            assertFalse(SearchHandler.isPackageSearch("MyClass"));
        }

        @Test
        void simpleWildcard() {
            assertFalse(SearchHandler.isPackageSearch(
                    "*Controller"));
        }

        @Test
        void wildcardInMiddle() {
            assertFalse(SearchHandler.isPackageSearch(
                    "com.example.*Service"));
        }
    }

    @Nested
    class NormalizePackage {

        @Test
        void stripsTrailingDot() {
            assertEquals("com.example.core.user",
                    SearchHandler.normalizePackage(
                            "com.example.core.user."));
        }

        @Test
        void stripsTrailingDotStar() {
            assertEquals("com.example.core.user",
                    SearchHandler.normalizePackage(
                            "com.example.core.user.*"));
        }

        @Test
        void stripsTrailingDotQuestion() {
            assertEquals("com.example.core.user",
                    SearchHandler.normalizePackage(
                            "com.example.core.user.?"));
        }

        @Test
        void noSuffixUnchanged() {
            assertEquals("com.example.core.user",
                    SearchHandler.normalizePackage(
                            "com.example.core.user"));
        }
    }
}
