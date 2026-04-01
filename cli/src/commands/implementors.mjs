import { get } from "../client.mjs";
import { extractPositional, parseFqmn } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";
import { output } from "../output.mjs";

export async function implementors(args) {
  const pos = extractPositional(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  const method = parsed.method || pos[1];
  if (!fqn || !method) {
    console.error("Usage: implementors <FQN>#<method>[(param types)] [--json]");
    process.exit(1);
  }
  let url = `/implementors?class=${encodeURIComponent(fqn)}&method=${encodeURIComponent(method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }

  const data = await get(url, 30_000);

  output(args, data, {
    empty: "(no implementors)",
    text(data) {
      for (const r of data) {
        console.log(`${r.fqn}  ${toSandboxPath(stripProject(r.file))}:${r.line}`);
      }
    },
  });
}

export const help = `Find implementations of an interface method across all implementing classes.

Usage:  jdt implementors <FQN>#<method>[(param types)] [--json]

Examples:
  jdt implementors com.example.core.HasId#getId
  jdt implementors com.example.core.HasId#getId --json`;
