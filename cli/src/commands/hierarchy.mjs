import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { output } from "../output.mjs";
import { formatHierarchy } from "../format/hierarchy.mjs";

export async function hierarchy(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: hierarchy <FQN> [--json]");
    process.exit(1);
  }

  const data = await get(
    `/hierarchy?class=${encodeURIComponent(fqn)}`,
    30_000,
  );

  output(args, data, {
    text(data) {
      const lines = [];
      lines.push(`#### ${data.fqn || fqn}`);
      lines.push("");
      lines.push(...formatHierarchy(data));

      if (lines.length > 2) {
        console.log(lines.join("\n"));
      } else {
        console.log("(no hierarchy)");
      }
    },
  });
}

export const help = `Show full type hierarchy: superclasses, interfaces, and subtypes.

Usage:  jdt hierarchy <FQN> [--json]

Examples:
  jdt hierarchy com.example.client.AppEntryPoint
  jdt hierarchy com.example.client.AppEntryPoint --json`;
