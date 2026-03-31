import { get } from "../client.mjs";
import { parseFlags } from "../args.mjs";

export async function build(args) {
  const flags = parseFlags(args);
  const params = [];
  if (flags.project) params.push(`project=${encodeURIComponent(flags.project)}`);

  // Default is clean when project specified; --incremental to skip
  if (flags.project && !flags.incremental) params.push("clean");

  let url = "/build";
  if (params.length > 0) url += "?" + params.join("&");
  const result = await get(url, 180_000);
  if (result.error) {
    console.error(result.error);
    return;
  }
  const n = result.errors || 0;
  if (n === 0) {
    console.log("Build complete (0 errors)");
  } else {
    console.log(`Build complete (${n} errors)`);
    process.exit(1);
  }
}

export const help = `Build a project via Eclipse clean or incremental builder.

Usage:  jdt build [--project <name>] [--incremental]

Options:
  --project <name>   project to build (omit for workspace-wide incremental)
  --incremental      incremental build only (default is clean + full rebuild)

Default is clean build when --project is specified.
Without --project, triggers workspace-wide incremental build.
Always refreshes from disk before building.
Exit code: 0 if no compilation errors, 1 if errors found.

Examples:
  jdt build --project my-app                clean build (default)
  jdt build --project my-app --incremental  incremental only
  jdt build                                 workspace incremental`;
