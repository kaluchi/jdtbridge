package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SourceReport} — javadoc extraction
 * and markdown formatting utilities.
 */
public class SourceReportTest {

    @Nested
    class ExtractFirstSentence {

        @Test
        void simpleSentence() {
            assertEquals("Does something useful.",
                    SourceReport.extractFirstSentence(
                            "/** Does something useful. */"));
        }

        @Test
        void multiLine() {
            assertEquals("Returns the value.",
                    SourceReport.extractFirstSentence(
                            "/**\n * Returns the value.\n */"));
        }

        @Test
        void stopsAtPeriod() {
            assertEquals("First sentence.",
                    SourceReport.extractFirstSentence(
                            "/** First sentence. Second sentence. */"));
        }

        @Test
        void stopsAtAtTag() {
            assertEquals("Description here.",
                    SourceReport.extractFirstSentence(
                            "/**\n * Description here.\n * @param x\n */"));
        }

        @Test
        void stopsAtParagraph() {
            assertEquals("Short desc.",
                    SourceReport.extractFirstSentence(
                            "/**\n * Short desc.\n * <p>\n * More. */"));
        }

        @Test
        void stopsAtEmptyLine() {
            assertEquals("First part.",
                    SourceReport.extractFirstSentence(
                            "/**\n * First part.\n *\n * Second. */"));
        }

        @Test
        void handlesCodeTag() {
            assertEquals("Uses `Map` internally.",
                    SourceReport.extractFirstSentence(
                            "/** Uses {@code Map} internally. */"));
        }

        @Test
        void handlesLinkTag() {
            assertEquals("See `String` for details.",
                    SourceReport.extractFirstSentence(
                            "/** See {@link String} for details. */"));
        }

        @Test
        void stripsHtmlTags() {
            assertEquals("Bold text here.",
                    SourceReport.extractFirstSentence(
                            "/** <b>Bold</b> text here. */"));
        }

        @Test
        void multiLineBeforePeriod() {
            assertEquals("A long description that spans multiple lines.",
                    SourceReport.extractFirstSentence(
                            "/**\n * A long description that\n"
                            + " * spans multiple lines.\n */"));
        }

        @Test
        void emptyJavadoc() {
            assertNull(SourceReport.extractFirstSentence(
                    "/** */"));
        }

        @Test
        void onlyTags() {
            assertNull(SourceReport.extractFirstSentence(
                    "/**\n * @param x the value\n */"));
        }
    }
}
