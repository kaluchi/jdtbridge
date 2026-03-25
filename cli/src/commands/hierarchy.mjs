import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject } from "../paths.mjs";
import { bold } from "../color.mjs";

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
    process.exit(1);
  }
  if (result.supers.length > 0) {
    console.log(bold("Superclasses:"));
    for (const s of result.supers) {
      const loc = s.binary ? "(binary)" : stripProject(s.file);
      console.log(`  ${s.fqn}  ${loc}`);
    }
  }
  if (result.interfaces.length > 0) {
    console.log(bold("Interfaces:"));
    for (const s of result.interfaces) {
      const loc = s.binary ? "(binary)" : stripProject(s.file);
      console.log(`  ${s.fqn}  ${loc}`);
    }
  }
  if (result.subtypes.length > 0) {
    console.log(bold("Subtypes:"));
    for (const s of result.subtypes) {
      const loc = s.binary ? "(binary)" : stripProject(s.file);
      console.log(`  ${s.fqn}  ${loc}`);
    }
  }
}

export const help = `Show full type hierarchy: superclasses, interfaces, and subtypes.

Usage:  jdt hierarchy <FQN>

Example:  jdt hierarchy com.example.client.AppEntryPoint`;
