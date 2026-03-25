package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests that source output preserves original indentation.
 * Eclipse's IMember.getSource() strips leading whitespace
 * from the first line. Our handler must not.
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class SourceIndentTest {

    private static final SearchHandler handler = new SearchHandler();

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception { TestFixture.destroy(); }

    // ---- Eclipse API behavior (documenting the bug we work around) ----

    @Test
    void eclipseGetSourceStripsLeadingIndent() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        IMethod bark = type.getMethod("bark", new String[0]);
        String raw = bark.getSource();
        assertNotNull(raw);
        assertTrue(raw.startsWith("public"),
                "Eclipse getSource() strips indent: " + raw);
    }

    @Test
    void fullSourceHasIndent() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        ICompilationUnit cu = type.getCompilationUnit();
        String full = cu.getSource();
        assertTrue(full.contains("    public void bark()"),
                "Full source should have 4-space indent");
    }

    @Test
    void sourceRangeOffsetPointsToContent() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        IMethod bark = type.getMethod("bark", new String[0]);
        ISourceRange range = bark.getSourceRange();
        ICompilationUnit cu = type.getCompilationUnit();
        String full = cu.getSource();
        char atOffset = full.charAt(range.getOffset());
        assertEquals('p', atOffset,
                "Offset points to 'p' of 'public', not whitespace");
    }

    @Test
    void fullSourceCharBeforeOffsetIsWhitespace() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        IMethod bark = type.getMethod("bark", new String[0]);
        ISourceRange range = bark.getSourceRange();
        ICompilationUnit cu = type.getCompilationUnit();
        String full = cu.getSource();
        int offset = range.getOffset();
        // Characters before offset (on same line) should be spaces
        int lineStart = full.lastIndexOf('\n', offset - 1);
        String indent = full.substring(lineStart + 1, offset);
        assertTrue(indent.isBlank() && !indent.isEmpty(),
                "Should have whitespace before offset: ["
                        + indent + "]");
    }

    // ---- Our handler output ----

    @Test
    void methodSourceHasLeadingIndent() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "bark"));
        var parsed = Json.parse(resp.body());
        String source = Json.getString(parsed, "source");
        assertNotNull(source);
        assertFalse(source.startsWith("public"),
                "Must NOT start with 'public' (stripped)");
        assertTrue(source.startsWith("    "),
                "Must start with 4 spaces: ["
                        + source.substring(0,
                                Math.min(20, source.length()))
                        + "]");
    }

    @Test
    void fieldSourceHasLeadingIndent() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        IField field = type.getField("age");
        assertTrue(field.exists(), "Dog should have 'age' field");

        // Verify via Eclipse API that indent exists
        ICompilationUnit cu = type.getCompilationUnit();
        String full = cu.getSource();
        assertTrue(full.contains("    private int age"),
                "Full source has indented field");

        // Verify getSource strips it
        String raw = field.getSource();
        assertTrue(raw.startsWith("private"),
                "getSource strips indent from field too");
    }

    @Test
    void classSourceStartsAtColumnZero() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog"));
        var parsed = Json.parse(resp.body());
        String source = Json.getString(parsed, "source");
        assertNotNull(source);
        // Top-level class starts at column 0 — no indent expected
        assertTrue(source.startsWith("package")
                || source.startsWith("public")
                || source.startsWith("/**"),
                "Class source starts at column 0: ["
                        + source.substring(0,
                                Math.min(20, source.length()))
                        + "]");
    }

    @Test
    void methodSourceContainsFullBody() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "bark"));
        var parsed = Json.parse(resp.body());
        String source = Json.getString(parsed, "source");
        assertTrue(source.contains("Woof!"),
                "Should contain method body");
        assertTrue(source.contains("}"),
                "Should contain closing brace");
    }

    @Test
    void sourceMatchesFileLineRange() throws Exception {
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.model.Dog",
                        "method", "bark"));
        var parsed = Json.parse(resp.body());
        String source = Json.getString(parsed, "source");
        int startLine = ((Number) parsed.get("startLine"))
                .intValue();
        int endLine = ((Number) parsed.get("endLine"))
                .intValue();
        // Line count in source should match range
        long lineCount = source.chars()
                .filter(c -> c == '\n').count();
        // endLine - startLine + 1 gives the line span
        // source might have trailing newline
        assertTrue(lineCount >= (endLine - startLine),
                "Source lines (" + lineCount
                        + ") should span range "
                        + startLine + "-" + endLine);
    }

    // ---- getFullSource invariants ----

    @Test
    void getFullSourceMatchesDiskFile() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        ICompilationUnit cu = type.getCompilationUnit();
        assertNotNull(cu, "Dog should have compilation unit");
        String fullSource = cu.getSource();
        assertNotNull(fullSource, "fullSource should not be null");

        // Read from disk
        var resource = cu.getResource();
        assertNotNull(resource, "Should have resource");
        var location = resource.getLocation();
        assertNotNull(location, "Should have location");
        String disk = Files.readString(
                Path.of(location.toOSString()));

        assertEquals(disk, fullSource,
                "fullSource must be byte-exact match with file");
    }

    @Test
    void getFullSourceContainsAllMembers() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        String full = type.getCompilationUnit().getSource();
        assertTrue(full.contains("package test.model"),
                "Should contain package declaration");
        assertTrue(full.contains("public class Dog"),
                "Should contain class declaration");
        assertTrue(full.contains("private int age"),
                "Should contain field");
        assertTrue(full.contains("public void bark()"),
                "Should contain method");
        assertTrue(full.contains("public String name()"),
                "Should contain name method");
    }

    @Test
    void getFullSourcePreservesIndentAtEveryLine() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        String full = type.getCompilationUnit().getSource();
        String[] lines = full.split("\n");
        // Find bark method line
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("public void bark()")) {
                assertTrue(lines[i].startsWith("    "),
                        "bark() at line " + (i + 1)
                                + " must have indent: ["
                                + lines[i] + "]");
                return;
            }
        }
        fail("bark() not found in fullSource");
    }

    @Test
    void getFullSourceForBinaryReturnsSourceOrNull()
            throws Exception {
        // IType from JDK — binary, may or may not have source
        IType listType = JdtUtils.findType("java.util.ArrayList");
        assertNotNull(listType, "ArrayList should exist");
        assertTrue(listType.isBinary(), "Should be binary");
        IClassFile cf = listType.getClassFile();
        // Source may be null (no src.zip) or non-null
        // Either way, should not throw
        String source = cf != null ? cf.getSource() : null;
        // If source exists, it should contain class declaration
        if (source != null) {
            assertTrue(source.contains("ArrayList"),
                    "Binary source should contain class name");
        }
    }

    @Test
    void substringFromFullSourcePreservesIndent() throws Exception {
        IType type = JdtUtils.findType("test.model.Dog");
        IMethod bark = type.getMethod("bark", new String[0]);
        ISourceRange range = bark.getSourceRange();
        String full = type.getCompilationUnit().getSource();

        // Find line start before offset
        int offset = range.getOffset();
        int lineStart = full.lastIndexOf('\n', offset - 1);
        String fromLineStart = full.substring(
                lineStart + 1,
                offset + range.getLength());

        assertTrue(fromLineStart.startsWith("    "),
                "Substring from line start should have indent: ["
                        + fromLineStart.substring(0,
                                Math.min(30, fromLineStart.length()))
                        + "]");
        assertTrue(fromLineStart.contains("public void bark()"),
                "Should contain method declaration");
        assertTrue(fromLineStart.contains("Woof!"),
                "Should contain method body");
    }

    // ---- binary sources ----

    @Test
    void binaryMethodSourceHasIndent() throws Exception {
        // Source bundle added as Tycho test dependency
        IType type = JdtUtils.findType(
                "org.eclipse.core.resources.IResource");
        assertNotNull(type);
        assertTrue(type.isBinary());
        IClassFile cf = type.getClassFile();
        assertNotNull(cf);
        String fullSource = cf.getSource();
        assertNotNull(fullSource,
                "Source bundle must provide source");

        HttpServer.Response resp = handler.handleSource(
                Map.of("class",
                        "org.eclipse.core.resources.IResource",
                        "method", "getLocation"));
        var parsed = Json.parse(resp.body());
        String source = Json.getString(parsed, "source");
        assertNotNull(source, "Should have source");
        assertTrue(source.startsWith("\t"),
                "Binary method must have tab indent: ["
                        + source.substring(0,
                                Math.min(20, source.length()))
                        + "]");
    }

    // ---- overloads ----

    @Test
    void overloadedMethodSourceHasIndent() throws Exception {
        // Calculator has overloaded add() methods
        HttpServer.Response resp = handler.handleSource(
                Map.of("class", "test.edge.Calculator",
                        "method", "add"));
        // Returns JSON array for overloads
        String body = resp.body();
        assertTrue(body.startsWith("["),
                "Should be array: " + body);
        // Each overload source should have indent
        assertFalse(body.contains("\"source\":\"public"),
                "No overload should start with 'public' "
                        + "(indent stripped)");
    }
}
