package io.github.kaluchi.jdtbridge;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

import java.util.Map;

/**
 * Builds structured JSON for a source member + resolved references.
 * Markdown formatting happens on the CLI side.
 */
class SourceReport {

    static String toJson(String fqmn, IMember member,
            String absPath, String source,
            int startLine, int endLine,
            Map<String, ReferenceCollector.Ref> refs) {

        IType declaringType = member instanceof IType t
                ? t : member.getDeclaringType();
        String ownFqn = declaringType != null
                ? declaringType.getFullyQualifiedName() : "";

        Json result = Json.object()
                .put("fqmn", fqmn)
                .put("file", absPath)
                .put("startLine", startLine)
                .put("endLine", endLine)
                .put("source", source);

        Json refsArr = Json.array();
        for (var ref : refs.values()) {
            Json entry = Json.object()
                    .put("fqmn", ref.fqmn())
                    .put("direction", "outgoing")
                    .put("kind",
                            ref.kind().name().toLowerCase());

            // Type kind of declaring type
            if (ref.declaringTypeKind() != null) {
                entry.put("typeKind",
                        ref.declaringTypeKind());
            }

            String refTypeFqn = extractTypeFqn(ref.fqmn());

            // Classify: same-class, project, dependency
            // Skip same-class refs when viewing full class
            // (already visible in source)
            if (refTypeFqn.equals(ownFqn)) {
                if (member instanceof IType) continue;
                entry.put("scope", "class");
            } else if (isProjectSource(ref.element())) {
                entry.put("scope", "project");
            } else {
                entry.put("scope", "dependency");
            }

            // File path for all scopes (null for binary deps)
            String path = absolutePath(ref.element());
            if (path != null) entry.put("file", path);

            // Return/field type (call-site resolved from binding)
            if (ref.resolvedType() != null) {
                entry.put("type", ref.resolvedType());
            }
            if (ref.resolvedTypeFqn() != null) {
                entry.put("returnTypeFqn",
                        ref.resolvedTypeFqn());
            }
            if (ref.resolvedTypeKind() != null) {
                entry.put("returnTypeKind",
                        ref.resolvedTypeKind());
            }

            // Type variable + bound
            if (ref.isTypeVariable()) {
                entry.put("isTypeVariable", true);
            }
            if (ref.typeBound() != null) {
                entry.put("typeBound", ref.typeBound());
            }

            // Static modifier
            if (ref.isStatic()) {
                entry.put("static", true);
            }

            // Inherited
            if (ref.isInherited()) {
                entry.put("inherited", true);
                if (ref.inheritedFrom() != null) {
                    entry.put("inheritedFrom",
                            ref.inheritedFrom());
                }
            }

            // Line range
            int[] lines = memberLines(ref.element());
            if (lines != null) {
                entry.put("line", lines[0]);
                if (lines[1] != lines[0]) {
                    entry.put("endLine", lines[1]);
                }
            }

            // Javadoc summary for ALL scopes
            String doc = javadocSummary(ref.element());
            if (doc != null) entry.put("doc", doc);

            refsArr.add(entry);
        }
        result.put("refs", refsArr);

        return result.toString();
    }

    // ---- Helpers ----

    private static int[] memberLines(IJavaElement element) {
        try {
            if (element instanceof IMember m) {
                ISourceRange range = m.getSourceRange();
                if (range == null || range.getOffset() < 0)
                    return null;
                var cu = m.getCompilationUnit();
                String source = cu != null ? cu.getSource()
                        : (m.getClassFile() != null
                                ? m.getClassFile().getSource()
                                : null);
                if (source == null) return null;
                int start = offsetToLine(source,
                        range.getOffset());
                int end = offsetToLine(source,
                        range.getOffset() + range.getLength());
                return new int[]{start, end};
            }
        } catch (JavaModelException e) { /* ignore */ }
        return null;
    }

    static String javadocSummary(IJavaElement element) {
        try {
            if (element instanceof IMember m) {
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
                return extractFirstSentence(raw);
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    static String extractFirstSentence(String javadoc) {
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
            // Stop at first sentence-ending period
            int dot = sb.indexOf(". ");
            if (dot >= 0) {
                sb.setLength(dot + 1);
                break;
            }
            if (trimmed.endsWith(".")) break;
        }
        String result = sb.toString()
                .replaceAll("\\{@code\\s+([^}]+)}", "`$1`")
                .replaceAll("\\{@link\\s+([^}]+)}", "`$1`")
                .replaceAll("<[^>]+>", "")
                .strip();
        return result.isEmpty() ? null : result;
    }

    static String absolutePath(IJavaElement element) {
        try {
            if (element instanceof IType t
                    && t.getResource() != null) {
                return t.getResource().getLocation().toOSString();
            }
            if (element instanceof IMember m) {
                IType t = m.getDeclaringType();
                if (t != null && t.getResource() != null) {
                    return t.getResource().getLocation()
                            .toOSString();
                }
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private static boolean isProjectSource(IJavaElement element) {
        try {
            if (element instanceof IType t) {
                return !t.isBinary();
            }
            if (element instanceof IMember m) {
                IType t = m.getDeclaringType();
                return t != null && !t.isBinary();
            }
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    private static String extractTypeFqn(String fqmn) {
        int hash = fqmn.indexOf('#');
        return hash >= 0 ? fqmn.substring(0, hash) : fqmn;
    }

    private static int offsetToLine(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }
}
