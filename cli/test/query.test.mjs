import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createServer } from "node:http";
import { setColorEnabled } from "../src/color.mjs";

// jdt q is read-only: every outcome (parse error, usage error,
// fail-track result) must exit 0 with the error descriptor printed
// on stdout. Non-zero exit would cancel sibling parallel tool calls.

function startServer(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port }));
  });
}
function stopServer(server) {
  return new Promise((resolve) => server.close(resolve));
}

function captureConsole() {
  const logs = [];
  const errors = [];
  const origLog = console.log;
  const origError = console.error;
  const origExit = process.exit;
  const exits = [];
  console.log = (...args) => logs.push(args.join(" "));
  console.error = (...args) => errors.push(args.join(" "));
  // Treat process.exit as a recorded event, not a throw — the fix
  // removes every exit(1) from the read-only path, so any test that
  // sees an exit recorded with a non-zero code is a regression.
  process.exit = (code) => { exits.push(code); };
  return {
    logs, errors, exits,
    restore() {
      console.log = origLog;
      console.error = origError;
      process.exit = origExit;
    },
  };
}

describe("jdt q — read-only exit-0 contract", () => {
  let server, port, io;

  beforeEach(() => {
    setColorEnabled(false);
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    vi.resetModules();
  });

  async function setupMock(handler) {
    ({ server, port } = await startServer(handler));
    vi.doMock("../src/resolve.mjs", () => ({
      resolveInstance: async () => ({
        port, token: null, pid: process.pid,
        workspace: "/test", host: "127.0.0.1", file: "",
      }),
    }));
  }

  it("missing positional prints usage-error value, no non-zero exit", async () => {
    const { query } = await import("../src/commands/query.mjs");
    await query([]);
    expect(io.exits).toEqual([]);
    const stdout = io.logs.join("\n");
    // Per-site identity rides on the `::Tag` head ahead of `!{…}`;
    // the descriptor body carries the remaining structured fields.
    expect(stdout).toContain("::UsageError!{");
    expect(stdout).toContain(":category :usage-error");
    expect(stdout).toContain(":origin :jdt/cli");
    expect(stdout).toContain("jdt q <qlang-query>");
  });

  it("parse error prints parse-error value with :location, no non-zero exit", async () => {
    await setupMock(() => {});
    const { query } = await import("../src/commands/query.mjs");
    await query(["bad syntax [[["]);
    expect(io.exits).toEqual([]);
    const stdout = io.logs.join("\n");
    expect(stdout).toContain("::ParseError!{");
    expect(stdout).toContain(":category :parse-error");
    expect(stdout).toContain(":origin :qlang/parse");
    expect(stdout).toContain(":location");
    expect(stdout).toContain(":line");
    expect(stdout).toContain(":column");
  });

  it("server fail-track error prints as !{...} value, no non-zero exit", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        _error: {
          kind: "type-not-found",
          thrown: "TypeNotFound",
          origin: "jdt/plugin",
          message: "Type not found: no.such.Type",
        },
      }));
    });
    const { query } = await import("../src/commands/query.mjs");
    await query(['"no.such.Type" | @type']);
    expect(io.exits).toEqual([]);
    const stdout = io.logs.join("\n");
    // Per-site identity surfaces through the `::TagName` head ahead
    // of `!{…}`; the plugin's broad-bucket kind lands on `:category`.
    // `:message` is suppressed in the printed form by qlang's
    // hypertext-error convention (the prose lives on `::Tag | docs`).
    expect(stdout).toContain("::TypeNotFound!{");
    expect(stdout).toContain(":category :type-not-found");
  });

  it("successful string result prints raw (no quotes), no non-zero exit", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        fqn: "some.Type",
        kind: "type",
        origin: "source",
      }));
    });
    const { query } = await import("../src/commands/query.mjs");
    await query(['"some.Type" | @type | /fqn']);
    expect(io.exits).toEqual([]);
    // `/fqn` projects the :fqn String; it should print raw, without
    // surrounding quotes, so `jdt q '"X" | @source' > X.java` stays
    // byte-faithful.
    expect(io.logs[0]).toBe("some.Type");
  });
});
