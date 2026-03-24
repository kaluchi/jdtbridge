package io.github.kaluchi.jdtbridge;

import java.util.List;
import java.util.Map;

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

    String handleProjects() throws Exception {
        Json arr = Json.array();
        for (IJavaProject p : JavaCore.create(
                ResourcesPlugin.getWorkspace().getRoot())
                .getJavaProjects()) {
            arr.add(p.getElementName());
        }
        return arr.toString();
    }

    String handleFind(Map<String, String> params) throws CoreException {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return Json.error("Missing 'name' parameter");
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

        Json arr = Json.array();
        SearchEngine engine = new SearchEngine();
        SearchPattern pattern = SearchPattern.createPattern(
                name,
                IJavaSearchConstants.TYPE,
                IJavaSearchConstants.DECLARATIONS,
                matchRule);
        if (pattern == null) {
            return Json.error("Invalid search pattern: " + name);
        }

        engine.search(pattern,
                new SearchParticipant[]{
                        SearchEngine.getDefaultSearchParticipant()},
                SearchEngine.createWorkspaceScope(),
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getElement() instanceof IType type) {
                            if (sourceOnly && type.isBinary()) return;
                            arr.add(Json.object()
                                    .put("fqn",
                                            type.getFullyQualifiedName())
                                    .put("file",
                                            resourcePath(match)));
                        }
                    }
                },
                null);

        return arr.toString();
    }

    /**
     * Detect package search patterns:
     * - "app.m8.core.user" — dots, no wildcards, last segment lowercase
     * - "app.m8.core.user." — trailing dot
     * - "app.m8.core.user.*" — package + wildcard
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
        Json arr = Json.array();
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
                        arr.add(Json.object()
                                .put("fqn",
                                        type.getFullyQualifiedName())
                                .put("file",
                                        resourcePath(type)));
                    }
                }
                if (!sourceOnly) {
                    for (var cf : pkg.getClassFiles()) {
                        IType type = cf.getType();
                        arr.add(Json.object()
                                .put("fqn",
                                        type.getFullyQualifiedName())
                                .put("file", type.getPath()
                                        .toOSString()));
                    }
                }
            }
        }
        return arr.toString();
    }

    String handleReferences(Map<String, String> params)
            throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return Json.error("Missing 'class' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return Json.error("Type not found: " + fqn);
        }

        String methodName = params.get("method");
        String fieldName = params.get("field");
        IJavaElement target;

        if (fieldName != null && !fieldName.isBlank()) {
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return Json.error("Field not found: " + fieldName
                        + " in " + fqn);
            }
            target = field;
        } else if (methodName != null && !methodName.isBlank()) {
            IMethod method = JdtUtils.findMethod(type, methodName,
                    params.get("paramTypes"));
            if (method == null) {
                return Json.error("Method not found: " + methodName
                        + " in " + fqn);
            }
            target = method;
        } else {
            target = type;
        }

        Json arr = Json.array();
        SearchEngine engine = new SearchEngine();
        SearchPattern pattern = SearchPattern.createPattern(
                target, IJavaSearchConstants.REFERENCES);

        engine.search(pattern,
                new SearchParticipant[]{
                        SearchEngine.getDefaultSearchParticipant()},
                SearchEngine.createWorkspaceScope(),
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getAccuracy()
                                != SearchMatch.A_ACCURATE) return;
                        if (match.isInsideDocComment()) return;

                        String project = getMatchProject(match);
                        String enclosing = getEnclosingName(match);
                        String content = getLineContent(match);

                        Json entry = Json.object()
                                .put("file", getMatchFile(match))
                                .put("line", getLine(match));
                        if (project != null) {
                            entry.put("project", project);
                        }
                        if (enclosing != null) {
                            entry.put("in", enclosing);
                        }
                        if (content != null) {
                            entry.put("content", content);
                        }
                        arr.add(entry);
                    }
                },
                null);

        return arr.toString();
    }

    String handleSubtypes(Map<String, String> params)
            throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return Json.error("Missing 'class' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return Json.error("Type not found: " + fqn);
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);
        Json arr = Json.array();
        for (IType sub : hierarchy.getAllSubtypes(type)) {
            arr.add(typeEntry(sub));
        }
        return arr.toString();
    }

    String handleHierarchy(Map<String, String> params)
            throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return Json.error("Missing 'class' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return Json.error("Type not found: " + fqn);
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);

        // Superclass chain
        Json supers = Json.array();
        IType current = type;
        while (true) {
            IType superType = hierarchy.getSuperclass(current);
            if (superType == null) break;
            supers.add(typeEntry(superType));
            current = superType;
        }

        // All super interfaces
        Json interfaces = Json.array();
        for (IType iface : hierarchy.getAllSuperInterfaces(type)) {
            interfaces.add(typeEntry(iface));
        }

        // Subtypes
        Json subtypes = Json.array();
        for (IType sub : hierarchy.getAllSubtypes(type)) {
            subtypes.add(typeEntry(sub));
        }

        return Json.object()
                .put("supers", supers)
                .put("interfaces", interfaces)
                .put("subtypes", subtypes)
                .toString();
    }

    String handleImplementors(Map<String, String> params)
            throws CoreException {
        String fqn = params.get("class");
        String methodName = params.get("method");
        if (fqn == null || fqn.isBlank()) {
            return Json.error("Missing 'class' parameter");
        }
        if (methodName == null || methodName.isBlank()) {
            return Json.error("Missing 'method' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return Json.error("Type not found: " + fqn);
        }

        IMethod method = JdtUtils.findMethod(type, methodName,
                params.get("paramTypes"));
        if (method == null) {
            return Json.error("Method not found: " + methodName
                    + " in " + fqn);
        }

        int arity = method.getNumberOfParameters();
        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);
        Json arr = Json.array();
        for (IType sub : hierarchy.getAllSubtypes(type)) {
            if (sub.isAnonymous()) continue;
            try {
                for (IMethod m : sub.getMethods()) {
                    if (m.getElementName().equals(methodName)
                            && m.getNumberOfParameters()
                                    == arity) {
                        arr.add(Json.object()
                                .put("fqn",
                                        sub.getFullyQualifiedName())
                                .put("file", filePath(sub))
                                .put("line", getLineOfMember(m)));
                        break;
                    }
                }
            } catch (JavaModelException e) {
                Log.warn("Skipping type "
                        + sub.getFullyQualifiedName(), e);
            }
        }
        return arr.toString();
    }

    String handleTypeInfo(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return Json.error("Missing 'class' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return Json.error("Type not found: " + fqn);
        }

        Json result = Json.object()
                .put("fqn", type.getFullyQualifiedName())
                .put("kind", JdtUtils.typeKind(type))
                .put("file", filePath(type))
                .putIf(type.isBinary(), "binary", true);

        // Superclass
        String superSig = type.getSuperclassTypeSignature();
        if (superSig != null) {
            result.put("superclass", Signature.toString(superSig));
        }

        // Interfaces
        Json interfaces = Json.array();
        for (String sig : type.getSuperInterfaceTypeSignatures()) {
            interfaces.add(Signature.toString(sig));
        }
        result.put("interfaces", interfaces);

        // Fields
        Json fields = Json.array();
        for (IField f : type.getFields()) {
            String mods = Flags.toString(f.getFlags());
            Json field = Json.object()
                    .put("name", f.getElementName())
                    .put("type", Signature.toString(
                            f.getTypeSignature()));
            if (!mods.isEmpty()) {
                field.put("modifiers", mods);
            }
            field.put("line", getLineOfMember(f));
            fields.add(field);
        }
        result.put("fields", fields);

        // Methods
        Json methods = Json.array();
        for (IMethod m : type.getMethods()) {
            methods.add(Json.object()
                    .put("name", m.getElementName())
                    .put("signature", buildSignature(m))
                    .put("line", getLineOfMember(m)));
        }
        result.put("methods", methods);

        return result.toString();
    }

    // ---- /source?class=FQN[&method=name] ----

    HttpServer.Response handleSource(Map<String, String> params)
            throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");

        if (fqn == null || fqn.isBlank()) {
            return HttpServer.Response.json(
                    Json.error("Missing 'class' parameter"));
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.Response.json(
                    Json.error("Type not found: " + fqn));
        }

        String file = filePath(type);
        String fullSource = getFullSource(type);

        if (methodName != null && !methodName.isBlank()) {
            List<IMethod> methods = JdtUtils.findMethods(
                    type, methodName,
                    params.get("paramTypes"));
            if (methods.isEmpty()) {
                return HttpServer.Response.json(
                        Json.error("Method not found: " + methodName
                                + " in " + fqn));
            }

            if (methods.size() == 1) {
                return singleMemberResponse(
                        methods.get(0), file, fullSource);
            }

            // Multiple overloads — each prefixed with :start-end
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < methods.size(); i++) {
                if (i > 0) sb.append("\n\n");
                IMethod method = methods.get(i);
                String source = method.getSource();
                if (source == null) continue;
                int[] lines = memberLines(method, fullSource);
                sb.append(":").append(lines[0])
                        .append("-").append(lines[1]).append("\n");
                sb.append(source);
            }
            return HttpServer.Response.text(sb.toString(), Map.of(
                    "X-File", file,
                    "X-Start-Line", "-1",
                    "X-End-Line", "-1"));
        }

        // Full class source
        return singleMemberResponse(type, file, fullSource);
    }

    // ---- Helpers ----

    private Json typeEntry(IType type) {
        Json obj = Json.object()
                .put("fqn", type.getFullyQualifiedName())
                .put("file", filePath(type))
                .putIf(type.isBinary(), "binary", true);
        return obj;
    }

    private String filePath(IType type) {
        if (type.getResource() != null) {
            return type.getResource().getFullPath().toString();
        }
        return type.getFullyQualifiedName().replace('.', '/')
                + ".java";
    }

    private HttpServer.Response singleMemberResponse(
            IMember member, String file, String fullSource)
            throws Exception {
        String source = member.getSource();
        if (source == null) {
            return HttpServer.Response.json(
                    Json.error("Source not available"));
        }
        int[] lines = memberLines(member, fullSource);
        return HttpServer.Response.text(source, Map.of(
                "X-File", file,
                "X-Start-Line", String.valueOf(lines[0]),
                "X-End-Line", String.valueOf(lines[1])));
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
                return typeFqn + "."
                        + JdtUtils.compactSignature(m);
            } catch (JavaModelException e) {
                return typeFqn + "." + m.getElementName() + "()";
            }
        }
        if (member instanceof IField f) {
            return typeFqn + "." + f.getElementName();
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
        return match.getResource() != null
                ? match.getResource().getFullPath().toString()
                : "?";
    }

    private static String resourcePath(IType type) {
        try {
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu != null && cu.getResource() != null) {
                return cu.getResource().getFullPath().toString();
            }
        } catch (Exception e) { /* fall through */ }
        return type.getPath().toString();
    }
}
