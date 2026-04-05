// Main CLI dispatcher.
// Maps command names to handler functions and provides help.

import { projects, help as projectsHelp } from "./commands/projects.mjs";
import { projectInfo, help as projectInfoHelp } from "./commands/project-info.mjs";
import { find, help as findHelp } from "./commands/find.mjs";
import { references, help as referencesHelp } from "./commands/references.mjs";
import { hierarchy, help as hierarchyHelp } from "./commands/hierarchy.mjs";
import { implementors, help as implementorsHelp } from "./commands/implementors.mjs";
import { typeInfo, help as typeInfoHelp } from "./commands/type-info.mjs";
import { source, help as sourceHelp } from "./commands/source.mjs";
import { build, help as buildHelp } from "./commands/build.mjs";
import { testRun, help as testRunHelp } from "./commands/test-run.mjs";
import { testStatus, help as testStatusHelp } from "./commands/test-status.mjs";
import { testSessions, help as testSessionsHelp } from "./commands/test-sessions.mjs";
import { problems, help as problemsHelp } from "./commands/problems.mjs";
import { refresh, help as refreshHelp } from "./commands/refresh.mjs";
import { maven, help as mavenHelp } from "./commands/maven.mjs";
import {
  organizeImports,
  format,
  rename,
  move,
  organizeImportsHelp,
  formatHelp,
  renameHelp,
  moveHelp,
} from "./commands/refactoring.mjs";
import {
  editors,
  open,
  editorsHelp,
  openHelp,
} from "./commands/editor.mjs";
import {
  agentRun,
  agentList,
  agentStop,
  agentLogs,
  agentProviders,
  agentRunHelp,
  agentListHelp,
  agentStopHelp,
  agentLogsHelp,
  agentProvidersHelp,
} from "./commands/agent.mjs";
import { status, help as statusHelp } from "./commands/status.mjs";
import { git, help as gitHelp } from "./commands/git.mjs";
import {
  launchList,
  launchConfigs,
  launchConfig,
  launchRun,
  launchDebug,
  launchStop,
  launchClear,
  launchLogs,
  launchConsole,
  launchListHelp,
  launchConfigsHelp,
  launchConfigHelp,
  launchRunHelp,
  launchDebugHelp,
  launchStopHelp,
  launchClearHelp,
  launchLogsHelp,
  launchConsoleHelp,
} from "./commands/launch.mjs";
import { setup, help as setupHelp } from "./commands/setup.mjs";
import { use, help as useHelp } from "./commands/use.mjs";
import { isConnectionError } from "./client.mjs";
import { bold, red, dim } from "./color.mjs";
import { installTelemetry } from "./telemetry.mjs";
import { createRequire } from "node:module";

installTelemetry();

const { version } = createRequire(import.meta.url)("../package.json");

const launchSubcommands = {
  list: { fn: launchList, help: launchListHelp },
  configs: { fn: launchConfigs, help: launchConfigsHelp },
  config: { fn: launchConfig, help: launchConfigHelp },
  run: { fn: launchRun, help: launchRunHelp },
  debug: { fn: launchDebug, help: launchDebugHelp },
  stop: { fn: launchStop, help: launchStopHelp },
  clear: { fn: launchClear, help: launchClearHelp },
  logs: { fn: launchLogs, help: launchLogsHelp },
  console: { fn: launchConsole, help: launchConsoleHelp },
};

const launchHelp = `Manage launch configurations and running processes.

Subcommands:
  jdt launch configs                              list saved configurations
  jdt launch config <configId> [--xml] [--json]   show configuration details
  jdt launch run <configId> [-f] [-q]             launch a configuration
  jdt launch debug <configId> [-f] [-q]           launch in debug mode
  jdt launch list                                 list running/terminated launches
  jdt launch logs <launchId> [-f] [--tail N]      show console output
  jdt launch stop <launchId>                      stop a running launch
  jdt launch clear [launchId]                     remove terminated launches
  jdt launch config --import <path> [--configid <name>]  import .launch file

LaunchId is configId:pid (e.g. my-server:12345) for disambiguation.
Plain configId works when there is only one launch with that name.

Use "jdt help launch <subcommand>" for details.`;

async function launchDispatch(args) {
  const [sub, ...rest] = args;
  if (!sub || sub === "--help") {
    console.log(launchHelp);
    return;
  }
  const cmd = launchSubcommands[sub];
  if (!cmd) {
    console.error(`Unknown launch subcommand: ${sub}\n`);
    printFullSubcommandHelp("launch", launchSubcommands);
    process.exit(1);
  }
  await cmd.fn(rest);
}

const testSubcommands = {
  run: { fn: testRun, help: testRunHelp },
  status: { fn: testStatus, help: testStatusHelp },
  runs: { fn: testSessions, help: testSessionsHelp },
};

const testHelp = `Run and monitor JUnit tests via Eclipse's built-in runner.

Subcommands:
  jdt test run <FQN> [-f] [-q]                    launch tests (non-blocking)
  jdt test status <testRunId> [-f] [--all]         show test progress/results
  jdt test runs                                    list test runs

Use "jdt help test <subcommand>" for details.`;

async function testDispatch(args) {
  const [sub, ...rest] = args;
  if (!sub || sub === "--help") {
    console.log(testHelp);
    return;
  }
  const cmd = testSubcommands[sub];
  if (!cmd) {
    console.error(`Unknown test subcommand: ${sub}\n`);
    printFullSubcommandHelp("test", testSubcommands);
    process.exit(1);
  }
  await cmd.fn(rest);
}

const agentSubcommands = {
  run: { fn: agentRun, help: agentRunHelp },
  list: { fn: agentList, help: agentListHelp },
  stop: { fn: agentStop, help: agentStopHelp },
  logs: { fn: agentLogs, help: agentLogsHelp },
  providers: { fn: agentProviders, help: agentProvidersHelp },
};

const agentHelp = `Manage AI agent sessions (run, stop, list, logs).

Subcommands:
  jdt agent run <provider> <agent> [--name <id>]   launch agent session
  jdt agent list                                    running sessions
  jdt agent stop <name>                             stop by session ID
  jdt agent logs <name> [-f]                        stream output
  jdt agent providers                               available providers

Use "jdt help agent <subcommand>" for details.`;

async function agentDispatch(args) {
  const [sub, ...rest] = args;
  if (!sub || sub === "--help") {
    console.log(agentHelp);
    return;
  }
  const cmd = agentSubcommands[sub];
  if (!cmd) {
    console.error(`Unknown agent subcommand: ${sub}\n`);
    printFullSubcommandHelp("agent", agentSubcommands);
    process.exit(1);
  }
  await cmd.fn(rest);
}

const commands = {
  projects: { fn: projects, help: projectsHelp },
  "project-info": { fn: projectInfo, help: projectInfoHelp },
  find: { fn: find, help: findHelp },
  references: { fn: references, help: referencesHelp },
  implementors: { fn: implementors, help: implementorsHelp },
  hierarchy: { fn: hierarchy, help: hierarchyHelp },
  "type-info": { fn: typeInfo, help: typeInfoHelp },
  source: { fn: source, help: sourceHelp },
  build: { fn: build, help: buildHelp },
  test: { fn: testDispatch, help: testHelp },
  problems: { fn: problems, help: problemsHelp },
  refresh: { fn: refresh, help: refreshHelp },
  maven: { fn: maven, help: mavenHelp },
  "organize-imports": { fn: organizeImports, help: organizeImportsHelp },
  format: { fn: format, help: formatHelp },
  rename: { fn: rename, help: renameHelp },
  move: { fn: move, help: moveHelp },
  editors: { fn: editors, help: editorsHelp },
  open: { fn: open, help: openHelp },
  status: { fn: status, help: statusHelp },
  git: { fn: git, help: gitHelp },
  launch: { fn: launchDispatch, help: launchHelp },
  agent: { fn: agentDispatch, help: agentHelp },
  setup: { fn: setup, help: setupHelp },
  use: { fn: use, help: useHelp },
};

/** Short aliases for frequently used commands. */
const aliases = {
  refs: "references",
  impl: "implementors",
  hier: "hierarchy",
  pi: "project-info",
  ti: "type-info",
  oi: "organize-imports",
  ed: "editors",
  src: "source",
  b: "build",
  err: "problems",
  r: "refresh",
  fmt: "format",
};

// Reverse map: command name → list of its aliases (for display).
const aliasesOf = {};
for (const [short, full] of Object.entries(aliases)) {
  (aliasesOf[full] ||= []).push(short);
}

/** Resolve a command name or alias to its full name. */
function resolve(name) {
  if (commands[name]) return name;
  return aliases[name] || null;
}

function fmtAliases(name) {
  const list = aliasesOf[name];
  return list ? " " + dim("(" + list.join(", ") + ")") : "";
}

function printFullSubcommandHelp(parentName, subcommands) {
  const names = Object.keys(subcommands);
  console.log(`jdt ${parentName} has ${names.length} subcommands: ${names.join(", ")}\n`);
  for (const [name, { help }] of Object.entries(subcommands)) {
    if (help) {
      console.log(`--- jdt ${parentName} ${name} ---\n`);
      console.log(help);
      console.log();
    }
  }
}

/** Strip ANSI escape codes to get visible character count. */
function stripAnsi(str) {
  return str.replace(/\x1b\[[0-9;]*m/g, "");
}

const HELP_COL = 53;

/** Pad left part to HELP_COL (visible width), minimum 2 spaces before desc. */
function helpLine(left, desc) {
  const visible = stripAnsi(left).length;
  const pad = Math.max(2, HELP_COL - visible);
  return left + " ".repeat(pad) + desc;
}


function printOverview() {
  const h = helpLine;
  console.log([
    "Eclipse JDT Bridge — semantic Java analysis via Eclipse JDT SearchEngine.",
    "Requires: Eclipse running with the jdtbridge plugin.",
    "",
    "Dashboard:",
    h("  status [sections...] [-q]",                       "CLI screenshot of Eclipse (start here)"),
    "",
    "Search & navigation:",
    h(`  find <Name|*Pattern*|pkg> [--source-only]`,       "table: type declarations by name/wildcard/package"),
    h(`  references${fmtAliases("references")} <FQMN> [--field <name>]`, "markdown: call sites as code snippets"),
    h(`  implementors${fmtAliases("implementors")} <FQN>[#method]`,    "list: type or method implementors"),
    h(`  hierarchy${fmtAliases("hierarchy")} <FQN>`,                "markdown: supers + interfaces + subtypes"),
    h(`  type-info${fmtAliases("type-info")} <FQN>`,                "text: class overview (fields, methods, lines)"),
    h(`  source${fmtAliases("source")} <FQMN>`,                     "markdown: source + resolved references"),
    "",
    "Testing & building:",
    h(`  build${fmtAliases("build")} [--project <name>] [--clean]`, "build project (incremental or clean)"),
    h("  test run <FQN> [-f] [-q]",                        "launch JUnit/PDE tests (non-blocking)"),
    h("  test status <testRunId> [-f] [--all]",            "text: test progress and results"),
    h("  test runs",                                       "table: recent test sessions"),
    "",
    "Diagnostics:",
    h(`  problems${fmtAliases("problems")} [--file <path>] [--project <name>]`, "text: IMarker.PROBLEM (errors, warnings)"),
    h(`  refresh${fmtAliases("refresh")} [<file> ...] [--project <name>]`,  "notify Eclipse of external file changes"),
    "",
    "Maven:",
    h("  maven update [--project <name>]",                 "update Maven project (Alt+F5)"),
    "",
    "Refactoring:",
    h(`  organize-imports${fmtAliases("organize-imports")} <file>`,  "organize imports"),
    h(`  format${fmtAliases("format")} <file>`,                      "format with Eclipse project settings"),
    h("  rename <FQMN> <newName> [--field <old>]",         "rename type/method/field"),
    h("  move <FQN> <target.package>",                     "move type to another package"),
    "",
    "Launches:",
    h("  launch configs",                                  "table: saved ILaunchConfigurations"),
    h("  launch config <configId> [--xml] [--json]",       "text/JSON/XML: configuration details"),
    h("  launch run <configId> [-f] [-q]",                 "run a launch configuration"),
    h("  launch debug <configId> [-f] [-q]",               "run with Eclipse debugger attached"),
    h("  launch list",                                     "table: running + terminated ILaunches"),
    h("  launch logs <launchId> [-f] [--tail N]",          "text: console output (stdout/stderr)"),
    h("  launch stop <launchId>",                          "terminate a running ILaunch"),
    h("  launch clear [launchId]",                         "remove terminated ILaunches from list"),
    h("  launch config --import <path> [--configid <name>]", "import .launch file into workspace"),
    "",
    "Editor:",
    h("  open <FQMN>",                                    "open in Eclipse editor"),
    "",
    "Workspace detail:",
    h("  projects",                                        "table: workspace projects (name, location, repo)"),
    h(`  project-info${fmtAliases("project-info")} <name> [--lines N]`, "text: project overview (adaptive detail)"),
    h(`  editors${fmtAliases("editors")}`,                          "table: open editor tabs (FQN, project, path)"),
    h("  git [list] [repo...] [--no-files]",               "table: git repos, branches, dirty state"),
    "",
    "Agents:",
    h("  agent run <provider> <agent> [--name <id>]",      "launch agent session via Eclipse"),
    h("  agent list",                                      "table: running agent sessions"),
    h("  agent stop <name>",                               "terminate agent by session ID"),
    h("  agent logs <name> [-f]",                          "text: stream agent console output"),
    h("  agent providers",                                 "table: available agent providers"),
    "",
    "Instance management:",
    h("  use [N|alias|path] [--alias <name>] [--delete]",  "pin terminal to Eclipse workspace"),
    "",
    "Setup:",
    h("  setup [--check|--remove]",                        "install/check/remove Eclipse plugin"),
    h("  setup remote [--bridge-socket <host>:<port>]",     "configure remote Eclipse connection"),
    "",
    'Use "jdt help <command>" for detailed usage of any command.',
  ].join("\n"));
}

export { helpLine, stripAnsi, HELP_COL };

export async function run(argv) {
  const [command, ...rest] = argv;

  if (!command || command === "--help") {
    printOverview();
    return;
  }

  if (command === "--version" || command === "-v") {
    console.log(version);
    return;
  }

  if (command === "help") {
    const topic = rest[0];
    const resolved = topic ? resolve(topic) : null;
    if (resolved === "setup" && rest[1] === "remote") {
      const { setupRemoteHelp } = await import("./commands/setup-remote.mjs");
      console.log(setupRemoteHelp);
      return;
    } else if (resolved === "launch" && rest[1] && launchSubcommands[rest[1]]) {
      console.log(launchSubcommands[rest[1]].help);
    } else if (resolved === "test" && rest[1] && testSubcommands[rest[1]]) {
      console.log(testSubcommands[rest[1]].help);
    } else if (resolved === "agent" && rest[1] && agentSubcommands[rest[1]]) {
      console.log(agentSubcommands[rest[1]].help);
    } else if (resolved) {
      console.log(commands[resolved].help);
    } else if (topic) {
      console.error(`Unknown command: ${topic}`);
      console.log();
      printOverview();
    } else {
      printOverview();
    }
    return;
  }

  const resolved = resolve(command);
  if (!resolved) {
    console.error(`Unknown command: ${command}`);
    console.log();
    printOverview();
    process.exit(1);
  }

  try {
    await commands[resolved].fn(rest);
  } catch (e) {
    if (isConnectionError(e)) {
      console.error(
        bold(red("Eclipse JDT Bridge not responding.")) +
          "\nCheck that Eclipse is running with the jdtbridge plugin.\n",
      );
    } else {
      console.error(e.message);
    }
    process.exit(1);
  }
}
