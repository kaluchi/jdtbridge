import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject, toSandboxPath } from "../paths.mjs";
import { output } from "../output.mjs";
import { formatTable } from "../format/table.mjs";

const BADGE = { class: "[C]", interface: "[I]", enum: "[E]", annotation: "[A]" };

export async function find(args) {
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: find <Name|Pattern> [--source-only] [--json]");
    process.exit(1);
  }
  let url = `/find?name=${encodeURIComponent(name)}`;
  if (args.includes("--source-only")) url += "&source";

  const results = await get(url, 30_000);

  // Deduplicate binary types (appear once per project on classpath)
  const data = results.error ? results : dedup(results);

  output(args, data, {
    empty: "(no results)",
    text(data) {
      const headers = ["KIND", "FQN", "ORIGIN"];
      const rows = data.map((r) => [
        BADGE[r.kind] || "[C]",
        `\`${r.fqn}\``,
        r.binary ? (r.origin || "binary") : toSandboxPath(stripProject(r.file)),
      ]);
      console.log(formatTable(headers, rows));
    },
  });
}

function dedup(results) {
  const seen = new Set();
  return results.filter((r) => {
    if (!r.binary) return true;
    if (seen.has(r.fqn)) return false;
    seen.add(r.fqn);
    return true;
  });
}

export const help = `Find type declarations by name, wildcard, or package.

Usage:  jdt find <Name|*Pattern*|package.name> [--source-only] [--json]

Arguments:
  Name           exact type name (e.g. DataSourceUtils)
  *Pattern*      wildcard pattern (e.g. *Controller*, Find*)
  package.name   dotted package name — lists all types in the package

Flags:
  --source-only   exclude binary/library types, show only workspace sources
  --json          output as JSON

Examples:
  jdt find DataSourceUtils
  jdt find *Controller* --source-only
  jdt find com.example.service
  jdt find Foo --json`;
