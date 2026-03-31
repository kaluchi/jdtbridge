import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";
import { dim } from "../color.mjs";

export async function typeInfo(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: type-info <FQN>");
    process.exit(1);
  }
  const result = await get(
    `/type-info?class=${encodeURIComponent(fqn)}`,
    30_000,
  );
  if (result.error) {
    console.error(result.error);
    return;
  }

  const filePath = toSandboxPath(stripProject(result.file));
  console.log(`${result.kind} ${result.fqn}  ${dim(`(${filePath})`)}`);
  if (result.superclass) console.log(`  extends ${result.superclass}`);
  for (const iface of result.interfaces) {
    console.log(`  implements ${iface}`);
  }
  if (result.fields.length > 0) {
    console.log();
    for (const f of result.fields) {
      const mods = f.modifiers ? f.modifiers + " " : "";
      console.log(`  ${mods}${f.type} ${f.name}  ${dim(`:${f.line}`)}`);
    }
  }
  if (result.methods.length > 0) {
    console.log();
    for (const m of result.methods) {
      console.log(`  ${m.signature}  ${dim(`:${m.line}`)}`);
    }
  }
}

export const help = `Show class overview: fields, methods, modifiers, and line numbers.

Usage:  jdt type-info <FQN>

Example:  jdt type-info com.example.dto.BaseEntity`;
