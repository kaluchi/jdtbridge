// Connection resolution — 4-step chain for multi-instance support.
// See docs/jdt-use-spec.md for full algorithm and rationale.

import { execSync } from "node:child_process";
import { getPinnedBridge } from "./bridge-env.mjs";
import { discoverInstances } from "./discovery.mjs";
import { resolveTerminalId } from "./terminal-id.mjs";
import { readPin, writePin, deletePin, listPins } from "./home.mjs";

/**
 * Resolve the target Eclipse instance.
 *
 * 1. Env vars JDT_BRIDGE_PORT/TOKEN → use directly
 * 2. ppid pin (ppid alive?) → resolve workspace from live instances
 * 3. Terminal ID pin → resolve workspace from live instances
 * 4. Discovery → auto / warn if multiple
 *
 * @returns {Promise<import('./discovery.mjs').Instance|null>}
 */
export async function resolveInstance() {
  // Step 1: env vars (AgentLaunchDelegate, Docker)
  const pinned = getPinnedBridge();
  if (pinned) {
    return {
      port: pinned.port,
      token: pinned.token,
      host: pinned.host,
      workspace: "",
      pid: 0,
      file: "",
      session: pinned.session,
    };
  }

  // Discover once, reuse across steps 2-4
  const instances = await discoverInstances();

  // Step 2: ppid pin
  const ppidFile = `ppid-${process.ppid}.json`;
  const ppidPin = readPin(ppidFile);
  if (ppidPin) {
    if (isPidAlive(process.ppid)) {
      const match = findByWorkspace(instances, ppidPin.workspace);
      if (match) return match;
    }
    deletePin(ppidFile);
  }

  // Step 3: terminal ID pin
  const termId = resolveTerminalId();
  if (termId) {
    const termFile = `term-${termId}.json`;
    const termPin = readPin(termFile);
    if (termPin) {
      const match = findByWorkspace(instances, termPin.workspace);
      if (match) return match;
      // Workspace offline — stale pin
      deletePin(termFile);
    }
  }

  // Step 4: discovery fallback
  if (instances.length === 0) return null;
  if (instances.length === 1) return instances[0];

  // Multiple instances — warn
  process.stderr.write(
    "\u26A0 Multiple Eclipse instances found. Using first.\n" +
    "  Run `jdt use` to see all and pin one.\n",
  );
  return instances[0];
}

/**
 * Write both pin files (ppid + terminal) for a workspace.
 * Called by `jdt use N` after user selects a workspace.
 * @param {string} workspacePath
 */
export function writePinFiles(workspacePath) {
  const pinData = {
    workspace: workspacePath,
    pinnedAt: new Date().toISOString(),
  };
  writePin(`ppid-${process.ppid}.json`, pinData);
  const termId = resolveTerminalId();
  if (termId) {
    writePin(`term-${termId}.json`, pinData);
  }
}

/**
 * Clean up stale pin files.
 * - ppid pins: delete if PID is dead
 * - term pins: delete if workspace has no live instance
 * @param {import('./discovery.mjs').Instance[]} liveInstances
 */
export function cleanStalePins(liveInstances) {
  for (const filename of listPins()) {
    const pin = readPin(filename);
    if (!pin) { deletePin(filename); continue; }

    if (filename.startsWith("ppid-")) {
      const pid = parseInt(filename.slice(5, -5), 10);
      if (!isPidAlive(pid)) deletePin(filename);
    } else if (filename.startsWith("term-")) {
      if (!findByWorkspace(liveInstances, pin.workspace)) {
        deletePin(filename);
      }
    }
  }
}

/** Lowercase, forward-slash normalized path for comparison. */
export function normalizeWorkspacePath(p) {
  return p.toLowerCase().replace(/\\/g, "/");
}

/**
 * Case-insensitive, backslash-normalized workspace path comparison.
 * @param {string} a
 * @param {string} b
 * @returns {boolean}
 */
export function workspacePathsMatch(a, b) {
  return normalizeWorkspacePath(a) === normalizeWorkspacePath(b);
}

/**
 * Find an instance by workspace path among live instances.
 * @param {import('./discovery.mjs').Instance[]} instances
 * @param {string} workspacePath
 * @returns {import('./discovery.mjs').Instance|undefined}
 */
function findByWorkspace(instances, workspacePath) {
  return instances.find(i =>
    workspacePathsMatch(i.workspace, workspacePath));
}

/**
 * Cross-platform PID liveness check.
 * @param {number} pid
 * @returns {boolean}
 */
function isPidAlive(pid) {
  if (!pid || pid <= 0) return false;
  if (process.platform === "win32") {
    try {
      const out = execSync(
        `tasklist /FI "PID eq ${pid}" /NH`,
        { encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] },
      );
      return out.includes(String(pid));
    } catch {
      return false;
    }
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}
