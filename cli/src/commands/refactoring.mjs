import { get } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";
import { toWsPath } from "../paths.mjs";
import { green, yellow } from "../color.mjs";

export async function organizeImports(args) {
  const pos = extractPositional(args);
  const filePath = pos[0];
  if (!filePath) {
    console.error("Usage: organize-imports <workspace-relative-path>");
    process.exit(1);
  }
  const result = await get(
    `/organize-imports?file=${encodeURIComponent(toWsPath(filePath))}`,
    30_000,
  );
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  console.log(`Imports: +${result.added} -${result.removed}`);
}

export async function format(args) {
  const pos = extractPositional(args);
  const filePath = pos[0];
  if (!filePath) {
    console.error("Usage: format <workspace-relative-path>");
    process.exit(1);
  }
  const result = await get(
    `/format?file=${encodeURIComponent(toWsPath(filePath))}`,
    30_000,
  );
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  if (result.modified) {
    console.log(green("Formatted"));
  } else {
    console.log(`No changes${result.reason ? ": " + result.reason : ""}`);
  }
}

export async function rename(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;
  const newName = pos[1];
  if (!fqn || !newName) {
    console.error(
      "Usage: rename <FQN>[#method[(param types)]] <newName> [--field name]",
    );
    process.exit(1);
  }
  let url = `/rename?class=${encodeURIComponent(fqn)}&newName=${encodeURIComponent(newName)}`;
  if (flags.field) url += `&field=${encodeURIComponent(flags.field)}`;
  if (parsed.method) url += `&method=${encodeURIComponent(parsed.method)}`;
  if (parsed.paramTypes) {
    url += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(","))}`;
  }
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  console.log(green("Renamed"));
  if (result.warnings) {
    for (const w of result.warnings) console.log(yellow(`  warning: ${w}`));
  }
}

export async function move(args) {
  const pos = extractPositional(args);
  const [fqn, target] = pos;
  if (!fqn || !target) {
    console.error("Usage: move <FQN> <target.package>");
    process.exit(1);
  }
  const url = `/move?class=${encodeURIComponent(fqn)}&target=${encodeURIComponent(target)}`;
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  console.log(green("Moved"));
  if (result.warnings) {
    for (const w of result.warnings) console.log(yellow(`  warning: ${w}`));
  }
}

export const organizeImportsHelp = `Organize imports in a Java file.

Usage:  jdt organize-imports <file>

Example:  jdt organize-imports m8-server/src/main/java/.../Foo.java`;

export const formatHelp = `Format a Java file using Eclipse project settings.

Usage:  jdt format <file>

Example:  jdt format m8-server/src/main/java/.../Foo.java`;

export const renameHelp = `Rename a type, method, or field (updates all references).

Usage:  jdt rename <FQN>[#method[(param types)]] <newName>
        jdt rename <FQN> <newName> [--field <old>]

Examples:
  jdt rename app.m8.dto.Foo Bar
  jdt rename app.m8.dto.Foo#getFoo getBar`;

export const moveHelp = `Move a type to another package (updates all references).

Usage:  jdt move <FQN> <target.package>

Example:  jdt move app.m8.dto.Foo app.m8.dto.shared`;
