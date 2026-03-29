// Eclipse instance discovery — find running instances via bridge files and HTTP probe.

import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { request } from "node:http";
import { instancesDir } from "./home.mjs";
import { proxyAwareOptions } from "./proxy.mjs";

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

  const results = await Promise.all(
    candidates.map((inst) => probe(inst).then(() => inst).catch(() => null)),
  );
  return results.filter(Boolean);
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

/** HTTP probe — check if bridge is alive. */
function probe(inst) {
  return new Promise((resolve, reject) => {
    const opts = proxyAwareOptions(
      inst.host, inst.port, "/status", "GET", 2000);
    const req = request(opts, (res) => {
      res.resume();
      resolve();
    });
    req.on("error", reject);
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("timeout"));
    });
    req.end();
  });
}

