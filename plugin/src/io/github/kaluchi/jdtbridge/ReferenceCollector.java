package io.github.kaluchi.jdtbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * Collects resolved references from a Java source member using
 * Eclipse AST with binding resolution. Each reference maps to
 * an {@link IJavaElement} that can be navigated with jdt source.
 */
class ReferenceCollector {

    /**
     * A resolved reference with binding-derived metadata.
     * All type/bound resolution happens here at AST visit time,
     * so SourceReport doesn't need the binding.
     */
    record Ref(
            String fqmn,
            IJavaElement element,
            RefKind kind,
            String declaringTypeKind,
            boolean isStatic,
            String resolvedType,
            String resolvedTypeFqn,
            String resolvedTypeKind,
            boolean isTypeVariable,
            String typeBound,
            boolean isInherited,
            String inheritedFrom,
            String implementationOf) {}

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
                        String receiverFqn =
                                resolveReceiver(node);
                        addMethod(mb, refs, declaringFqn,
                                receiverFqn);
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

    /**
     * For each outgoing interface method call, resolve workspace +
     * classpath implementations and add them as refs with
     * implementationOf linking back to the interface method.
     */
    static void resolveImplementations(
            Map<String, Ref> refs) {
        List<Ref> implRefs = new ArrayList<>();
        for (Ref ref : refs.values()) {
            if (ref.kind() != RefKind.METHOD) continue;
            if (!"interface".equals(ref.declaringTypeKind()))
                continue;
            if (ref.element() == null) continue;

            try {
                IMethod ifaceMethod = (IMethod) ref.element();
                IType ifaceType = ifaceMethod.getDeclaringType();
                if (ifaceType == null) continue;

                String methodName =
                        ifaceMethod.getElementName();
                String ifaceSig =
                        paramSig(ifaceMethod);
                ITypeHierarchy hierarchy =
                        ifaceType.newTypeHierarchy(null);

                for (IType sub
                        : hierarchy.getAllSubtypes(ifaceType)) {
                    if (sub.isAnonymous()) continue;
                    for (IMethod m : sub.getMethods()) {
                        if (!m.getElementName()
                                .equals(methodName)) continue;
                        if (!paramSig(m).equals(ifaceSig))
                            continue;

                        String subFqn =
                                sub.getFullyQualifiedName();
                        if (isJdkType(subFqn)) continue;
                        String implFqmn = subFqn + "#"
                                + methodName + "("
                                + paramSig(m) + ")";
                        if (refs.containsKey(implFqmn)) break;

                        String typeKind = sub.isInterface()
                                ? "interface"
                                : sub.isEnum() ? "enum"
                                : sub.isAnnotation()
                                ? "annotation" : "class";

                        implRefs.add(new Ref(implFqmn, m,
                                RefKind.METHOD, typeKind,
                                false, null, null, null,
                                false, null, false, null,
                                ref.fqmn()));
                        break;
                    }
                }
            } catch (Exception e) { /* skip */ }
        }
        for (Ref impl : implRefs) {
            refs.put(impl.fqmn(), impl);
        }
    }

    static String paramSig(IMethod m)
            throws JavaModelException {
        var paramTypes = m.getParameterTypes();
        if (paramTypes.length == 0) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(org.eclipse.jdt.core.Signature.toString(
                    paramTypes[i]));
        }
        return sb.toString();
    }

    /** Resolve the receiver type of a method invocation. */
    private static String resolveReceiver(SimpleName node) {
        var parent = node.getParent();
        if (parent instanceof MethodInvocation mi) {
            Expression expr = mi.getExpression();
            if (expr != null) {
                ITypeBinding rcv = expr.resolveTypeBinding();
                if (rcv != null) {
                    return stripGenerics(
                            rcv.getQualifiedName());
                }
            }
        }
        return null;
    }

    private static boolean isJdkType(String fqn) {
        return stripGenerics(fqn).startsWith("java.");
    }

    static String stripGenerics(String fqn) {
        int angle = fqn.indexOf('<');
        return angle >= 0 ? fqn.substring(0, angle) : fqn;
    }

    static String typeKindOf(ITypeBinding tb) {
        if (tb.isInterface()) return "interface";
        if (tb.isEnum()) return "enum";
        if (tb.isAnnotation()) return "annotation";
        if (tb.isClass()) return "class";
        return null;
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
        int mods = vb.getModifiers();
        boolean isStatic = java.lang.reflect.Modifier.isStatic(
                mods);
        boolean isConst = isStatic
                && java.lang.reflect.Modifier.isFinal(mods);
        String declTypeKind = typeKindOf(declType);

        // Resolve field type from binding
        ITypeBinding fieldType = vb.getType();
        String resolvedType = null;
        String resolvedTypeFqn = null;
        String resolvedTypeKind = null;
        boolean isTypeVariable = false;
        String typeBound = null;

        if (fieldType != null) {
            resolvedType = fieldType.getName();
            if (fieldType.isTypeVariable()) {
                isTypeVariable = true;
                typeBound = resolveBound(fieldType);
            } else if (!fieldType.isPrimitive()) {
                resolvedTypeFqn = stripGenerics(
                        fieldType.getQualifiedName());
                resolvedTypeKind = typeKindOf(fieldType);
            }
        }

        refs.put(fqmn, new Ref(fqmn, elem,
                isConst ? RefKind.CONSTANT : RefKind.FIELD,
                declTypeKind, isStatic,
                resolvedType, resolvedTypeFqn, resolvedTypeKind,
                isTypeVariable, typeBound, false, null, null));
    }

    private static void addMethod(IMethodBinding mb,
            Map<String, Ref> refs, String declaringFqn,
            String receiverFqn) {
        ITypeBinding declType = mb.getDeclaringClass();
        if (declType == null) return;
        String typeFqn = stripGenerics(
                declType.getQualifiedName());
        if (isJdkType(typeFqn)) return;
        String fqmn = typeFqn + "#" + mb.getName()
                + "(" + paramSignature(mb) + ")";
        if (refs.containsKey(fqmn)) return;

        IJavaElement elem = mb.getJavaElement();
        boolean isStatic = java.lang.reflect.Modifier.isStatic(
                mb.getModifiers());
        String declTypeKind = typeKindOf(declType);

        // Resolve return type at call site
        ITypeBinding returnType = mb.isConstructor()
                ? null : mb.getReturnType();
        String resolvedType = null;
        String resolvedTypeFqn = null;
        String resolvedTypeKind = null;
        boolean isTypeVariable = false;
        String typeBound = null;

        if (returnType != null) {
            resolvedType = returnType.getName();
            if (returnType.isTypeVariable()) {
                isTypeVariable = true;
                typeBound = resolveBound(returnType);
            } else if (!returnType.isPrimitive()) {
                resolvedTypeFqn = stripGenerics(
                        returnType.getQualifiedName());
                resolvedTypeKind = typeKindOf(returnType);
            }
        }

        // Inherited: declared in ancestor, called on subtype
        boolean isInherited = false;
        String inheritedFrom = null;
        if (receiverFqn != null
                && !receiverFqn.equals(typeFqn)) {
            isInherited = true;
            inheritedFrom = typeFqn;
        }

        refs.put(fqmn, new Ref(fqmn, elem, RefKind.METHOD,
                declTypeKind, isStatic,
                resolvedType, resolvedTypeFqn, resolvedTypeKind,
                isTypeVariable, typeBound,
                isInherited, inheritedFrom, null));
    }

    private static void addType(ITypeBinding tb,
            Map<String, Ref> refs, String declaringFqn) {
        if (tb.isPrimitive() || tb.isArray()) return;
        String fqn = stripGenerics(tb.getQualifiedName());
        if (fqn.isEmpty() || fqn.equals(declaringFqn)) return;
        if (isJdkType(fqn)) return;
        if (refs.containsKey(fqn)) return;

        IJavaElement elem = tb.getJavaElement();
        refs.put(fqn, new Ref(fqn, elem, RefKind.TYPE,
                typeKindOf(tb), false,
                null, null, null,
                false, null, false, null, null));
    }

    /** Resolve upper bound of a type variable, null if Object. */
    private static String resolveBound(ITypeBinding typeVar) {
        ITypeBinding[] bounds = typeVar.getTypeBounds();
        if (bounds.length > 0) {
            String boundFqn = stripGenerics(
                    bounds[0].getQualifiedName());
            if (!"java.lang.Object".equals(boundFqn)) {
                return boundFqn;
            }
        }
        return null;
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
