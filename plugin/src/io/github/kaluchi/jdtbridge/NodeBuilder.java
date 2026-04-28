package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotatable;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.SearchMatch;

/**
 * Single source of truth for the canonical graph node JSON shape.
 * <p>
 * Each node has a five-field {@code skeleton} header
 * (fqn / kind / origin / location / containingProject) shared across
 * all kinds, plus per-kind {@code detail} fields layered on top.
 * Bulk-query endpoints emit skeletons; point endpoints emit detail.
 * <p>
 * Identity discipline (FQN grammar, see ABI spec):
 * <ul>
 *   <li>type   — {@code pkg.Type} or {@code pkg.Outer.Inner} (dotted, never {@code $})</li>
 *   <li>method — {@code pkg.Type#name(ErasedParam1,ErasedParam2)} (erased, no generics, no spaces)</li>
 *   <li>field  — {@code pkg.Type#fieldName}</li>
 * </ul>
 * Modifiers always emitted as a Vec of lowercase keyword-stringified
 * tokens; field absent from container = node lacks that field
 * (no {@code null} sentinels for optional booleans).
 * <p>
 * Builders never throw on missing source ranges — they return a
 * {@code null} location and let the consumer decide. Per ABI: field
 * absent ≠ "fetch again", just absent.
 */
class NodeBuilder {

    private NodeBuilder() {}

    // ── Identity helpers ────────────────────────────────────────────

    /**
     * FQN of a type: dotted form, inner classes use {@code .}
     * not {@code $}. Anonymous and lambda types emit a composite
     * suffix matching Eclipse's Copy Qualified Name format so the
     * result round-trips through {@link JdtUtils#findType}:
     * <pre>
     *   lambda    : pkg.Outer#enclose(Args).() -> {...} Iface
     *   anonymous : pkg.Outer#enclose(Args).new Iface() {...}
     *   anonymous : pkg.Outer#enclose(Args).new Super() {...}  (no iface)
     * </pre>
     * The suffix separator is {@code '.'} — consistent with Eclipse
     * {@code appendTypeLabel}. Simple names (not FQN) of the
     * interface / superclass follow Eclipse's label composition.
     */
    static String fqnOf(IType type) {
        try {
            if (type.isAnonymous() || type.isLambda()) {
                return fqnOfEnclosing(type.getParent())
                        + "." + syntheticSuffix(type);
            }
            return type.getFullyQualifiedName('.');
        } catch (JavaModelException e) {
            // isAnonymous / isLambda / the suffix builders throw only
            // when the IType handle is invalid (not elaborated). Loud
            // failure — the skeleton serialization cannot recover
            // from a type whose own model refuses to answer.
            throw new IllegalStateException(
                    "fqnOf(IType) failed: " + type.getElementName(), e);
        }
    }

    /**
     * FQN of a type's enclosing IJavaElement — the host member of
     * an anonymous / lambda type. Parents are IMethod (most
     * common), IField, IInitializer, or another IType.
     */
    private static String fqnOfEnclosing(IJavaElement parent)
            throws JavaModelException {
        if (parent instanceof IMethod m) return fqnOf(m);
        if (parent instanceof IField f)  return fqnOf(f);
        if (parent instanceof IType t)   return fqnOf(t);
        IType ancestor = parent != null
                ? (IType) parent.getAncestor(IJavaElement.TYPE) : null;
        return ancestor != null ? fqnOf(ancestor) : "";
    }

    /**
     * Eclipse-compatible suffix for a synthetic type. Mirrors
     * {@code JavaElementLabelComposerCore.appendTypeLabel}'s
     * lambda / anonymous rendering — simple super-interface name
     * (when present) or superclass name for a class-only anonymous.
     */
    private static String syntheticSuffix(IType type)
            throws JavaModelException {
        String iface = firstSuperInterfaceSimpleName(type);
        if (type.isLambda()) {
            return iface != null
                    ? "() -> {...} " + iface
                    : "() -> {...}";
        }
        if (iface != null) return "new " + iface + "() {...}";
        String superName = simpleNameOfSignature(
                type, type.getSuperclassTypeSignature());
        if (superName != null) return "new " + superName + "() {...}";
        return "new {...}";
    }

    private static String firstSuperInterfaceSimpleName(IType type)
            throws JavaModelException {
        String[] sigs = type.getSuperInterfaceTypeSignatures();
        return sigs.length > 0
                ? simpleNameOfSignature(type, sigs[0]) : null;
    }

    private static String simpleNameOfSignature(IType context,
            String signature) throws JavaModelException {
        if (signature == null) return null;
        String fqName = resolveTypeName(signature, context);
        int lastDot = fqName.lastIndexOf('.');
        return lastDot >= 0 ? fqName.substring(lastDot + 1) : fqName;
    }

    /**
     * Resolve a JDT type signature to its FQN form, using the
     * declaring context's import scope for unresolved
     * ({@code Q}-prefixed) source signatures. Erasure is applied
     * before resolution so generics are dropped. Arrays are preserved
     * as suffixes after the element-type FQN.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code "I"} (primitive) → {@code "int"}</li>
     *   <li>{@code "Ljava.lang.String;"} (resolved) → {@code "java.lang.String"}</li>
     *   <li>{@code "QString;"} in test.model context → {@code "java.lang.String"}</li>
     *   <li>{@code "QList<QString;>;"} → {@code "java.util.List"}</li>
     *   <li>{@code "[QString;"} → {@code "java.lang.String[]"}</li>
     * </ul>
     */
    static String resolveTypeName(String signature, IType context)
            throws JavaModelException {
        String erased = Signature.getTypeErasure(signature);
        int arrayDims = Signature.getArrayCount(erased);
        String elementSig = arrayDims > 0
                ? Signature.getElementType(erased) : erased;
        String elementName = Signature.toString(elementSig);

        String resolvedName = elementName;
        if (elementSig.length() > 0
                && elementSig.charAt(0) == 'Q'
                && context != null) {
            String[][] resolved = context.resolveType(elementName);
            if (resolved != null && resolved.length > 0) {
                String pkg = resolved[0][0];
                String simple = resolved[0][1];
                resolvedName = pkg.isEmpty()
                        ? simple : pkg + "." + simple;
            }
        }

        if (arrayDims == 0) return resolvedName;
        var sb = new StringBuilder(resolvedName);
        for (int i = 0; i < arrayDims; i++) sb.append("[]");
        return sb.toString();
    }

    /** Erased parameter signature for FQN: {@code "T1,T2,T3"} or {@code ""} for nullary. */
    static String erasedParams(IMethod method) throws JavaModelException {
        String[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) return "";
        IType context = method.getDeclaringType();
        var sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(resolveTypeName(paramTypes[i], context));
        }
        return sb.toString();
    }

    /** Compact display signature {@code "name(T1,T2)"} — what {@code :signature} carries. */
    static String compactSignature(IMethod method) throws JavaModelException {
        return method.getElementName() + "(" + erasedParams(method) + ")";
    }

    /** FQN of a method: {@code pkg.Type#name(erased,params)}. */
    static String fqnOf(IMethod method) throws JavaModelException {
        IType declaring = method.getDeclaringType();
        String typeFqn = declaring != null
                ? fqnOf(declaring) : "";
        return typeFqn + "#" + compactSignature(method);
    }

    /** FQN of a field: {@code pkg.Type#fieldName}. */
    static String fqnOf(IField field) {
        IType declaring = field.getDeclaringType();
        String typeFqn = declaring != null
                ? fqnOf(declaring) : "";
        return typeFqn + "#" + field.getElementName();
    }

    // ── Field translators ───────────────────────────────────────────

    /** {@code :typeKind} discriminator: class | interface | enum | annotation | record. */
    static String typeKindOf(IType type) throws JavaModelException {
        if (type.isAnnotation()) return "annotation";
        if (type.isEnum()) return "enum";
        if (type.isInterface()) return "interface";
        if (type.isRecord()) return "record";
        return "class";
    }

    /** {@code :origin} discriminator: source | binary | synthetic. */
    static String originOf(IJavaElement element) {
        if (element instanceof IMember member) {
            try {
                if (member.isBinary()) return "binary";
            } catch (Exception ignored) { /* fall through */ }
            return "source";
        }
        return "source";
    }

    /** {@code :modifiers} as a Vec of lowercase keyword tokens. */
    static JsonArray modifiers(int flags) {
        var arr = new JsonArray();
        if (Flags.isPublic(flags))       arr.add("public");
        if (Flags.isProtected(flags))    arr.add("protected");
        if (Flags.isPrivate(flags))      arr.add("private");
        if (Flags.isStatic(flags))       arr.add("static");
        if (Flags.isFinal(flags))        arr.add("final");
        if (Flags.isAbstract(flags))     arr.add("abstract");
        if (Flags.isDefaultMethod(flags)) arr.add("default");
        if (Flags.isSynchronized(flags)) arr.add("synchronized");
        if (Flags.isNative(flags))       arr.add("native");
        if (Flags.isVolatile(flags))     arr.add("volatile");
        if (Flags.isTransient(flags))    arr.add("transient");
        if (Flags.isStrictfp(flags))     arr.add("strictfp");
        if (Flags.isSealed(flags))       arr.add("sealed");
        if (Flags.isNonSealed(flags))    arr.add("non-sealed");
        return arr;
    }

    // ── Annotations ─────────────────────────────────────────────────

    /**
     * Resolve annotations declared directly on an element into a Vec
     * of fully qualified annotation type names. For source elements
     * IAnnotation.getElementName() returns the name as written
     * (simple, qualified, or aliased); IType.resolveType() promotes
     * it to the canonical FQN using the enclosing compilation unit's
     * imports. For binary elements IAnnotation already yields FQN.
     *
     * Always returns a JsonArray (possibly empty) so pipelines like
     *     filter(/annotations | any(eq("org.junit.jupiter.api.Test")))
     * run against every member without tripping on /annotations →
     * null for the unannotated majority.
     */
    static JsonArray annotationsOf(IJavaElement element,
            IType context) {
        var arr = new JsonArray();
        if (!(element instanceof IAnnotatable ann)) return arr;
        try {
            for (IAnnotation decl : ann.getAnnotations()) {
                arr.add(resolveAnnotationName(decl, context));
            }
        } catch (JavaModelException ignored) { /* closed element */ }
        return arr;
    }

    private static String resolveAnnotationName(IAnnotation ann,
            IType context) throws JavaModelException {
        String name = ann.getElementName();
        // Binary elements and already-qualified source writers both
        // embed a '.' — no resolution needed.
        if (name.indexOf('.') >= 0) return name;
        if (context == null) return name;
        String[][] resolved = context.resolveType(name);
        if (resolved != null && resolved.length > 0) {
            String[] parts = resolved[0];
            return parts[0].isEmpty() ? parts[1] : parts[0] + "." + parts[1];
        }
        return name;
    }

    // ── Test-scope classification ───────────────────────────────────

    /**
     * True when the element sits in a test-scoped source root or in
     * a containing type that declares any JUnit/TestNG-annotated
     * method. Classpath-attribute signal fires first; annotation
     * scan runs only when the classpath does not classify the root.
     * Defaults to false.
     */
    static boolean isTestScope(IJavaElement element) {
        if (element == null) return false;
        IPackageFragmentRoot root = (IPackageFragmentRoot)
                element.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (root != null) {
            try {
                IClasspathEntry entry = root.getRawClasspathEntry();
                if (entry != null) {
                    for (IClasspathAttribute attr
                            : entry.getExtraAttributes()) {
                        if (IClasspathAttribute.TEST.equals(
                                attr.getName())
                                && "true".equals(attr.getValue())) {
                            return true;
                        }
                    }
                    if (entry.isTest()) return true;
                }
            } catch (JavaModelException ignored) { /* broken root */ }
        }
        IType type = (element instanceof IType t) ? t
                : (IType) element.getAncestor(IJavaElement.TYPE);
        if (type == null) return false;
        try {
            for (IMethod m : type.getMethods()) {
                if (hasTestAnnotation(m)) return true;
            }
        } catch (JavaModelException ignored) { /* unreadable */ }
        return false;
    }

    /**
     * Project-level test-scope: true only when the project hosts
     * tests exclusively (no production source roots). A standard
     * Maven module with both src/main/java and src/test/java is
     * `false` — it is a production project that happens to ship
     * tests, not a test-only module. A PDE fragment whose single
     * source root contains only `@Test`-bearing types is `true`.
     *
     * Scanning rule:
     *
     *  1. If any source classpath entry is test AND any other is
     *     production → false (mixed = production with tests).
     *  2. If every source classpath entry is test → true.
     *  3. If no source entry is test via classpath attribute →
     *     annotation fallback: scan top-level types for any
     *     `@Test`-family method. A hit against any type in any
     *     root still returns true (PDE fragment pattern), but an
     *     absence of hits returns false.
     */
    private static boolean projectIsTestScope(IProject project) {
        IJavaProject jp = JavaCore.create(project);
        if (jp == null || !jp.exists()) return false;
        try {
            int testRoots = 0;
            int productionRoots = 0;
            for (IClasspathEntry entry : jp.getRawClasspath()) {
                if (entry.getEntryKind() != IClasspathEntry.CPE_SOURCE) {
                    continue;
                }
                if (isTestEntry(entry)) testRoots++;
                else productionRoots++;
            }
            if (productionRoots == 0 && testRoots > 0) return true;
            if (productionRoots > 0 && testRoots > 0) return false;
            // Zero test classpath markers — hand off to annotation
            // fallback. Bounded: short-circuits on first @Test hit.
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                    continue;
                }
                for (IJavaElement pkg : root.getChildren()) {
                    if (!(pkg instanceof IPackageFragment frag)) continue;
                    for (ICompilationUnit cu
                            : frag.getCompilationUnits()) {
                        for (IType type : cu.getTypes()) {
                            for (IMethod m : type.getMethods()) {
                                if (hasTestAnnotation(m)) return true;
                            }
                        }
                    }
                }
            }
        } catch (JavaModelException ignored) { /* broken project */ }
        return false;
    }

    private static boolean isTestEntry(IClasspathEntry entry) {
        if (entry.isTest()) return true;
        for (IClasspathAttribute attr : entry.getExtraAttributes()) {
            if (IClasspathAttribute.TEST.equals(attr.getName())
                    && "true".equals(attr.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTestAnnotation(IMethod method)
            throws JavaModelException {
        for (IAnnotation ann : method.getAnnotations()) {
            String name = ann.getElementName();
            // Match by terminal segment — JUnit 4/5 (`org.junit.Test`,
            // `org.junit.jupiter.api.Test`, `ParameterizedTest`,
            // `RepeatedTest`, `TestFactory`, `TestTemplate`) and
            // TestNG (`org.testng.annotations.Test`) all land on one
            // of these terminal tokens. Purposefully does not
            // resolve FQN — classpath-attribute check runs first for
            // unambiguous cases; annotation-fallback is a
            // best-effort conventional match.
            String last = name.substring(name.lastIndexOf('.') + 1);
            if (last.equals("Test")
                    || last.equals("ParameterizedTest")
                    || last.equals("RepeatedTest")
                    || last.equals("TestFactory")
                    || last.equals("TestTemplate")) {
                return true;
            }
        }
        return false;
    }

    // ── Location ────────────────────────────────────────────────────

    /**
     * Build the canonical {@code :location} sub-Map for a member.
     * Returns {@code null} when the member has no resolvable source
     * range — packages, synthetics, elements without bound resources.
     * <p>
     * Lines and columns are 1-based inclusive. Name-range offsets
     * are character offsets into the source file (for cursor placement
     * and rename anchoring), only attached when JDT exposes them.
     */
    static JsonObject location(IMember member) {
        try {
            ISourceRange sourceRange = member.getSourceRange();
            if (sourceRange == null || sourceRange.getOffset() < 0) {
                return null;
            }
            String source = sourceOf(member);
            if (source == null) return null;

            String filePath = filePathOf(member);
            if (filePath == null) return null;

            int sourceStartOffset = sourceRange.getOffset();
            int sourceEndOffset = sourceStartOffset + sourceRange.getLength();
            int startLine = offsetToLine(source, sourceStartOffset);
            int endLine = offsetToLine(source, sourceEndOffset);

            var loc = new JsonObject();
            loc.addProperty("file", filePath);
            loc.addProperty("startLine", startLine);
            loc.addProperty("endLine", endLine);
            // :lineCount saves the repeated
            //   add(m | /location/endLine | sub(m | /location/startLine), 1)
            // idiom in sort/audit pipelines. Plain inclusive span,
            // 1-based matching startLine / endLine.
            loc.addProperty("lineCount",
                    endLine - startLine + 1);

            ISourceRange nameRange = member.getNameRange();
            if (nameRange != null && nameRange.getOffset() >= 0) {
                loc.addProperty("nameStart", nameRange.getOffset());
                loc.addProperty("nameEnd",
                        nameRange.getOffset() + nameRange.getLength());
            }
            return loc;
        } catch (JavaModelException e) {
            Log.warn("location() failed for " + member.getElementName(), e);
            return null;
        }
    }

    private static int offsetToLine(String source, int offset) {
        int line = 1;
        int limit = Math.min(offset, source.length());
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static String sourceOf(IMember member) throws JavaModelException {
        ICompilationUnit cu = member.getCompilationUnit();
        if (cu != null) return cu.getSource();
        IClassFile cf = member.getClassFile();
        return cf != null ? cf.getSource() : null;
    }

    private static String filePathOf(IMember member) {
        IType type = member instanceof IType t
                ? t : member.getDeclaringType();
        if (type == null) return null;
        if (type.getResource() != null
                && type.getResource().getLocation() != null) {
            return type.getResource().getLocation().toOSString();
        }
        try {
            IPackageFragmentRoot root = (IPackageFragmentRoot)
                    type.getAncestor(
                            IJavaElement.PACKAGE_FRAGMENT_ROOT);
            if (root != null && root.getPath() != null) {
                return root.getPath().toOSString();
            }
        } catch (Exception ignored) { /* fall through */ }
        return null;
    }

    // ── Skeleton header builder ─────────────────────────────────────

    /** The three always-present skeleton fields. Per-kind builders
     *  explicitly add :location and :containingProject when the
     *  node has them — null arguments are not part of the API. */
    private static JsonObject baseHeader(String fqn, String kind,
            String origin) {
        var obj = new JsonObject();
        obj.addProperty("fqn", fqn);
        obj.addProperty("kind", kind);
        obj.addProperty("origin", origin);
        return obj;
    }

    // ── :type ───────────────────────────────────────────────────────

    static JsonObject typeSkeleton(IType type) {
        var obj = baseHeader(fqnOf(type), "type", originOf(type));
        JsonObject loc = location(type);
        if (loc != null) obj.add("location", loc);
        obj.addProperty("containingProject",
                type.getJavaProject().getElementName());
        String packageName = type.getPackageFragment().getElementName();
        if (!packageName.isEmpty()) {
            obj.addProperty("containingPackage", packageName);
        }
        try {
            obj.addProperty("typeKind", typeKindOf(type));
            obj.add("modifiers", modifiers(type.getFlags()));
        } catch (JavaModelException ignored) { /* skip */ }
        obj.add("annotations", annotationsOf(type, type));
        obj.addProperty("isTestScope", isTestScope(type));
        return obj;
    }

    static JsonObject typeDetail(IType type) throws JavaModelException {
        var obj = typeSkeleton(type);
        obj.addProperty("typeKind", typeKindOf(type));
        obj.add("modifiers", modifiers(type.getFlags()));

        var typeParameters = new JsonArray();
        for (ITypeParameter tp : type.getTypeParameters()) {
            var tpObj = new JsonObject();
            tpObj.addProperty("name", tp.getElementName());
            String[] bounds = tp.getBounds();
            if (bounds != null && bounds.length > 0) {
                tpObj.addProperty("bound", bounds[0]);
            }
            typeParameters.add(tpObj);
        }
        obj.add("typeParameters", typeParameters);

        IType declaring = type.getDeclaringType();
        if (declaring != null) {
            obj.addProperty("containingType", fqnOf(declaring));
        }

        String packageName = type.getPackageFragment().getElementName();
        if (!packageName.isEmpty()) {
            obj.addProperty("containingPackage", packageName);
        }

        String superSig = type.getSuperclassTypeSignature();
        if (superSig != null) {
            obj.addProperty("superclass",
                    resolveTypeName(superSig, type));
        }

        var interfaces = new JsonArray();
        for (String sig : type.getSuperInterfaceTypeSignatures()) {
            interfaces.add(resolveTypeName(sig, type));
        }
        obj.add("interfaces", interfaces);

        if (type.isAnonymous())  obj.addProperty("isAnonymous", true);
        if (Flags.isDeprecated(type.getFlags())) {
            obj.addProperty("isDeprecated", true);
        }

        String javadocSummary = javadocSummary(type);
        if (javadocSummary != null) {
            obj.addProperty("javadocSummary", javadocSummary);
        }
        return obj;
    }

    // ── :method ─────────────────────────────────────────────────────

    static JsonObject methodSkeleton(IMethod method) throws JavaModelException {
        var obj = baseHeader(fqnOf(method), "method", originOf(method));
        JsonObject loc = location(method);
        if (loc != null) obj.add("location", loc);
        obj.addProperty("containingProject",
                method.getJavaProject().getElementName());
        obj.addProperty("name", method.getElementName());
        obj.addProperty("signature", compactSignature(method));
        obj.add("modifiers", modifiers(method.getFlags()));
        IType declaring = method.getDeclaringType();
        if (declaring != null) {
            obj.addProperty("containingType", fqnOf(declaring));
        }
        if (!method.isConstructor()) {
            obj.addProperty("returnType",
                    resolveTypeName(method.getReturnType(), declaring));
        }
        obj.add("annotations", annotationsOf(method, declaring));
        obj.addProperty("isTestScope", isTestScope(method));
        return obj;
    }

    static JsonObject methodDetail(IMethod method) throws JavaModelException {
        var obj = methodSkeleton(method);
        obj.addProperty("name", method.getElementName());

        var parameters = new JsonArray();
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();
        IType declaring = method.getDeclaringType();
        for (int i = 0; i < paramTypes.length; i++) {
            var p = new JsonObject();
            p.addProperty("name",
                    i < paramNames.length ? paramNames[i] : ("arg" + i));
            p.addProperty("type",
                    resolveTypeName(paramTypes[i], declaring));
            parameters.add(p);
        }
        obj.add("parameters", parameters);

        if (!method.isConstructor()) {
            obj.addProperty("returnType",
                    resolveTypeName(method.getReturnType(), declaring));
        }

        obj.add("modifiers", modifiers(method.getFlags()));

        var typeParameters = new JsonArray();
        for (ITypeParameter tp : method.getTypeParameters()) {
            var tpObj = new JsonObject();
            tpObj.addProperty("name", tp.getElementName());
            String[] bounds = tp.getBounds();
            if (bounds != null && bounds.length > 0) {
                tpObj.addProperty("bound", bounds[0]);
            }
            typeParameters.add(tpObj);
        }
        obj.add("typeParameters", typeParameters);

        var throwsArr = new JsonArray();
        for (String thrown : method.getExceptionTypes()) {
            throwsArr.add(resolveTypeName(thrown, declaring));
        }
        obj.add("throws", throwsArr);

        if (declaring != null) {
            obj.addProperty("containingType", fqnOf(declaring));
        }
        obj.addProperty("signature", compactSignature(method));

        if (method.isConstructor()) {
            obj.addProperty("isConstructor", true);
        }
        if (Flags.isAbstract(method.getFlags())) {
            obj.addProperty("isAbstract", true);
        }
        if (Flags.isDefaultMethod(method.getFlags())) {
            obj.addProperty("isDefault", true);
        }
        if (Flags.isDeprecated(method.getFlags())) {
            obj.addProperty("isDeprecated", true);
        }

        String javadocSummary = javadocSummary(method);
        if (javadocSummary != null) {
            obj.addProperty("javadocSummary", javadocSummary);
        }
        return obj;
    }

    // ── :field ──────────────────────────────────────────────────────

    static JsonObject fieldSkeleton(IField field) {
        var obj = baseHeader(fqnOf(field), "field", originOf(field));
        JsonObject loc = location(field);
        if (loc != null) obj.add("location", loc);
        obj.addProperty("containingProject",
                field.getJavaProject().getElementName());
        obj.addProperty("name", field.getElementName());
        IType declaring = field.getDeclaringType();
        if (declaring != null) {
            obj.addProperty("containingType", fqnOf(declaring));
        }
        try {
            obj.add("modifiers", modifiers(field.getFlags()));
            obj.addProperty("type",
                    resolveTypeName(field.getTypeSignature(), declaring));
        } catch (JavaModelException ignored) { /* skip */ }
        obj.add("annotations", annotationsOf(field, declaring));
        obj.addProperty("isTestScope", isTestScope(field));
        return obj;
    }

    /**
     * First-sentence summary of an element's javadoc, or null when
     * the element has no attached doc. Strips comment markers and
     * the leading per-line {@code *} asterisk, then stops at the
     * first {@code @}-tag, blank line, or {@code <p>} separator.
     */
    static String javadocSummary(IJavaElement element) {
        if (!(element instanceof IMember m)) return null;
        try {
            ISourceRange range = m.getJavadocRange();
            if (range == null) return null;
            var cu = m.getCompilationUnit();
            String source = cu != null ? cu.getSource()
                    : (m.getClassFile() != null
                            ? m.getClassFile().getSource()
                            : null);
            if (source == null) return null;
            String raw = source.substring(range.getOffset(),
                    range.getOffset() + range.getLength());
            return firstJavadocSentence(raw);
        } catch (Exception e) { return null; }
    }

    static String firstJavadocSentence(String javadoc) {
        String text = javadoc
                .replaceAll("^/\\*\\*\\s*", "")
                .replaceAll("\\s*\\*/$", "")
                .replaceAll("(?m)^\\s*\\*\\s?", "")
                .strip();
        var sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("@") || trimmed.equals("<p>")
                    || trimmed.isEmpty()) {
                break;
            }
            if (sb.length() > 0) sb.append(" ");
            sb.append(trimmed);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Collapse an Eclipse nature id to a short domain keyword
     * ({@code "java"} / {@code "maven"} / {@code "pde"} /
     * {@code "gradle"}); fall back to the terminal segment of the
     * nature id for anything the rule set does not recognise.
     */
    static String shortNature(String natureId) {
        if (natureId.contains("javanature")) return "java";
        if (natureId.contains("maven")) return "maven";
        if (natureId.contains("pde") || natureId.contains("Plugin")) {
            return "pde";
        }
        if (natureId.contains("gradle")) return "gradle";
        int dot = natureId.lastIndexOf('.');
        return dot >= 0 ? natureId.substring(dot + 1) : natureId;
    }

    // ── :project ────────────────────────────────────────────────────

    /**
     * {@code :project} skeleton. {@code :location} is intentionally
     * null — projects are containers, not code positions. Filesystem
     * root path lives in detail under {@code :rootPath} so the
     * canonical {@code :location} stays a code-coordinate sub-Map.
     */
    static JsonObject projectSkeleton(IProject project) {
        var obj = baseHeader(
                project.getName(), "project", "source");
        // Projects have a filesystem root, but no code-position
        // :location sub-Map (no line/column). Root path lives at
        // top-level under :rootPath.
        if (project.getLocation() != null) {
            obj.addProperty("rootPath",
                    project.getLocation().toOSString());
        }
        // :natures lives on the skeleton (not just detail) because
        // workspace-wide filters like `@projects | filter(/natures
        // | any(eq("java")))` are the baseline pre-filter before any
        // per-project axis (@packagesInProject / @typesInProject)
        // that would fail on non-Java natures. Cheap to read —
        // IProjectDescription is already loaded for each open
        // project.
        try {
            var natures = new JsonArray();
            for (String id : project.getDescription().getNatureIds()) {
                natures.add(shortNature(id));
            }
            obj.add("natures", natures);
        } catch (Exception ignored) { /* closed / deleted project */ }
        // :isTestScope on the project carries true when any of its
        // source roots is itself test-scoped (Maven src/test/java,
        // PDE fragment with test annotations on any type). Lets
        // `@projects | filter(/isTestScope)` gate workspace audits
        // before any per-project axis.
        obj.addProperty("isTestScope", projectIsTestScope(project));
        // Git context — workspace projects overwhelmingly live in
        // git repos and the membership is fundamental enough that
        // bulk listings (jdt q '@projects') must show it without an
        // @detail roundtrip per project.
        var mapping = org.eclipse.egit.core.project
                .RepositoryMapping.getMapping(project);
        if (mapping != null && mapping.getRepository() != null) {
            var repo = mapping.getRepository();
            obj.addProperty("repo",
                    repo.getWorkTree().getAbsolutePath());
            try {
                String branch = repo.getBranch();
                if (branch != null) obj.addProperty("branch", branch);
            } catch (Exception ignored) { /* git read failure */ }
        }
        return obj;
    }

    static JsonObject projectDetail(IProject project) throws Exception {
        var obj = projectSkeleton(project);

        IJavaProject jp = JavaCore.create(project);
        if (jp != null && jp.exists()) {
            var classpath = new JsonArray();
            for (IClasspathEntry entry : jp.getResolvedClasspath(true)) {
                classpath.add(classpathEntrySkeleton(entry, project));
            }
            obj.add("classpathEntries", classpath);

            var deps = new JsonArray();
            for (String dep : jp.getRequiredProjectNames()) {
                deps.add(dep);
            }
            obj.add("dependencies", deps);

            var sourceRoots = new JsonArray();
            for (IPackageFragmentRoot root
                    : jp.getPackageFragmentRoots()) {
                if (root.getKind()
                        == IPackageFragmentRoot.K_SOURCE
                        && root.getResource() != null
                        && root.getResource().getLocation() != null) {
                    sourceRoots.add(root.getResource()
                            .getLocation().toOSString());
                }
            }
            obj.add("sourceRoots", sourceRoots);

            if (jp.getOutputLocation() != null) {
                obj.addProperty("outputLocation",
                        workspacePathAbsolute(jp.getOutputLocation()));
            }

            String compliance =
                    jp.getOption(JavaCore.COMPILER_COMPLIANCE, true);
            if (compliance != null) {
                obj.addProperty("javaVersion", compliance);
            }
        }

        // :repo / :branch already in skeleton.
        return obj;
    }

    // ── :package ────────────────────────────────────────────────────

    static JsonObject packageSkeleton(IPackageFragment pkg) {
        String name = pkg.getElementName();
        if (name.isEmpty()) name = "(default)";
        String origin;
        try {
            origin = pkg.getKind() == IPackageFragmentRoot.K_BINARY
                    ? "binary" : "source";
        } catch (JavaModelException e) {
            origin = "source";
        }
        var obj = baseHeader(name, "package", origin);
        obj.addProperty("containingProject",
                pkg.getJavaProject().getElementName());
        try {
            int typeCount = 0;
            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                typeCount += cu.getTypes().length;
            }
            obj.addProperty("typeCount", typeCount);
        } catch (JavaModelException ignored) { /* skip */ }
        return obj;
    }

    static JsonObject packageDetail(IPackageFragment pkg)
            throws JavaModelException {
        var obj = packageSkeleton(pkg);

        IPackageFragmentRoot root = (IPackageFragmentRoot)
                pkg.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (root != null && root.getResource() != null) {
            obj.addProperty("sourceRoot",
                    root.getResource()
                            .getProjectRelativePath().toString());
        }

        int typeCount = 0;
        for (ICompilationUnit cu : pkg.getCompilationUnits()) {
            typeCount += cu.getTypes().length;
        }
        obj.addProperty("typeCount", typeCount);
        return obj;
    }

    // ── :file ───────────────────────────────────────────────────────

    static JsonObject fileSkeleton(IFile file) {
        String fqn = file.getLocation() != null
                ? file.getLocation().toOSString()
                : file.getFullPath().toOSString();
        String origin = file.getName().endsWith(".class")
                ? "binary" : "source";
        String name = file.getName();
        String language = name.endsWith(".java")
                ? "java"
                : name.endsWith(".class") ? "class" : "other";
        var obj = baseHeader(fqn, "file", origin);
        obj.addProperty("containingProject",
                file.getProject().getName());
        obj.addProperty("language", language);
        return obj;
    }

    static JsonObject fileDetail(IFile file) {
        var obj = fileSkeleton(file);
        String name = file.getName();
        String language = name.endsWith(".java")
                ? "java"
                : name.endsWith(".class") ? "class" : "other";
        obj.addProperty("language", language);
        try {
            String charset = file.getCharset();
            if (charset != null) obj.addProperty("charset", charset);
        } catch (Exception ignored) { /* skip */ }
        long mtime = file.getModificationStamp();
        if (mtime > 0) obj.addProperty("modificationTime", mtime);
        return obj;
    }

    // ── :classpathEntry ─────────────────────────────────────────────

    /**
     * :classpathEntry skeleton for a RESOLVED classpath entry.
     * Callers must pass entries from
     * {@link IJavaProject#getResolvedClasspath(boolean)} — container
     * and variable kinds have been expanded into their constituent
     * source / library / project entries by that call, so this
     * builder handles only those three.
     * <ul>
     *   <li>{@code :path} — absolute filesystem path on the Eclipse
     *       host, in the host's native format (Windows:
     *       {@code D:\…}, Linux: {@code /…}).</li>
     *   <li>{@code :origin} — {@code "source"} for source roots,
     *       {@code "binary"} for library JARs and project-dep
     *       outputs.</li>
     *   <li>{@code :outputLocation} — absolute filesystem path,
     *       same format as {@code :path}. Present only on source
     *       entries that override the project default.</li>
     * </ul>
     */
    static JsonObject classpathEntrySkeleton(IClasspathEntry entry,
            IProject project) {
        int kind = entry.getEntryKind();
        String entryKind;
        String origin;
        switch (kind) {
            case IClasspathEntry.CPE_SOURCE:
                entryKind = "source"; origin = "source"; break;
            case IClasspathEntry.CPE_LIBRARY:
                entryKind = "library"; origin = "binary"; break;
            case IClasspathEntry.CPE_PROJECT:
                entryKind = "project"; origin = "binary"; break;
            default:
                throw new IllegalStateException(
                        "resolved classpath must not contain entry kind "
                        + kind + "; callers must feed "
                        + "IJavaProject.getResolvedClasspath(true)");
        }
        String absolutePath = resolveEntryPath(entry);
        String projName = project.getName();
        String fqn = projName + "#" + entryKind + "#" + absolutePath;

        var obj = baseHeader(fqn, "classpathEntry", origin);
        obj.addProperty("containingProject", projName);
        obj.addProperty("entryKind", entryKind);
        obj.addProperty("path", absolutePath);
        if (entry.getOutputLocation() != null) {
            obj.addProperty("outputLocation",
                    workspacePathAbsolute(entry.getOutputLocation()));
        }
        if (entry.isTest()) obj.addProperty("isTest", true);
        if (entry.isExported()) obj.addProperty("isExported", true);
        return obj;
    }

    /**
     * Absolute filesystem path for a classpath entry. Workspace-
     * internal paths resolve through the workspace root; external
     * paths (library JARs outside the workspace, JDK modules)
     * already carry a filesystem-absolute {@code IPath} and pass
     * through verbatim. An entry path that resolves to neither is
     * a contract violation — throws rather than emit ambiguous
     * data.
     */
    private static String resolveEntryPath(IClasspathEntry entry) {
        var entryPath = entry.getPath();
        if (entryPath == null) {
            throw new IllegalStateException(
                    "classpath entry has no path: " + entry);
        }
        var resource = workspaceRoot().findMember(entryPath);
        if (resource != null && resource.getLocation() != null) {
            return resource.getLocation().toOSString();
        }
        String raw = entryPath.toOSString();
        if (new java.io.File(raw).isAbsolute()) {
            return raw;
        }
        throw new IllegalStateException(
                "Unresolvable classpath entry path: " + entryPath
                + " (workspace lookup missed and path is not "
                + "filesystem-absolute)");
    }

    private static org.eclipse.core.resources.IWorkspaceRoot workspaceRoot() {
        return org.eclipse.core.resources.ResourcesPlugin
                .getWorkspace().getRoot();
    }

    private static String workspacePathAbsolute(
            org.eclipse.core.runtime.IPath workspacePath) {
        var resource = workspaceRoot().findMember(workspacePath);
        if (resource == null || resource.getLocation() == null) {
            throw new IllegalStateException(
                    "Cannot resolve absolute path for " + workspacePath);
        }
        return resource.getLocation().toOSString();
    }

    // ── Source text extraction ─────────────────────────────────────

    /**
     * Source text for a member, sliced along line boundaries.
     * Top-level types return the full compilation unit. Methods,
     * fields, and inner types return the full lines that span their
     * declaration range — leading indentation of the opening line
     * is preserved, and the trailing newline of the closing line is
     * included.
     * <p>
     * Text is read from the JDT working-copy source (same source
     * the declaration range is measured against), so unsaved edits
     * stay consistent. Line separators of the source are kept as
     * authored — CRLF files stay CRLF, LF files stay LF.
     */
    static String sourceTextOf(IMember member) throws JavaModelException {
        boolean isTopLevelType = member instanceof IType t
                && t.getDeclaringType() == null;
        if (isTopLevelType) {
            ICompilationUnit cu = member.getCompilationUnit();
            if (cu != null) return cu.getSource();
            IClassFile cf = member.getClassFile();
            if (cf == null) {
                throw new IllegalStateException(
                        "type has neither compilation unit nor class file: "
                        + member.getElementName());
            }
            return cf.getSource();
        }
        ISourceRange range = member.getSourceRange();
        String fullSource = sourceOf(member);
        if (range == null || range.getOffset() < 0) {
            throw new IllegalStateException(
                    "member has no source range: "
                    + member.getElementName());
        }
        if (fullSource == null) {
            throw new IllegalStateException(
                    "compilation unit source unavailable for "
                    + member.getElementName());
        }
        int start = lineStart(fullSource, range.getOffset());
        int end = lineEnd(fullSource,
                range.getOffset() + range.getLength());
        return fullSource.substring(start, end);
    }

    /** Offset of the first char on the line containing {@code off}. */
    private static int lineStart(String source, int off) {
        int i = Math.min(off, source.length());
        while (i > 0 && source.charAt(i - 1) != '\n') i--;
        return i;
    }

    /**
     * Offset immediately after the line terminator of the line
     * containing {@code off}. Returns {@code source.length()} when
     * the last line has no trailing terminator.
     */
    private static int lineEnd(String source, int off) {
        int i = Math.min(off, source.length());
        int n = source.length();
        while (i < n && source.charAt(i) != '\n') i++;
        return i < n ? i + 1 : n;
    }

    // ── :reference ──────────────────────────────────────────────────

    /**
     * Polymorphic skeleton dispatcher. Resolves the right per-kind
     * builder based on the element's runtime type. Collapses
     * FQN-unaddressable hosts (anonymous classes, lambda types,
     * initializer blocks) onto their nearest resolvable enclosing
     * member, so every skeleton emitted carries a fqn the caller
     * can feed back into @type / @method / @field / @source for
     * round-trip navigation.
     * <p>
     * For example, a reference from inside a Runnable lambda body
     * declared in {@code HttpServer#acceptLoop(ServerSocket)}
     * previously surfaced as {@code HttpServer.1#run()}, which
     * {@code JdtUtils.findType} cannot resolve (the {@code .N}
     * anonymous suffix is a compilation-unit label, not a Java
     * Model FQN). The collapsed skeleton of the enclosing method
     * — {@code HttpServer#acceptLoop(ServerSocket)} — is what the
     * caller can navigate.
     */
    static JsonObject memberSkeleton(IJavaElement element)
            throws JavaModelException {
        if (element == null) return null;
        if (element instanceof IType type) return typeSkeleton(type);
        if (element instanceof IMethod method) return methodSkeleton(method);
        if (element instanceof IField field) return fieldSkeleton(field);
        // Initializers and other element kinds without their own
        // skeleton fall back to the enclosing type — references from
        // a static block attribute to the type that declared the block.
        IType enclosing = (IType) element.getAncestor(IJavaElement.TYPE);
        if (enclosing != null) return typeSkeleton(enclosing);
        // File-level elements (IImportDeclaration, IPackageDeclaration)
        // have no IType ancestor — SearchMatch gives them as the host
        // for import-statement / package-level refs. Resolve them to
        // the compilation unit's primary type so every emitted ref
        // carries a :from skeleton whose fqn round-trips through
        // @type / @source.
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(
                IJavaElement.COMPILATION_UNIT);
        if (cu != null) {
            IType primary = cu.findPrimaryType();
            if (primary != null) return typeSkeleton(primary);
        }
        return null;
    }

    /**
     * Build a {@code :reference} node from a {@link SearchMatch} that
     * pinned the {@code from}-side and a pre-built {@code to}-side
     * skeleton (the query target).
     * <p>
     * References are terminal nodes — no FQN, identified by the
     * {@code (from, to, location, refKind)} tuple. The
     * {@code :containingProject} mirrors the from-side's project,
     * which is where the reference physically lives.
     */
    static JsonObject referenceFromMatch(JsonObject toSkeleton,
            SearchMatch match, String refKind) throws JavaModelException {
        var obj = new JsonObject();
        obj.addProperty("kind", "reference");
        obj.addProperty("direction", "incoming");
        obj.addProperty("origin", originOfMatch(match));

        JsonObject loc = matchLocation(match);
        if (loc != null) {
            obj.add("location", loc);
        }

        JsonObject fromSkeleton = match.getElement() instanceof IJavaElement el
                ? memberSkeleton(el) : null;
        if (fromSkeleton != null) {
            obj.add("from", fromSkeleton);
            JsonElement containingProj =
                    fromSkeleton.get("containingProject");
            if (containingProj != null && !containingProj.isJsonNull()) {
                obj.add("containingProject", containingProj);
            }
        }

        obj.add("to", toSkeleton);
        obj.addProperty("refKind", refKind);

        if (match.isInsideDocComment()) {
            obj.addProperty("inJavadoc", true);
        }
        return obj;
    }

    private static String originOfMatch(SearchMatch match) {
        if (match.getElement() instanceof IMember member) {
            try {
                if (member.isBinary()) return "binary";
            } catch (Exception ignored) { /* fall through */ }
        }
        return "source";
    }

    /** Convert a SearchMatch's offset/length into the canonical location shape. */
    static JsonObject matchLocation(SearchMatch match) {
        if (!(match.getResource() instanceof IFile file)
                || file.getLocation() == null) {
            return null;
        }
        try {
            ICompilationUnit cu =
                    JavaCore.createCompilationUnitFrom(file);
            if (cu == null) return null;
            String source = cu.getSource();
            if (source == null) return null;

            int startOffset = match.getOffset();
            int endOffset = startOffset + match.getLength();
            int startLine = offsetToLine(source, startOffset);
            int endLine = offsetToLine(source, endOffset);

            var loc = new JsonObject();
            loc.addProperty("file", file.getLocation().toOSString());
            loc.addProperty("startLine", startLine);
            loc.addProperty("endLine", endLine);
            loc.addProperty("nameStart", startOffset);
            loc.addProperty("nameEnd", endOffset);
            return loc;
        } catch (JavaModelException e) {
            Log.warn("matchLocation failed", e);
            return null;
        }
    }

    static JsonObject fieldDetail(IField field) throws JavaModelException {
        var obj = fieldSkeleton(field);
        obj.addProperty("name", field.getElementName());
        IType declaring = field.getDeclaringType();
        obj.addProperty("type",
                resolveTypeName(field.getTypeSignature(), declaring));

        if (declaring != null) {
            obj.addProperty("containingType", fqnOf(declaring));
        }

        obj.add("modifiers", modifiers(field.getFlags()));

        if (Flags.isStatic(field.getFlags())
                && Flags.isFinal(field.getFlags())) {
            obj.addProperty("isConstant", true);
        }
        if (Flags.isDeprecated(field.getFlags())) {
            obj.addProperty("isDeprecated", true);
        }

        String javadocSummary = javadocSummary(field);
        if (javadocSummary != null) {
            obj.addProperty("javadocSummary", javadocSummary);
        }
        return obj;
    }
}
