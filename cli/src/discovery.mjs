// Eclipse instance discovery — find running instances via bridge files and HTTP probe.

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { request } from "node:http";
import { instancesDir } from "./home.mjs";

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
 */

/**
 * Read all instance files and probe each via HTTP.
 * Supports JDT_BRIDGE_HOST env var and host field in instance JSON.
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

  const envHost = process.env.JDT_BRIDGE_HOST;
  const candidates = [];
  for (const file of files) {
    const filePath = join(dir, file);
    try {
      const data = JSON.parse(readFileSync(filePath, "utf8"));
      if (!data.port) continue;
      const host = envHost || data.host || "127.0.0.1";
      candidates.push({ ...data, host, file: filePath });
    } catch {
      // corrupt file — skip
    }
  }

  // Remote instances (non-localhost): trust the file — probe
  // won't work through Docker sandbox HTTP proxy anyway.
  // Local instances: probe to filter stale.
  const local = [];
  const remote = [];
  for (const inst of candidates) {
    if (isLocal(inst.host)) {
      local.push(inst);
    } else {
      remote.push(inst);
    }
  }
  const probed = await Promise.all(
    local.map((inst) => probe(inst).then(() => inst).catch(() => null)),
  );
  return [...probed.filter(Boolean), ...remote];
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
    const normalized = workspaceHint.replace(/\\/g, "/").toLowerCase();
    const match = instances.find((i) =>
      i.workspace.replace(/\\/g, "/").toLowerCase().includes(normalized),
    );
    if (match) return match;
  }

  return instances[0];
}

function isLocal(host) {
  return host === "127.0.0.1" || host === "localhost" || host === "::1";
}

/**
 * HTTP probe — check if bridge is alive on host:port.
 */
function probe(inst) {
  return new Promise((resolve, reject) => {
    const req = request(
      { hostname: inst.host, port: inst.port, path: "/status", method: "GET", timeout: 2000 },
      (res) => {
        res.resume();
        resolve();
      },
    );
    req.on("error", reject);
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("timeout"));
    });
    req.end();
  });
}

