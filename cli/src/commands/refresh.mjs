import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";

export async function refresh(args) {
  const quiet = args.includes("-q") || args.includes("--quiet");
  const projectFlag = args.indexOf("--project");
  const projectName = projectFlag >= 0 ? args[projectFlag + 1] : null;
  const pos = extractPositional(args).filter(
    (a) => a !== "-q" && a !== "--quiet");

  try {
    if (pos.length > 0) {
      // File mode: refresh each file
      let refreshed = 0;
      for (const filePath of pos) {
        const result = await get(
          `/refresh?file=${encodeURIComponent(filePath)}`,
          10_000,
        );
        if (result.refreshed) refreshed++;
      }
      if (refreshed > 0) {
        console.log(`Refreshed ${refreshed} file${refreshed > 1 ? "s" : ""}`);
      }
    } else if (projectName) {
      // Project mode
      const result = await get(
        `/refresh?project=${encodeURIComponent(projectName)}`,
        30_000,
      );
      if (result.error) {
        console.error(result.error);
        return;
      }
      console.log(`Refreshed project ${projectName}`);
    } else {
      // Workspace mode
      const result = await get("/refresh", 60_000);
      if (result.error) {
        console.error(result.error);
        return;
      }
      console.log("Refreshed workspace");
    }

    if (!quiet) {
      console.log(refreshGuide());
    }
  } catch {
    // Eclipse not running — silent
  }
}

function refreshGuide() {
  return `
  Eclipse auto-build will compile changes in the background.
  To check compilation results after build completes:

    jdt errors                            all workspace errors
    jdt errors --project <name>           errors in one project
    jdt errors --file <workspace-path>    errors in one file

  When to use \`jdt refresh\`:
  - After any file-mutating operation outside Edit/Write — Bash commands
    (mv, sed, git checkout/pull/merge/rebase), MCP tools, scripts, etc.
  - After modifying non-.java files that affect compilation
    (pom.xml, .classpath, .project, resource files)
  - To sync an entire project or workspace after bulk changes

  When NOT needed:
  - After Edit/Write — the PostToolUse hook refreshes every edited
    file automatically (\`jdt setup --claude\` to install)
  - Between individual edits in a series — wait until the series is
    done, then check \`jdt errors\` once

  The hook covers all Edit/Write operations. File-mutating operations
  outside Edit/Write (Bash, MCP tools, scripts) still need explicit
  \`jdt refresh\`.

  Add -q to suppress this guide.`;
}

export const help = `Explicitly notify Eclipse that files changed on disk.

No build wait, no error checking — Eclipse auto-build picks up changes.
The PostToolUse hook (jdt setup --claude) does this automatically for
every Edit/Write. Use this command for manual refresh
(e.g. after Bash commands, non-.java files, or project-wide sync).

Usage:
  jdt refresh <file> [<file> ...]       refresh specific files
  jdt refresh --project <name>           refresh entire project
  jdt refresh                            refresh entire workspace

Flags:
  -q, --quiet    suppress onboarding guide

Accepts absolute paths for files. Files outside workspace are silently ignored.
When Eclipse is not running, exits silently.

Examples:
  jdt refresh /path/to/Foo.java
  jdt refresh --project m8-server
  jdt refresh`;
