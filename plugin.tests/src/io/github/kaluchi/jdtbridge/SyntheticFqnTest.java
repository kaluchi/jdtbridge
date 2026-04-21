package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IParent;
import org.eclipse.jdt.core.IType;
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
        // their ITypes materialise only through AST binding.
        // Round-trip: resolve expected fqn → IType → rebuild fqn.
        String expected = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable";
        IJavaElement resolved = JdtUtils.resolveElement(expected);
        assertTrue(resolved instanceof IType,
                "expected a lambda IType, got "
                + (resolved == null ? "null"
                        : resolved.getClass().getSimpleName()));
        IType lambda = (IType) resolved;
        assertTrue(lambda.isLambda());
        assertEquals(expected, NodeBuilder.fqnOf(lambda));
    }

    @Test
    void methodInsideLambdaCarriesCompositeFqn() throws Exception {
        // The SAM method on a lambda IType is accessible via
        // resolveElement on the composite method fqn — go through
        // the same entry users rely on, not via IType.getMethod
        // which does not populate on AST-materialised lambdas.
        String expected = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable#run()";
        IJavaElement resolved = JdtUtils.resolveElement(expected);
        assertTrue(resolved instanceof IMethod,
                "expected IMethod, got "
                + (resolved == null ? "null"
                        : resolved.getClass().getSimpleName()));
        IMethod run = (IMethod) resolved;
        assertEquals("run", run.getElementName());
        assertTrue(run.getDeclaringType().isLambda());
        assertEquals(expected, NodeBuilder.fqnOf(run));
    }

    // ── JdtUtils.resolveElement round-trips every composite form ────

    @Test
    void resolveElementRoundTripsLambdaType() throws Exception {
        String fqn = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable";
        IJavaElement resolved = JdtUtils.resolveElement(fqn);
        assertTrue(resolved instanceof IType,
                "expected IType, got "
                + (resolved == null ? "null"
                        : resolved.getClass().getSimpleName()));
        IType type = (IType) resolved;
        assertTrue(type.isLambda());
        // Round-trip identity: rebuilt fqn equals input.
        assertEquals(fqn, NodeBuilder.fqnOf(type));
    }

    @Test
    void resolveElementRoundTripsAnonymousType() throws Exception {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}";
        IJavaElement resolved = JdtUtils.resolveElement(fqn);
        assertTrue(resolved instanceof IType);
        IType type = (IType) resolved;
        assertTrue(type.isAnonymous());
        assertEquals(fqn, NodeBuilder.fqnOf(type));
    }

    @Test
    void resolveElementRoundTripsLambdaMethod() throws Exception {
        String fqn = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable#run()";
        IJavaElement resolved = JdtUtils.resolveElement(fqn);
        assertTrue(resolved instanceof IMethod,
                "expected IMethod, got "
                + (resolved == null ? "null"
                        : resolved.getClass().getSimpleName()));
        IMethod method = (IMethod) resolved;
        assertEquals("run", method.getElementName());
        assertTrue(method.getDeclaringType().isLambda());
        // Round-trip through fqnOf keeps the full composite form.
        assertEquals(fqn, NodeBuilder.fqnOf(method));
    }

    @Test
    void resolveElementRoundTripsAnonymousMethod() throws Exception {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}#name()";
        IJavaElement resolved = JdtUtils.resolveElement(fqn);
        assertTrue(resolved instanceof IMethod);
        IMethod method = (IMethod) resolved;
        assertEquals("name", method.getElementName());
        assertTrue(method.getDeclaringType().isAnonymous());
        assertEquals(fqn, NodeBuilder.fqnOf(method));
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
        IJavaElement resolved = JdtUtils.resolveElement(
                "test.model.Dog");
        assertTrue(resolved instanceof IType);
        assertEquals("Dog", resolved.getElementName());
    }

    @Test
    void resolveElementHandlesRegularMethodFqn() throws Exception {
        IJavaElement resolved = JdtUtils.resolveElement(
                "test.model.Dog#bark()");
        assertTrue(resolved instanceof IMethod);
        assertEquals("bark", resolved.getElementName());
    }
}
