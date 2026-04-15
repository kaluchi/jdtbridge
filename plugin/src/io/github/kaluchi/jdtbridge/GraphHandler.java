package io.github.kaluchi.jdtbridge;

import java.util.Map;

import com.google.gson.JsonArray;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;

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
