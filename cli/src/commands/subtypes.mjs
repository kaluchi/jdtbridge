import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";
import { output } from "../output.mjs";

export async function subtypes(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: subtypes <FQN> [--json]");
    process.exit(1);
  }

  const data = await get(
    `/subtypes?class=${encodeURIComponent(fqn)}`,
    30_000,
  );

  output(args, data, {
    empty: "(no subtypes)",
    text(data) {
      for (const r of data) {
        console.log(`${r.fqn}  ${toSandboxPath(stripProject(r.file))}`);
      }
    },
  });
}

export const help = `Find all direct and indirect subtypes/implementors of a type.

Usage:  jdt subtypes <FQN> [--json]

Examples:
  jdt subtypes com.example.core.HasId
  jdt subtypes com.example.core.HasId --json`;
