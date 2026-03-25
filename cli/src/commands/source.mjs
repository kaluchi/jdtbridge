import { get } from "../client.mjs";
import { extractPositional, parseFqmn } from "../args.mjs";

export async function source(args) {
  const pos = extractPositional(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  if (!fqn) {
    console.error("Usage: source <FQN>[#method[(param types)]]");
    process.exit(1);
  }
  const method = parsed.method || pos[1];
  let url = `/source?class=${encodeURIComponent(fqn)}`;
  if (method) url += `&method=${encodeURIComponent(method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }

  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }

  // Multiple overloads → array
  if (Array.isArray(result)) {
    console.log(result.map(formatMarkdown).join("\n\n---\n\n"));
  } else {
    console.log(formatMarkdown(result));
  }
}

function formatMarkdown(result) {
  const lines = [];

  // Header
  lines.push(`#### ${result.fqmn}`);
  lines.push(`\`${result.file}:${result.startLine}-${result.endLine}\``);
  lines.push("");

  // Source in code block
  lines.push("```java");
  lines.push(result.source.trimEnd());
  lines.push("```");

  if (!result.refs || result.refs.length === 0) {
    return lines.join("\n");
  }

  // Group refs by scope
  const classRefs = result.refs.filter((r) => r.scope === "class");
  const projectRefs = result.refs.filter((r) => r.scope === "project");
  const depRefs = result.refs.filter((r) => r.scope === "dependency");

  // Same-class members — with javadoc
  if (classRefs.length > 0) {
    const className = extractClassName(result.fqmn);
    lines.push("");
    lines.push(`**${className}:**`);
    lines.push("");
    for (const ref of classRefs) {
      lines.push(formatClassRef(ref));
    }
  }

  // Project source — grouped by declaring type
  if (projectRefs.length > 0) {
    const byType = groupByType(projectRefs);
    for (const [typeFqn, group] of Object.entries(byType)) {
      lines.push("");
      lines.push(`**${typeFqn}:**`);
      // Type-level info from first ref with file/doc
      const typeRef = group.find((r) => !r.fqmn.includes("#"));
      const memberRefs = group.filter((r) => r.fqmn.includes("#"));
      if (typeRef) {
        if (typeRef.file) lines.push(`\`${typeRef.file}\``);
        if (typeRef.doc) lines.push(typeRef.doc);
      } else if (group[0].file) {
        lines.push(`\`${group[0].file}\``);
      }
      lines.push("");
      for (const ref of memberRefs) {
        let line = `- \`${ref.fqmn}\``;
        if (ref.type) line += ` → \`${ref.type}\``;
        lines.push(line);
        if (ref.doc) lines.push(`  ${ref.doc}`);
      }
      // Type ref without members (just the type itself)
      if (typeRef && memberRefs.length === 0) {
        lines.push(`- \`${typeRef.fqmn}\``);
        if (typeRef.doc) lines.push(`  ${typeRef.doc}`);
      }
    }
  }

  // Dependencies — bare FQMNs
  if (depRefs.length > 0) {
    lines.push("");
    lines.push("**References:**");
    for (const ref of depRefs) {
      lines.push(`- \`${ref.fqmn}\``);
    }
  }

  return lines.join("\n");
}

function formatClassRef(ref) {
  let line = `- \`${ref.fqmn}\``;
  if (ref.type) line += ` → \`${ref.type}\``;
  if (ref.line) {
    line += ` :${ref.line}`;
    if (ref.endLine) line += `-${ref.endLine}`;
  }
  const parts = [line];
  if (ref.doc) parts.push(`  ${ref.doc}`);
  parts.push("");
  return parts.join("\n");
}

function extractClassName(fqmn) {
  const hash = fqmn.indexOf("#");
  const fqn = hash >= 0 ? fqmn.substring(0, hash) : fqmn;
  const dot = fqn.lastIndexOf(".");
  return dot >= 0 ? fqn.substring(dot + 1) : fqn;
}

function groupByType(refs) {
  const groups = {};
  for (const ref of refs) {
    const typeFqn = ref.fqmn.split("#")[0];
    if (!groups[typeFqn]) groups[typeFqn] = [];
    groups[typeFqn].push(ref);
  }
  return groups;
}

export const help = `Print source code of a type or method with resolved references.

Returns markdown with source in a code block and references grouped by:
- Same-class members (with javadoc)
- Project source (with paths and javadoc)
- Dependencies (FQMNs for navigation)

Each reference is a ready argument for the next jdt source call.

Usage:  jdt source <FQN>[#method[(param types)]]

Examples:
  jdt source com.example.dao.UserDaoImpl
  jdt source com.example.dao.UserDaoImpl#getStaff
  jdt source "com.example.dao.UserDaoImpl#save(Order)"`;
