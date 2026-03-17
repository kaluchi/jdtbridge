// HTTP client for JDT Bridge server.

import { request } from "node:http";
import { findInstance } from "./discovery.mjs";
import { red, bold } from "./color.mjs";

/** @type {import('./discovery.mjs').Instance|null} */
let _instance;

/**
 * Ensure we have a connected instance. Call before any HTTP request.
 * @param {string} [workspaceHint]
 * @returns {import('./discovery.mjs').Instance}
 */
export function connect(workspaceHint) {
  if (_instance) return _instance;
  _instance = findInstance(workspaceHint);
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

/** Reset cached instance (for testing). */
export function resetClient() {
  _instance = null;
}

function authHeaders() {
  const inst = _instance;
  return inst && inst.token
    ? { Authorization: `Bearer ${inst.token}` }
    : {};
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
 * @param {string} path - URL path with query string
 * @param {number} [timeoutMs=10000]
 * @returns {Promise<any>}
 */
export function get(path, timeoutMs = 10_000) {
  const inst = connect();
  return new Promise((resolve, reject) => {
    const req = request(
      {
        hostname: "127.0.0.1",
        port: inst.port,
        path,
        method: "GET",
        timeout: timeoutMs,
        headers: authHeaders(),
      },
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
export function getRaw(path, timeoutMs = 10_000) {
  const inst = connect();
  return new Promise((resolve, reject) => {
    const req = request(
      {
        hostname: "127.0.0.1",
        port: inst.port,
        path,
        method: "GET",
        timeout: timeoutMs,
        headers: authHeaders(),
      },
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
 * Check if error is a connection refused error.
 * @param {Error} e
 * @returns {boolean}
 */
export function isConnectionError(e) {
  return e.code === "ECONNREFUSED" || e.code === "ECONNRESET";
}
