import { toSandboxPath } from "../paths.mjs";

const TYPE_KIND_BADGE = {
  class: "[C]",
  interface: "[I]",
  enum: "[E]",
  annotation: "[A]",
};

export function formatHierEntry(arrow, s) {
  const depth = s.depth || 0;
  const indent = "  ".repeat(depth);
  const b = TYPE_KIND_BADGE[s.kind] || "[C]";
  const prefix = arrow ? `${arrow} ` : "";
  let line = `${indent}- ${prefix}${b} \`${s.fqn}\``;
  if (s.anonymous && s.enclosingFqmn) {
    line += ` — in \`${s.enclosingFqmn}\``;
  }
  const lines = [line];
  if (s.file) {
    let loc = toSandboxPath(s.file);
    if (s.line) {
      loc += `:${s.line}`;
      if (s.endLine && s.endLine !== s.line) loc += `-${s.endLine}`;
    }
    lines.push(`${indent}  \`${loc}\``);
  }
  return lines;
}

export function formatHierarchy(result) {
  const lines = [];
  const supers = result.supertypes || [];
  const subs = result.subtypes || [];
  if (supers.length > 0) {
    lines.push("#### Supertypes:");
    for (const s of supers) {
      lines.push(...formatHierEntry("↑", s));
    }
  }
  if (subs.length > 0) {
    if (lines.length > 0) lines.push("");
    lines.push("#### Subtypes:");
    for (const s of subs) {
      lines.push(...formatHierEntry("", s));
    }
  }
  return lines;
}
