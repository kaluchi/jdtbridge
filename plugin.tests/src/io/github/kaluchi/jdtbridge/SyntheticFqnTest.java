package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SyntheticFqnTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    private static <T extends IJavaElement> T assertResolves(
            String fqn, Class<T> kind) throws Exception {
        IJavaElement resolved = JdtUtils.resolveElement(fqn);
        assertNotNull(resolved, "must resolve: " + fqn);
        assertTrue(kind.isInstance(resolved),
                "expected " + kind.getSimpleName() + " for " + fqn
                + ", got " + resolved.getClass().getSimpleName());
        return kind.cast(resolved);
    }

    // ── NodeBuilder.fqnOf emits composite FQNs ──────────────────────

    @Test
    void fqnOfAnonymousTypeEmitsCompositeForm() throws Exception {
        IType type = JdtUtils.findType(
                "test.service.AnonymousCallerService");
        IMethod createAnon = JdtUtils.findMethod(
                type, "createAnonymous", null);
        IType anon = (IType) createAnon.getChildren()[0];
        assertTrue(anon.isAnonymous(),
                "First child must be an anonymous IType");
        assertEquals(
                "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}",
                NodeBuilder.fqnOf(anon));
    }

    @Test
    void fqnOfLambdaTypeEmitsCompositeForm() throws Exception {
        String expected = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable";
        IType lambda = assertResolves(expected, IType.class);
        assertTrue(lambda.isLambda());
        assertEquals(expected, NodeBuilder.fqnOf(lambda));
    }

    @Test
    void methodInsideLambdaCarriesCompositeFqn() throws Exception {
        String expected = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable#run()";
        IMethod run = assertResolves(expected, IMethod.class);
        assertEquals("run", run.getElementName());
        assertTrue(run.getDeclaringType().isLambda());
        assertEquals(expected, NodeBuilder.fqnOf(run));
    }

    // ── JdtUtils.resolveElement round-trips every composite form ────

    @Test
    void resolveElementRoundTripsAnonymousType() throws Exception {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}";
        IType type = assertResolves(fqn, IType.class);
        assertTrue(type.isAnonymous());
        assertEquals(fqn, NodeBuilder.fqnOf(type));
    }

    @Test
    void resolveElementRoundTripsAnonymousMethod() throws Exception {
        String fqn = "test.service.AnonymousCallerService"
                + "#createAnonymous().new Animal() {...}#name()";
        IMethod method = assertResolves(fqn, IMethod.class);
        assertEquals("name", method.getElementName());
        assertTrue(method.getDeclaringType().isAnonymous());
        assertEquals(fqn, NodeBuilder.fqnOf(method));
    }

    // ── Error paths ─────────────────────────────────────────────────

    @Test
    void resolveElementReturnsNullForUnknownEnclosingMember()
            throws Exception {
        assertNull(JdtUtils.resolveElement(
                "test.service.LambdaCallerService"
                + "#doesNotExist().() -> {...} Runnable"));
    }

    @Test
    void resolveElementReturnsNullForUnmatchedSuffix()
            throws Exception {
        assertNull(JdtUtils.resolveElement(
                "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Callable"));
    }

    @Test
    void resolveElementReturnsNullForUnknownInnerMember()
            throws Exception {
        assertNull(JdtUtils.resolveElement(
                "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable#typo()"));
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
