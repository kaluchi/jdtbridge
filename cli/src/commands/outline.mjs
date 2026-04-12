import { get } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { output } from "../output.mjs";
import { dim, bold } from "../color.mjs";

export async function outline(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: outline <FQN> [--no-fields] [--no-static] [--public] [--sort] [--json] [-q]");
    process.exit(1);
  }

  const url = `/outline?class=${encodeURIComponent(fqn)}`;
  const data = await get(url);

  output(args, data, {
    text(data) {
      const flags = parseFlags(args);
      const noFields = args.includes("--no-fields");
      const noStatic = args.includes("--no-static");
      const publicOnly = args.includes("--public");
      const noLocal = args.includes("--no-local");
      const sort = args.includes("--sort");
      const quiet = args.includes("-q") || args.includes("--quiet");

      // Header
      const badge = typeBadge(data.kind);
      console.log(`#### ${badge} ${data.fqn}`);
      console.log(data.file);
      console.log();

      // Tree
      const children = data.children || [];
      const filtered = filterChildren(children, { noFields, noStatic, publicOnly, noLocal });
      const sorted = sort ? sortChildren(filtered) : filtered;
      renderTree(sorted, data.fqn, "", { noFields, noStatic, publicOnly, noLocal, sort });

      // Guide
      if (!quiet) {
        console.log();
        console.log(guide(data.fqn));
      }
    },
  });
}

function filterChildren(children, opts) {
  return children.filter((c) => {
    if (opts.noFields && c.kind === "field") return false;
    if (opts.noStatic && isStatic(c)) return false;
    if (opts.publicOnly && !isPublic(c)) return false;
    if (opts.noLocal && c.kind === "type" && isLocal(c)) return false;
    return true;
  });
}

function sortChildren(children) {
  return [...children].sort((a, b) =>
    (a.name || "").localeCompare(b.name || ""));
}

function isStatic(node) {
  return node.modifiers?.includes("static");
}

function isPublic(node) {
  if (!node.modifiers || node.modifiers.length === 0) return true;
  return node.modifiers.includes("public");
}

function isLocal(node) {
  return !node.name || node.name === "";
}

function renderTree(children, parentFqn, indent, opts) {
  for (const child of children) {
    const line = formatNode(child, parentFqn);
    console.log(`${indent}${line}`);

    if (child.children) {
      const nested = filterChildren(child.children, opts);
      const sorted = opts.sort ? sortChildren(nested) : nested;
      renderTree(sorted, child.fqn || parentFqn, indent + "  ", opts);
    }
  }
}

function formatNode(node, parentFqn) {
  switch (node.kind) {
    case "field": return formatField(node);
    case "method": return formatMethod(node, parentFqn);
    case "type": return formatType(node);
    case "initializer": return formatInitializer(node);
    default: return `${node.name || "?"}`;
  }
}

function formatField(f) {
  const badge = f.constant ? "[K]" : "[F]";
  const mods = modsStr(f);
  const lines = lineRange(f);
  return `${badge} ${f.name} : ${simpleName(f.type)}${mods}${lines}`;
}

function formatMethod(m, parentFqn) {
  const sig = m.signature || m.name + "()";
  const ret = m.isConstructor ? "" : ` : ${simpleName(m.returnType || "void")}`;
  const mods = modsStr(m);
  const lines = lineRange(m);
  const fqmn = parentFqn ? `\`${parentFqn}#${m.name}\`` : "";
  return `[M] ${sig}${ret}${mods}${lines}`;
}

function formatType(t) {
  const badge = typeBadge(t.typeKind);
  const mods = modsStr(t);
  const lines = lineRange(t);
  return `${badge} ${t.name}${mods}${lines}`;
}

function formatInitializer(init) {
  const badge = isStatic(init) ? "[S]" : "[B]";
  const lines = lineRange(init);
  return `${badge} ${isStatic(init) ? "static {}" : "{}"}${lines}`;
}

function typeBadge(kind) {
  switch (kind) {
    case "class": return "[C]";
    case "interface": return "[I]";
    case "enum": return "[E]";
    case "record": return "[R]";
    case "annotation": return "[A]";
    default: return "[C]";
  }
}

function simpleName(fqn) {
  if (!fqn) return "void";
  // Keep generic part: Map<String,String> → Map<String,String>
  const genIdx = fqn.indexOf("<");
  if (genIdx >= 0) {
    const base = fqn.substring(0, genIdx);
    const generic = fqn.substring(genIdx);
    const simpleBase = base.includes(".") ? base.substring(base.lastIndexOf(".") + 1) : base;
    // Simplify generic args too
    const simpleGeneric = generic.replace(/[a-z]+(?:\.[a-z]+)*\.([A-Z]\w*)/g, "$1");
    return simpleBase + simpleGeneric;
  }
  // Array: Type[] → simple[]
  if (fqn.endsWith("[]")) {
    return simpleName(fqn.slice(0, -2)) + "[]";
  }
  return fqn.includes(".") ? fqn.substring(fqn.lastIndexOf(".") + 1) : fqn;
}

function modsStr(node) {
  if (!node.modifiers || node.modifiers.length === 0) return "";
  return dim(` (${node.modifiers.join(" ")})`);
}

function lineRange(node) {
  if (!node.startLine) return "";
  if (node.endLine && node.endLine !== node.startLine) {
    return dim(` :${node.startLine}-${node.endLine}`);
  }
  return dim(` :${node.startLine}`);
}

function guide(fqn) {
  return `${dim("─".repeat(60))}

This is the structure of a Java type as shown in the Eclipse Outline View.
Each line is a member: field, method, constructor, inner type, or initializer.

${bold("Badges:")}
  [C] class        [I] interface    [E] enum       [R] record
  [A] annotation   [M] method       [F] field      [K] constant (static final)
  [S] static {}    [B] instance {}

${bold("Reading a line:")}
  [M] start(InetAddress addr, int port) : void (public) :79-85
       method name + params              return  mods    source lines

${bold("Filters")} (same as Eclipse Outline toolbar):
  --no-fields    hide fields
  --no-static    hide static members
  --public       public only
  --no-local     hide local/anonymous types
  --sort         alphabetical (default: source order)

${bold("Navigate from outline:")}
  jdt source ${fqn}#<method>     source + references
  jdt refs ${fqn}#<method>       call sites
  jdt impl ${fqn}                implementations
  jdt hier ${fqn}                type hierarchy

Add -q to suppress this guide.`;
}

export const help = `Show the structure of a Java type (Eclipse Outline View equivalent).

Usage:  jdt outline <FQN> [--no-fields] [--no-static] [--public] [--sort] [--json] [-q]

Displays fields, methods, constructors, inner types, and initializers
in source order with badges, types, modifiers, and line ranges.

Filters (same as Eclipse Outline toolbar toggles):
  --no-fields    hide fields
  --no-static    hide static members
  --public       show only public members
  --no-local     hide local/anonymous types
  --sort         alphabetical order (default: source order)

Other:
  --json         raw JSON from server (full tree, no filtering)
  -q, --quiet    suppress guide section

Examples:
  jdt outline com.example.MyService                            full outline
  jdt outline com.example.MyService --no-fields --public       quick API overview
  jdt outline com.example.MyService --sort                     alphabetical
  jdt outline com.example.MyService --json                     raw JSON
  jdt outline com.example.MyService -q | grep handle           find members by name
  jdt outline com.example.MyService -q | wc -l                 count members`;
