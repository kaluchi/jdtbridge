import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";
import { formatTable } from "../format/table.mjs";

const BADGE = { class: "[C]", interface: "[I]", enum: "[E]", annotation: "[A]" };

export async function find(args) {
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: find <Name|Pattern> [--source-only]");
    process.exit(1);
  }
  let url = `/find?name=${encodeURIComponent(name)}`;
  if (args.includes("--source-only")) url += "&source";
  const results = await get(url, 30_000);
  if (results.error) {
    console.error(results.error);
    return;
  }
  if (results.length === 0) {
    console.log("(no results)");
    return;
  }

  // Deduplicate binary types (appear once per project on classpath)
  const seen = new Set();
  const deduped = results.filter((r) => {
    if (!r.binary) return true;
    if (seen.has(r.fqn)) return false;
    seen.add(r.fqn);
    return true;
  });

  const headers = ["KIND", "FQN", "ORIGIN"];
  const rows = deduped.map((r) => [
    BADGE[r.kind] || "[C]",
    `\`${r.fqn}\``,
    r.binary ? (r.origin || "binary") : toSandboxPath(stripProject(r.file)),
  ]);
  console.log(formatTable(headers, rows));
}

export const help = `Find type declarations by name, wildcard, or package.

Usage:  jdt find <Name|*Pattern*|package.name> [--source-only]

Arguments:
  Name           exact type name (e.g. DataSourceUtils)
  *Pattern*      wildcard pattern (e.g. *Controller*, Find*)
  package.name   dotted package name — lists all types in the package

Flags:
  --source-only   exclude binary/library types, show only workspace sources

Examples:
  jdt find DataSourceUtils
  jdt find *Controller* --source-only
  jdt find com.example.service
  jdt find com.example.service.`;
