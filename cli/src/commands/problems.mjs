import { get } from "../client.mjs";
import { parseFlags } from "../args.mjs";
import { toWsPath, toSandboxPath } from "../paths.mjs";
import { output } from "../output.mjs";
import { red, yellow } from "../color.mjs";

export async function problems(args) {
  const flags = parseFlags(args);
  const params = [];
  if (flags.file) params.push(`file=${encodeURIComponent(toWsPath(flags.file))}`);
  if (flags.project) params.push(`project=${encodeURIComponent(flags.project)}`);
  if (flags.warnings) params.push("warnings");
  if (flags.all) params.push("all");

  let url = "/problems";
  if (params.length > 0) url += "?" + params.join("&");

  const data = await get(url, 180_000);

  output(args, data, {
    empty: "(no problems)",
    text(data) {
      for (const r of data) {
        const sev = r.severity === "ERROR" ? red("ERROR") : yellow("WARN ");
        const src = r.source ? `[${r.source}] ` : "";
        console.log(`${sev} ${src}${toSandboxPath(r.file)}:${r.line}  ${r.message}`);
      }
    },
  });
}

export const help = `Eclipse Problems view — IMarker.PROBLEM markers.

Usage:  jdt problems [--file <path>] [--project <name>]
                   [--warnings] [--all] [--json]

Scope (pick one or omit for entire workspace):
  --file <path>       single file (workspace-relative)
  --project <name>    entire project

Options:
  --warnings          include warnings (default: errors only)
  --all               all marker types (jdt + checkstyle + maven + ...)
  --json              output as JSON

Refreshes from disk and waits for auto-build before reading markers.
Use 'jdt build' to trigger explicit builds.

Examples:
  jdt problems --project my-server
  jdt problems --file my-server/src/main/java/.../Foo.java
  jdt problems --project my-server --all --warnings
  jdt problems --project my-server --json`;
