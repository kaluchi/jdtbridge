// Eclipse instance discovery — find running instances via bridge files.

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { instancesDir, remoteInstancesDir } from "./home.mjs";
import { getPinnedBridge } from "./bridge-env.mjs";
import { normalizePath } from "./paths.mjs";
import { httpRequest } from "./http.mjs";

/**
 * @typedef {Object} Instance
 * @property {number} port
 * @property {string} token
 * @property {number} pid
 * @property {string} workspace
 * @property {string} [version]
 * @property {string} [location]
 * @property {string} host - bridge host (default 127.0.0.1)
 * @property {string} file - path to the instance file
 * @property {boolean} [remote] - true for remote instances
 */

/**
 * Read all instance files. No validation — connection errors are
 * handled at request time by the HTTP client.
 * @returns {Promise<Instance[]>}
 */
export async function discoverInstances() {
  const dir = instancesDir();
  let files;
  try {
    files = readdirSync(dir).filter((f) => f.endsWith(".json"));
  } catch {
    return [];
  }

  const pinned = getPinnedBridge();
  const envHost = pinned?.host;
  const instances = [];
  for (const file of files) {
    const filePath = join(dir, file);
    try {
      const data = JSON.parse(readFileSync(filePath, "utf8"));
      if (!data.port) continue;
      const host = envHost || data.host || "127.0.0.1";
      instances.push({ ...data, host, file: filePath });
    } catch {
      // corrupt file — skip
    }
  }

  // Remote instances (jdt setup remote)
  const remoteDir = remoteInstancesDir();
  try {
    const remoteFiles = readdirSync(remoteDir).filter(
      (f) => f.endsWith(".json"));
    for (const file of remoteFiles) {
      const filePath = join(remoteDir, file);
      try {
        const data = JSON.parse(readFileSync(filePath, "utf8"));
        const bridgeSocket = data["bridge-socket"];
        if (!bridgeSocket) continue;
        const colonIdx = bridgeSocket.lastIndexOf(":");
        if (colonIdx < 0) continue;
        const host = bridgeSocket.substring(0, colonIdx);
        const port = parseInt(bridgeSocket.substring(colonIdx + 1), 10);
        if (!port) continue;
        instances.push({
          port,
          token: data.token,
          host,
          pid: 0,
          workspace: bridgeSocket,
          file: filePath,
          remote: true,
        });
      } catch {
        // corrupt file — skip
      }
    }
  } catch {
    // dir not found
  }

  return instances;
}

/**
 * Find a single instance.
 * @param {string} [workspaceHint]
 * @returns {Promise<Instance|null>}
 */
export async function findInstance(workspaceHint) {
  const instances = await discoverInstances();
  if (instances.length === 0) return null;
  if (instances.length === 1) return instances[0];

  if (workspaceHint) {
    const normalized = normalizePath(workspaceHint).toLowerCase();
    const match = instances.find((i) =>
      normalizePath(i.workspace).toLowerCase().includes(normalized),
    );
    if (match) return match;
  }

  return instances[0];
}

/**
 * HTTP probe — check if bridge is alive.
 * Used only for diagnostics (setup --check), not for normal discovery.
 */
export async function probe(inst) {
  const res = await httpRequest(inst, "/status");
  if (res.error) throw res.error;
}

/**
 * Fetch the /projects list from an instance.
 * Returns an array of {fqn, rootPath, ...} objects or [] on any
 * failure (network error, non-200 status, invalid JSON). Used by
 * cwd-match resolution (resolve.mjs), where "can't tell" should
 * simply fall through to the next resolution step rather than break.
 */
export async function fetchProjects(inst, timeoutMs = 3000) {
  const { status, body } = await httpRequest(inst, "/projects", "GET", timeoutMs);
  if (status !== 200) return [];
  try {
    const parsed = JSON.parse(body);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}
