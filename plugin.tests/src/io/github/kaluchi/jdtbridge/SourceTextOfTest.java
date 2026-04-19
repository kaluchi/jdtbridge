package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link NodeBuilder#sourceTextOf(
 * org.eclipse.jdt.core.IMember)}. Exercises the line-boundary
 * slicing path — full-line output for member declarations,
 * separator preservation for CRLF vs LF files, full compilation
 * unit for top-level types.
 */
public class SourceTextOfTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception { TestFixture.destroy(); }

    /**
     * Create a one-off compilation unit in a dedicated package so
     * we can seed it with whatever line separator we want.
     */
    private static IType createType(String pkgName, String cuName,
            String source) throws Exception {
        IProject project = org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot().getProject("jdtbridge-test");
        IJavaProject jp = JavaCore.create(project);
        IPackageFragmentRoot srcRoot = null;
        for (IPackageFragmentRoot r : jp.getPackageFragmentRoots()) {
            if (r.getKind() == IPackageFragmentRoot.K_SOURCE) {
                srcRoot = r;
                break;
            }
        }
        assertNotNull(srcRoot, "fixture src root");
        IPackageFragment pkg = srcRoot.createPackageFragment(
                pkgName, true, null);
        var cu = pkg.createCompilationUnit(cuName, source, true, null);
        return cu.getTypes()[0];
    }

    @Test
    void lfFilePreservesLfLineSeparators() throws Exception {
        String src = "package test.sep.lf;\n"
                + "public class LfType {\n"
                + "    public void greet() {\n"
                + "        System.out.println(\"hi\");\n"
                + "    }\n"
                + "}\n";
        IType type = createType("test.sep.lf", "LfType.java", src);
        IMethod greet = type.getMethods()[0];

        String text = NodeBuilder.sourceTextOf(greet);
        assertNotNull(text);
        assertTrue(text.contains("\n"), "LF expected in output");
        assertFalse(text.contains("\r"),
                "LF-authored file must NOT contain CR in result");
        assertTrue(text.contains("public void greet()"),
                "body contains the declaration");
    }

    @Test
    void crlfFilePreservesCrlfLineSeparators() throws Exception {
        String src = "package test.sep.crlf;\r\n"
                + "public class CrlfType {\r\n"
                + "    public void greet() {\r\n"
                + "        System.out.println(\"hi\");\r\n"
                + "    }\r\n"
                + "}\r\n";
        IType type = createType("test.sep.crlf", "CrlfType.java", src);
        IMethod greet = type.getMethods()[0];

        String text = NodeBuilder.sourceTextOf(greet);
        assertNotNull(text);
        assertTrue(text.contains("\r\n"),
                "CRLF-authored file must keep CRLF in the sliced "
                + "output — the previous implementation stripped "
                + "line terminators via Files.readAllLines and "
                + "forced LF, breaking byte-exactness");
        assertTrue(text.contains("public void greet()"));
    }

    @Test
    void firstLineOfMemberIncludesLeadingIndentation() throws Exception {
        String src = "package test.sep.indent;\n"
                + "public class IndentType {\n"
                + "    public void greet() {\n"
                + "        System.out.println(\"hi\");\n"
                + "    }\n"
                + "}\n";
        IType type = createType("test.sep.indent", "IndentType.java", src);
        IMethod greet = type.getMethods()[0];

        String text = NodeBuilder.sourceTextOf(greet);
        assertNotNull(text);
        // Declaration starts at column 4 (four-space indent).
        // Line-boundary slicing must include the leading spaces —
        // Eclipse's IMember.getSource() drops them, which is the
        // behaviour this method exists to correct.
        assertTrue(text.startsWith("    public void greet()"),
                "first line must carry leading indentation, got ["
                + text.substring(0, Math.min(40, text.length())) + "]");
    }

    @Test
    void lastLineOfMemberEndsWithLineTerminator() throws Exception {
        String src = "package test.sep.tail;\n"
                + "public class TailType {\n"
                + "    public void greet() {\n"
                + "        System.out.println(\"hi\");\n"
                + "    }\n"
                + "}\n";
        IType type = createType("test.sep.tail", "TailType.java", src);
        IMethod greet = type.getMethods()[0];

        String text = NodeBuilder.sourceTextOf(greet);
        assertNotNull(text);
        // Line-boundary slicing ends immediately past the closing
        // brace's newline, so output always terminates with \n
        // (or \r\n when the source is CRLF).
        assertTrue(text.endsWith("\n"),
                "tail must end with line terminator, got ["
                + text.substring(Math.max(0, text.length() - 10))
                + "]");
    }

    @Test
    void topLevelTypeReturnsFullCompilationUnit() throws Exception {
        String src = "package test.sep.full;\n"
                + "import java.util.List;\n"
                + "public class FullType {\n"
                + "    int x;\n"
                + "}\n";
        IType type = createType("test.sep.full", "FullType.java", src);

        String text = NodeBuilder.sourceTextOf(type);
        assertEquals(src, text,
                "top-level type returns the whole compilation unit "
                + "verbatim — package line, imports, type body");
    }
}
