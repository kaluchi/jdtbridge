import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { stripProject } from "../paths.mjs";

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
    process.exit(1);
  }
  if (results.length === 0) {
    console.log("(no results)");
    return;
  }
  for (const r of results) {
    console.log(`${r.fqn}  ${stripProject(r.file)}`);
  }
}

export const help = `Find type declarations by name or wildcard pattern.

Usage:  jdt find <Name|*Pattern*> [--source-only]

Arguments:
  Name        exact type name (e.g. DataSourceUtils)
  *Pattern*   wildcard pattern (e.g. *Controller*, Find*)

Flags:
  --source-only   exclude binary/library types, show only workspace sources

Examples:
  jdt find DataSourceUtils
  jdt find *Controller* --source-only`;
