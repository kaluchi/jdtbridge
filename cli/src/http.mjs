// Low-level HTTP helper for bridge communication.
//
// Single source of truth for building requests against an instance
// (auth + session headers, proxy awareness, timeout handling). Used by
// client.mjs (runtime CLI commands with reconnect), discovery.mjs
// (resolution-time probes), and anywhere else a bridge round-trip is
// needed. Each caller layers its own error-handling policy on top.

import { request } from "node:http";
import { proxyAwareOptions } from "./proxy.mjs";

/**
 * Execute an HTTP request against a bridge instance. Resolves with
 * { status, body, error? } — never rejects. Callers inspect
 * {@code error} for network/timeout failures and {@code status} for
 * HTTP-level outcomes.
 *
 * @param {{host: string, port: number, token?: string, session?: string}} inst
 * @param {string} path
 * @param {string} [method="GET"]
 * @param {number} [timeoutMs=5000]
 * @returns {Promise<{status: number, body: string, error?: Error}>}
 */
export function httpRequest(inst, path, method = "GET", timeoutMs = 5000) {
  return new Promise((resolve) => {
    const headers = {};
    if (inst.token) headers.Authorization = `Bearer ${inst.token}`;
    if (inst.session) headers["X-Bridge-Session"] = inst.session;
    const opts = proxyAwareOptions(
      inst.host, inst.port, path, method, timeoutMs, headers);
    const req = request(opts, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => resolve({ status: res.statusCode, body: data }));
    });
    req.on("error", (error) => resolve({ status: 0, body: "", error }));
    req.on("timeout", () => {
      req.destroy();
      resolve({ status: 0, body: "", error: new Error("Request timed out") });
    });
    req.end();
  });
}
