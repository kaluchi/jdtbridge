import { get } from "../client.mjs";
import { parseFlags } from "../args.mjs";

export async function build(args) {
  const flags = parseFlags(args);
  const params = [];
  if (flags.project) params.push(`project=${encodeURIComponent(flags.project)}`);

  // Default for --project is clean (use --incremental to skip);
  // workspace-wide clean is opt-in via explicit --clean.
  const clean = flags.clean
      || (flags.project && !flags.incremental);
  if (clean) params.push("clean");

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

Usage:  jdt build [--project <name>] [--clean | --incremental]

Options:
  --project <name>   project to build (omit for workspace-wide build)
  --clean            clean + full rebuild (Project > Clean equivalent)
  --incremental      incremental build (skip clean for --project)

Default with --project is clean + full rebuild.
Default without --project is workspace-wide incremental.
--clean without --project runs workspace-wide CLEAN_BUILD + FULL_BUILD,
the same operation as Project > Clean > Clean all projects in Eclipse;
this is the cure for stale cached errors after Eclipse restarts.
Always refreshes from disk before building.
Exit code: 0 if no compilation errors, 1 if errors found.

Examples:
  jdt build --project my-app                clean build (default)
  jdt build --project my-app --incremental  incremental only
  jdt build                                 workspace incremental
  jdt build --clean                         workspace clean + full`;
