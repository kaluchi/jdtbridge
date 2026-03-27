package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;

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
            var parsed = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals("test.model.Dog",
                    parsed.get("fqmn").getAsString());
            assertTrue(parsed.has("supertypes"));
            assertFalse(parsed.has("refs"));
        }

        @Test
        void methodHasRefsWithDirection() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            assertNotNull(method);
            var refs = ReferenceCollector.collect(method);
            String json = SourceReport.toJson(
                    "test.service.AnimalService#process(Animal)",
                    method, "D:/test/AnimalService.java",
                    method.getSource(), 1, 10, refs, null);
            var parsed = JsonParser.parseString(json)
                    .getAsJsonObject();
            assertEquals(
                    "test.service.AnimalService#process(Animal)",
                    parsed.get("fqmn").getAsString());
            assertTrue(parsed.has("refs"));
            assertTrue(json.contains(
                    "\"direction\":\"outgoing\""),
                    "Refs should have outgoing direction: "
                    + json);
        }

        @Test
        void methodRefHasTypeKindField() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod method = JdtUtils.findMethod(
                    type, "process", null);
            var refs = ReferenceCollector.collect(method);
            String json = SourceReport.toJson(
                    "test.service.AnimalService#process(Animal)",
                    method, "D:/test/AnimalService.java",
                    method.getSource(), 1, 10, refs, null);
            // Animal#name() should have typeKind:interface
            assertTrue(json.contains(
                    "\"typeKind\":\"interface\""),
                    "Should have interface typeKind: " + json);
        }
    }
}
