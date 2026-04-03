import { get } from "../client.mjs";
import { extractPositional, parseFqmn } from "../args.mjs";
import { stripProject, toSandboxPath, formatLineRange } from "../paths.mjs";
import { output } from "../output.mjs";

export async function implementors(args) {
  const pos = extractPositional(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  if (!fqn) {
    console.error("Usage: implementors <FQN>[#method[(param types)]] [--json]");
    process.exit(1);
  }

  let url = `/implementors?class=${encodeURIComponent(fqn)}`;
  if (parsed.method) url += `&method=${encodeURIComponent(parsed.method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }

  const data = await get(url, 30_000);

  output(args, data, {
    empty: "(no implementors)",
    text(data) {
      for (const r of data) {
        const file = toSandboxPath(stripProject(r.file));
        const name = r.fqmn || r.fqn;
        console.log(`\`${name}\`  ${file}${formatLineRange(r.startLine, r.endLine)}`);
      }
    },
  });
}

export const help = `Find implementors of a type or method.

Usage:  jdt implementors <FQN>[#method[(param types)]] [--json]

  <FQN>            all classes that extend/implement the type
  <FQN>#method     concrete method implementations in subtypes

Examples:
  jdt implementors com.example.core.HasId
  jdt implementors com.example.core.HasId#getId
  jdt implementors "com.example.core.HasId#process(String)"
  jdt implementors com.example.core.HasId --json`;
