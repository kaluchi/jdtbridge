package io.github.kaluchi.jdtbridge;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

/**
 * Handlers for the new graph-axis endpoints. Each endpoint answers
 * one primitive question against the JDT model and returns a node
 * (or Vec of nodes) in the canonical shape produced by
 * {@link NodeBuilder}. Errors come back as
 * {@link ErrorDescriptor} JSON, never as the legacy stringly-typed
 * {@code {"error": "..."}} form.
 * <p>
 * Endpoint surface in this batch (point lookups + polymorphic detail):
 * <ul>
 *   <li>{@code /type?of=<fqn>}     — single :type detail</li>
 *   <li>{@code /method?of=<fqmn>}  — single :method detail; supports
 *       paramTypes for overload disambiguation</li>
 *   <li>{@code /field?of=<fqmn>}   — single :field detail</li>
 *   <li>{@code /detail?of=<fqn-or-fqmn>} — polymorphic; routes by
 *       {@code #}-presence in the identifier</li>
 * </ul>
 * Subsequent batches add bulk-search ({@code /types}), navigation
 * ({@code /members /supers /subtypes}), references, annotations,
 * source/AST, and resources.
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
     * Single point-lookup for a method by FQMN. Optional
     * {@code paramTypes} param disambiguates overloads; absent means
     * "any overload" — fails with AmbiguousMatch when multiple exist.
     */
    String handleMethod(Map<String, String> params) {
        String fqmn = params.get("of");
        if (fqmn == null || fqmn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        int hash = fqmn.indexOf('#');
        if (hash < 0) {
            return ErrorDescriptor.invalidFqmn(fqmn).toJsonString();
        }
        String typeFqn = fqmn.substring(0, hash);
        String memberPart = fqmn.substring(hash + 1);
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
                return ErrorDescriptor.typeNotFound(typeFqn)
                        .with("fqmn", fqmn).toJsonString();
            }
            var matches = JdtUtils.findMethods(type, methodName,
                    paramTypesParam);
            if (matches.isEmpty()) {
                return ErrorDescriptor.methodNotFound(fqmn).toJsonString();
            }
            if (matches.size() > 1) {
                return ErrorDescriptor.ambiguousMatch(fqmn,
                        matches.size()).toJsonString();
            }
            return NodeBuilder.methodDetail(matches.get(0)).toString();
        } catch (Exception e) {
            Log.warn("/method failed for " + fqmn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed to resolve method: " + fqmn, e).toJsonString();
        }
    }

    /** Single point-lookup for a field by FQMN. */
    String handleField(Map<String, String> params) {
        String fqmn = params.get("of");
        if (fqmn == null || fqmn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        int hash = fqmn.indexOf('#');
        if (hash < 0) {
            return ErrorDescriptor.invalidFqmn(fqmn).toJsonString();
        }
        String typeFqn = fqmn.substring(0, hash);
        String fieldName = fqmn.substring(hash + 1);
        if (fieldName.contains("(")) {
            return ErrorDescriptor.invalidFqmn(fqmn)
                    .with("reason", "field FQMN must not contain parens")
                    .toJsonString();
        }
        try {
            IType type = JdtUtils.findType(typeFqn);
            if (type == null) {
                return ErrorDescriptor.typeNotFound(typeFqn)
                        .with("fqmn", fqmn).toJsonString();
            }
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return ErrorDescriptor.fieldNotFound(fqmn).toJsonString();
            }
            return NodeBuilder.fieldDetail(field).toString();
        } catch (Exception e) {
            Log.warn("/field failed for " + fqmn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed to resolve field: " + fqmn, e).toJsonString();
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
                String paramSig = ReferenceCollector.paramSig(method);
                ITypeHierarchy hier =
                        declaring.newSupertypeHierarchy(null);

                IType current = declaring;
                while (true) {
                    IType superClass = hier.getSuperclass(current);
                    if (superClass == null) break;
                    IMethod found = matchingMethod(
                            superClass, name, paramSig);
                    if (found != null) {
                        return NodeBuilder.methodSkeleton(found)
                                .toString();
                    }
                    current = superClass;
                }
                for (IType iface
                        : hier.getAllSuperInterfaces(declaring)) {
                    IMethod found = matchingMethod(
                            iface, name, paramSig);
                    if (found != null) {
                        return NodeBuilder.methodSkeleton(found)
                                .toString();
                    }
                }
                return "null";
            } catch (Exception e) {
                Log.warn("/overrides walk failed", e);
                return "null";
            }
        });
    }

    private static IMethod matchingMethod(IType type, String name,
            String paramSig) throws JavaModelException {
        for (IMethod m : type.getMethods()) {
            if (m.getElementName().equals(name)
                    && ReferenceCollector.paramSig(m).equals(paramSig)) {
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
    String handleRefsTo(Map<String, String> params, ProjectScope scope) {
        String fqmn = params.get("of");
        if (fqmn == null || fqmn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        String refKindParam = params.getOrDefault("refKind", "all");
        try {
            ResolvedTarget target = resolveTarget(fqmn, params);
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
                        "Cannot create search pattern for " + fqmn, null)
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
            Log.warn("/refs?to failed for " + fqmn, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed /refs to=" + fqmn, e).toJsonString();
        }
    }

    private static String refKindFor(IJavaElement element,
            String requested) {
        if (element instanceof IMethod) return "call";
        if (element instanceof IType)   return "typeUse";
        if (element instanceof IField)  return requested;
        return requested;
    }

    // ── Common helpers ──────────────────────────────────────────────

    @FunctionalInterface
    private interface MethodHandler {
        String handle(IMethod method);
    }

    private String resolveTargetMethod(Map<String, String> params,
            String endpointName, MethodHandler body) {
        String fqmn = params.get("of");
        if (fqmn == null || fqmn.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        ResolvedTarget target;
        try {
            target = resolveTarget(fqmn, params);
        } catch (Exception e) {
            Log.warn(endpointName + " resolve failed", e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed " + endpointName + " for " + fqmn, e)
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

    /** Result of FQN/FQMN resolution: either an element + skeleton, or an error JSON. */
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
                    ErrorDescriptor.typeNotFound(typeFqn)
                            .with("fqmn", identifier).toJsonString());
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
                                identifier, matches.size())
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
                            identifier, matches.size()).toJsonString());
        }
        IMethod m = matches.get(0);
        return ResolvedTarget.ok(m, NodeBuilder.methodSkeleton(m));
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

    /**
     * Polymorphic point-lookup. Routes to {@link #handleType},
     * {@link #handleMethod}, or {@link #handleField} based on
     * the identifier shape: contains {@code (} → method,
     * contains {@code #} → field or method (heuristic by parens),
     * otherwise → type.
     */
    String handleDetail(Map<String, String> params) {
        String identifier = params.get("of");
        if (identifier == null || identifier.isBlank()) {
            return ErrorDescriptor.missingParameter("of").toJsonString();
        }
        if (identifier.contains("(")) {
            return handleMethod(params);
        }
        int hash = identifier.indexOf('#');
        if (hash < 0) {
            return handleType(params);
        }
        // Member without parens: try field first, fall back to
        // method (zero-arg). The choice is unambiguous because
        // fields and methods can't share names in Java (modulo
        // unusual cases the bridge doesn't model).
        String typeFqn = identifier.substring(0, hash);
        String memberName = identifier.substring(hash + 1);
        try {
            IType type = JdtUtils.findType(typeFqn);
            if (type == null) {
                return ErrorDescriptor.typeNotFound(typeFqn)
                        .with("identifier", identifier)
                        .toJsonString();
            }
            IField field = type.getField(memberName);
            if (field != null && field.exists()) {
                return NodeBuilder.fieldDetail(field).toString();
            }
            return handleMethod(params);
        } catch (Exception e) {
            Log.warn("/detail failed for " + identifier, e);
            return ErrorDescriptor.jdtInternalError(
                    "Failed to resolve: " + identifier, e)
                    .toJsonString();
        }
    }
}
