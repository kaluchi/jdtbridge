// jdt use — multi-instance workspace management.
// See docs/jdt-use-spec.md for full design.

import { parseFlags, extractPositional } from "../args.mjs";
import { readWorkspaces, writeWorkspaces, listPins, readPin } from "../home.mjs";
import { discoverInstances } from "../discovery.mjs";
import {
  workspacePathsMatch,
  writePinFiles,
  cleanStalePins,
} from "../resolve.mjs";
import { resolveTerminalId } from "../terminal-id.mjs";
import { formatTable } from "../format/table.mjs";
import { green, yellow, dim, bold } from "../color.mjs";
import { printJson } from "../json-output.mjs";

export async function use(args) {
  const flags = parseFlags(args);
  const positional = extractPositional(args).filter(
    a => a !== "-q" && a !== "--quiet",
  );

  if (flags.pins) {
    return handlePins(flags);
  }

  if (flags.delete) {
    return handleDelete(flags.delete);
  }

  if (flags.alias !== undefined && positional.length > 0) {
    return handleAlias(positional[0], flags.alias);
  }

  if (positional.length > 0) {
    return handlePin(positional[0]);
  }

  return handleList(flags);
}

async function handleList(flags) {
  const workspaces = readWorkspaces();
  const instances = await discoverInstances();
  const allWorkspaces = syncNewInstances(workspaces, instances);
  const updated = allWorkspaces.length > workspaces.length;

  // Resolve currently pinned workspace
  const pinnedWorkspace = resolvePinnedWorkspace();

  // Build display rows
  const rows = allWorkspaces.map((entry, i) => {
    const inst = instances.find(
      inst => workspacePathsMatch(inst.workspace, entry.workspace),
    );
    const isNew = i >= workspaces.length;
    const pinned = pinnedWorkspace &&
      workspacePathsMatch(entry.workspace, pinnedWorkspace);

    let status;
    if (isNew) status = yellow("*new*");
    else if (inst) status = green("online");
    else status = dim("offline");

    return [
      String(i + 1),
      entry.alias || "",
      entry.workspace,
      status,
      pinned ? bold("pinned") : "",
      inst?.version || "",
      inst ? String(inst.port) : "",
    ];
  });

  // Clean stale pins
  cleanStalePins(instances);

  // Save updated registry
  if (updated) writeWorkspaces(allWorkspaces);

  if (flags.json) {
    const jsonData = allWorkspaces.map((entry, i) => {
      const inst = instances.find(
        inst => workspacePathsMatch(inst.workspace, entry.workspace),
      );
      const pinned = pinnedWorkspace &&
        workspacePathsMatch(entry.workspace, pinnedWorkspace);
      return {
        index: i + 1,
        alias: entry.alias || null,
        workspace: entry.workspace,
        status: i >= workspaces.length ? "new"
          : inst ? "online" : "offline",
        pinned: !!pinned,
        port: inst?.port || null,
        version: inst?.version || null,
      };
    });
    printJson(jsonData);
    return;
  }

  if (rows.length === 0) {
    console.log("No workspaces registered. Start an Eclipse instance first.");
    return;
  }

  console.log(
    formatTable(
      ["#", "ALIAS", "WORKSPACE", "STATUS", "PINNED", "VERSION", "PORT"],
      rows,
    ),
  );
  console.log();
  console.log(`Pin this terminal:  ${dim("jdt use <N|alias|path>")}`);
}

async function handlePin(target) {
  const workspaces = readWorkspaces();
  const instances = await discoverInstances();
  const allWorkspaces = syncNewInstances(workspaces, instances);
  if (allWorkspaces.length > workspaces.length) {
    writeWorkspaces(allWorkspaces);
  }

  const entry = resolveTarget(allWorkspaces, target);
  if (!entry) {
    console.error(`No workspace matching: ${target}`);
    process.exit(1);
  }

  const inst = instances.find(
    i => workspacePathsMatch(i.workspace, entry.workspace),
  );
  if (!inst) {
    console.error(
      `Workspace offline — no running Eclipse instance for:\n  ${entry.workspace}`,
    );
    process.exit(1);
  }

  writePinFiles(entry.workspace);

  const aliasLabel = entry.alias ? ` (${entry.alias})` : "";
  console.log(
    `Pinned to: ${bold(entry.workspace)}${aliasLabel} port ${inst.port}`,
  );
}

function handlePins(flags) {
  const files = listPins();
  const termId = resolveTerminalId();
  const pins = files.map(f => {
    const pin = readPin(f);
    const { pinType, pinKey } = parsePinFilename(f);
    const active = pinType === "terminal"
      ? termId === pinKey
      : String(process.ppid) === pinKey;
    return { file: f, pinType, pinKey, active, ...pin };
  });

  if (flags.json) {
    printJson(pins);
    return;
  }

  if (pins.length === 0) {
    console.log("No active pins.");
    return;
  }

  const rows = pins.map(p => [
    p.pinType,
    p.pinKey,
    p.workspace || "",
    p.active ? green("active") : dim("stale"),
    p.pinnedAt || "",
  ]);
  console.log(formatTable(
    ["PINTYPE", "PINKEY", "WORKSPACE", "STATUS", "PINNED_AT"],
    rows,
  ));
}

/** Parse pin filename. "term-abc.json" → {pinType:"terminal", pinKey:"abc"} */
function parsePinFilename(filename) {
  const name = filename.replace(/\.json$/, "");
  const dash = name.indexOf("-");
  const prefix = name.slice(0, dash);
  const pinKey = name.slice(dash + 1);
  return {
    pinType: prefix === "term" ? "terminal" : prefix,
    pinKey,
  };
}

function handleAlias(target, aliasValue) {
  const workspaces = readWorkspaces();
  const entry = resolveTarget(workspaces, target);
  if (!entry) {
    console.error(`No workspace matching: ${target}`);
    process.exit(1);
  }

  if (aliasValue === "" || aliasValue === true) {
    // Remove alias
    delete entry.alias;
    writeWorkspaces(workspaces);
    console.log(`Alias removed for: ${entry.workspace}`);
    return;
  }

  if (!/^[a-zA-Z0-9-]+$/.test(aliasValue)) {
    console.error(
      "Alias must be alphanumeric with hyphens (e.g. my-project).",
    );
    process.exit(1);
  }

  // Check uniqueness
  const existing = workspaces.find(
    w => w.alias === aliasValue && w !== entry,
  );
  if (existing) {
    console.error(
      `Alias "${aliasValue}" already used by: ${existing.workspace}`,
    );
    process.exit(1);
  }

  entry.alias = aliasValue;
  writeWorkspaces(workspaces);
  console.log(`Alias set: ${bold(aliasValue)} → ${entry.workspace}`);
}

function handleDelete(target) {
  const workspaces = readWorkspaces();
  const entry = resolveTarget(workspaces, target);
  if (!entry) {
    console.error(`No workspace matching: ${target}`);
    process.exit(1);
  }

  const idx = workspaces.indexOf(entry);
  workspaces.splice(idx, 1);
  writeWorkspaces(workspaces);
  console.log(`Removed: ${entry.workspace}`);
}

/**
 * Check current pins to find which workspace is pinned in this terminal.
 * Same priority as resolve.mjs: ppid pin → terminal pin.
 * @returns {string|null} workspace path or null
 */
function resolvePinnedWorkspace() {
  const ppidPin = readPin(`ppid-${process.ppid}.json`);
  if (ppidPin?.workspace) return ppidPin.workspace;
  const termId = resolveTerminalId();
  if (termId) {
    const termPin = readPin(`term-${termId}.json`);
    if (termPin?.workspace) return termPin.workspace;
  }
  return null;
}

/**
 * Resolve target argument: number → alias → path substring.
 * @param {Array} workspaces
 * @param {string} target
 * @returns {object|null}
 */
function resolveTarget(workspaces, target) {
  // Number
  const num = parseInt(target, 10);
  if (!isNaN(num) && num >= 1 && num <= workspaces.length) {
    return workspaces[num - 1];
  }

  // Alias
  const byAlias = workspaces.find(w => w.alias === target);
  if (byAlias) return byAlias;

  // Path (exact or substring)
  const normTarget = normalizePath(target);
  return workspaces.find(w =>
    normalizePath(w.workspace).includes(normTarget),
  ) || null;
}

/** Lowercase, forward-slash normalized path for comparison. */
function normalizePath(p) {
  return p.toLowerCase().replace(/\\/g, "/");
}

/**
 * Sync new instances into workspaces list (in-memory only).
 * Returns the combined list.
 */
function syncNewInstances(workspaces, instances) {
  const knownPaths = new Set(workspaces.map(w => normalizePath(w.workspace)));
  const combined = [...workspaces];
  for (const inst of instances) {
    if (!knownPaths.has(normalizePath(inst.workspace))) {
      combined.push({
        workspace: inst.workspace,
        addedAt: new Date().toISOString(),
      });
      knownPaths.add(normalizePath(inst.workspace));
    }
  }
  return combined;
}

export const help = `Manage connections to multiple Eclipse instances.

Lists known workspaces, pins the current terminal to a specific
Eclipse instance, and manages workspace aliases.

Usage:
  jdt use                                list known workspaces
  jdt use <N|alias|path>                 pin this terminal to a workspace
  jdt use <N|alias> --alias <name>       set alias for a workspace
  jdt use --delete <N|alias>             remove workspace from registry
  jdt use --pins                         show active pin files

Flags:
  --json       JSON output (list and pins modes)
  --alias <n>  set or change alias (alphanumeric + hyphens)
  --delete     remove workspace entry
  --pins       show pin files for debugging

The target argument resolves in order: number, alias, path substring.
Pinning writes a file keyed by terminal session ID — all subsequent
jdt commands in this terminal tab use the pinned workspace.

For parallel subagents: jdt use N && jdt find Foo (ppid isolation).

Examples:
  jdt use                          show all workspaces
  jdt use 2                        pin to workspace #2
  jdt use web                      pin by alias
  jdt use 2 --alias web            set alias "web" for workspace #2
  jdt use --delete 3               remove workspace #3
  jdt use --json                   JSON output for scripting`;
