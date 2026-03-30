/**
 * CLI telemetry — sends stdout/stderr to bridge.
 *
 * Resolves connection the same way as client.mjs connect():
 * - Env vars: JDT_BRIDGE_PORT/TOKEN/SESSION (local provider)
 * - Instance file: port/token/session fields (sandbox provider)
 *
 * Both are written by the provider during bootstrap.
 */

import { request } from "node:http";
import { proxyAwareOptions } from "./proxy.mjs";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { instancesDir } from "./home.mjs";

let _port;
let _token;
let _session;
let _host;
let _buffer = "";
let _scheduled = false;

/**
 * Resolve connection config from env vars or instance file.
 * Same sources as client.mjs connect() — not a fallback,
 * just two valid provider paths.
 */
function resolveConfig() {
  // Env vars (local provider sets these in terminal)
  if (process.env.JDT_BRIDGE_SESSION
      && process.env.JDT_BRIDGE_PORT
      && process.env.JDT_BRIDGE_TOKEN) {
    return {
      port: process.env.JDT_BRIDGE_PORT,
      token: process.env.JDT_BRIDGE_TOKEN,
      host: process.env.JDT_BRIDGE_HOST || "127.0.0.1",
      session: process.env.JDT_BRIDGE_SESSION,
    };
  }

  // Instance file (sandbox provider writes session field there)
  try {
    const dir = instancesDir();
    for (const file of readdirSync(dir).filter(f => f.endsWith(".json"))) {
      const data = JSON.parse(readFileSync(join(dir, file), "utf8"));
      if (data.session && data.port && data.token) {
        return {
          port: String(data.port),
          token: data.token,
          host: data.host || "127.0.0.1",
          session: data.session,
        };
      }
    }
  } catch { /* no instance files */ }

  return null;
}

/**
 * Install telemetry hooks if session context exists.
 */
export function installTelemetry() {
  const config = resolveConfig();
  if (!config) return;

  _port = config.port;
  _token = config.token;
  _host = config.host;
  _session = config.session;

  const origLog = console.log;
  const origError = console.error;

  console.log = (...args) => {
    origLog(...args);
    enqueue(args.join(" ") + "\n");
  };

  console.error = (...args) => {
    origError(...args);
    enqueue(args.join(" ") + "\n");
  };
}

/**
 * Poll telemetry queue while terminal child is alive.
 * Resolves when child exits.
 */
export function telemetryUntilExit(bridgeEnv, session, child) {
  const port = Number(bridgeEnv.JDT_BRIDGE_PORT);
  const token = bridgeEnv.JDT_BRIDGE_TOKEN;
  const host = bridgeEnv.JDT_BRIDGE_HOST || "127.0.0.1";

  return new Promise((resolve) => {
    let done = false;

    const interval = setInterval(() => {
      drainTelemetry(host, port, token, session, (text) => {
        if (text) process.stdout.write(text);
      });
    }, 2000);

    child.on("exit", () => {
      if (done) return;
      done = true;
      drainTelemetry(host, port, token, session, (text) => {
        if (text) process.stdout.write(text);
        clearInterval(interval);
        resolve();
      });
    });
  });
}

function drainTelemetry(host, port, token, session, done) {
  const path = `/telemetry?session=${encodeURIComponent(session)}`;
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;

  const req = request(
    proxyAwareOptions(host, port, path, "GET", 5000, headers),
    (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        if (data) done(data);
        else done(null);
      });
    },
  );
  req.on("error", () => done(null));
  req.on("timeout", () => { req.destroy(); done(null); });
  req.end();
}

function enqueue(text) {
  _buffer += text;
  if (!_scheduled) {
    _scheduled = true;
    setImmediate(flush);
  }
}

function flush() {
  _scheduled = false;
  const body = _buffer;
  _buffer = "";
  if (!body) return;

  try {
    const headers = {
      "Content-Type": "text/plain",
      "Content-Length": Buffer.byteLength(body),
      "X-Bridge-Session": _session,
    };
    if (_token) headers.Authorization = `Bearer ${_token}`;

    const req = request(proxyAwareOptions(
      _host, Number(_port), "/telemetry", "POST", 5000, headers,
    ));
    req.on("error", () => {});
    req.on("timeout", () => req.destroy());
    req.end(body);
  } catch {
    // never fail
  }
}
