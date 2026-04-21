// Connection resolution — 4-step chain for multi-instance support.
// See docs/jdt-use-spec.md for full algorithm and rationale.

import { execSync } from "node:child_process";
import { getPinnedBridge } from "./bridge-env.mjs";
import { discoverInstances, fetchProjects } from "./discovery.mjs";
import { resolveTerminalId } from "./terminal-id.mjs";
import { readPin, writePin, deletePin, listPins } from "./home.mjs";
import { normalizePath } from "./paths.mjs";

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

  // Discover once, reuse across steps 2-4. Filter to live
  // instances up front — remote instances have no PID to check.
  const instances = await discoverInstances();
  const live = instances.filter(
      i => i.remote || !i.pid || isPidAlive(i.pid));

  // Step 2: ppid pin
  const ppidFile = `ppid-${process.ppid}.json`;
  const ppidPin = readPin(ppidFile);
  if (ppidPin) {
    if (isPidAlive(process.ppid)) {
      const match = findByWorkspace(live, ppidPin.workspace);
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
      const match = findByWorkspace(live, termPin.workspace);
      if (match) return match;
      // Workspace offline — stale pin
      deletePin(termFile);
    }
  }

  // Step 4: discovery fallback
  if (live.length === 0) return null;
  if (live.length === 1) return live[0];

  // Step 5: cwd-match across local live instances.
  // Fires only in multi-instance ambiguity. Picks the instance whose
  // project tree contains cwd (longest-prefix match). Unique winner
  // wins silently; ties or no match fall through to the warning.
  const cwdMatch = await findInstanceByCwd(live);
  if (cwdMatch) return cwdMatch;

  // Multiple live instances, no pin, no cwd match — warn
  process.stderr.write(
    "\u26A0 Multiple running Eclipse instances found. Using first.\n" +
    "  Run `jdt use` to see all and pin one.\n",
  );
  return live[0];
}

/**
 * cwd-match: for multi-instance ambiguity, find the instance whose
 * projects contain cwd. Returns the instance or null.
 *
 * Longest-prefix match across (instance, projectRootPath) pairs.
 * Remote instances are skipped — their project rootPaths live in a
 * different filesystem namespace than the CLI's cwd.
 *
 * @param {import('./discovery.mjs').Instance[]} liveInstances
 * @returns {Promise<import('./discovery.mjs').Instance|null>}
 */
async function findInstanceByCwd(liveInstances) {
  const cwd = normalizeWorkspacePath(process.cwd());
  const localLive = liveInstances.filter((i) => !i.remote);
  if (localLive.length === 0) return null;

  const projectsByInstance = await Promise.all(
    localLive.map((inst) => fetchProjects(inst)),
  );

  let winner = null;
  let longest = 0;
  let tied = false;
  for (let i = 0; i < localLive.length; i++) {
    const inst = localLive[i];
    const projects = projectsByInstance[i];
    for (const p of projects) {
      if (!p || typeof p.rootPath !== "string") continue;
      const root = normalizeWorkspacePath(p.rootPath);
      if (!cwdStartsWith(cwd, root)) continue;
      if (root.length > longest) {
        longest = root.length;
        winner = inst;
        tied = false;
      } else if (root.length === longest && inst !== winner) {
        tied = true;
      }
    }
  }
  return tied ? null : winner;
}

/** cwd starts with root at a path boundary (exact or followed by /). */
function cwdStartsWith(cwd, root) {
  if (!cwd.startsWith(root)) return false;
  return cwd.length === root.length || cwd[root.length] === "/";
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
  return normalizePath(p).toLowerCase();
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
