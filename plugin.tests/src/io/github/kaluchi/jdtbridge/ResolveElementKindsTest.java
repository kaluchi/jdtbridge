package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JdtUtils#resolveElement} extension covering the
 * non-member, non-synthetic kinds (project, package, file). Type
 * resolution and synthetic-fqn paths live in their own test files.
 */
public class ResolveElementKindsTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    // -- type still wins when fqn has no '#' ----------------------

    @Test
    void typeFqnResolvesToIType() throws Exception {
        IJavaElement el = JdtUtils.resolveElement(
                "test.model.Animal");
        assertInstanceOf(IType.class, el);
        assertEquals("test.model.Animal",
                ((IType) el).getFullyQualifiedName());
    }

    @Test
    void nestedTypeFqnResolvesToIType() throws Exception {
        IJavaElement el = JdtUtils.resolveElement(
                "test.edge.Outer.Inner");
        assertInstanceOf(IType.class, el);
        // Eclipse's IType.getFullyQualifiedName() uses '$' for nested
        // types; the dotted form goes through getFullyQualifiedName('.').
        assertEquals("test.edge.Outer.Inner",
                ((IType) el).getFullyQualifiedName('.'));
    }

    // -- project ---------------------------------------------------

    @Test
    void projectNameResolvesToIJavaProject() throws Exception {
        IJavaElement el = JdtUtils.resolveElement(
                TestFixture.PROJECT_NAME);
        assertInstanceOf(IJavaProject.class, el);
        assertEquals(TestFixture.PROJECT_NAME,
                ((IJavaProject) el).getElementName());
    }

    @Test
    void unknownProjectReturnsNull() throws Exception {
        IJavaElement el = JdtUtils.resolveElement(
                "no-such-project-name-zzz");
        assertNull(el);
    }

    // -- package ---------------------------------------------------

    @Test
    void packageFqnResolvesToIPackageFragment() throws Exception {
        IJavaElement el = JdtUtils.resolveElement("test.model");
        assertInstanceOf(IPackageFragment.class, el);
        assertEquals("test.model",
                ((IPackageFragment) el).getElementName());
    }

    @Test
    void deeperPackageFqnAlsoResolves() throws Exception {
        IJavaElement el = JdtUtils.resolveElement("test.service");
        assertInstanceOf(IPackageFragment.class, el);
        assertEquals("test.service",
                ((IPackageFragment) el).getElementName());
    }

    @Test
    void unknownPackageReturnsNull() throws Exception {
        IJavaElement el = JdtUtils.resolveElement(
                "no.such.package.zzz");
        assertNull(el);
    }

    // -- backward-compat: existing callers' instanceof checks ------

    @Test
    void projectFqnDoesNotResolveAsType() throws Exception {
        // findType on a project name must keep returning null —
        // GraphHandler.findTypeRaw / handleType callers all check
        // 'instanceof IType'; a project IJavaElement must not slip
        // through.
        IType type = JdtUtils.findType(TestFixture.PROJECT_NAME);
        assertNull(type);
    }

    @Test
    void packageFqnDoesNotResolveAsType() throws Exception {
        IType type = JdtUtils.findType("test.model");
        assertNull(type);
    }

    // -- priority: type fqn that overlaps a package name ----------

    @Test
    void typePriorityOverContainerKinds() throws Exception {
        // When a type name happens to match a string that could also
        // be a project / package, type wins (it's checked first in
        // resolveContainerOrType). The fixture has type
        // test.edge.Calculator and a package test.edge — the type
        // path "test.edge.Calculator" must still hand back IType.
        IJavaElement el = JdtUtils.resolveElement(
                "test.edge.Calculator");
        assertInstanceOf(IType.class, el);
    }

    // -- file path heuristic --------------------------------------

    @Test
    void plainDottedNameIsNotTreatedAsFilePath() throws Exception {
        // 'no.such.thing' has no path separator and no drive prefix,
        // so the file branch is skipped and the call returns null
        // after type / project / package miss. Without the heuristic
        // guard a workspace.getFileForLocation lookup would still
        // return null but cost more.
        assertNull(JdtUtils.resolveElement("no.such.thing"));
    }

    @Test
    void unixAbsolutePathTriggersFileBranch() throws Exception {
        // Path doesn't exist in this temp workbench, so result is
        // null — what we care about here is that the call doesn't
        // throw and the file branch was taken (covered by
        // looksLikeFilePath returning true).
        assertNull(JdtUtils.resolveElement(
                "/no/such/file/here.java"));
    }

    @Test
    void windowsAbsolutePathTriggersFileBranch() throws Exception {
        assertNull(JdtUtils.resolveElement(
                "X:\\no\\such\\file\\here.java"));
    }

    @Test
    void emptyStringResolvesToNull() throws Exception {
        // Defensive: empty fqn must not crash any branch.
        assertNull(JdtUtils.resolveElement(""));
    }

    @Test
    void unknownFqnResolvesToNull() throws Exception {
        assertNull(JdtUtils.resolveElement("totally.unknown.thing"));
    }
}
