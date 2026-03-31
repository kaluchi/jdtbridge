import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";

export async function subtypes(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: subtypes <FQN>");
    process.exit(1);
  }
  const results = await get(
    `/subtypes?class=${encodeURIComponent(fqn)}`,
    30_000,
  );
  if (results.error) {
    console.error(results.error);
    return;
  }
  if (results.length === 0) {
    console.log("(no subtypes)");
    return;
  }
  for (const r of results) {
    console.log(`${r.fqn}  ${toSandboxPath(stripProject(r.file))}`);
  }
}

export const help = `Find all direct and indirect subtypes/implementors of a type.

Usage:  jdt subtypes <FQN>

Example:  jdt subtypes com.example.core.HasId`;
