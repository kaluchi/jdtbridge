import { get } from "../client.mjs";
import { parseFlags } from "../args.mjs";
import { dim } from "../color.mjs";
import { translateHostPath } from "../path-translate.mjs";

const ERROR_SAMPLE = 5;

export async function build(args) {
  const flags = parseFlags(args);
  const params = [];
  if (flags.project) params.push(`project=${encodeURIComponent(flags.project)}`);

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
    await printTopErrors(flags.project);
    process.exit(1);
  }
}

async function printTopErrors(projectName) {
  const problems = await get("/problems"
      + (projectName ? `?project=${encodeURIComponent(projectName)}` : ""));
  if (!Array.isArray(problems)) return;
  const errors = problems.filter((p) => p.severity === "error");
  console.error("");
  for (const p of errors.slice(0, ERROR_SAMPLE)) {
    const file = translateHostPath(p.location?.file ?? "?");
    const line = p.location?.startLine ?? "?";
    console.error(`  ${p.message}`);
    console.error(dim(`    ${file}:${line}`));
  }
  if (errors.length > ERROR_SAMPLE) {
    console.error(dim(`  (${ERROR_SAMPLE} of ${errors.length} — jdt q '@problems | filter(/error) * {:message /message :file /location/file :line /location/startLine}')`));
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
On failure, prints up to 5 errors with file:line and message.

Examples:
  jdt build --project my-app                clean build (default)
  jdt build --project my-app --incremental  incremental only
  jdt build                                 workspace incremental
  jdt build --clean                         workspace clean + full`;
