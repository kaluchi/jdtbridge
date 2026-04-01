import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";
import { output } from "../output.mjs";
import { dim } from "../color.mjs";

export async function typeInfo(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: type-info <FQN> [--json]");
    process.exit(1);
  }

  const data = await get(
    `/type-info?class=${encodeURIComponent(fqn)}`,
    30_000,
  );

  output(args, data, {
    text(data) {
      const filePath = toSandboxPath(stripProject(data.file));
      console.log(`${data.kind} ${data.fqn}  ${dim(`(${filePath})`)}`);
      if (data.superclass) console.log(`  extends ${data.superclass}`);
      for (const iface of data.interfaces) {
        console.log(`  implements ${iface}`);
      }
      if (data.fields.length > 0) {
        console.log();
        for (const f of data.fields) {
          const mods = f.modifiers ? f.modifiers + " " : "";
          console.log(`  ${mods}${f.type} ${f.name}  ${dim(`:${f.line}`)}`);
        }
      }
      if (data.methods.length > 0) {
        console.log();
        for (const m of data.methods) {
          console.log(`  ${m.signature}  ${dim(`:${m.line}`)}`);
        }
      }
    },
  });
}

export const help = `Show class overview: fields, methods, modifiers, and line numbers.

Usage:  jdt type-info <FQN> [--json]

Examples:
  jdt type-info com.example.dto.BaseEntity
  jdt type-info com.example.dto.BaseEntity --json`;
