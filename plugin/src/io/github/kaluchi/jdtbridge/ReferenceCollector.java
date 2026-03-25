package io.github.kaluchi.jdtbridge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * Collects resolved references from a Java source member using
 * Eclipse AST with binding resolution. Each reference maps to
 * an {@link IJavaElement} that can be navigated with jdt source.
 */
class ReferenceCollector {

    /** A resolved reference from the source. */
    record Ref(String fqmn, IJavaElement element, RefKind kind) {}

    enum RefKind { FIELD, METHOD, TYPE, CONSTANT }

    /**
     * Collect all references within the given member's source range.
     * Returns a deduped map keyed by FQMN.
     */
    static Map<String, Ref> collect(IMember member)
            throws JavaModelException {
        var cu = parseWithBindings(member);
        if (cu == null) return Map.of();

        ISourceRange range = member.getSourceRange();
        if (range == null) return Map.of();

        int start = range.getOffset();
        int end = start + range.getLength();
        IType declaringType = member instanceof IType t
                ? t : member.getDeclaringType();
        String declaringFqn = declaringType != null
                ? declaringType.getFullyQualifiedName() : "";

        var refs = new LinkedHashMap<String, Ref>();

        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                int pos = node.getStartPosition();
                if (pos < start || pos > end) return false;
                if (node.isDeclaration()) return false;

                IBinding binding = node.resolveBinding();
                if (binding == null) return false;

                switch (binding) {
                    case IVariableBinding vb -> {
                        if (vb.isField()) {
                            addField(vb, refs, declaringFqn);
                        }
                    }
                    case IMethodBinding mb -> {
                        addMethod(mb, refs, declaringFqn);
                    }
                    case ITypeBinding tb -> {
                        addType(tb, refs, declaringFqn);
                    }
                    default -> {}
                }
                return false;
            }
        });

        return refs;
    }

    private static boolean isJdkType(String fqn) {
        return stripGenerics(fqn).startsWith("java.");
    }

    private static String stripGenerics(String fqn) {
        int angle = fqn.indexOf('<');
        return angle >= 0 ? fqn.substring(0, angle) : fqn;
    }

    private static void addField(IVariableBinding vb,
            Map<String, Ref> refs, String declaringFqn) {
        ITypeBinding declType = vb.getDeclaringClass();
        if (declType == null) return;
        String typeFqn = stripGenerics(
                declType.getQualifiedName());
        if (isJdkType(typeFqn)) return;
        String fqmn = typeFqn + "#" + vb.getName();
        if (refs.containsKey(fqmn)) return;

        IJavaElement elem = vb.getJavaElement();
        boolean isConst = java.lang.reflect.Modifier.isStatic(
                vb.getModifiers())
                && java.lang.reflect.Modifier.isFinal(
                        vb.getModifiers());
        refs.put(fqmn, new Ref(fqmn, elem,
                isConst ? RefKind.CONSTANT : RefKind.FIELD));
    }

    private static void addMethod(IMethodBinding mb,
            Map<String, Ref> refs, String declaringFqn) {
        ITypeBinding declType = mb.getDeclaringClass();
        if (declType == null) return;
        String typeFqn = stripGenerics(
                declType.getQualifiedName());
        if (isJdkType(typeFqn)) return;
        String fqmn = typeFqn + "#" + mb.getName()
                + "(" + paramSignature(mb) + ")";
        if (refs.containsKey(fqmn)) return;

        IJavaElement elem = mb.getJavaElement();
        refs.put(fqmn, new Ref(fqmn, elem, RefKind.METHOD));
    }

    private static void addType(ITypeBinding tb,
            Map<String, Ref> refs, String declaringFqn) {
        if (tb.isPrimitive() || tb.isArray()) return;
        String fqn = stripGenerics(tb.getQualifiedName());
        if (fqn.isEmpty() || fqn.equals(declaringFqn)) return;
        if (isJdkType(fqn)) return;
        if (refs.containsKey(fqn)) return;

        IJavaElement elem = tb.getJavaElement();
        refs.put(fqn, new Ref(fqn, elem, RefKind.TYPE));
    }

    private static String paramSignature(IMethodBinding mb) {
        var params = mb.getParameterTypes();
        if (params.length == 0) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(params[i].getName());
        }
        return sb.toString();
    }

    private static CompilationUnit parseWithBindings(
            IMember member) {
        var cu = member.getCompilationUnit();
        var cf = cu == null ? member.getClassFile() : null;

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setResolveBindings(true);
        if (cu != null) {
            parser.setSource(cu);
        } else if (cf != null) {
            parser.setSource(cf);
        } else {
            return null;
        }
        return (CompilationUnit) parser.createAST(null);
    }
}
