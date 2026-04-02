package io.github.kaluchi.jdtbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClassFile;
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
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

/**
 * Handlers for JDT search and code inspection operations.
 */
class SearchHandler {

    String handleProjects(ProjectScope scope) throws Exception {
        var arr = new JsonArray();
        scope.openProjects().forEach(p -> {
            var obj = new JsonObject();
            obj.addProperty("name", p.getName());
            var loc = p.getLocation();
            obj.addProperty("location",
                    loc != null ? loc.toOSString() : "");
            // Git repo info via EGit
            var mapping = org.eclipse.egit.core.project
                    .RepositoryMapping.getMapping(p);
            if (mapping != null) {
                var repo = mapping.getRepository();
                obj.addProperty("repo",
                        repo.getWorkTree().getAbsolutePath());
                try {
                    obj.addProperty("branch",
                            repo.getBranch());
                } catch (Exception ignored) { }
            }
            arr.add(obj);
        });
        return arr.toString();
    }

    /**
     * Find types by name, wildcard pattern, or package prefix.
     * Supports exact match, pattern match (`*`, `?`), and dotted
     * package search. 
     * 
     * @param params 
     * @return JSON array of `{fqn, file}` entries.
     * @throws CoreException
     */
    String handleFind(Map<String, String> params,
            ProjectScope scope) throws CoreException {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return HttpServer.jsonError("Missing 'name' parameter");
        }

        boolean sourceOnly = params.containsKey("source");

        // Dotted name without wildcards — package search
        if (isPackageSearch(name)) {
            return findByPackage(normalizePackage(name), sourceOnly);
        }

        int matchRule = (name.contains("*") || name.contains("?"))
                ? SearchPattern.R_PATTERN_MATCH
                        | SearchPattern.R_CASE_SENSITIVE
                : SearchPattern.R_EXACT_MATCH
                        | SearchPattern.R_CASE_SENSITIVE;

        var arr = new JsonArray();
        SearchEngine engine = new SearchEngine();
        SearchPattern pattern = SearchPattern.createPattern(
                name,
                IJavaSearchConstants.TYPE,
                IJavaSearchConstants.DECLARATIONS,
                matchRule);
        if (pattern == null) {
            return HttpServer.jsonError("Invalid search pattern: " + name);
        }

        engine.search(pattern,
                new SearchParticipant[]{
                        SearchEngine.getDefaultSearchParticipant()},
                scope.searchScope(),
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getElement() instanceof IType type) {
                            if (sourceOnly && type.isBinary()) return;
                            arr.add(findEntry(type, resourcePath(match)));
                        }
                    }
                },
                null);

        return arr.toString();
    }

    /**
     * Detect package search patterns:
     * - "com.example.core.user" — dots, no wildcards, last segment lowercase
     * - "com.example.core.user." — trailing dot
     * - "com.example.core.user.*" — package + wildcard
     */
    static boolean isPackageSearch(String name) {
        String normalized = normalizePackage(name);
        if (!normalized.contains(".")) return false;
        String last = normalized.substring(
                normalized.lastIndexOf('.') + 1);
        return !last.isEmpty()
                && Character.isLowerCase(last.charAt(0));
    }

    /** Strip trailing ".", ".*", ".?" */
    static String normalizePackage(String name) {
        if (name.endsWith(".*") || name.endsWith(".?")) {
            return name.substring(0, name.length() - 2);
        }
        if (name.endsWith(".")) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    private String findByPackage(String pkgName, boolean sourceOnly)
            throws CoreException {
        var arr = new JsonArray();
        for (IJavaProject jp : JavaCore.create(
                ResourcesPlugin.getWorkspace().getRoot())
                .getJavaProjects()) {
            for (IPackageFragmentRoot root
                    : jp.getPackageFragmentRoots()) {
                if (sourceOnly
                        && root.getKind()
                                != IPackageFragmentRoot.K_SOURCE) {
                    continue;
                }
                IPackageFragment pkg = root.getPackageFragment(
                        pkgName);
                if (!pkg.exists()) continue;
                for (ICompilationUnit cu
                        : pkg.getCompilationUnits()) {
                    for (IType type : cu.getTypes()) {
                        arr.add(findEntry(type, resourcePath(type)));
                    }
                }
                if (!sourceOnly) {
                    for (var cf : pkg.getOrdinaryClassFiles()) {
                        arr.add(findEntry(cf.getType(),
                                cf.getType().getPath().toOSString()));
                    }
                }
            }
        }
        return arr.toString();
    }

    String handleReferences(Map<String, String> params,
            ProjectScope scope)
            throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError("Missing 'class' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError("Type not found: " + fqn);
        }

        String methodName = params.get("method");
        String fieldName = params.get("field");
        IJavaElement target;

        if (fieldName != null && !fieldName.isBlank()) {
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return HttpServer.jsonError("Field not found: " + fieldName
                        + " in " + fqn);
            }
            target = field;
        } else if (methodName != null && !methodName.isBlank()) {
            IMethod method = JdtUtils.findMethod(type, methodName,
                    params.get("paramTypes"));
            if (method == null) {
                return HttpServer.jsonError("Method not found: " + methodName
                        + " in " + fqn);
            }
            target = method;
        } else {
            target = type;
        }

        var arr = new JsonArray();
        SearchEngine engine = new SearchEngine();
        SearchPattern pattern = SearchPattern.createPattern(
                target, IJavaSearchConstants.REFERENCES);

        engine.search(pattern,
                new SearchParticipant[]{
                        SearchEngine.getDefaultSearchParticipant()},
                scope.searchScope(),
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getAccuracy()
                                != SearchMatch.A_ACCURATE) return;
                        if (match.isInsideDocComment()) return;

                        String project = getMatchProject(match);
                        String enclosing = getEnclosingName(match);
                        String content = getLineContent(match);

                        var entry = new JsonObject();
                        entry.addProperty("file",
                                getMatchFile(match));
                        entry.addProperty("line",
                                getLine(match));
                        if (project != null) {
                            entry.addProperty("project",
                                    project);
                        }
                        if (enclosing != null) {
                            entry.addProperty("in",
                                    enclosing);
                        }
                        if (content != null) {
                            entry.addProperty("content",
                                    content);
                        }
                        arr.add(entry);
                    }
                },
                null);

        return arr.toString();
    }

    String handleSubtypes(Map<String, String> params,
            ProjectScope scope)
            throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError("Missing 'class' parameter");
        }
        if ("java.lang.Object".equals(fqn)) {
            return HttpServer.jsonError(
                    "Hierarchy too broad for java.lang.Object");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError("Type not found: " + fqn);
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);
        var arr = new JsonArray();
        for (IType sub : hierarchy.getAllSubtypes(type)) {
            arr.add(typeEntry(sub));
        }
        return arr.toString();
    }

    String handleHierarchy(Map<String, String> params,
            ProjectScope scope)
            throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError("Missing 'class' parameter");
        }
        if ("java.lang.Object".equals(fqn)) {
            return HttpServer.jsonError(
                    "Hierarchy too broad for java.lang.Object");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError("Type not found: " + fqn);
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);

        var supers = new JsonArray();
        SourceReport.addSupersRecursive(
                supers, hierarchy, type, 0);

        var subtypes = new JsonArray();
        SourceReport.addSubsRecursive(
                subtypes, hierarchy, type, 0);

        var result = new JsonObject();
        result.addProperty("fqn", fqn);
        result.add("supertypes", supers);
        result.add("subtypes", subtypes);
        return result.toString();
    }

    String handleImplementors(Map<String, String> params,
            ProjectScope scope)
            throws CoreException {
        String fqn = params.get("class");
        String methodName = params.get("method");
        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError("Missing 'class' parameter");
        }
        if (methodName == null || methodName.isBlank()) {
            return HttpServer.jsonError("Missing 'method' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError("Type not found: " + fqn);
        }

        IMethod method = JdtUtils.findMethod(type, methodName,
                params.get("paramTypes"));
        if (method == null) {
            return HttpServer.jsonError("Method not found: " + methodName
                    + " in " + fqn);
        }

        var impls = JdtUtils.findImplementations(method);
        var arr = new JsonArray();
        for (var entry : impls.entrySet()) {
            IMethod m = entry.getValue();
            IType sub = m.getDeclaringType();
            if (sub.isAnonymous()) continue;
            var e = new JsonObject();
            e.addProperty("fqn",
                    sub.getFullyQualifiedName());
            e.addProperty("file", filePath(sub));
            e.addProperty("line", getLineOfMember(m));
            arr.add(e);
        }
        return arr.toString();
    }

    String handleTypeInfo(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError("Missing 'class' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError("Type not found: " + fqn);
        }

        var result = new JsonObject();
        result.addProperty("fqn",
                type.getFullyQualifiedName());
        result.addProperty("kind", JdtUtils.typeKind(type));
        result.addProperty("file", filePath(type));
        if (type.isBinary())
            result.addProperty("binary", true);

        String superSig = type.getSuperclassTypeSignature();
        if (superSig != null) {
            result.addProperty("superclass",
                    Signature.toString(superSig));
        }

        var interfaces = new JsonArray();
        for (String sig
                : type.getSuperInterfaceTypeSignatures()) {
            interfaces.add(Signature.toString(sig));
        }
        result.add("interfaces", interfaces);

        var fields = new JsonArray();
        for (IField f : type.getFields()) {
            String mods = Flags.toString(f.getFlags());
            var field = new JsonObject();
            field.addProperty("name", f.getElementName());
            field.addProperty("type", Signature.toString(
                    f.getTypeSignature()));
            if (!mods.isEmpty()) {
                field.addProperty("modifiers", mods);
            }
            field.addProperty("line", getLineOfMember(f));
            fields.add(field);
        }
        result.add("fields", fields);

        var methods = new JsonArray();
        for (IMethod m : type.getMethods()) {
            var me = new JsonObject();
            me.addProperty("name", m.getElementName());
            me.addProperty("signature",
                    buildSignature(m));
            me.addProperty("line", getLineOfMember(m));
            methods.add(me);
        }
        result.add("methods", methods);

        return result.toString();
    }

    // ---- /source?class=FQN[&method=name] ----

    HttpServer.Response handleSource(Map<String, String> params,
            ProjectScope scope)
            throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");

        if (fqn == null || fqn.isBlank()) {
            return HttpServer.Response.json(
                    HttpServer.jsonError("Missing 'class' parameter"));
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.Response.json(
                    HttpServer.jsonError("Type not found: " + fqn));
        }

        // Refresh from disk — Claude Code (or other editors) may have
        // changed the file since Eclipse last synced. DEPTH_ZERO is cheap.
        if (type.getResource() != null) {
            type.getResource().refreshLocal(
                    org.eclipse.core.resources.IResource.DEPTH_ZERO,
                    null);
        }

        String absPath = absolutePath(type);
        String fullSource = getFullSource(type);

        if (methodName != null && !methodName.isBlank()) {
            List<IMethod> methods = JdtUtils.findMethods(
                    type, methodName,
                    params.get("paramTypes"));
            if (methods.isEmpty()) {
                return HttpServer.Response.json(
                        HttpServer.jsonError("Method not found: " + methodName
                                + " in " + fqn));
            }

            if (methods.size() == 1) {
                return resolvedResponse(
                        methods.get(0), fqn, methodName,
                        absPath, fullSource, params, scope);
            }

            // Multiple overloads — JSON array, each fully enriched
            StringBuilder arrJson = new StringBuilder("[");
            boolean first = true;
            for (IMethod method : methods) {
                int[] lines = memberLines(method, fullSource);
                String src = sourceFromDisk(absPath,
                        lines[0], lines[1]);
                if (src == null) src = substringWithIndent(
                        method, fullSource);
                if (src == null) continue;
                String sig = JdtUtils.compactSignature(method);
                String mFqmn = fqn + "#" + sig;
                var refs = ReferenceCollector.collect(method);
                var incoming = collectIncomingRefs(method, scope);
                String json = SourceReport.toJson(
                        mFqmn, method, absPath, src,
                        lines[0], lines[1], refs, incoming);
                if (!first) arrJson.append(",");
                arrJson.append(json);
                first = false;
            }
            arrJson.append("]");
            return HttpServer.Response.json(arrJson.toString());
        }

        // Full class source
        return resolvedResponse(type, fqn, null,
                absPath, fullSource, params, scope);
    }

    private HttpServer.Response resolvedResponse(IMember member,
            String fqn, String methodName, String absPath,
            String fullSource, Map<String, String> params,
            ProjectScope scope) throws Exception {
        int[] lines = memberLines(member, fullSource);
        // Top-level type: include package + imports (from line 1)
        // Inner classes keep their declaration range
        if (member instanceof IType t && methodName == null
                && t.getDeclaringType() == null) {
            lines[0] = 1;
        }
        // Read from disk to preserve indentation
        String source = sourceFromDisk(absPath,
                lines[0], lines[1]);
        if (source == null) {
            // Fallback: extract from fullSource with indent
            source = substringWithIndent(member, fullSource);
        }
        if (source == null) {
            return HttpServer.Response.json(
                    HttpServer.jsonError("Source not available"));
        }
        String fqmn = methodName != null
                ? fqn + "#" + methodName
                        + JdtUtils.compactSignature((IMethod) member)
                                .substring(
                                        ((IMethod) member)
                                                .getElementName()
                                                .length())
                : fqn;

        var refs = ReferenceCollector.collect(member);
        var incoming = collectIncomingRefs(member, scope);
        String json = SourceReport.toJson(
                fqmn, member, absPath, source,
                lines[0], lines[1], refs, incoming);

        return HttpServer.Response.json(json);
    }

    /**
     * Collect incoming references (callers) for a member using
     * SearchEngine. Returns enriched refs for the JSON output.
     */
    private List<SourceReport.IncomingRef> collectIncomingRefs(
            IMember member, ProjectScope scope) {
        var result = new ArrayList<SourceReport.IncomingRef>();
        var seen = new java.util.HashSet<String>();
        try {
            SearchEngine engine = new SearchEngine();
            SearchPattern pattern = SearchPattern.createPattern(
                    member, IJavaSearchConstants.REFERENCES);
            if (pattern == null) return result;

            engine.search(pattern,
                    new SearchParticipant[]{
                            SearchEngine
                                    .getDefaultSearchParticipant()},
                    scope.searchScope(),
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(
                                SearchMatch match) {
                            if (match.getAccuracy()
                                    != SearchMatch.A_ACCURATE)
                                return;
                            if (match.isInsideDocComment())
                                return;

                            String enclosing =
                                    getEnclosingName(match);
                            if (enclosing == null) return;

                            String file = getMatchFile(match);
                            int line = getLine(match);

                            boolean isProject = false;
                            String typeKind = null;
                            try {
                                if (match.getElement()
                                        instanceof IMember m) {
                                    IType dt =
                                            m.getDeclaringType();
                                    if (dt == null
                                            && m instanceof IType t)
                                        dt = t;
                                    if (dt != null) {
                                        isProject =
                                                !dt.isBinary();
                                        typeKind =
                                                JdtUtils.typeKind(
                                                        dt);
                                    }
                                }
                            } catch (Exception e) { /* skip */ }

                            if (seen.add(enclosing)) {
                                result.add(
                                        new SourceReport.IncomingRef(
                                                enclosing, file,
                                                line, typeKind,
                                                isProject));
                            }
                        }
                    },
                    null);
        } catch (Exception e) {
            Log.warn("collectIncomingRefs failed", e);
        }
        return result;
    }

    private String absolutePath(IType type) {
        if (type.getResource() != null) {
            return type.getResource().getLocation().toOSString();
        }
        return filePath(type);
    }

    /**
     * Extract source from fullSource preserving leading indent.
     * Eclipse's getSourceRange() offset skips leading whitespace.
     * We back up to the line start to include the indent.
     */
    private String substringWithIndent(IMember member,
            String fullSource) throws Exception {
        if (fullSource == null) return member.getSource();
        ISourceRange range = member.getSourceRange();
        if (range == null || range.getOffset() < 0)
            return member.getSource();
        int offset = range.getOffset();
        int end = Math.min(offset + range.getLength(),
                fullSource.length());
        int lineStart = fullSource.lastIndexOf('\n', offset - 1);
        int from = (lineStart >= 0) ? lineStart + 1 : 0;
        return fullSource.substring(from, end);
    }

    /**
     * Read source lines directly from disk. Eclipse API strips
     * leading whitespace; reading the file preserves indentation.
     */
    private String sourceFromDisk(String absPath,
            int startLine, int endLine) {
        if (startLine < 1 || endLine < 1) return null;
        try {
            var path = java.nio.file.Path.of(absPath);
            if (!java.nio.file.Files.exists(path)) return null;
            var lines = java.nio.file.Files.readAllLines(path);
            int from = Math.max(0, startLine - 1);
            int to = Math.min(lines.size(), endLine);
            return String.join("\n",
                    lines.subList(from, to)) + "\n";
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Helpers ----

    /** Build a find result entry with FQN, kind, origin, binary. */
    private static JsonObject findEntry(IType type, String file) {
        var obj = new JsonObject();
        obj.addProperty("fqn", type.getFullyQualifiedName());
        obj.addProperty("file", file);
        obj.addProperty("kind", SourceReport.typeKindStr(type));
        if (type.isBinary()) {
            obj.addProperty("binary", true);
            try {
                var pkg = type.getPackageFragment();
                var root = (IPackageFragmentRoot) pkg.getParent();
                var path = root.getPath();
                obj.addProperty("origin", path.lastSegment());
            } catch (Exception e) {
                obj.addProperty("origin", "binary");
            }
        } else {
            obj.addProperty("origin",
                    type.getJavaProject().getElementName());
        }
        return obj;
    }

    private JsonObject typeEntry(IType type) {
        var obj = new JsonObject();
        obj.addProperty("fqn",
                type.getFullyQualifiedName());
        obj.addProperty("file", filePath(type));
        if (type.isBinary())
            obj.addProperty("binary", true);
        return obj;
    }

    private String filePath(IType type) {
        if (type.getResource() != null
                && type.getResource().getLocation() != null) {
            return type.getResource().getLocation().toOSString();
        }
        return type.getFullyQualifiedName().replace('.', '/')
                + ".java";
    }

    private int[] memberLines(IMember member, String fullSource)
            throws Exception {
        ISourceRange range = member.getSourceRange();
        if (fullSource != null && range != null
                && range.getOffset() >= 0) {
            return new int[]{
                    offsetToLine(fullSource, range.getOffset()),
                    offsetToLine(fullSource,
                            range.getOffset() + range.getLength())
            };
        }
        return new int[]{-1, -1};
    }

    private String getFullSource(IType type) throws Exception {
        ICompilationUnit cu = type.getCompilationUnit();
        if (cu != null) return cu.getSource();
        IClassFile cf = type.getClassFile();
        if (cf != null) return cf.getSource();
        return null;
    }

    private String buildSignature(IMethod m)
            throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        String mods = Flags.toString(m.getFlags());
        if (!mods.isEmpty()) sig.append(mods).append(" ");
        if (!m.isConstructor()) {
            sig.append(Signature.toString(m.getReturnType()))
                    .append(" ");
        }
        sig.append(m.getElementName()).append("(");
        String[] paramTypes = m.getParameterTypes();
        String[] paramNames = m.getParameterNames();
        for (int j = 0; j < paramTypes.length; j++) {
            if (j > 0) sig.append(", ");
            sig.append(Signature.toString(paramTypes[j]))
                    .append(" ").append(paramNames[j]);
        }
        sig.append(")");
        return sig.toString();
    }

    private int getLineOfMember(IMember member) {
        try {
            ISourceRange range = member.getNameRange();
            if (range != null && range.getOffset() >= 0) {
                ICompilationUnit cu = member.getCompilationUnit();
                String source;
                if (cu != null) {
                    source = cu.getSource();
                } else {
                    IClassFile cf = member.getClassFile();
                    source = cf != null ? cf.getSource() : null;
                }
                if (source != null) {
                    return offsetToLine(source, range.getOffset());
                }
            }
        } catch (JavaModelException e) {
            Log.warn("getLineOfMember failed", e);
        }
        return -1;
    }

    private int offsetToLine(String source, int offset) {
        int line = 1;
        int limit = Math.min(offset, source.length());
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private String getMatchProject(SearchMatch match) {
        if (match.getElement() instanceof IMember member) {
            IJavaProject jp = member.getJavaProject();
            if (jp != null) return jp.getElementName();
        }
        return null;
    }

    private String getEnclosingName(SearchMatch match) {
        if (!(match.getElement() instanceof IMember member)) {
            return null;
        }
        IType declaringType = member.getDeclaringType();
        String typeFqn = declaringType != null
                ? declaringType.getFullyQualifiedName()
                : (member instanceof IType t
                        ? t.getFullyQualifiedName()
                        : member.getElementName());
        if (member instanceof IMethod m) {
            try {
                return typeFqn + "#"
                        + JdtUtils.compactSignature(m);
            } catch (JavaModelException e) {
                return typeFqn + "#" + m.getElementName() + "()";
            }
        }
        if (member instanceof IField f) {
            return typeFqn + "#" + f.getElementName();
        }
        if (member instanceof IType t) {
            return t.getFullyQualifiedName();
        }
        return null;
    }

    private String getMatchFile(SearchMatch match) {
        if (match.getElement() instanceof IMember member
                && member.isBinary()) {
            try {
                IPackageFragmentRoot root = (IPackageFragmentRoot)
                        member.getAncestor(
                                IJavaElement.PACKAGE_FRAGMENT_ROOT);
                if (root != null && root.getPath() != null) {
                    return root.getPath().toOSString();
                }
            } catch (Exception e) {
                Log.warn("getMatchFile failed", e);
            }
        }
        return resourcePath(match);
    }

    private String getLineContent(SearchMatch match) {
        try {
            String source = null;
            if (match.getResource() instanceof IFile file) {
                ICompilationUnit cu =
                        JavaCore.createCompilationUnitFrom(file);
                source = cu.getSource();
            } else if (match.getElement() instanceof IMember member) {
                IClassFile cf = member.getClassFile();
                if (cf != null) source = cf.getSource();
            }
            if (source != null) {
                int offset = match.getOffset();
                int lineStart =
                        source.lastIndexOf('\n', offset - 1) + 1;
                int lineEnd = source.indexOf('\n', offset);
                if (lineEnd < 0) lineEnd = source.length();
                return source.substring(lineStart, lineEnd).trim();
            }
        } catch (Exception e) {
            Log.warn("getLineContent failed", e);
        }
        return null;
    }

    private int getLine(SearchMatch match) {
        try {
            if (match.getResource() instanceof IFile file) {
                ICompilationUnit cu =
                        JavaCore.createCompilationUnitFrom(file);
                String source = cu.getSource();
                if (source != null) {
                    return offsetToLine(source, match.getOffset());
                }
            }
        } catch (Exception e) {
            Log.warn("getLine failed", e);
        }
        return -1;
    }

    private static String resourcePath(SearchMatch match) {
        if (match.getResource() != null
                && match.getResource().getLocation() != null) {
            return match.getResource().getLocation().toOSString();
        }
        return "?";
    }

    /**
     * Resolve absolute filesystem path for a type. Falls back to
     * the workspace-relative path for binary types without source.
     */
    static String resourcePath(IType type) {
        try {
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu != null && cu.getResource() != null
                    && cu.getResource().getLocation() != null) {
                return cu.getResource().getLocation()
                        .toOSString();
            }
        } catch (Exception e) { /* fall through */ }
        return type.getPath().toString();
    }
}
