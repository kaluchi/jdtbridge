import { get } from "../client.mjs";
import { extractPositional, parseFqmn } from "../args.mjs";
import { formatHierEntry } from "../format/hierarchy.mjs";
import { toSandboxPath } from "../paths.mjs";

export async function source(args) {
  const jsonFlag = args.includes("--json");
  const pos = extractPositional(args).filter((a) => a !== "--json");
  if (pos.length === 0) {
    console.error("Usage: source <FQMN> [<FQMN> ...] [--json]");
    process.exit(1);
  }

  const results = await Promise.all(pos.map((arg) => fetchOne(arg)));

  if (jsonFlag) {
    console.log(JSON.stringify(results.length === 1 ? results[0] : results, null, 2));
    return;
  }

  const blocks = [];
  for (const r of results) {
    if (r.error) {
      console.error(r.error);
    } else if (Array.isArray(r)) {
      blocks.push(...r.map(formatMarkdown));
    } else {
      blocks.push(formatMarkdown(r));
    }
  }
  if (blocks.length === 0) process.exit(1);
  console.log(blocks.join("\n\n---\n\n"));
}

async function fetchOne(fqmn) {
  const parsed = parseFqmn(fqmn);
  if (!parsed.className) return { error: `Invalid FQMN: ${fqmn}` };
  let url = `/source?class=${encodeURIComponent(parsed.className)}`;
  if (parsed.method) url += `&method=${encodeURIComponent(parsed.method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }
  return get(url, 30_000);
}

// ---- Badge helpers ----

const KIND_BADGE = {
  method: "[M]",
  field: "[F]",
  constant: "[K]",
  type: "[C]",
};

const TYPE_KIND_BADGE = {
  class: "[C]",
  interface: "[I]",
  enum: "[E]",
  annotation: "[A]",
};

function badge(ref) {
  if (ref.kind === "type") return TYPE_KIND_BADGE[ref.typeKind] || "[C]";
  return KIND_BADGE[ref.kind] || "[?]";
}

function returnTypeBadge(ref) {
  if (ref.returnTypeKind) return TYPE_KIND_BADGE[ref.returnTypeKind] || "";
  return "";
}

// ---- Ref formatting ----

function formatMemberRef(ref) {
  let line = `${badge(ref)} \`${ref.fqmn}\``;

  // Return / field type
  if (ref.type) {
    const rtBadge = returnTypeBadge(ref);
    const typeName = ref.returnTypeFqn || ref.type;
    if (ref.isTypeVariable && ref.typeBound) {
      line += ` → \`${ref.typeBound}\` (bound)`;
    } else if (rtBadge) {
      line += ` → ${rtBadge} \`${typeName}\``;
    } else {
      line += ` → \`${typeName}\``;
    }
  }

  // Annotations
  const annotations = [];
  if (ref.static) annotations.push("static");
  if (ref.inherited) annotations.push("inherited");
  if (annotations.length > 0) line += ` (${annotations.join(", ")})`;

  // Line number: server sends it but we don't render for incoming —
  // callers are navigable by FQMN, line numbers just add noise

  // Javadoc inline after —
  if (ref.doc) line += ` — ${ref.doc}`;

  return line;
}

function formatTypeHeader(ref) {
  let line = `${badge(ref)} \`${ref.fqmn}\``;
  if (ref.doc) line += ` — ${ref.doc}`;
  return line;
}

// ---- Grouping ----

function groupByDeclaringType(refs) {
  const groups = [];
  const groupMap = {};
  for (const ref of refs) {
    const typeFqn = ref.fqmn.split("#")[0];
    if (!groupMap[typeFqn]) {
      groupMap[typeFqn] = { typeRef: null, members: [] };
      groups.push({ typeFqn, group: groupMap[typeFqn] });
    }
    if (!ref.fqmn.includes("#")) {
      groupMap[typeFqn].typeRef = ref;
    } else {
      groupMap[typeFqn].members.push(ref);
    }
  }
  return groups;
}

function formatRefGroup({ typeFqn, group }, implIndex, viewScope) {
  const lines = [];
  // Type header: only show for standalone type refs (no members).
  // When members exist, the type is already visible in their FQMNs.
  const standalone = group.members.length === 0;
  if (standalone && group.typeRef) {
    lines.push(formatTypeHeader(group.typeRef));
    // Show type implementations (domain-scoped)
    const impls = implIndex[group.typeRef.fqmn];
    if (impls && !(viewScope === "project" && group.typeRef.scope === "dependency")) {
      for (const impl of impls) {
        let implLine = `  ${badge(impl)} \`${impl.fqmn}\``;
        if (impl.anonymous && impl.enclosingFqmn) {
          implLine += ` — in \`${impl.enclosingFqmn}\``;
        }
        lines.push(implLine);
      }
    }
  }
  for (const ref of group.members) {
    lines.push(formatMemberRef(ref));
    // Show implementations — skip dependency interface impls
    // when viewing project source (domain scoping)
    const impls = implIndex[ref.fqmn];
    if (impls && !(viewScope === "project" && ref.scope === "dependency")) {
      for (const impl of impls) {
        lines.push(`  → ${badge(impl)} \`${impl.fqmn}\``);
      }
    }
  }
  return lines.join("\n");
}

/** Build index: interfaceFqmn → [impl refs] */
function buildImplIndex(refs) {
  const index = {};
  for (const ref of refs) {
    if (ref.implementationOf) {
      if (!index[ref.implementationOf]) index[ref.implementationOf] = [];
      index[ref.implementationOf].push(ref);
    }
  }
  return index;
}


// ---- Hierarchy (type-level) ----

function formatHierarchySection(lines, result) {
  const supers = result.supertypes || [];
  const subs = result.subtypes || [];
  if (supers.length > 0 || subs.length > 0) {
    lines.push("");
    lines.push("#### Hierarchy:");
    for (const s of supers) {
      lines.push(...formatHierEntry("↑", s));
    }
    for (const s of subs) {
      lines.push(...formatHierEntry("", s));
    }
  }
  if (result.enclosingType) {
    lines.push("");
    lines.push("#### Enclosing Type:");
    const et = result.enclosingType;
    const fqn = typeof et === "string" ? et : et.fqn;
    const kind = typeof et === "string" ? "class" : (et.kind || "class");
    lines.push(...formatHierEntry("", { fqn, kind, ...et }));
  }
}

// ---- Markdown output ----

function formatMarkdown(result) {
  const lines = [];

  // Header
  const headerBadge = result.fqmn.includes("#") ? "[M]" : "[C]";
  lines.push(`#### ${headerBadge} ${result.fqmn}`);
  if (result.overrideTarget) {
    const ot = result.overrideTarget;
    // Support both object {fqmn, kind} and legacy string format
    const fqmn = typeof ot === "string"
      ? (ot.includes(" ") ? ot.split(" ", 2)[1] : ot)
      : ot.fqmn;
    lines.push(`overrides [M] \`${fqmn}\``);
  }
  lines.push(`\`${toSandboxPath(result.file)}:${result.startLine}-${result.endLine}\``);
  lines.push("");

  // Source
  lines.push("```java");
  lines.push((result.source || "").trimEnd());
  lines.push("```");

  // Type-level: hierarchy instead of refs
  if (result.supertypes || result.subtypes) {
    formatHierarchySection(lines, result);
    return lines.join("\n");
  }

  if (!result.refs || result.refs.length === 0) {
    return lines.join("\n");
  }

  // Self-reference: the viewed member's declaring type
  const selfFqn = result.fqmn.includes("#")
    ? result.fqmn.split("#")[0] : null;

  // Split by direction, filter self-reference type refs
  const outgoing = result.refs.filter((r) =>
    r.direction !== "incoming"
    && !(r.kind === "type" && r.fqmn === selfFqn));
  const incoming = result.refs.filter((r) => r.direction === "incoming");

  const viewScope = result.viewScope;

  // Implementations section (interface/abstract methods)
  const impls = result.implementations || [];
  if (impls.length > 0) {
    lines.push("");
    lines.push("#### Implementations:");
    for (const impl of impls) {
      let line = `[M] \`${impl.fqmn}\``;
      if (impl.anonymous && impl.enclosingFqmn) {
        line += ` — in \`${impl.enclosingFqmn}\``;
      }
      lines.push(line);
    }
  }

  if (outgoing.length > 0) {
    const implIndex = buildImplIndex(outgoing);
    const mainRefs = outgoing.filter((r) => !r.implementationOf);
    lines.push("");
    lines.push("#### Outgoing Calls:");
    const groups = groupByDeclaringType(mainRefs);
    for (const g of groups) {
      lines.push(formatRefGroup(g, implIndex, viewScope));
    }
  }

  if (incoming.length > 0) {
    const implIndex = buildImplIndex(incoming);
    const mainRefs = incoming.filter((r) => !r.implementationOf);
    lines.push("");
    lines.push("#### Incoming Calls:");
    const groups = groupByDeclaringType(mainRefs);
    for (const g of groups) {
      lines.push(formatRefGroup(g, implIndex, viewScope));
    }
  }

  return lines.join("\n");
}

export const help = `Print source code of a type or method with resolved references.

Returns markdown with source in a code block and references split into:
- Outgoing Calls — what this method/type calls or references
- Incoming Calls — who calls this method/type (when available)

References are grouped by declaring type. Each has a badge
([M] method, [C] class, [I] interface, [E] enum, [K] constant,
[F] field, [A] annotation) and metadata (static, inherited,
return type with kind).

Usage:  jdt source <FQMN> [<FQMN> ...] [--json]

Flags:
  --json    output raw JSON from the server (for debugging)

Examples:
  jdt source com.example.dao.UserDaoImpl
  jdt source com.example.dao.UserDaoImpl#getStaff
  jdt source "com.example.dao.UserDaoImpl#save(Order)"
  jdt source com.example.Foo#bar --json`;
