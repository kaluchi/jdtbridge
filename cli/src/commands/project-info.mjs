import { get } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { formatProjectInfo } from "../format/project-info.mjs";

export async function projectInfo(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const name = pos[0];
  if (!name) {
    console.error(
      "Usage: project-info <name> [--lines N] [--members-threshold N]",
    );
    process.exit(1);
  }
  let url = `/project-info?project=${encodeURIComponent(name)}`;
  if (flags["members-threshold"])
    url += `&members-threshold=${flags["members-threshold"]}`;
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    return;
  }
  const maxLines = parseInt(flags.lines) || 50;
  console.log(formatProjectInfo(result, maxLines));
}

export const help = `Show project overview with adaptive detail level.

Usage:  jdt project-info <name> [--lines N] [--members-threshold N]

Arguments:
  name    Eclipse project name (e.g. m8-server, io.github.kaluchi.jdtbridge)

Flags:
  --lines <N>               max output lines (default: 50)
  --members-threshold <N>   include method signatures when totalTypes <= N (default: 200)

Detail adapts to --lines budget:
  - Small budget: location + source roots + dependencies + package list
  - Medium budget: + type names per package
  - Large budget: + method signatures per type (if server included them)

Examples:
  jdt project-info m8-server
  jdt project-info m8-server --lines 100`;
