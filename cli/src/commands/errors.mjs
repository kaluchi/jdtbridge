import { get } from "../client.mjs";
import { parseFlags } from "../args.mjs";
import { stripProject, toWsPath } from "../paths.mjs";
import { red, yellow } from "../color.mjs";

export async function errors(args) {
  const flags = parseFlags(args);
  const params = [];
  if (flags.file) params.push(`file=${encodeURIComponent(toWsPath(flags.file))}`);
  if (flags.project) params.push(`project=${encodeURIComponent(flags.project)}`);
  if (flags.warnings) params.push("warnings");
  if (flags.all) params.push("all");

  let url = "/errors";
  if (params.length > 0) url += "?" + params.join("&");
  const results = await get(url, 180_000);
  if (results.error) {
    console.error(results.error);
    process.exit(1);
  }
  if (results.length === 0) {
    console.log("(no errors)");
    return;
  }

  for (const r of results) {
    const sev = r.severity === "ERROR" ? red("ERROR") : yellow("WARN ");
    const src = r.source ? `[${r.source}] ` : "";
    console.log(`${sev} ${src}${stripProject(r.file)}:${r.line}  ${r.message}`);
  }
}

export const help = `Check compilation errors and diagnostics.

Usage:  jdt errors [--file <path>] [--project <name>]
                   [--warnings] [--all]

Scope (pick one or omit for entire workspace):
  --file <path>       single file (workspace-relative)
  --project <name>    entire project

Options:
  --warnings          include warnings (default: errors only)
  --all               all marker types (jdt + checkstyle + maven + ...)

Refreshes from disk and waits for auto-build before reading markers.
Use 'jdt build' to trigger explicit builds.

Examples:
  jdt errors --project m8-server
  jdt errors --file m8-server/src/main/java/.../Foo.java
  jdt errors --project m8-server --all --warnings`;
