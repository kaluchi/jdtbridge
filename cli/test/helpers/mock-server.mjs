// Shared test helpers for command integration tests.
// Mock HTTP server, console capture, JSON parsing.
//
// NOTE: setupMock() and mockSandboxPaths() use vi.doMock with
// relative paths — these must stay in the test file itself because
// vitest resolves paths relative to the calling file.

import { createServer } from "node:http";
import { setColorEnabled } from "../../src/color.mjs";

export function startServer(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port }));
  });
}

export function stopServer(server) {
  return new Promise((resolve) => server.close(resolve));
}

export function captureConsole() {
  const logs = [];
  const errors = [];
  const origLog = console.log;
  const origError = console.error;
  const origExit = process.exit;
  console.log = (...args) => logs.push(args.join(" "));
  console.error = (...args) => errors.push(args.join(" "));
  process.exit = (code) => { throw new Error(`exit(${code})`); };
  return {
    logs, errors,
    restore() {
      console.log = origLog;
      console.error = origError;
      process.exit = origExit;
    },
  };
}

export function errorServer() {
  return (req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "Something went wrong" }));
  };
}

/** Parse captured console output as JSON; asserts no ANSI codes. */
export function parseJsonOutput(logs) {
  const raw = logs.join("\n");
  if (/\x1b\[/.test(raw)) throw new Error("ANSI escape codes found in JSON output");
  return JSON.parse(raw);
}

/** Disable color for consistent test output. */
export function disableColor() {
  setColorEnabled(false);
}
