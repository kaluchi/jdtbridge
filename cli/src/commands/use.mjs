// jdt use — multi-instance workspace management.
// See docs/jdt-use-spec.md for full design.

import { parseFlags, extractPositional } from "../args.mjs";
import { readWorkspaces, writeWorkspaces, listPins, readPin } from "../home.mjs";
import { discoverInstances } from "../discovery.mjs";
import {
  workspacePathsMatch,
  normalizeWorkspacePath,
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
  const liveInstances = await discoverInstances();
  const allWorkspaces = syncNewInstances(workspaces, liveInstances);
  const updated = allWorkspaces.length > workspaces.length;
  const pinnedWorkspace = resolvePinnedWorkspace();

  // Build enriched model — one pass, shared by text and JSON
  const enriched = allWorkspaces.map((wsEntry, idx) => {
    const liveInstance = liveInstances.find(
      li => workspacePathsMatch(li.workspace, wsEntry.workspace),
    );
    const isNew = idx >= workspaces.length;
    return {
      index: idx + 1,
      alias: wsEntry.alias || null,
      workspace: wsEntry.workspace,
      status: isNew ? "new" : liveInstance ? "online" : "offline",
      pinned: !!pinnedWorkspace &&
        workspacePathsMatch(wsEntry.workspace, pinnedWorkspace),
      port: liveInstance?.port || null,
      version: liveInstance?.version || null,
    };
  });

  cleanStalePins(liveInstances);
  if (updated) writeWorkspaces(allWorkspaces);

  if (flags.json) {
    printJson(enriched);
    return;
  }

  if (enriched.length === 0) {
    console.log("No workspaces registered. Start an Eclipse instance first.");
    return;
  }

  const STATUS_COLOR = { new: yellow, online: green, offline: dim };
  const rows = enriched.map(ws => [
    String(ws.index),
    ws.alias || "",
    ws.workspace,
    (STATUS_COLOR[ws.status] || dim)(ws.status === "new" ? "*new*" : ws.status),
    ws.pinned ? bold("pinned") : "",
    ws.version || "",
    ws.port ? String(ws.port) : "",
  ]);

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
  const liveInstances = await discoverInstances();
  const allWorkspaces = syncNewInstances(workspaces, liveInstances);
  if (allWorkspaces.length > workspaces.length) {
    writeWorkspaces(allWorkspaces);
  }

  const wsEntry = resolveTarget(allWorkspaces, target);
  if (!wsEntry) {
    console.error(`No workspace matching: ${target}`);
    process.exit(1);
  }

  const liveInstance = liveInstances.find(
    li => workspacePathsMatch(li.workspace, wsEntry.workspace),
  );
  if (!liveInstance) {
    console.error(
      `Workspace offline — no running Eclipse instance for:\n  ${wsEntry.workspace}`,
    );
    process.exit(1);
  }

  writePinFiles(wsEntry.workspace);

  const aliasLabel = wsEntry.alias ? ` (${wsEntry.alias})` : "";
  console.log(
    `Pinned to: ${bold(wsEntry.workspace)}${aliasLabel} port ${liveInstance.port}`,
  );
}

function handlePins(flags) {
  const files = listPins();
  const termId = resolveTerminalId();
  const pins = files.map(pinFilename => {
    const pinData = readPin(pinFilename);
    const { pinType, pinKey } = parsePinFilename(pinFilename);
    const active = pinType === "terminal"
      ? termId === pinKey
      : String(process.ppid) === pinKey;
    return { file: pinFilename, pinType, pinKey, active, ...pinData };
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
  const wsEntry = resolveTarget(workspaces, target);
  if (!wsEntry) {
    console.error(`No workspace matching: ${target}`);
    process.exit(1);
  }

  if (aliasValue === "" || aliasValue === true) {
    delete wsEntry.alias;
    writeWorkspaces(workspaces);
    console.log(`Alias removed for: ${wsEntry.workspace}`);
    return;
  }

  if (!/^[a-zA-Z0-9-]+$/.test(aliasValue)) {
    console.error(
      "Alias must be alphanumeric with hyphens (e.g. my-project).",
    );
    process.exit(1);
  }

  const conflicting = workspaces.find(
    w => w.alias === aliasValue && w !== wsEntry,
  );
  if (conflicting) {
    console.error(
      `Alias "${aliasValue}" already used by: ${conflicting.workspace}`,
    );
    process.exit(1);
  }

  wsEntry.alias = aliasValue;
  writeWorkspaces(workspaces);
  console.log(`Alias set: ${bold(aliasValue)} → ${wsEntry.workspace}`);
}

function handleDelete(target) {
  const workspaces = readWorkspaces();
  const wsEntry = resolveTarget(workspaces, target);
  if (!wsEntry) {
    console.error(`No workspace matching: ${target}`);
    process.exit(1);
  }

  workspaces.splice(workspaces.indexOf(wsEntry), 1);
  writeWorkspaces(workspaces);
  console.log(`Removed: ${wsEntry.workspace}`);
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
  const normTarget = normalizeWorkspacePath(target);
  return workspaces.find(w =>
    normalizeWorkspacePath(w.workspace).includes(normTarget),
  ) || null;
}

/**
 * Sync new instances into workspaces list (in-memory only).
 * Returns the combined list.
 */
function syncNewInstances(workspaces, liveInstances) {
  const knownPaths = new Set(
    workspaces.map(w => normalizeWorkspacePath(w.workspace)),
  );
  const combined = [...workspaces];
  for (const liveInstance of liveInstances) {
    const normPath = normalizeWorkspacePath(liveInstance.workspace);
    if (!knownPaths.has(normPath)) {
      combined.push({
        workspace: liveInstance.workspace,
        addedAt: new Date().toISOString(),
      });
      knownPaths.add(normPath);
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
