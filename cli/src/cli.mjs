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
import { test, help as testHelp } from "./commands/test.mjs";
import { errors, help as errorsHelp } from "./commands/errors.mjs";
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
import { setup, help as setupHelp } from "./commands/setup.mjs";
import { isConnectionError } from "./client.mjs";
import { bold, red, dim } from "./color.mjs";
import { createRequire } from "node:module";

const { version } = createRequire(import.meta.url)("../package.json");

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
  test: { fn: test, help: testHelp },
  errors: { fn: errors, help: errorsHelp },
  "organize-imports": { fn: organizeImports, help: organizeImportsHelp },
  format: { fn: format, help: formatHelp },
  rename: { fn: rename, help: renameHelp },
  move: { fn: move, help: moveHelp },
  editors: { fn: editors, help: editorsHelp },
  open: { fn: open, help: openHelp },
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
  source${fmtAliases("source")} <FQMN>                              type or method source code (project and libraries)

Testing & building:
  build${fmtAliases("build")} [--project <name>] [--clean]      build project (incremental or clean)
  test <FQMN>                                 run JUnit test class or method
  test --project <name> [--package <pkg>]     run tests in project/package

Diagnostics:
  errors${fmtAliases("errors")} [--file <path>] [--project <name>]   compilation errors

Refactoring:
  organize-imports${fmtAliases("organize-imports")} <file>                     organize imports
  format${fmtAliases("format")} <file>                               format with Eclipse project settings
  rename <FQMN> <newName> [--field <old>]     rename type/method/field
  move <FQN> <target.package>                 move type to another package

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
    if (resolved) {
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
