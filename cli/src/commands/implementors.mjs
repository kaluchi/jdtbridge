import { get } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";

export async function implementors(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  const method = parsed.method || pos[1];
  if (!fqn || !method) {
    console.error("Usage: implementors <FQN>#<method>[(param types)]");
    process.exit(1);
  }
  let url = `/implementors?class=${encodeURIComponent(fqn)}&method=${encodeURIComponent(method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }
  const results = await get(url, 30_000);
  if (results.error) {
    console.error(results.error);
    return;
  }
  if (results.length === 0) {
    console.log("(no implementors)");
    return;
  }
  for (const r of results) {
    console.log(`${r.fqn}  ${toSandboxPath(stripProject(r.file))}:${r.line}`);
  }
}

export const help = `Find implementations of an interface method across all implementing classes.

Usage:  jdt implementors <FQN>#<method>[(param types)]

Examples:
  jdt implementors com.example.core.HasId#getId`;
