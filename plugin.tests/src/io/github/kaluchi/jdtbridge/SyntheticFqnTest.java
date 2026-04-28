package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round-trip coverage for the lambda / anonymous synthetic-FQN
 * convention. The NodeBuilder emits composite FQNs for refs whose
 * true origin is inside a lambda body or anonymous-class body
 * (e.g. {@code Outer#enclose(Args).() -> {...} Iface}); JdtUtils
 * must parse the same form back into the original IType / IMethod
 * so every fqn surfaced in a skeleton can be fed into
 * {@code @type / @method / @source} for navigation.
 *
 * Fixture anchors:
 * <ul>
 *   <li>{@code test.service.LambdaCallerService#createLambda()}
 *       contains a single {@code Runnable} lambda.</li>
 *   <li>{@code test.service.AnonymousCallerService#createAnonymous()}
 *       contains a single anonymous {@code Animal} class.</li>
 * </ul>
 */
public class SyntheticFqnTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    private static IMethod enclosingMethod(String typeFqn,
            String methodName) throws Exception {
        IType type = JdtUtils.findType(typeFqn);
        assertNotNull(type, "type missing: " + typeFqn);
        IMethod m = JdtUtils.findMethod(type, methodName, null);
        assertNotNull(m, "method missing: "
                + typeFqn + "#" + methodName);
        return m;
    }

    private static IType syntheticAnonymousChild(IMethod enclosing)
            throws Exception {
        // Anonymous types — but not lambdas — appear in
        // IParent.getChildren(). Used for the anonymous-specific
        // fqnOf emission test; lambda tests go through
        // resolveElement (AST-resolved).
        for (IJavaElement child : enclosing.getChildren()) {
            if (child instanceof IType t && t.isAnonymous()) return t;
        }
        return null;
    }

    /**
     * Resolve {@code fqn} via {@link JdtUtils#resolveElement} and
     * assert the returned element is of the expected kind. Round-
     * trip helper — every resolveElement test follows the shape:
     * resolve → verify kind → rebuild fqn → assertEquals.
     */
    private static <T extends IJavaElement> T assertResolves(
            String fqn, Class<T> kind) throws Exception {
        IJavaElement resolved = JdtUtils.resolveElement(fqn);
        assertTrue(kind.isInstance(resolved),
                "expected " + kind.getSimpleName() + " for " + fqn
                + ", got " + (resolved == null ? "null"
                        : resolved.getClass().getSimpleName()));
        return kind.cast(resolved);
    }

    private static String fqnOf(IJavaElement e) throws JavaModelException {
        if (e instanceof IType t) return NodeBuilder.fqnOf(t);
        if (e instanceof IMethod m) return NodeBuilder.fqnOf(m);
        if (e instanceof IField f) return NodeBuilder.fqnOf(f);
        throw new IllegalArgumentException(
                "unsupported kind: " + e.getClass().getSimpleName());
    }

    // ── NodeBuilder.fqnOf emits composite FQNs ──────────────────────

    @Test
    void fqnOfAnonymousTypeEmitsCompositeForm() throws Exception {
        IMethod createAnon = enclosingMethod(
                "test.service.AnonymousCallerService",
                "createAnonymous");
        IType anon = syntheticAnonymousChild(createAnon);
        assertNotNull(anon,
                "createAnonymous() must declare an anonymous IType");
        assertTrue(anon.isAnonymous());

        String fqn = NodeBuilder.fqnOf(anon);
        assertEquals(
                "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}",
                fqn);
    }

    @Test
    void fqnOfLambdaTypeEmitsCompositeForm() throws Exception {
        // Lambdas aren't accessible through IParent.getChildren() —
        // their ITypes materialise only through AST binding. Round-
        // trip anchors every lambda-side test: resolve the expected
        // fqn and verify the rebuilt fqn equals the input.
        String expected = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable";
        IType lambda = assertResolves(expected, IType.class);
        assertTrue(lambda.isLambda());
        assertEquals(expected, fqnOf(lambda));
    }

    @Test
    void methodInsideLambdaCarriesCompositeFqn() throws Exception {
        String expected = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable#run()";
        IMethod run = assertResolves(expected, IMethod.class);
        assertEquals("run", run.getElementName());
        assertTrue(run.getDeclaringType().isLambda());
        assertEquals(expected, fqnOf(run));
    }

    // ── JdtUtils.resolveElement round-trips every composite form ────

    @Test
    void resolveElementRoundTripsAnonymousType() throws Exception {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}";
        IType type = assertResolves(fqn, IType.class);
        assertTrue(type.isAnonymous());
        assertEquals(fqn, fqnOf(type));
    }

    @Test
    void resolveElementRoundTripsAnonymousMethod() throws Exception {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}#name()";
        IMethod method = assertResolves(fqn, IMethod.class);
        assertEquals("name", method.getElementName());
        assertTrue(method.getDeclaringType().isAnonymous());
        assertEquals(fqn, fqnOf(method));
    }

    // ── Error paths ─────────────────────────────────────────────────

    @Test
    void resolveElementReturnsNullForUnknownEnclosingMember() throws Exception {
        String fqn = "test.service.LambdaCallerService"
                + "#doesNotExist().() -> {...} Runnable";
        assertNull(JdtUtils.resolveElement(fqn));
    }

    @Test
    void resolveElementReturnsNullForUnmatchedSuffix() throws Exception {
        // interface name swapped — no matching synthetic type
        String fqn = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Callable";
        assertNull(JdtUtils.resolveElement(fqn));
    }

    @Test
    void resolveElementReturnsNullForUnknownInnerMember() throws Exception {
        String fqn = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable#typo()";
        assertNull(JdtUtils.resolveElement(fqn));
    }

    // ── Non-synthetic FQN still routes through resolveElement ───────

    @Test
    void resolveElementHandlesRegularTypeFqn() throws Exception {
        assertEquals("Dog",
                assertResolves("test.model.Dog", IType.class)
                        .getElementName());
    }

    @Test
    void resolveElementHandlesRegularMethodFqn() throws Exception {
        assertEquals("bark",
                assertResolves("test.model.Dog#bark()", IMethod.class)
                        .getElementName());
    }
}
