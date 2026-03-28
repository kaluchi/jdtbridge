// Main CLI dispatcher.
// Maps command names to handler functions and provides help.

import { projects, help as projectsHelp } from "./commands/projects.mjs";
import { projectInfo, help as projectInfoHelp } from "./commands/project-info.mjs";
import { find, help as findHelp } from "./commands/find.mjs";
import { references, help as referencesHelp } from "./commands/references.mjs";
import { subtypes, help as subtypesHelp } from "./commands/subtypes.mjs";
import { hierarchy, help as hierarchyHelp } from "./commands/hierarchy.mjs";
import { implementors, help as implementorsHelp } from "./commands/implementors.mjs";
import { typeInfo, help as typeInfoHelp } from "./commands/type-info.mjs";
import { source, help as sourceHelp } from "./commands/source.mjs";
import { build, help as buildHelp } from "./commands/build.mjs";
import { testRun, help as testRunHelp } from "./commands/test-run.mjs";
import { testStatus, help as testStatusHelp } from "./commands/test-status.mjs";
import { testSessions, help as testSessionsHelp } from "./commands/test-sessions.mjs";
import { errors, help as errorsHelp } from "./commands/errors.mjs";
import { refresh, help as refreshHelp } from "./commands/refresh.mjs";
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
  launchList,
  launchConfigs,
  launchRun,
  launchDebug,
  launchStop,
  launchClear,
  launchLogs,
  launchConsole,
  launchListHelp,
  launchConfigsHelp,
  launchRunHelp,
  launchDebugHelp,
  launchStopHelp,
  launchClearHelp,
  launchLogsHelp,
  launchConsoleHelp,
} from "./commands/launch.mjs";
import { setup, help as setupHelp } from "./commands/setup.mjs";
import { isConnectionError } from "./client.mjs";
import { bold, red, dim } from "./color.mjs";
import { createRequire } from "node:module";

const { version } = createRequire(import.meta.url)("../package.json");

const launchSubcommands = {
  list: { fn: launchList, help: launchListHelp },
  configs: { fn: launchConfigs, help: launchConfigsHelp },
  run: { fn: launchRun, help: launchRunHelp },
  debug: { fn: launchDebug, help: launchDebugHelp },
  stop: { fn: launchStop, help: launchStopHelp },
  clear: { fn: launchClear, help: launchClearHelp },
  logs: { fn: launchLogs, help: launchLogsHelp },
  console: { fn: launchConsole, help: launchConsoleHelp },
};

const launchHelp = `Manage launches (running and terminated processes).

Subcommands:
  jdt launch list                           list all launches
  jdt launch configs                        list saved launch configurations
  jdt launch run <config> [-f] [-q]         launch a configuration
  jdt launch debug <config> [-f] [-q]       launch in debug mode
  jdt launch logs <name> [-f] [--tail N]    show console output
  jdt launch stop <name>                    stop a running launch
  jdt launch clear [name]                   remove terminated launches

Use "jdt help launch <subcommand>" for details.`;

async function launchDispatch(args) {
  const [sub, ...rest] = args;
  if (!sub || sub === "--help") {
    console.log(launchHelp);
    return;
  }
  const cmd = launchSubcommands[sub];
  if (!cmd) {
    console.error(`Unknown launch subcommand: ${sub}`);
    console.log(launchHelp);
    process.exit(1);
  }
  await cmd.fn(rest);
}

const testSubcommands = {
  run: { fn: testRun, help: testRunHelp },
  status: { fn: testStatus, help: testStatusHelp },
  sessions: { fn: testSessions, help: testSessionsHelp },
};

const testHelp = `Run and monitor JUnit tests via Eclipse's built-in runner.

Subcommands:
  jdt test run <FQN> [-f] [-q]                launch tests (non-blocking)
  jdt test status <session> [-f] [--all]      show test progress/results
  jdt test sessions                           list test sessions

Use "jdt help test <subcommand>" for details.`;

async function testDispatch(args) {
  const [sub, ...rest] = args;
  if (!sub || sub === "--help") {
    console.log(testHelp);
    return;
  }
  const cmd = testSubcommands[sub];
  if (!cmd) {
    console.error(`Unknown test subcommand: ${sub}`);
    console.log(testHelp);
    process.exit(1);
  }
  await cmd.fn(rest);
}

const commands = {
  projects: { fn: projects, help: projectsHelp },
  "project-info": { fn: projectInfo, help: projectInfoHelp },
  find: { fn: find, help: findHelp },
  references: { fn: references, help: referencesHelp },
  subtypes: { fn: subtypes, help: subtypesHelp },
  hierarchy: { fn: hierarchy, help: hierarchyHelp },
  implementors: { fn: implementors, help: implementorsHelp },
  "type-info": { fn: typeInfo, help: typeInfoHelp },
  source: { fn: source, help: sourceHelp },
  build: { fn: build, help: buildHelp },
  test: { fn: testDispatch, help: testHelp },
  errors: { fn: errors, help: errorsHelp },
  refresh: { fn: refresh, help: refreshHelp },
  "organize-imports": { fn: organizeImports, help: organizeImportsHelp },
  format: { fn: format, help: formatHelp },
  rename: { fn: rename, help: renameHelp },
  move: { fn: move, help: moveHelp },
  editors: { fn: editors, help: editorsHelp },
  open: { fn: open, help: openHelp },
  launch: { fn: launchDispatch, help: launchHelp },
  setup: { fn: setup, help: setupHelp },
};

/** Short aliases for frequently used commands. */
const aliases = {
  refs: "references",
  impl: "implementors",
  subt: "subtypes",
  hier: "hierarchy",
  pi: "project-info",
  ti: "type-info",
  oi: "organize-imports",
  ed: "editors",
  src: "source",
  b: "build",
  err: "errors",
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

function printOverview() {
  console.log(`Eclipse JDT Bridge — semantic Java analysis via Eclipse JDT SearchEngine.
Requires: Eclipse running with the jdtbridge plugin.

Search & navigation:
  projects                                    list workspace projects
  project-info${fmtAliases("project-info")} <name> [--lines N]             project overview (adaptive detail)
  find <Name|*Pattern*|pkg> [--source-only]   find types by name, wildcard, or package
  references${fmtAliases("references")} <FQMN> [--field <name>]         references to type/method/field
  subtypes${fmtAliases("subtypes")} <FQN>                              all subtypes/implementors
  hierarchy${fmtAliases("hierarchy")} <FQN>                             full hierarchy (supers + interfaces + subtypes)
  implementors${fmtAliases("implementors")} <FQMN>                            implementations of interface method
  type-info${fmtAliases("type-info")} <FQN>                             class overview (fields, methods, line numbers)
  source${fmtAliases("source")} <FQMN>                              source + resolved references (navigation)

Testing & building:
  build${fmtAliases("build")} [--project <name>] [--clean]      build project (incremental or clean)
  test run <FQN> [-f] [-q]                    launch tests (non-blocking)
  test status <session> [-f] [--all]          show test progress/results
  test sessions                               list test sessions

Diagnostics:
  errors${fmtAliases("errors")} [--file <path>] [--project <name>]   compilation errors
  refresh${fmtAliases("refresh")} [<file> ...] [--project <name>]  explicitly notify Eclipse of file changes

Refactoring:
  organize-imports${fmtAliases("organize-imports")} <file>                     organize imports
  format${fmtAliases("format")} <file>                               format with Eclipse project settings
  rename <FQMN> <newName> [--field <old>]     rename type/method/field
  move <FQN> <target.package>                 move type to another package

Launches:
  launch list                                 list launches (running + terminated)
  launch configs                              list saved launch configurations
  launch run <config> [-f] [-q]               launch a configuration
  launch debug <config> [-f] [-q]             launch in debug mode
  launch logs <name> [-f] [--tail N]          show console output
  launch stop <name>                          stop a running launch
  launch clear [name]                         remove terminated launches

Editor:
  editors${fmtAliases("editors")}                                    list open editors (absolute paths)
  open <FQMN>                                 open in Eclipse editor

Setup:
  setup [--check|--remove]                    install/check/remove Eclipse plugin

Use "jdt help <command>" for detailed usage of any command.`);
}

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
    if (resolved === "launch" && rest[1] && launchSubcommands[rest[1]]) {
      console.log(launchSubcommands[rest[1]].help);
    } else if (resolved === "test" && rest[1] && testSubcommands[rest[1]]) {
      console.log(testSubcommands[rest[1]].help);
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
