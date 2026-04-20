package io.github.kaluchi.jdtbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

/**
 * Handlers for the graph-axis endpoints. Each endpoint answers one
 * primitive question against the JDT model and returns a node (or
 * Vec of nodes) in the canonical shape produced by
 * {@link NodeBuilder}. Errors come back as
 * {@link ErrorDescriptor} JSON.
 * <p>
 * Endpoint surface (one primitive per HTTP path). Polymorphic
 * routing by kind is done on the qlang side via the {@code @detail}
 * conduit in {@code graph.qlang}; there is no server-side
 * {@code /detail} endpoint.
 * <ul>
 *   <li>{@code /type?of=<fqn>}     — single :type detail</li>
 *   <li>{@code /method?of=<fqn>}   — single :method detail; supports
 *       {@code paramTypes} for overload disambiguation</li>
 *   <li>{@code /field?of=<fqn>}    — single :field detail</li>
 *   <li>{@code /members /methods /fields /innerTypes} — containment down</li>
 *   <li>{@code /supers /subtypes /implementors /overrides /overloads} — hierarchy</li>
 *   <li>{@code /refs /outgoingRefs} — references</li>
 *   <li>{@code /types /projects /project /package /file /classpath} — search &amp; resources</li>
 *   <li>{@code /source /problems} — text &amp; diagnostics</li>
 * </ul>
 */
class GraphHandler {

    /** Single point-lookup for a type by its FQN. Returns detail node. */
    String handleType(Map<String, String> params) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            IType type = JdtUtils.findType(fqn);
            if (type == null) {
                return ErrorDescriptor.typeNotFound(fqn).toJsonString();
            }
            return NodeBuilder.typeDetail(type).toString();
        } catch (Exception e) {
            Log.warn("/type failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed to resolve type: " + fqn, e).toJsonString();
        }
    }

    /**
     * Single point-lookup for a method by FQN. Optional
     * {@code paramTypes} param disambiguates overloads; absent means
     * "any overload" — fails with AmbiguousMatch when multiple exist.
     */
    String handleMethod(Map<String, String> params) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        int hash = fqn.indexOf('#');
        if (hash < 0) {
            return ErrorDescriptor.invalidFqn(fqn).toJsonString();
        }
        String typeFqn = fqn.substring(0, hash);
        String memberPart = fqn.substring(hash + 1);
        int paren = memberPart.indexOf('(');
        String methodName = paren < 0
                ? memberPart : memberPart.substring(0, paren);
        String paramTypesParam = params.get("paramTypes");
        if (paramTypesParam == null && paren >= 0) {
            int closeParen = memberPart.lastIndexOf(')');
            String inner = closeParen > paren
                    ? memberPart.substring(paren + 1, closeParen)
                    : memberPart.substring(paren + 1);
            paramTypesParam = inner;
        }
        try {
            IType type = JdtUtils.findType(typeFqn);
            if (type == null) {
                return ErrorDescriptor.typeNotFound(typeFqn).toJsonString();
            }
            var matches = JdtUtils.findMethods(type, methodName,
                    paramTypesParam);
            if (matches.isEmpty()) {
                return ErrorDescriptor.methodNotFound(fqn).toJsonString();
            }
            if (matches.size() > 1) {
                return ErrorDescriptor.ambiguousMatch(fqn,
                        fqnsOf(matches)).toJsonString();
            }
            return NodeBuilder.methodDetail(matches.get(0)).toString();
        } catch (Exception e) {
            Log.warn("/method failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed to resolve method: " + fqn, e).toJsonString();
        }
    }

    /** Single point-lookup for a field by FQN. */
    String handleField(Map<String, String> params) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        int hash = fqn.indexOf('#');
        if (hash < 0) {
            return ErrorDescriptor.invalidFqn(fqn).toJsonString();
        }
        String typeFqn = fqn.substring(0, hash);
        String fieldName = fqn.substring(hash + 1);
        if (fieldName.contains("(")) {
            return ErrorDescriptor.invalidFqn(fqn)
                    .with("reason", "field FQN must not contain parens")
                    .toJsonString();
        }
        try {
            IType type = JdtUtils.findType(typeFqn);
            if (type == null) {
                return ErrorDescriptor.typeNotFound(typeFqn).toJsonString();
            }
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return ErrorDescriptor.fieldNotFound(fqn).toJsonString();
            }
            return NodeBuilder.fieldDetail(field).toString();
        } catch (Exception e) {
            Log.warn("/field failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed to resolve field: " + fqn, e).toJsonString();
        }
    }

    // ── Down-navigation: direct contents of a type ──────────────────

    /** All direct members of a type — methods + fields + inner types. */
    String handleMembers(Map<String, String> params) {
        return listForType(params, "/members", type -> {
            var arr = new JsonArray();
            for (IMethod m : type.getMethods()) {
                arr.add(NodeBuilder.methodSkeleton(m));
            }
            for (IField f : type.getFields()) {
                arr.add(NodeBuilder.fieldSkeleton(f));
            }
            for (IType inner : type.getTypes()) {
                arr.add(NodeBuilder.typeSkeleton(inner));
            }
            return arr;
        });
    }

    String handleMethods(Map<String, String> params) {
        return listForType(params, "/methods", type -> {
            var arr = new JsonArray();
            for (IMethod m : type.getMethods()) {
                arr.add(NodeBuilder.methodSkeleton(m));
            }
            return arr;
        });
    }

    String handleFields(Map<String, String> params) {
        return listForType(params, "/fields", type -> {
            var arr = new JsonArray();
            for (IField f : type.getFields()) {
                arr.add(NodeBuilder.fieldSkeleton(f));
            }
            return arr;
        });
    }

    String handleInnerTypes(Map<String, String> params) {
        return listForType(params, "/innerTypes", type -> {
            var arr = new JsonArray();
            for (IType inner : type.getTypes()) {
                arr.add(NodeBuilder.typeSkeleton(inner));
            }
            return arr;
        });
    }

    // ── Hierarchy: direct supers and subtypes ───────────────────────

    /** Direct supertypes — superclass plus directly-declared interfaces. */
    String handleSupers(Map<String, String> params) {
        return listForType(params, "/supers", type -> {
            ITypeHierarchy hier = type.newSupertypeHierarchy(null);
            var arr = new JsonArray();
            IType superClass = hier.getSuperclass(type);
            if (superClass != null) {
                arr.add(NodeBuilder.typeSkeleton(superClass));
            }
            for (IType iface : hier.getSuperInterfaces(type)) {
                arr.add(NodeBuilder.typeSkeleton(iface));
            }
            return arr;
        });
    }

    /** Direct subtypes only — transitive descent is a qlang conduit. */
    String handleSubtypes(Map<String, String> params) {
        return listForType(params, "/subtypes", type -> {
            ITypeHierarchy hier = type.newTypeHierarchy(null);
            var arr = new JsonArray();
            for (IType sub : hier.getSubtypes(type)) {
                arr.add(NodeBuilder.typeSkeleton(sub));
            }
            return arr;
        });
    }

    // ── Method-on-method navigation: overrides / overloads / implementors ──

    /**
     * The closest super-method this method overrides, or
     * {@code null}-as-JSON-null when there is no override.
     * Walks superclass chain first (deterministic), then
     * super-interfaces.
     */
    String handleOverrides(Map<String, String> params) {
        return resolveTargetMethod(params, "/overrides", method -> {
            try {
                IType declaring = method.getDeclaringType();
                if (declaring == null) return "null";
                String name = method.getElementName();
                String resolvedSig = NodeBuilder.erasedParams(method);
                ITypeHierarchy hier =
                        declaring.newSupertypeHierarchy(null);

                IType current = declaring;
                while (true) {
                    IType superClass = hier.getSuperclass(current);
                    if (superClass == null) break;
                    IMethod found = matchingMethod(
                            superClass, name, resolvedSig);
                    if (found != null) {
                        return NodeBuilder.methodSkeleton(found)
                                .toString();
                    }
                    current = superClass;
                }
                for (IType iface
                        : hier.getAllSuperInterfaces(declaring)) {
                    IMethod found = matchingMethod(
                            iface, name, resolvedSig);
                    if (found != null) {
                        return NodeBuilder.methodSkeleton(found)
                                .toString();
                    }
                }
                return "null";
            } catch (Exception e) {
                Log.warn("/overrides walk failed", e);
                return ErrorDescriptor.jdtInternalError(
                        "Failed /overrides for "
                                + method.getElementName(), e)
                        .toJsonString();
            }
        });
    }

    /**
     * Match by resolved FQN-erased param signature so source-side
     * methods compare correctly against binary super methods (the
     * {@code Q}-vs-{@code L} signature divergence that defeats
     * {@code ReferenceCollector.paramSig}).
     */
    private static IMethod matchingMethod(IType type, String name,
            String resolvedSig) throws JavaModelException {
        for (IMethod m : type.getMethods()) {
            if (m.getElementName().equals(name)
                    && NodeBuilder.erasedParams(m).equals(resolvedSig)) {
                return m;
            }
        }
        return null;
    }

    /** Sibling overloads in the containing type (same name, including the queried method). */
    String handleOverloads(Map<String, String> params) {
        return resolveTargetMethod(params, "/overloads", method -> {
            try {
                IType declaring = method.getDeclaringType();
                if (declaring == null) return "[]";
                String name = method.getElementName();
                var arr = new JsonArray();
                for (IMethod sibling : declaring.getMethods()) {
                    if (sibling.getElementName().equals(name)) {
                        arr.add(NodeBuilder.methodSkeleton(sibling));
                    }
                }
                return arr.toString();
            } catch (JavaModelException e) {
                Log.warn("/overloads failed", e);
                return ErrorDescriptor.jdtInternalError(
                        "Failed /overloads", e).toJsonString();
            }
        });
    }

    /**
     * Implementors: subtypes for a type, override-methods for a
     * method. Returns ALL subtypes (including abstract) — filtering
     * to concrete is a qlang predicate
     * {@code filter(/modifiers | has(:abstract) | not)}.
     */
    String handleImplementors(Map<String, String> params) {
        String identifier = params.get("of");
        if (identifier == null || identifier.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        if (identifier.contains("#")) {
            return resolveTargetMethod(params, "/implementors", method -> {
                try {
                    var impls = JdtUtils.findImplementations(method);
                    var arr = new JsonArray();
                    for (var entry : impls.entrySet()) {
                        IMethod m = entry.getValue();
                        IType sub = m.getDeclaringType();
                        if (sub == null || sub.isAnonymous()) continue;
                        arr.add(NodeBuilder.methodSkeleton(m));
                    }
                    return arr.toString();
                } catch (JavaModelException e) {
                    Log.warn("/implementors method failed", e);
                    return ErrorDescriptor.jdtInternalError(
                            "Failed /implementors", e).toJsonString();
                }
            });
        }
        // Type mode: all subtypes
        return listForType(params, "/implementors", type -> {
            ITypeHierarchy hier = type.newTypeHierarchy(null);
            var arr = new JsonArray();
            for (IType sub : hier.getAllSubtypes(type)) {
                if (sub.isAnonymous()) continue;
                arr.add(NodeBuilder.typeSkeleton(sub));
            }
            return arr;
        });
    }

    // ── Workspace navigation ────────────────────────────────────────

    /** All open workspace projects in scope. */
    String handleProjects(ProjectScope scope) {
        var arr = new JsonArray();
        scope.openProjects().forEach(p ->
                arr.add(NodeBuilder.projectSkeleton(p)));
        return arr.toString();
    }

    /** Single project detail by name. */
    String handleProject(Map<String, String> params) {
        String name = params.get("of");
        if (name == null || name.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            IProject project = ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(name);
            if (!project.exists()) {
                return ErrorDescriptor.projectNotFound(name)
                        .toJsonString();
            }
            return NodeBuilder.projectDetail(project).toString();
        } catch (Exception e) {
            Log.warn("/project failed for " + name, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /project " + name, e).toJsonString();
        }
    }

    /** Classpath entries of a project. */
    String handleClasspath(Map<String, String> params) {
        String name = params.get("of");
        if (name == null || name.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            IProject project = ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(name);
            if (!project.exists()) {
                return ErrorDescriptor.projectNotFound(name)
                        .toJsonString();
            }
            IJavaProject jp = JavaCore.create(project);
            if (jp == null || !jp.exists()) {
                return ErrorDescriptor.projectNotFound(name)
                        .with("reason", "not a Java project")
                        .toJsonString();
            }
            var arr = new JsonArray();
            for (var entry : jp.getResolvedClasspath(true)) {
                arr.add(NodeBuilder.classpathEntrySkeleton(
                        entry, project));
            }
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/classpath failed for " + name, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /classpath " + name, e).toJsonString();
        }
    }

    String handlePackage(Map<String, String> params) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            IPackageFragment pkg = findPackage(fqn);
            if (pkg == null) {
                return ErrorDescriptor.packageNotFound(fqn)
                        .toJsonString();
            }
            return NodeBuilder.packageDetail(pkg).toString();
        } catch (Exception e) {
            Log.warn("/package failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /package " + fqn, e).toJsonString();
        }
    }

    String handleFile(Map<String, String> params) {
        String path = params.get("of");
        if (path == null || path.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        IFile file = findFile(path);
        if (file == null) {
            return ErrorDescriptor.fileNotFound(path).toJsonString();
        }
        return NodeBuilder.fileDetail(file).toString();
    }

    String handleTypesInPackage(Map<String, String> params) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            var arr = new JsonArray();
            // Aggregate across all package fragments matching the name
            // — same package name may appear in multiple source roots
            // (Tycho test fragments etc.). Dedupe by type FQN.
            var seen = new java.util.HashSet<String>();
            for (IJavaProject jp : allJavaProjects()) {
                for (IPackageFragmentRoot root
                        : jp.getPackageFragmentRoots()) {
                    IPackageFragment pkg =
                            root.getPackageFragment(fqn);
                    if (!pkg.exists()) continue;
                    for (ICompilationUnit cu
                            : pkg.getCompilationUnits()) {
                        for (IType type : cu.getTypes()) {
                            if (seen.add(type
                                    .getFullyQualifiedName('.'))) {
                                arr.add(NodeBuilder.typeSkeleton(
                                        type));
                            }
                        }
                    }
                }
            }
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/typesInPackage failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /typesInPackage " + fqn, e)
                    .toJsonString();
        }
    }

    String handleTypesInFile(Map<String, String> params) {
        String path = params.get("of");
        if (path == null || path.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        IFile file = findFile(path);
        if (file == null) {
            return ErrorDescriptor.fileNotFound(path).toJsonString();
        }
        try {
            ICompilationUnit cu =
                    JavaCore.createCompilationUnitFrom(file);
            if (cu == null || !cu.exists()) {
                return ErrorDescriptor.fileNotFound(path)
                        .with("reason", "not a Java compilation unit")
                        .toJsonString();
            }
            var arr = new JsonArray();
            for (IType type : cu.getTypes()) {
                arr.add(NodeBuilder.typeSkeleton(type));
            }
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/typesInFile failed for " + path, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /typesInFile " + path, e).toJsonString();
        }
    }

    String handlePackagesInProject(Map<String, String> params) {
        String name = params.get("of");
        if (name == null || name.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            IProject project = ResourcesPlugin.getWorkspace()
                    .getRoot().getProject(name);
            if (!project.exists()) {
                return ErrorDescriptor.projectNotFound(name)
                        .toJsonString();
            }
            IJavaProject jp = JavaCore.create(project);
            if (jp == null || !jp.exists()) {
                return ErrorDescriptor.projectNotFound(name)
                        .with("reason", "not a Java project")
                        .toJsonString();
            }
            var arr = new JsonArray();
            var seen = new java.util.HashSet<String>();
            for (IPackageFragmentRoot root
                    : jp.getPackageFragmentRoots()) {
                if (root.getKind()
                        != IPackageFragmentRoot.K_SOURCE) continue;
                for (IJavaElement child : root.getChildren()) {
                    if (child instanceof IPackageFragment pkg
                            && pkg.hasChildren()
                            && seen.add(pkg.getElementName())) {
                        arr.add(NodeBuilder.packageSkeleton(pkg));
                    }
                }
            }
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/packagesInProject failed for " + name, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /packagesInProject " + name, e)
                    .toJsonString();
        }
    }

    // ── Workspace lookup helpers ────────────────────────────────────

    private static IJavaProject[] allJavaProjects()
            throws JavaModelException {
        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();
        return JavaCore.create(root).getJavaProjects();
    }

    private static IPackageFragment findPackage(String name)
            throws JavaModelException {
        for (IJavaProject jp : allJavaProjects()) {
            for (IPackageFragmentRoot root
                    : jp.getPackageFragmentRoots()) {
                IPackageFragment pkg = root.getPackageFragment(name);
                if (pkg.exists()) return pkg;
            }
        }
        return null;
    }

    private static IFile findFile(String absPath) {
        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();
        // Try as workspace-relative first
        var resource = root.findMember(absPath);
        if (resource instanceof IFile f) return f;
        // Try as filesystem path
        IFile[] matches = root.findFilesForLocationURI(
                Path.fromOSString(absPath).toFile().toURI());
        return matches.length > 0 ? matches[0] : null;
    }

    // ── Source text ───────────────────────────────────────────────

    /**
     * Raw source text of a type or member — byte-exact from disk
     * (preserving indentation). Returns the text as a JSON string.
     * The node itself is already in the caller's hand from prior
     * navigation — no need to bundle it back.
     */
    String handleSource(Map<String, String> params) {
        String identifier = params.get("of");
        if (identifier == null || identifier.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            ResolvedTarget target = resolveTarget(identifier, params);
            if (target.errorJson != null) return target.errorJson;

            if (!(target.element instanceof org.eclipse.jdt.core.IMember member)) {
                return ErrorDescriptor.wrongSubjectKind("/source",
                        "type|method|field",
                        elementKindOf(target.element)).toJsonString();
            }

            String text = NodeBuilder.sourceTextOf(member);
            return new com.google.gson.Gson().toJson(text);
        } catch (Exception e) {
            Log.warn("/source failed for " + identifier, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /source " + identifier, e).toJsonString();
        }
    }

    // ── Problems ────────────────────────────────────────────────────

    /**
     * Compilation problems in canonical :problem shape.
     * Scope: {@code &file=}, {@code &project=}, or workspace (default).
     */
    String handleProblems(Map<String, String> params, ProjectScope scope) {
        try {
            var root = org.eclipse.core.resources.ResourcesPlugin
                    .getWorkspace().getRoot();
            String filePath = params.get("file");
            String projectName = params.get("project");

            org.eclipse.core.resources.IResource resource;
            if (filePath != null && !filePath.isBlank()) {
                var absolute = org.eclipse.core.runtime.Path
                        .fromOSString(filePath);
                resource = root.getFileForLocation(absolute);
                if (resource == null || !resource.exists()) {
                    return ErrorDescriptor.fileNotFound(filePath).toJsonString();
                }
            } else if (projectName != null && !projectName.isBlank()) {
                var project = root.getProject(projectName);
                if (!project.exists()) {
                    return ErrorDescriptor.projectNotFound(projectName).toJsonString();
                }
                resource = project;
            } else {
                resource = root;
            }

            int depth = (resource instanceof org.eclipse.core.resources.IFile)
                    ? org.eclipse.core.resources.IResource.DEPTH_ZERO
                    : org.eclipse.core.resources.IResource.DEPTH_INFINITE;
            resource.refreshLocal(depth, null);
            JdtUtils.joinAutoBuild();

            var markers = resource.findMarkers(
                    JdtUtils.JDT_PROBLEM_MARKER, true,
                    org.eclipse.core.resources.IResource.DEPTH_INFINITE);

            var arr = new JsonArray();
            for (var marker : markers) {
                int severity = marker.getAttribute(
                        org.eclipse.core.resources.IMarker.SEVERITY, -1);
                if (severity < org.eclipse.core.resources.IMarker.SEVERITY_WARNING) continue;
                if (!scope.containsProject(
                        marker.getResource().getProject().getName())) continue;

                var node = new JsonObject();
                node.addProperty("kind", "problem");
                node.addProperty("origin", "source");
                var loc = marker.getResource().getLocation();
                if (loc != null) {
                    var locObj = new JsonObject();
                    locObj.addProperty("file", loc.toOSString());
                    locObj.addProperty("startLine",
                            marker.getAttribute(
                                    org.eclipse.core.resources.IMarker.LINE_NUMBER, -1));
                    locObj.addProperty("endLine",
                            marker.getAttribute(
                                    org.eclipse.core.resources.IMarker.LINE_NUMBER, -1));
                    node.add("location", locObj);
                }
                node.addProperty("containingProject",
                        marker.getResource().getProject().getName());
                String sevStr = severity == org.eclipse.core.resources.IMarker.SEVERITY_ERROR
                        ? "error" : "warning";
                node.addProperty("severity", sevStr);
                node.addProperty("message",
                        marker.getAttribute(
                                org.eclipse.core.resources.IMarker.MESSAGE, ""));
                node.addProperty("markerType", "jdt");
                arr.add(node);
            }
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/problems failed", e);
            return ErrorDescriptor.jdtInternalError("Failed /problems", e).toJsonString();
        }
    }

    // ── Bulk search ─────────────────────────────────────────────────

    /**
     * Type pattern search. Returns a Vec of {@code :type} skeletons
     * matching the wildcard pattern. Optional {@code &sourceOnly}
     * flag excludes binary types. Binary types are deduped by FQN
     * (they may appear once per project that has them on the
     * classpath).
     */
    String handleTypes(Map<String, String> params, ProjectScope scope) {
        String pattern = params.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ErrorDescriptor.missingParameter("pattern")
                    .toJsonString();
        }
        boolean sourceOnly = params.containsKey("sourceOnly");

        int matchRule = (pattern.contains("*") || pattern.contains("?"))
                ? SearchPattern.R_PATTERN_MATCH
                        | SearchPattern.R_CASE_SENSITIVE
                : SearchPattern.R_EXACT_MATCH
                        | SearchPattern.R_CASE_SENSITIVE;

        SearchPattern searchPattern = SearchPattern.createPattern(
                pattern, IJavaSearchConstants.TYPE,
                IJavaSearchConstants.DECLARATIONS, matchRule);
        if (searchPattern == null) {
            return ErrorDescriptor.invalidFqn(pattern)
                    .with("reason",
                            "Invalid type-pattern syntax")
                    .toJsonString();
        }

        var arr = new JsonArray();
        var seen = new java.util.HashSet<String>();
        try {
            new SearchEngine().search(searchPattern,
                    new SearchParticipant[] {
                        SearchEngine.getDefaultSearchParticipant() },
                    scope.searchScope(),
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            if (!(match.getElement() instanceof IType type)) {
                                return;
                            }
                            try {
                                if (sourceOnly && type.isBinary()) return;
                                if (type.isBinary()
                                        && !seen.add(type
                                                .getFullyQualifiedName('.'))) {
                                    return;
                                }
                                arr.add(NodeBuilder.typeSkeleton(type));
                            } catch (Exception e) {
                                Log.warn("typeSkeleton failed in /types",
                                        e);
                            }
                        }
                    }, null);
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/types failed for " + pattern, e);
            return ErrorDescriptor.jdtInternalError(
                    "/types search failed for " + pattern, e)
                    .toJsonString();
        }
    }

    // ── References ──────────────────────────────────────────────────

    /**
     * Incoming references to a target. Returns Vec of :reference nodes
     * each pointing back at the from-side enclosing member through
     * {@code :from}. Optional {@code &refKind=} narrows the search:
     * <ul>
     *   <li>method target: {@code :call} (synonymous with default {@code :all})</li>
     *   <li>field target:  {@code :read} | {@code :write} | {@code :all} — uses
     *       READ_ACCESSES / WRITE_ACCESSES SearchEngine patterns</li>
     *   <li>type target:   {@code :typeUse} (synonymous with default {@code :all})</li>
     * </ul>
     */
    private static final java.util.Set<String> VALID_REF_KINDS =
            java.util.Set.of("all", "call", "read", "write", "typeUse");

    String handleRefsTo(Map<String, String> params, ProjectScope scope) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        String refKindParam = params.getOrDefault("refKind", "all");
        if (!VALID_REF_KINDS.contains(refKindParam)) {
            return ErrorDescriptor.invalidModifier(
                    "refKind", refKindParam, VALID_REF_KINDS)
                    .toJsonString();
        }
        try {
            ResolvedTarget target = resolveTarget(fqn, params);
            if (target.errorJson != null) return target.errorJson;

            int searchPattern = IJavaSearchConstants.REFERENCES;
            String effectiveRefKind = refKindFor(target.element, refKindParam);
            if (target.element instanceof IField) {
                if ("read".equals(refKindParam)) {
                    searchPattern = IJavaSearchConstants.READ_ACCESSES;
                } else if ("write".equals(refKindParam)) {
                    searchPattern = IJavaSearchConstants.WRITE_ACCESSES;
                }
            }

            SearchEngine engine = new SearchEngine();
            SearchPattern pattern = SearchPattern.createPattern(
                    target.element, searchPattern);
            if (pattern == null) {
                return ErrorDescriptor.jdtInternalError(
                        "Cannot create search pattern for " + fqn, null)
                        .toJsonString();
            }

            var arr = new JsonArray();
            final JsonObject toSkeleton = target.skeleton;
            final String refKindLabel = effectiveRefKind;
            engine.search(pattern,
                    new SearchParticipant[] {
                        SearchEngine.getDefaultSearchParticipant() },
                    scope.searchScope(),
                    new SearchRequestor() {
                        @Override
                        public void acceptSearchMatch(SearchMatch match) {
                            if (match.getAccuracy()
                                    != SearchMatch.A_ACCURATE) return;
                            try {
                                arr.add(NodeBuilder.referenceFromMatch(
                                        toSkeleton, match, refKindLabel));
                            } catch (Exception e) {
                                Log.warn("ref skeleton failed", e);
                            }
                        }
                    },
                    null);
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/refs?to failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /refs to=" + fqn, e).toJsonString();
        }
    }

    private static String refKindFor(IJavaElement element,
            String requested) {
        if (element instanceof IMethod) return "call";
        if (element instanceof IType)   return "typeUse";
        if (element instanceof IField)  return requested;
        return requested;
    }

    /**
     * Outgoing references from a source member. Returns Vec of
     * :reference nodes where the subject-as-`:from` calls / reads
     * / touches the target carried under `:to`. AST-visitor based
     * (ReferenceCollector) — resolves bindings to FQN and kind.
     *
     * Member-scoped: calls on a type flatten into one record per
     * distinct callee across every declared method body. Constants
     * and fields referenced by a body are both emitted as :read
     * for this MVP; write-detection would take an extra assignment
     * visitor and is not exposed yet.
     */
    String handleOutgoingRefs(Map<String, String> params,
            ProjectScope scope) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            ResolvedTarget target = resolveTarget(fqn, params);
            if (target.errorJson != null) return target.errorJson;
            if (!(target.element instanceof IMember member)) {
                return ErrorDescriptor.wrongSubjectKind(
                        "@outgoingRefs", "member", "non-member")
                        .toJsonString();
            }

            Map<String, ReferenceCollector.Ref> collected =
                    ReferenceCollector.collect(member);

            var arr = new JsonArray();
            for (ReferenceCollector.Ref ref : collected.values()) {
                IJavaElement targetElement = ref.element();
                if (targetElement == null) continue;
                JsonObject toSkeleton =
                        NodeBuilder.memberSkeleton(targetElement);
                if (toSkeleton == null) continue;
                if (!scope.containsProject(
                        projectNameOf(targetElement))) continue;

                var node = new JsonObject();
                node.addProperty("kind", "reference");
                node.addProperty("direction", "outgoing");
                node.addProperty("origin",
                        NodeBuilder.originOf(targetElement));
                node.addProperty("refKind",
                        outgoingRefKindLabel(ref.kind()));
                node.add("from", target.skeleton);
                JsonElement containingProj =
                        target.skeleton.get("containingProject");
                if (containingProj != null
                        && !containingProj.isJsonNull()) {
                    node.add("containingProject", containingProj);
                }
                node.add("to", toSkeleton);
                arr.add(node);
            }
            return arr.toString();
        } catch (Exception e) {
            Log.warn("/outgoingRefs failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /outgoingRefs of=" + fqn, e).toJsonString();
        }
    }

    private static String outgoingRefKindLabel(
            ReferenceCollector.RefKind kind) {
        return switch (kind) {
            case METHOD -> "call";
            case FIELD, CONSTANT -> "read";
            case TYPE -> "typeUse";
        };
    }

    private static String projectNameOf(IJavaElement element) {
        var jp = element.getJavaProject();
        return jp != null ? jp.getElementName() : "";
    }

    // ── Common helpers ──────────────────────────────────────────────

    @FunctionalInterface
    private interface MethodHandler {
        String handle(IMethod method);
    }

    private String resolveTargetMethod(Map<String, String> params,
            String endpointName, MethodHandler body) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        ResolvedTarget target;
        try {
            target = resolveTarget(fqn, params);
        } catch (Exception e) {
            Log.warn(endpointName + " resolve failed", e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed " + endpointName + " for " + fqn, e)
                    .toJsonString();
        }
        if (target.errorJson != null) return target.errorJson;
        if (!(target.element instanceof IMethod method)) {
            return ErrorDescriptor.wrongSubjectKind(
                    endpointName, "method",
                    elementKindOf(target.element)).toJsonString();
        }
        return body.handle(method);
    }

    private static String elementKindOf(IJavaElement element) {
        if (element instanceof IType)   return "type";
        if (element instanceof IMethod) return "method";
        if (element instanceof IField)  return "field";
        return element.getClass().getSimpleName();
    }

    /** Result of FQN/FQN resolution: either an element + skeleton, or an error JSON. */
    private static final class ResolvedTarget {
        final IJavaElement element;
        final JsonObject skeleton;
        final String errorJson;

        private ResolvedTarget(IJavaElement element,
                JsonObject skeleton, String errorJson) {
            this.element = element;
            this.skeleton = skeleton;
            this.errorJson = errorJson;
        }

        static ResolvedTarget ok(IJavaElement element,
                JsonObject skeleton) {
            return new ResolvedTarget(element, skeleton, null);
        }

        static ResolvedTarget err(String json) {
            return new ResolvedTarget(null, null, json);
        }
    }

    private ResolvedTarget resolveTarget(String identifier,
            Map<String, String> params) throws JavaModelException {
        int hash = identifier.indexOf('#');
        if (hash < 0) {
            IType type = JdtUtils.findType(identifier);
            if (type == null) {
                return ResolvedTarget.err(
                        ErrorDescriptor.typeNotFound(identifier)
                                .toJsonString());
            }
            return ResolvedTarget.ok(type,
                    NodeBuilder.typeSkeleton(type));
        }
        String typeFqn = identifier.substring(0, hash);
        String memberPart = identifier.substring(hash + 1);
        IType type = JdtUtils.findType(typeFqn);
        if (type == null) {
            return ResolvedTarget.err(
                    ErrorDescriptor.typeNotFound(typeFqn).toJsonString());
        }
        if (!memberPart.contains("(")) {
            // Field first (no parens means no method signature)
            IField field = type.getField(memberPart);
            if (field != null && field.exists()) {
                return ResolvedTarget.ok(field,
                        NodeBuilder.fieldSkeleton(field));
            }
            // Fall back to nullary or sole method by name
            List<IMethod> matches = JdtUtils.findMethods(
                    type, memberPart, params.get("paramTypes"));
            if (matches.isEmpty()) {
                return ResolvedTarget.err(
                        ErrorDescriptor.methodNotFound(identifier)
                                .toJsonString());
            }
            if (matches.size() > 1) {
                return ResolvedTarget.err(
                        ErrorDescriptor.ambiguousMatch(
                                identifier, fqnsOf(matches))
                                .toJsonString());
            }
            IMethod m = matches.get(0);
            return ResolvedTarget.ok(m,
                    NodeBuilder.methodSkeleton(m));
        }
        // Method with sig
        int paren = memberPart.indexOf('(');
        String methodName = memberPart.substring(0, paren);
        int closeParen = memberPart.lastIndexOf(')');
        String paramTypesParam = closeParen > paren
                ? memberPart.substring(paren + 1, closeParen)
                : memberPart.substring(paren + 1);
        if (params.get("paramTypes") != null) {
            paramTypesParam = params.get("paramTypes");
        }
        List<IMethod> matches = JdtUtils.findMethods(
                type, methodName, paramTypesParam);
        if (matches.isEmpty()) {
            return ResolvedTarget.err(
                    ErrorDescriptor.methodNotFound(identifier)
                            .toJsonString());
        }
        if (matches.size() > 1) {
            return ResolvedTarget.err(
                    ErrorDescriptor.ambiguousMatch(
                            identifier, fqnsOf(matches)).toJsonString());
        }
        IMethod m = matches.get(0);
        return ResolvedTarget.ok(m, NodeBuilder.methodSkeleton(m));
    }

    /**
     * Project the candidate IMethod list onto their FQN strings for
     * an AmbiguousMatch error descriptor. The list becomes the
     * `:candidates` field a caller routes to `!| /context/candidates`
     * to choose a disambiguated FQN and retry.
     */
    private static List<String> fqnsOf(List<IMethod> methods) {
        var out = new ArrayList<String>(methods.size());
        for (IMethod m : methods) {
            try {
                out.add(NodeBuilder.fqnOf(m));
            } catch (JavaModelException ignored) {
                out.add(m.getElementName() + "(?)");
            }
        }
        return out;
    }

    // ── Common helper: validate :of param, resolve type, build Vec ──

    @FunctionalInterface
    private interface TypeListBuilder {
        JsonArray build(IType type) throws Exception;
    }

    private String listForType(Map<String, String> params,
            String endpointName, TypeListBuilder builder) {
        String fqn = params.get("of");
        if (fqn == null || fqn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        try {
            IType type = JdtUtils.findType(fqn);
            if (type == null) {
                return ErrorDescriptor.typeNotFound(fqn).toJsonString();
            }
            return builder.build(type).toString();
        } catch (Exception e) {
            Log.warn(endpointName + " failed for " + fqn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed " + endpointName + " on " + fqn, e)
                    .toJsonString();
        }
    }

}
