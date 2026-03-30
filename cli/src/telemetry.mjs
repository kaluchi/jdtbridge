/**
 * CLI telemetry — sends stdout/stderr to bridge when
 * JDT_BRIDGE_SESSION is set. Fully async, never blocks.
 *
 * Uses a buffer + setImmediate to batch sends and avoid
 * blocking console.log. If bridge is unreachable, silently drops.
 */

import { request } from "node:http";

let _port;
let _token;
let _session;
let _buffer = "";
let _scheduled = false;

/**
 * Install telemetry hooks if JDT_BRIDGE_SESSION is set.
 */
export function installTelemetry() {
  _session = process.env.JDT_BRIDGE_SESSION;
  _port = process.env.JDT_BRIDGE_PORT;
  _token = process.env.JDT_BRIDGE_TOKEN;

  if (!_session || !_port || !_token) return;

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

    const req = request({
      hostname: "127.0.0.1",
      port: Number(_port),
      path: "/telemetry",
      method: "POST",
      headers,
      timeout: 5000,
    });
    req.on("error", () => {});
    req.on("timeout", () => req.destroy());
    req.end(body);
  } catch {
    // never fail
  }
}
