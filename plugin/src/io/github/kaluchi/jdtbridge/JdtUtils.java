package io.github.kaluchi.jdtbridge;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IParent;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;

/**
 * Shared JDT utilities used by multiple handlers.
 */
public class JdtUtils {

    /** Lambda-suffix marker in a composite synthetic FQN. */
    private static final String LAMBDA_SUFFIX_HEAD = ".() -> {...}";
    /** Anonymous-suffix marker in a composite synthetic FQN. */
    private static final String ANON_SUFFIX_HEAD = ".new ";
    /** Anonymous-suffix tail (after the simple type name). */
    private static final String ANON_SUFFIX_TAIL = "() {...}";

    static IType findType(String fqn) throws JavaModelException {
        IJavaElement resolved = resolveElement(fqn);
        return (resolved instanceof IType t) ? t : null;
    }

    /**
     * Composite synthetic FQN — carries a lambda / anonymous suffix
     * that requires {@link #resolveElement} rather than the plain
     * {@code findType / first-# split} path. Callers guard their
     * legacy resolution branches with this check.
     */
    static boolean isCompositeFqn(String fqn) {
        return fqn.indexOf(LAMBDA_SUFFIX_HEAD) >= 0
                || fqn.indexOf(ANON_SUFFIX_HEAD) >= 0;
    }

    /**
     * Full composite-FQN → IJavaElement resolver. Handles every
     * shape the JDT Bridge fqn convention produces:
     * <ul>
     *   <li>type — {@code pkg.Outer} or {@code pkg.Outer.Inner}</li>
     *   <li>method — {@code pkg.Outer#name(erased,params)}</li>
     *   <li>field — {@code pkg.Outer#name}</li>
     *   <li>synthetic type — {@code pkg.Outer#enclose(Args).() -> {...} Iface}
     *       or {@code pkg.Outer#enclose(Args).new Iface() {...}}</li>
     *   <li>member within synthetic — either of the above with
     *       {@code #name(params)} or {@code #name} appended</li>
     * </ul>
     * Synthetic resolution walks the enclosing member's children
     * for an IType whose {@link #suffixOf synthetic suffix} equals
     * the parsed one; first match wins when multiple identical
     * synthetics share the enclosing method.
     */
    public static IJavaElement resolveElement(String fqn)
            throws JavaModelException {
        int syntheticIdx = syntheticSuffixStart(fqn);
        if (syntheticIdx < 0) return resolveRegular(fqn);

        String enclosingFqn = fqn.substring(0, syntheticIdx);
        String rest = fqn.substring(syntheticIdx + 1);
        int memberHash = rest.indexOf('#');
        String syntheticSuffix = memberHash < 0
                ? rest : rest.substring(0, memberHash);
        String memberPart = memberHash < 0
                ? null : rest.substring(memberHash + 1);

        IJavaElement enclosing = resolveElement(enclosingFqn);
        if (enclosing == null) return null;
        IType syntheticType = findSyntheticChild(
                enclosing, syntheticSuffix);
        if (syntheticType == null) return null;
        if (memberPart == null) return syntheticType;
        return resolveMemberInType(syntheticType, memberPart);
    }

    /**
     * Locate a synthetic IType (anonymous class or lambda) inside
     * an enclosing member. Dispatch by suffix shape — each shape
     * has one authoritative strategy:
     * <ul>
     *   <li>{@code () -> {...}} suffix → lambda, resolved via AST
     *       binding walk; lambdas do not appear in
     *       {@link IParent#getChildren()}.</li>
     *   <li>{@code new …() {...}} suffix → anonymous class,
     *       resolved via {@code getChildren()} on the enclosing
     *       member.</li>
     * </ul>
     */
    private static IType findSyntheticChild(
            IJavaElement enclosing, String suffix)
            throws JavaModelException {
        if (suffix.startsWith("() -> {...}")) {
            return findLambdaViaAst(enclosing, suffix);
        }
        return findAnonymousChild(enclosing, suffix);
    }

    private static IType findAnonymousChild(
            IJavaElement enclosing, String suffix)
            throws JavaModelException {
        if (!(enclosing instanceof IParent parent)) return null;
        for (IJavaElement child : parent.getChildren()) {
            if (child instanceof IType t && matchesSuffix(t, suffix)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Resolve a lambda IType inside an enclosing member by parsing
     * its compilation unit and visiting {@link LambdaExpression}
     * nodes. Each lambda's {@code resolveTypeBinding()} gives an
     * {@link ITypeBinding} whose {@code getJavaElement()} is the
     * lambda IType; the one whose host member equals the caller's
     * {@code enclosing} and whose synthetic suffix equals the
     * requested one wins.
     */
    private static IType findLambdaViaAst(
            IJavaElement enclosing, String suffix)
            throws JavaModelException {
        ICompilationUnit cu = (ICompilationUnit) enclosing
                .getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu == null) return null;
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit root = (CompilationUnit) parser.createAST(null);
        LambdaFinder finder = new LambdaFinder(enclosing, suffix);
        root.accept(finder);
        return finder.match;
    }

    /**
     * AST visitor that captures the first lambda whose host member
     * equals {@code enclosing} and whose Java-Model IType produces
     * the requested {@link #suffixOf synthetic suffix}.
     */
    private static final class LambdaFinder extends ASTVisitor {
        private final IJavaElement enclosing;
        private final String suffix;
        private IType match;

        LambdaFinder(IJavaElement enclosing, String suffix) {
            this.enclosing = enclosing;
            this.suffix = suffix;
        }

        @Override
        public boolean visit(LambdaExpression node) {
            if (match != null) return false;
            if (!inEnclosingMember(node)) return true;
            // resolveMethodBinding() on a lambda gives the SAM
            // IMethod whose declaringType IS the lambda IType.
            // (resolveTypeBinding gives the functional interface,
            // not the lambda's own synthetic type — wrong target.)
            IMethodBinding samBinding = node.resolveMethodBinding();
            if (samBinding == null) return true;
            IJavaElement samJe = samBinding.getJavaElement();
            if (!(samJe instanceof IMethod samMethod)) return true;
            IType candidate = samMethod.getDeclaringType();
            if (candidate == null) return true;
            try {
                if (candidate.isLambda()
                        && suffix.equals(suffixOf(candidate))) {
                    match = candidate;
                    return false;
                }
            } catch (JavaModelException e) {
                // skip this candidate on error — let the traversal
                // continue looking for another.
            }
            return true;
        }

        private boolean inEnclosingMember(ASTNode node) {
            ASTNode n = node.getParent();
            while (n != null) {
                if (n instanceof org.eclipse.jdt.core.dom
                        .MethodDeclaration md) {
                    IMethodBinding mb = md.resolveBinding();
                    if (mb == null) return false;
                    return enclosing.equals(mb.getJavaElement());
                }
                n = n.getParent();
            }
            return false;
        }
    }

    /**
     * Regular (non-synthetic) FQN → IJavaElement. Plain type if
     * no {@code #}, otherwise dispatches to {@link
     * #resolveMemberInType}.
     */
    private static IJavaElement resolveRegular(String fqn)
            throws JavaModelException {
        int hash = fqn.indexOf('#');
        if (hash < 0) return resolveContainerOrType(fqn);
        IType declaring = findTypeRaw(fqn.substring(0, hash));
        if (declaring == null) return null;
        return resolveMemberInType(
                declaring, fqn.substring(hash + 1));
    }

    /**
     * Hash-less fqn → workspace element. Probes in priority order:
     * <ol>
     *   <li>type ({@code pkg.Class}) — most common, kept first</li>
     *   <li>project (workspace name)</li>
     *   <li>package fragment (across all projects' source roots)</li>
     *   <li>file (absolute filesystem path → {@link ICompilationUnit}
     *       or class file)</li>
     * </ol>
     * Returns {@code null} when none match.
     */
    private static IJavaElement resolveContainerOrType(String fqn)
            throws JavaModelException {
        if (fqn.isEmpty()) return null;

        IType type = findTypeRaw(fqn);
        if (type != null) return type;

        var workspaceRoot =
                ResourcesPlugin.getWorkspace().getRoot();
        var model = JavaCore.create(workspaceRoot);

        if (isValidProjectName(fqn)) {
            IJavaProject project = model.getJavaProject(fqn);
            if (project != null && project.exists()) return project;
        }

        for (IJavaProject p : model.getJavaProjects()) {
            for (IPackageFragmentRoot root
                    : p.getPackageFragmentRoots()) {
                IPackageFragment pkg = root.getPackageFragment(fqn);
                if (pkg != null && pkg.exists()) return pkg;
            }
        }

        if (looksLikeFilePath(fqn)) {
            IPath path = IPath.fromOSString(fqn);
            IFile file = workspaceRoot.getFileForLocation(path);
            if (file != null && file.exists()) {
                IJavaElement el = JavaCore.create(file);
                if (el != null) return el;
            }
        }

        return null;
    }

    /** {@link org.eclipse.core.resources.IWorkspaceRoot#getProject}
     *  requires a single-segment name (no separators); guards the
     *  {@link org.eclipse.jdt.core.IJavaModel#getJavaProject} call
     *  which would otherwise throw {@link IllegalArgumentException}
     *  on a path-shaped fqn. */
    private static boolean isValidProjectName(String name) {
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /** Heuristic to decide whether {@code fqn} is an absolute
     *  filesystem path (and therefore a candidate for
     *  {@link ICompilationUnit} / class-file resolution). Matches
     *  Windows drive letters ({@code D:\…}, {@code D:/…}) and POSIX
     *  absolute paths ({@code /…}). Plain dotted names — types,
     *  packages, projects — never qualify. */
    private static boolean looksLikeFilePath(String fqn) {
        if (fqn.isEmpty()) return false;
        char first = fqn.charAt(0);
        if (first == '/' || first == '\\') return true;
        return fqn.length() >= 3
                && fqn.charAt(1) == ':'
                && (fqn.charAt(2) == '/' || fqn.charAt(2) == '\\');
    }

    /** Plain {@link IJavaProject#findType} walk across open projects. */
    private static IType findTypeRaw(String fqn)
            throws JavaModelException {
        var model = JavaCore.create(
                ResourcesPlugin.getWorkspace().getRoot());
        for (IJavaProject project : model.getJavaProjects()) {
            IType type = project.findType(fqn);
            if (type != null && type.exists()) return type;
        }
        return null;
    }

    /**
     * Resolve a member fragment ({@code name(params)} or
     * {@code name}) against a declaring type. Parens → method.
     * No parens → field first, then sole method of that name.
     */
    private static IJavaElement resolveMemberInType(
            IType type, String memberPart) throws JavaModelException {
        int paren = memberPart.indexOf('(');
        if (paren < 0) {
            IField field = type.getField(memberPart);
            if (field != null && field.exists()) return field;
            return findMethod(type, memberPart, null);
        }
        String name = memberPart.substring(0, paren);
        int closeParen = memberPart.lastIndexOf(')');
        String params = closeParen > paren
                ? memberPart.substring(paren + 1, closeParen)
                : memberPart.substring(paren + 1);
        return findMethod(type, name, params);
    }

    /**
     * Locate the {@code .}-separator that starts a lambda /
     * anonymous suffix in a composite synthetic FQN like
     * {@code pkg.Outer#enclose(Args).() -> {...} Iface}. Returns
     * {@code -1} when the fqn has no synthetic suffix.
     */
    private static int syntheticSuffixStart(String fqn) {
        int lambda = fqn.indexOf(LAMBDA_SUFFIX_HEAD);
        int anon   = fqn.indexOf(ANON_SUFFIX_HEAD);
        if (lambda < 0 && anon < 0) return -1;
        if (lambda < 0) return anon;
        if (anon < 0)   return lambda;
        return Math.min(lambda, anon);
    }

    private static boolean matchesSuffix(IType type, String suffix)
            throws JavaModelException {
        String expected = suffixOf(type);
        return expected != null && expected.equals(suffix);
    }

    private static String suffixOf(IType type) throws JavaModelException {
        if (type.isLambda()) {
            String iface = firstSuperInterfaceSimple(type);
            return iface != null
                    ? "() -> {...} " + iface
                    : "() -> {...}";
        }
        if (type.isAnonymous()) {
            String iface = firstSuperInterfaceSimple(type);
            if (iface != null) return "new " + iface + ANON_SUFFIX_TAIL;
            String superName = simpleOf(
                    type, type.getSuperclassTypeSignature());
            if (superName != null) return "new " + superName + ANON_SUFFIX_TAIL;
            return "new {...}";
        }
        return null;
    }

    private static String firstSuperInterfaceSimple(IType type)
            throws JavaModelException {
        String[] sigs = type.getSuperInterfaceTypeSignatures();
        return sigs.length > 0 ? simpleOf(type, sigs[0]) : null;
    }

    private static String simpleOf(IType context, String signature)
            throws JavaModelException {
        if (signature == null) return null;
        String erased = Signature.getTypeErasure(signature);
        String elementSig = Signature.getArrayCount(erased) > 0
                ? Signature.getElementType(erased) : erased;
        String elementName = Signature.toString(elementSig);
        if (elementSig.length() > 0
                && elementSig.charAt(0) == 'Q') {
            String[][] resolved = context.resolveType(elementName);
            if (resolved != null && resolved.length > 0) {
                return resolved[0][1];
            }
        }
        int lastDot = elementName.lastIndexOf('.');
        return lastDot >= 0
                ? elementName.substring(lastDot + 1)
                : elementName;
    }

    static IMethod findMethod(IType type, String name,
            String paramTypesStr) throws JavaModelException {
        String[] paramTypes = parseParamTypes(paramTypesStr);
        for (IMethod m : type.getMethods()) {
            if (!m.getElementName().equals(name)) continue;
            if (paramTypes != null) {
                if (matchesParamTypes(m, paramTypes)) return m;
            } else {
                return m;
            }
        }
        return null;
    }

    static List<IMethod> findMethods(IType type, String name,
            String paramTypesStr) throws JavaModelException {
        String[] paramTypes = parseParamTypes(paramTypesStr);
        List<IMethod> result = new ArrayList<>();
        for (IMethod m : type.getMethods()) {
            if (!m.getElementName().equals(name)) continue;
            if (paramTypes != null) {
                if (matchesParamTypes(m, paramTypes))
                    result.add(m);
            } else {
                result.add(m);
            }
        }
        return result;
    }

    /**
     * Split comma-separated param types, respecting generics.
     * {@code "Map<String,Integer>,int"} → {@code ["Map<String,Integer>", "int"]}.
     */
    static String[] parseParamTypes(String s) {
        if (s == null) return null;
        if (s.isEmpty()) return new String[0];
        var params = new java.util.ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                params.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = s.substring(start).trim();
        if (!last.isEmpty()) params.add(last);
        return params.toArray(String[]::new);
    }

    private static boolean matchesParamTypes(IMethod m,
            String[] paramTypes) throws JavaModelException {
        String[] methodParams = m.getParameterTypes();
        if (methodParams.length != paramTypes.length) return false;
        for (int i = 0; i < methodParams.length; i++) {
            String jdtType = Signature.toString(methodParams[i]);
            if (!typeMatches(jdtType, paramTypes[i].trim())) {
                return false;
            }
        }
        return true;
    }

    static boolean typeMatches(String jdtType, String userType) {
        String jdt = stripGenerics(jdtType);
        String user = stripGenerics(userType);
        if (jdt.equals(user)) return true;
        return simpleName(jdt).equals(simpleName(user));
    }

    private static String stripGenerics(String type) {
        int start = type.indexOf('<');
        if (start < 0) return type;
        int end = type.lastIndexOf('>');
        String suffix = end + 1 < type.length()
                ? type.substring(end + 1) : "";
        return type.substring(0, start) + suffix;
    }

    private static String simpleName(String type) {
        String suffix = "";
        String base = type;
        while (base.endsWith("[]")) {
            suffix += "[]";
            base = base.substring(0, base.length() - 2);
        }
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(dot + 1);
        return base + suffix;
    }

    static String typeKind(IType type) throws JavaModelException {
        if (type.isAnnotation()) return "annotation";
        if (type.isEnum()) return "enum";
        if (type.isInterface()) return "interface";
        return "class";
    }

    /**
     * Wait for Eclipse auto-build to finish, with a 2-minute
     * safety timeout to prevent indefinite hangs.
     */
    static void joinAutoBuild() throws InterruptedException {
        NullProgressMonitor monitor = new NullProgressMonitor();
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(120_000);
            } catch (InterruptedException e) { /* ok */ }
            monitor.setCanceled(true);
        });
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
    }

    static final String JDT_PROBLEM_MARKER =
            "org.eclipse.jdt.core.problem";

    /**
     * Count JDT compilation errors in the given resource scope.
     */
    static int countErrors(
            org.eclipse.core.resources.IResource scope)
            throws org.eclipse.core.runtime.CoreException {
        var markers = scope.findMarkers(
                JDT_PROBLEM_MARKER, true,
                org.eclipse.core.resources.IResource
                        .DEPTH_INFINITE);
        int count = 0;
        for (var m : markers) {
            if (m.getAttribute(
                    org.eclipse.core.resources.IMarker
                            .SEVERITY, -1)
                    == org.eclipse.core.resources.IMarker
                            .SEVERITY_ERROR) {
                count++;
            }
        }
        return count;
    }

    static String compactSignature(IMethod m) throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        sig.append(m.getElementName()).append("(");
        String[] paramTypes = m.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.toString(
                    Signature.getTypeErasure(paramTypes[i])));
        }
        sig.append(")");
        return sig.toString();
    }

    /**
     * Find all implementations of an interface/abstract method
     * via type hierarchy. Returns FQN → IMethod map. Callers:
     * {@link SourceReport}, {@link GraphHandler#handleImplementors}.
     */
    static java.util.LinkedHashMap<String, IMethod>
            findImplementations(IMethod method)
            throws JavaModelException {
        var result =
                new java.util.LinkedHashMap<String, IMethod>();
        IType declaringType = method.getDeclaringType();
        if (declaringType == null) return result;
        if (!declaringType.isInterface()
                && !java.lang.reflect.Modifier.isAbstract(
                        declaringType.getFlags()))
            return result;

        String methodName = method.getElementName();
        String paramSig;
        try {
            paramSig = ReferenceCollector.paramSig(method);
        } catch (Exception e) { return result; }

        ITypeHierarchy hierarchy =
                declaringType.newTypeHierarchy(null);

        for (IType sub
                : hierarchy.getAllSubtypes(declaringType)) {
            try {
                for (IMethod m : sub.getMethods()) {
                    if (!m.getElementName()
                            .equals(methodName)) continue;
                    if (!ReferenceCollector.paramSig(m)
                            .equals(paramSig)) continue;
                    result.put(
                            sub.getFullyQualifiedName()
                            + "#" + compactSignature(m),
                            m);
                    break;
                }
            } catch (Exception e) { /* skip */ }
        }
        return result;
    }
}
