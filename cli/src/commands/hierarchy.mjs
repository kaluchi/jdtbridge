import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { formatHierarchy } from "../format/hierarchy.mjs";

export async function hierarchy(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: hierarchy <FQN>");
    process.exit(1);
  }
  const result = await get(
    `/hierarchy?class=${encodeURIComponent(fqn)}`,
    30_000,
  );
  if (result.error) {
    console.error(result.error);
    return;
  }

  const lines = [];
  lines.push(`#### ${result.fqn || fqn}`);
  lines.push("");
  lines.push(...formatHierarchy(result));

  if (lines.length > 2) {
    console.log(lines.join("\n"));
  } else {
    console.log("(no hierarchy)");
  }
}

export const help = `Show full type hierarchy: superclasses, interfaces, and subtypes.

Usage:  jdt hierarchy <FQN>

Example:  jdt hierarchy com.example.client.AppEntryPoint`;
