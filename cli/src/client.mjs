// HTTP client for JDT Bridge server.

import { request } from "node:http";
import { findInstance, discoverInstances, probe } from "./discovery.mjs";
import { proxyAwareOptions } from "./proxy.mjs";
import { red, bold } from "./color.mjs";

/** @type {import('./discovery.mjs').Instance|null} */
let _instance;

/**
 * Ensure we have a connected instance. Call before any HTTP request.
 * Instant — reads instance files without probing.
 * @param {string} [workspaceHint]
 * @returns {import('./discovery.mjs').Instance}
 */
export async function connect(workspaceHint) {
  if (_instance) return _instance;

  // Pinned connection — skip discovery entirely
  const envPort = process.env.JDT_BRIDGE_PORT;
  const envToken = process.env.JDT_BRIDGE_TOKEN;
  if (envPort && envToken) {
    _instance = {
      port: Number(envPort),
      token: envToken,
      host: process.env.JDT_BRIDGE_HOST || "127.0.0.1",
      workspace: process.env.JDT_BRIDGE_WORKSPACE || "",
      pid: 0,
      file: "",
    };
    return _instance;
  }

  _instance = await findInstance(workspaceHint);
  if (!_instance) {
    console.error(
      bold(red("Eclipse JDT Bridge not running.")) +
        "\n\nNo live instances found. Check that:" +
        "\n  1. Eclipse is running" +
        "\n  2. The jdtbridge plugin is installed (io.github.kaluchi.jdtbridge)" +
        "\n  3. Instance files exist in ~/.jdtbridge/instances/",
    );
    process.exit(1);
  }
  return _instance;
}

/**
 * On connection failure, probe all instances to find a live one.
 * If found, switch to it and return true. Otherwise return false.
 */
async function reconnect() {
  _instance = null;
  const all = await discoverInstances();
  for (const inst of all) {
    try {
      await probe(inst);
      _instance = inst;
      return true;
    } catch {
      // dead instance — try next
    }
  }
  return false;
}

/** Reset cached instance (for testing). */
export function resetClient() {
  _instance = null;
}

function authHeaders() {
  const inst = _instance;
  const headers = {};
  if (inst && inst.token) {
    headers.Authorization = `Bearer ${inst.token}`;
  }
  const session = process.env.JDT_BRIDGE_SESSION
    || (inst && inst.session);
  if (session) {
    headers["X-Bridge-Session"] = session;
  }
  return headers;
}

function requestOpts(inst, path, method, timeoutMs) {
  return proxyAwareOptions(
    inst.host, inst.port, path, method, timeoutMs, authHeaders());
}

/**
 * Parse JSON responses, tolerating non-finite numeric literals sometimes
 * returned by the Eclipse bridge (for example `time: NaN` in test results).
 * @param {string} data
 * @returns {any}
 */
function parseJson(data) {
  try {
    return JSON.parse(data);
  } catch {
    const sanitized = data
      .replace(/:\s*NaN\b/g, ": null")
      .replace(/:\s*Infinity\b/g, ": null")
      .replace(/:\s*-Infinity\b/g, ": null");
    if (sanitized !== data) {
      return JSON.parse(sanitized);
    }
    throw new Error("Invalid JSON: " + data);
  }
}

/**
 * HTTP GET request, returns parsed JSON.
 * On connection error, probes for a live instance and retries once.
 * @param {string} path - URL path with query string
 * @param {number} [timeoutMs=10000]
 * @returns {Promise<any>}
 */
export async function get(path, timeoutMs = 10_000) {
  const inst = await connect();
  try {
    return await doGet(inst, path, timeoutMs);
  } catch (e) {
    if (!isConnectionError(e)) throw e;
    if (await reconnect()) {
      return doGet(_instance, path, timeoutMs);
    }
    throw e;
  }
}

function doGet(inst, path, timeoutMs) {
  return new Promise((resolve, reject) => {
    const req = request(
      requestOpts(inst, path, "GET", timeoutMs),
      (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          if (res.statusCode !== 200) {
            reject(new Error(`HTTP ${res.statusCode}: ${data}`));
            return;
          }
          try {
            resolve(parseJson(data));
          } catch (e) {
            reject(e);
          }
        });
      },
    );
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("Request timed out"));
    });
    req.on("error", reject);
    req.end();
  });
}

/**
 * HTTP GET request, returns raw response with headers.
 * Used for /source which returns text/plain.
 * @param {string} path
 * @param {number} [timeoutMs=10000]
 * @returns {Promise<{headers: object, body: string}>}
 */
export async function getRaw(path, timeoutMs = 10_000) {
  const inst = await connect();
  try {
    return await doGetRaw(inst, path, timeoutMs);
  } catch (e) {
    if (!isConnectionError(e)) throw e;
    if (await reconnect()) {
      return doGetRaw(_instance, path, timeoutMs);
    }
    throw e;
  }
}

function doGetRaw(inst, path, timeoutMs) {
  return new Promise((resolve, reject) => {
    const req = request(
      requestOpts(inst, path, "GET", timeoutMs),
      (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          if (res.statusCode !== 200) {
            reject(new Error(`HTTP ${res.statusCode}: ${data}`));
            return;
          }
          const contentType = res.headers["content-type"] || "";
          if (contentType.startsWith("application/json")) {
            try {
              const json = parseJson(data);
              if (json.error) {
                reject(new Error(json.error));
              } else {
                resolve({ headers: res.headers, body: data });
              }
            } catch (e) {
              reject(e);
            }
          } else {
            resolve({ headers: res.headers, body: data });
          }
        });
      },
    );
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("Request timed out"));
    });
    req.on("error", reject);
    req.end();
  });
}

/**
 * HTTP GET with streaming response. Pipes text/plain chunks to
 * the provided writable stream (typically process.stdout).
 * Resolves when the server closes the connection.
 * Rejects on non-200 status or connection error.
 * @param {string} path
 * @param {import('stream').Writable} dest
 * @returns {Promise<void>}
 */
export async function getStream(path, dest) {
  const inst = await connect();
  return new Promise((resolve, reject) => {
    const req = request(
      requestOpts(inst, path, "GET", 0),
      (res) => {
        if (res.statusCode !== 200) {
          let data = "";
          res.on("data", (chunk) => (data += chunk));
          res.on("end", () => reject(new Error(data.trim())));
          return;
        }
        res.pipe(dest, { end: false });
        res.on("end", resolve);
        res.on("error", reject);
      },
    );
    req.on("error", reject);
    req.end();
  });
}

/**
 * HTTP GET streaming with line-by-line callback. Designed for
 * JSONL (newline-delimited JSON) endpoints. Calls onLine for
 * each complete line received.
 * Resolves when the server closes the connection.
 * @param {string} path
 * @param {(line: string) => void} onLine
 * @returns {Promise<void>}
 */
export async function getStreamLines(path, onLine) {
  const inst = await connect();
  return new Promise((resolve, reject) => {
    const req = request(
      requestOpts(inst, path, "GET", 0),
      (res) => {
        if (res.statusCode !== 200) {
          let data = "";
          res.on("data", (chunk) => (data += chunk));
          res.on("end", () => reject(new Error(data.trim())));
          return;
        }
        let buffer = "";
        res.on("data", (chunk) => {
          buffer += chunk;
          const lines = buffer.split("\n");
          buffer = lines.pop();
          for (const line of lines) {
            if (line.trim()) onLine(line);
          }
        });
        res.on("end", () => {
          if (buffer.trim()) onLine(buffer);
          resolve();
        });
        res.on("error", reject);
      },
    );
    req.on("error", reject);
    req.end();
  });
}

/**
 * Check if error is a connection refused error.
 * @param {Error} e
 * @returns {boolean}
 */
export function isConnectionError(e) {
  return e.code === "ECONNREFUSED" || e.code === "ECONNRESET";
}
