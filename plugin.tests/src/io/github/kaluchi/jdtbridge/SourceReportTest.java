package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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

    @Nested
    @EnabledIfSystemProperty(
            named = "jdtbridge.integration-tests",
            matches = "true")
    class JsonOutput {

        @BeforeAll
        static void setUp() throws Exception { TestFixture.create(); }

        @AfterAll
        static void tearDown() throws Exception {
            TestFixture.destroy();
        }

        @Test
        void fullClassHasHierarchyNotRefs() throws Exception {
            IType type = JdtUtils.findType("test.model.Dog");
            var refs = ReferenceCollector.collect(type);
            String json = SourceReport.toJson(
                    "test.model.Dog", type,
                    "D:/test/Dog.java",
                    type.getSource(), 1, 20, refs, null);
            // Type-level: hierarchy, no outgoing refs
            assertTrue(json.contains("\"supertypes\""),
                    "Should have supertypes: " + json);
            assertFalse(json.contains("\"refs\""),
                    "Should not have refs: " + json);
        }

        @Test
        void methodIncludesSameClassRefs() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod[] methods = type.getMethods();
            assertTrue(methods.length > 0);
            var refs = ReferenceCollector.collect(methods[0]);
            String json = SourceReport.toJson(
                    "test.service.AnimalService#process",
                    methods[0], "D:/test/AnimalService.java",
                    methods[0].getSource(), 1, 10, refs, null);
            // May have class-scope refs if method references
            // other members
            assertTrue(json.contains("\"fqmn\""),
                    "Should have refs: " + json);
        }

        @Test
        void constructorHasNoReturnType() throws Exception {
            IType type = JdtUtils.findType("test.model.Dog");
            // Dog has a constructor (implicit or explicit)
            // Find any method ref that is a constructor
            var refs = ReferenceCollector.collect(type);
            for (var ref : refs.values()) {
                if (ref.element() instanceof IMethod m
                        && m.isConstructor()) {
                    String json = SourceReport.toJson(
                            "test", type, "D:/test.java",
                            "code", 1, 1,
                            java.util.Map.of(ref.fqmn(), ref),
                            null);
                    assertFalse(json.contains("\"type\":\"void\""),
                            "Constructor should not have void type: "
                                    + json);
                }
            }
        }
    }
}
