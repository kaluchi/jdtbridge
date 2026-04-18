package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IModuleDescription;
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

    /** FQN of a type: dotted form, inner classes use {@code .} not {@code $}. */
    static String fqnOf(IType type) {
        return type.getFullyQualifiedName('.');
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

    /** Erased parameter signature for FQMN: {@code "T1,T2,T3"} or {@code ""} for nullary. */
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

    /** FQMN of a method: {@code pkg.Type#name(erased,params)}. */
    static String fqmnOf(IMethod method) throws JavaModelException {
        IType declaring = method.getDeclaringType();
        String typeFqn = declaring != null
                ? fqnOf(declaring) : "";
        return typeFqn + "#" + compactSignature(method);
    }

    /** FQMN of a field: {@code pkg.Type#fieldName}. */
    static String fqmnOf(IField field) {
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

        String javadocSummary = SourceReport.javadocSummary(type);
        if (javadocSummary != null) {
            obj.addProperty("javadocSummary", javadocSummary);
        }
        return obj;
    }

    // ── :method ─────────────────────────────────────────────────────

    static JsonObject methodSkeleton(IMethod method) throws JavaModelException {
        var obj = baseHeader(fqmnOf(method), "method", originOf(method));
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

        String javadocSummary = SourceReport.javadocSummary(method);
        if (javadocSummary != null) {
            obj.addProperty("javadocSummary", javadocSummary);
        }
        return obj;
    }

    // ── :field ──────────────────────────────────────────────────────

    static JsonObject fieldSkeleton(IField field) {
        var obj = baseHeader(fqmnOf(field), "field", originOf(field));
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
        return obj;
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
                natures.add(ProjectHandler.shortNature(id));
            }
            obj.add("natures", natures);
        } catch (Exception ignored) { /* closed / deleted project */ }
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
            for (IClasspathEntry entry : jp.getRawClasspath()) {
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
                        == IPackageFragmentRoot.K_SOURCE) {
                    sourceRoots.add(root.getResource()
                            .getProjectRelativePath().toString());
                }
            }
            obj.add("sourceRoots", sourceRoots);

            String outputLoc = jp.getOutputLocation() != null
                    ? jp.getOutputLocation().toString() : null;
            if (outputLoc != null) {
                obj.addProperty("outputLocation", outputLoc);
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

    // ── :module ─────────────────────────────────────────────────────

    static JsonObject moduleSkeleton(IModuleDescription module)
            throws JavaModelException {
        String name = module.getElementName();
        String origin = module.isBinary() ? "binary" : "source";
        var obj = baseHeader(name, "module", origin);
        obj.addProperty("containingProject",
                module.getJavaProject().getElementName());
        return obj;
    }

    static JsonObject moduleDetail(IModuleDescription module)
            throws JavaModelException {
        var obj = moduleSkeleton(module);

        var requires = new JsonArray();
        for (String required : module.getRequiredModuleNames()) {
            var r = new JsonObject();
            r.addProperty("name", required);
            requires.add(r);
        }
        obj.add("requires", requires);

        // JPMS exports/opens/uses/provides — JDT API for these
        // varies across Eclipse versions and may require AST-level
        // inspection. Deferred — the bulk of consumers care about
        // requires and module identity, which are stable.
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

    static JsonObject classpathEntrySkeleton(IClasspathEntry entry,
            IProject project) {
        String entryKind = switch (entry.getEntryKind()) {
            case IClasspathEntry.CPE_SOURCE    -> "source";
            case IClasspathEntry.CPE_LIBRARY   -> "library";
            case IClasspathEntry.CPE_PROJECT   -> "project";
            case IClasspathEntry.CPE_VARIABLE  -> "variable";
            case IClasspathEntry.CPE_CONTAINER -> "container";
            default -> "unknown";
        };
        String path = entry.getPath() != null
                ? entry.getPath().toOSString() : "";
        String projName = project.getName();
        String fqn = projName + "#" + entryKind + "#" + path;

        var obj = baseHeader(fqn, "classpathEntry", "source");
        obj.addProperty("containingProject", projName);
        obj.addProperty("entryKind", entryKind);
        obj.addProperty("path", path);
        if (entry.getOutputLocation() != null) {
            obj.addProperty("outputLocation",
                    entry.getOutputLocation().toOSString());
        }
        if (entry.isTest()) obj.addProperty("isTest", true);
        if (entry.isExported()) obj.addProperty("isExported", true);
        return obj;
    }

    // ── Source text extraction ─────────────────────────────────────

    /**
     * Byte-exact source text for a member. Top-level types include
     * the full compilation unit (package + imports + type body).
     * Methods, fields, and inner types return their declaration range
     * read from disk to preserve indentation.
     */
    static String sourceTextOf(IMember member) {
        try {
            boolean isTopLevelType = member instanceof IType t
                    && t.getDeclaringType() == null;
            if (isTopLevelType) {
                ICompilationUnit cu = member.getCompilationUnit();
                if (cu != null) return cu.getSource();
                IClassFile cf = member.getClassFile();
                return cf != null ? cf.getSource() : null;
            }
            ISourceRange range = member.getSourceRange();
            if (range == null || range.getOffset() < 0) {
                return member.getSource();
            }
            String absPath = filePathOf(member);
            String fullSource = sourceOf(member);
            if (absPath != null && fullSource != null) {
                int startLine = offsetToLine(fullSource,
                        range.getOffset());
                int endLine = offsetToLine(fullSource,
                        range.getOffset() + range.getLength());
                try {
                    var path = java.nio.file.Path.of(absPath);
                    if (java.nio.file.Files.exists(path)) {
                        var lines = java.nio.file.Files.readAllLines(path);
                        int from = Math.max(0, startLine - 1);
                        int to = Math.min(lines.size(), endLine);
                        return String.join("\n",
                                lines.subList(from, to)) + "\n";
                    }
                } catch (Exception ignored) { /* fall through */ }
            }
            return member.getSource();
        } catch (JavaModelException e) {
            Log.warn("sourceTextOf failed", e);
            return null;
        }
    }

    // ── :reference ──────────────────────────────────────────────────

    /**
     * Polymorphic skeleton dispatcher. Resolves the right per-kind
     * builder based on the element's runtime type. Falls back to the
     * containing member when given an initializer or anonymous type
     * — those have no FQN-addressable identity but their host does.
     */
    static JsonObject memberSkeleton(IJavaElement element)
            throws JavaModelException {
        if (element == null) return null;
        if (element instanceof IType type) {
            if (type.isAnonymous()) {
                IJavaElement parent = type.getParent();
                if (parent != null) return memberSkeleton(parent);
                return null;
            }
            return typeSkeleton(type);
        }
        if (element instanceof IMethod method) {
            return methodSkeleton(method);
        }
        if (element instanceof IField field) {
            return fieldSkeleton(field);
        }
        // Initializers and other element kinds without their own
        // skeleton fall back to the enclosing type — references from
        // a static block attribute to the type that declared the block.
        IType enclosing = (IType) element.getAncestor(IJavaElement.TYPE);
        return enclosing != null ? typeSkeleton(enclosing) : null;
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

        String javadocSummary = SourceReport.javadocSummary(field);
        if (javadocSummary != null) {
            obj.addProperty("javadocSummary", javadocSummary);
        }
        return obj;
    }
}
