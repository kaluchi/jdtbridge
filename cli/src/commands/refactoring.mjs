import { get } from "../client.mjs";
import { extractPositional, parseFlags, parseFqn } from "../args.mjs";
import { green, yellow } from "../color.mjs";

export async function organizeImports(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: organize-imports <FQN>");
    process.exit(1);
  }
  const result = await get(
    `/organize-imports?class=${encodeURIComponent(fqn)}`,
    30_000,
  );
  if (result.error) {
    console.error(result.error);
    return;
  }
  console.log(`Imports: +${result.added} -${result.removed}`);
}

export async function format(args) {
  const pos = extractPositional(args);
  const fqn = pos[0];
  if (!fqn) {
    console.error("Usage: format <FQN>");
    process.exit(1);
  }
  const result = await get(
    `/format?class=${encodeURIComponent(fqn)}`,
    30_000,
  );
  if (result.error) {
    console.error(result.error);
    return;
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
  const parsed = parseFqn(pos[0]);
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
    return;
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
    return;
  }
  console.log(green("Moved"));
  if (result.warnings) {
    for (const w of result.warnings) console.log(yellow(`  warning: ${w}`));
  }
}

export const organizeImportsHelp = `Organize imports in a Java file.

Usage:  jdt organize-imports <FQN>

Example:  jdt organize-imports com.example.MyClass`;

export const formatHelp = `Format a Java file using Eclipse project settings.

Usage:  jdt format <FQN>

Example:  jdt format com.example.MyClass`;

export const renameHelp = `Rename a type, method, or field (updates all references).

Usage:  jdt rename <FQN>[#method[(param types)]] <newName>
        jdt rename <FQN> <newName> [--field <old>]

Examples:
  jdt rename com.example.dto.Foo Bar
  jdt rename com.example.dto.Foo#getFoo getBar`;

export const moveHelp = `Move a type to another package (updates all references).

Usage:  jdt move <FQN> <target.package>

Example:  jdt move com.example.dto.Foo com.example.dto.shared`;
