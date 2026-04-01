import { get } from "../client.mjs";

export async function maven(args) {
  const sub = args[0];
  if (sub === "update" || sub === "up") {
    return mavenUpdate(args.slice(1));
  }
  console.error("Usage: jdt maven update [--project <name>] [options]");
  process.exit(1);
}

async function mavenUpdate(args) {
  const quiet = args.includes("-q") || args.includes("--quiet");
  const projectFlag = args.indexOf("--project");
  const projectName = projectFlag >= 0 ? args[projectFlag + 1] : null;

  let url = "/maven/update?";
  if (projectName) url += `project=${encodeURIComponent(projectName)}&`;
  if (args.includes("--offline")) url += "offline&";
  if (args.includes("--force")) url += "force&";
  if (args.includes("--no-config")) url += "no-config&";
  if (args.includes("--no-clean")) url += "no-clean&";
  if (args.includes("--no-refresh")) url += "no-refresh&";
  if (args.includes("-f") || args.includes("--follow")) url += "wait&";

  const result = await get(url, 300_000);
  if (result.error) {
    console.error(result.error);
    return;
  }

  if (result.ok) {
    let msg = `Updated ${result.updated} Maven project${result.updated > 1 ? "s" : ""}`;
    if (result.errors !== undefined) {
      msg += ` (${result.errors} error${result.errors !== 1 ? "s" : ""})`;
    }
    console.log(msg);
  } else {
    console.error(`Maven update failed: ${result.message || "unknown error"}`);
    process.exit(1);
  }

  if (!quiet) {
    console.log(mavenUpdateGuide());
  }
}

function mavenUpdateGuide() {
  return `
  Equivalent to Alt+F5 in Eclipse (Update Maven Project).
  Updates dependencies, project configuration, and classpath.

  Options:
    --project <name>   update specific project (default: all)
    -f                 wait for auto-build to finish, show error count
    --offline          offline mode (no network)
    --force            force update of snapshots/releases
    --no-config        skip updating project configuration from pom.xml
    --no-clean         skip clean projects
    --no-refresh       skip refresh workspace resources

  Without -f, returns immediately — auto-build runs in background.
  With -f, waits until all projects are compiled and reports errors.

  When to use:
  - After changing pom.xml (dependencies, plugins, properties)
  - After git pull/merge that modifies pom.xml files
  - When Eclipse classpath is out of sync with Maven dependencies
  - After adding/removing Maven modules

  Add -q to suppress this guide.`;
}

export const help = `Maven project operations.

Usage:  jdt maven update [--project <name>] [options]

Subcommands:
  update (up)    update Maven project (Alt+F5 equivalent)

Options for update:
  --project <name>   update specific project (default: all)
  --offline          offline mode
  --force            force update of snapshots/releases
  --no-config        skip project configuration update
  --no-clean         skip clean projects
  --no-refresh       skip workspace refresh
  -f, --follow       wait for auto-build to finish, show error count
  -q, --quiet        suppress onboarding guide

Examples:
  jdt maven update
  jdt maven update --project m8-server
  jdt maven update --force
  jdt maven up -q`;
