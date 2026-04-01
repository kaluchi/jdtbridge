import { get } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { output } from "../output.mjs";
import { formatProjectInfo } from "../format/project-info.mjs";

export async function projectInfo(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const name = pos[0];
  if (!name) {
    console.error(
      "Usage: project-info <name> [--lines N] [--members-threshold N] [--json]",
    );
    process.exit(1);
  }
  let url = `/project-info?project=${encodeURIComponent(name)}`;
  if (flags["members-threshold"])
    url += `&members-threshold=${flags["members-threshold"]}`;

  const data = await get(url, 30_000);

  output(args, data, {
    text(data) {
      const maxLines = parseInt(flags.lines) || 50;
      console.log(formatProjectInfo(data, maxLines));
    },
  });
}

export const help = `Show project overview with adaptive detail level.

Usage:  jdt project-info <name> [--lines N] [--members-threshold N] [--json]

Arguments:
  name    Eclipse project name (e.g. my-server, io.github.kaluchi.jdtbridge)

Flags:
  --lines <N>               max output lines (default: 50)
  --members-threshold <N>   include method signatures when totalTypes <= N (default: 200)
  --json                    output as JSON (raw server response)

Detail adapts to --lines budget:
  - Small budget: location + source roots + dependencies + package list
  - Medium budget: + type names per package
  - Large budget: + method signatures per type (if server included them)

Examples:
  jdt project-info my-server
  jdt project-info my-server --lines 100
  jdt project-info my-server --json`;
