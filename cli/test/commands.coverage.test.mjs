import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createServer } from "node:http";
import { setColorEnabled } from "../src/color.mjs";

// HTTP-mocked tests for the `jdt coverage *` subcommands.
// Verifies CLI → bridge URL/method/body wiring and the rendered
// human output. The bridge contract itself is covered by Java
// tests in plugin.tests/src/.../coverage/.

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

describe("commands.coverage", () => {
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

  function readBody(req) {
    return new Promise((resolve) => {
      const chunks = [];
      req.on("data", (c) => chunks.push(c));
      req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    });
  }

  // ---- run --------------------------------------------------------

  it("run sends configId and prints header", async () => {
    let seen;
    await setupMock((req, res) => {
      seen = req.url;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        ok: true,
        configId: "MyTest",
        coverageId: "MyTest:1700000000000",
        launchId: "MyTest:6408",
        configType: "JUnit",
        configTypeId: "org.eclipse.jdt.junit.launchconfig",
        coverageScope: ["=MyProject/src"],
        launchTimestamp: 1700000000000,
        processPid: "6408",
      }));
    });
    const { coverageRun } = await import("../src/commands/coverage.mjs");
    await coverageRun(["MyTest", "-q"]);
    expect(seen).toBe("/coverage/run?configId=MyTest");
    expect(io.logs.join("\n")).toContain("#### Coverage: MyTest");
    expect(io.logs.join("\n")).toContain("CoverageId:    `MyTest:1700000000000`");
  });

  it("run prints guide when -q absent", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        ok: true, configId: "X", coverageId: "X:1", launchId: "X:42",
      }));
    });
    const { coverageRun } = await import("../src/commands/coverage.mjs");
    await coverageRun(["X"]);
    expect(io.logs.join("\n")).toContain("Coverage status");
    expect(io.logs.join("\n")).toContain("jdt coverage status X:1");
  });

  it("run with --json emits raw response", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        ok: true, configId: "X", coverageId: "X:1", launchId: "X:42",
      }));
    });
    const { coverageRun } = await import("../src/commands/coverage.mjs");
    await coverageRun(["X", "--json", "-q"]);
    const body = io.logs.join("\n");
    expect(body).toContain('"ok": true');
    expect(body).toContain('"coverageId": "X:1"');
  });

  it("run shows error and exits 1 on bridge error", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        error: "coverage-config-not-found",
        message: "Launch configuration not found: Bogus",
      }));
    });
    const { coverageRun } = await import("../src/commands/coverage.mjs");
    await expect(coverageRun(["Bogus", "-q"])).rejects.toThrow("exit(1)");
    expect(io.errors.join("\n")).toContain("coverage-config-not-found");
  });

  it("run prints usage on missing configId", async () => {
    const { coverageRun } = await import("../src/commands/coverage.mjs");
    await expect(coverageRun([])).rejects.toThrow("exit(1)");
    expect(io.errors.join("\n")).toContain("Usage:");
  });

  // ---- runs -------------------------------------------------------

  it("runs renders table from array response", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([{
        coverageId: "MyTest:1700000000000",
        coverageSessionKind: "live",
        configId: "MyTest",
        active: true,
        terminated: false,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        launchTimestamp: Date.now() - 30_000,
      }]));
    });
    const { coverageRuns } = await import("../src/commands/coverage.mjs");
    await coverageRuns([]);
    const out = io.logs.join("\n");
    expect(out).toContain("COVERAGEID");
    expect(out).toContain("MyTest:1700000000000");
    expect(out).toContain("running");
    expect(out).toContain("*"); // active marker
  });

  it("runs prints '(no coverage sessions)' on empty array", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { coverageRuns } = await import("../src/commands/coverage.mjs");
    await coverageRuns([]);
    expect(io.logs.join("\n")).toContain("(no coverage sessions)");
  });

  it("runs --json passes through raw array", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('[{"coverageId":"X:1"}]');
    });
    const { coverageRuns } = await import("../src/commands/coverage.mjs");
    await coverageRuns(["--json"]);
    expect(io.logs.join("\n")).toContain('"coverageId": "X:1"');
  });

  // ---- status -----------------------------------------------------

  it("status renders snapshot with counters", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("/coverage/session?coverageId=X");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        coverageId: "X:1", configId: "X",
        coverageSessionKind: "live",
        terminated: true, dataReceived: true,
        analysisLoading: false, analysisReady: true,
        dumpCount: 1, terminatedAt: Date.now(),
        counters: {
          instruction: { coveredCount: 5, missedCount: 2, totalCount: 7,
            coveredRatio: 0.71, missedRatio: 0.29,
            coverageStatus: "PARTLY_COVERED" },
        },
      }));
    });
    const { coverageStatus } = await import("../src/commands/coverage.mjs");
    await coverageStatus(["X:1"]);
    const out = io.logs.join("\n");
    expect(out).toContain("X:1");
    expect(out).toContain("Instructions");
    expect(out).toContain("PARTLY_COVERED");
  });

  it("status missing id exits 1", async () => {
    const { coverageStatus } = await import("../src/commands/coverage.mjs");
    await expect(coverageStatus([])).rejects.toThrow("exit(1)");
    expect(io.errors.join("\n")).toContain("Usage:");
  });

  // ---- dump -------------------------------------------------------

  it("dump POSTs JSON body with coverageId+reset", async () => {
    let body, ct;
    await setupMock(async (req, res) => {
      body = await readBody(req);
      ct = req.headers["content-type"];
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"ok":true}');
    });
    const { coverageDump } = await import("../src/commands/coverage.mjs");
    await coverageDump(["X:1", "--reset"]);
    expect(JSON.parse(body)).toEqual({ coverageId: "X:1", reset: true });
    expect(ct).toContain("application/json");
    expect(io.logs.join("\n")).toContain("Dumped X:1 (reset)");
  });

  it("dump without --reset sends reset:false", async () => {
    let body;
    await setupMock(async (req, res) => {
      body = await readBody(req);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"ok":true}');
    });
    const { coverageDump } = await import("../src/commands/coverage.mjs");
    await coverageDump(["X:1"]);
    expect(JSON.parse(body)).toEqual({ coverageId: "X:1", reset: false });
  });

  // ---- refresh ----------------------------------------------------

  it("refresh prints active session id", async () => {
    await setupMock((req, res) => {
      expect(req.method).toBe("POST");
      expect(req.url).toBe("/coverage/refresh");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"ok":true,"activeCoverageId":"MyTest:1"}');
    });
    const { coverageRefresh } = await import("../src/commands/coverage.mjs");
    await coverageRefresh([]);
    expect(io.logs.join("\n")).toContain("Refreshed MyTest:1");
  });

  // ---- active / activate -----------------------------------------

  it("active prints id when present", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"activeCoverageId":"MyTest:1"}');
    });
    const { coverageActive } = await import("../src/commands/coverage.mjs");
    await coverageActive([]);
    expect(io.logs.join("\n")).toContain("MyTest:1");
  });

  it("active prints 'none' when null", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"activeCoverageId":null}');
    });
    const { coverageActive } = await import("../src/commands/coverage.mjs");
    await coverageActive([]);
    expect(io.logs.join("\n")).toContain("none");
  });

  it("activate sends body and prints previous", async () => {
    let body;
    await setupMock(async (req, res) => {
      body = await readBody(req);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"ok":true,"activeCoverageId":"X:1","previousActiveCoverageId":"Y:2"}');
    });
    const { coverageActivate } = await import("../src/commands/coverage.mjs");
    await coverageActivate(["X:1"]);
    expect(JSON.parse(body)).toEqual({ coverageId: "X:1" });
    expect(io.logs.join("\n")).toContain("Activated X:1");
    expect(io.logs.join("\n")).toContain("Previous: Y:2");
  });

  // ---- merge ------------------------------------------------------

  it("merge sends array, prints summary", async () => {
    let body;
    await setupMock(async (req, res) => {
      body = await readBody(req);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        ok: true,
        mergedCoverageId: "merged:1700000000000",
        consumedCoverageIds: ["A:1", "B:2"],
        active: true,
      }));
    });
    const { coverageMerge } = await import("../src/commands/coverage.mjs");
    await coverageMerge(["A:1", "B:2", "--name", "Combined run"]);
    const sent = JSON.parse(body);
    expect(sent.coverageIds).toEqual(["A:1", "B:2"]);
    expect(sent.description).toBe("Combined run");
    const out = io.logs.join("\n");
    expect(out).toContain("Merged 2 sessions");
    expect(out).toContain("merged:1700000000000");
    expect(out).toContain("A:1  removed");
  });

  it("merge with <2 ids exits 1", async () => {
    const { coverageMerge } = await import("../src/commands/coverage.mjs");
    await expect(coverageMerge(["A:1"])).rejects.toThrow("exit(1)");
  });

  // ---- remove -----------------------------------------------------

  it("remove (default) sends empty body, prints count", async () => {
    let body;
    await setupMock(async (req, res) => {
      body = await readBody(req);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"ok":true,"removedCoverageIds":["X:1"]}');
    });
    const { coverageRemove } = await import("../src/commands/coverage.mjs");
    await coverageRemove([]);
    expect(JSON.parse(body)).toEqual({});
    expect(io.logs.join("\n")).toContain("Removed 1 coverage session");
  });

  it("remove --all sends {all:true}, plural", async () => {
    let body;
    await setupMock(async (req, res) => {
      body = await readBody(req);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"ok":true,"removedCoverageIds":["X:1","Y:2"]}');
    });
    const { coverageRemove } = await import("../src/commands/coverage.mjs");
    await coverageRemove(["--all"]);
    expect(JSON.parse(body)).toEqual({ all: true });
    expect(io.logs.join("\n")).toContain("Removed 2 coverage sessions");
  });

  // ---- stop -------------------------------------------------------

  it("stop resolves coverageId via /coverage/runs then GETs /launch/stop", async () => {
    const seen = [];
    await setupMock((req, res) => {
      seen.push(req.url);
      res.writeHead(200, { "Content-Type": "application/json" });
      if (req.url === "/coverage/runs") {
        res.end(JSON.stringify([{
          coverageId: "MyTest:1", coverageSessionKind: "live",
          launchId: "MyTest:6408",
        }]));
      } else if (req.url.startsWith("/launch/stop")) {
        res.end('{"ok":true,"configId":"MyTest"}');
      }
    });
    const { coverageStop } = await import("../src/commands/coverage.mjs");
    await coverageStop(["MyTest:1"]);
    expect(seen[0]).toBe("/coverage/runs");
    expect(seen[1]).toBe("/launch/stop?launchId=MyTest%3A6408");
    expect(io.logs.join("\n")).toContain("Stopped MyTest:1");
  });

  it("stop on merged exits with launch-not-live", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([{
        coverageId: "merged:1", coverageSessionKind: "merged",
      }]));
    });
    const { coverageStop } = await import("../src/commands/coverage.mjs");
    await expect(coverageStop(["merged:1"])).rejects.toThrow("exit(1)");
    expect(io.errors.join("\n")).toContain("coverage-launch-not-live");
  });

  it("stop unknown coverageId exits 1", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { coverageStop } = await import("../src/commands/coverage.mjs");
    await expect(coverageStop(["Bogus:1"])).rejects.toThrow("exit(1)");
    expect(io.errors.join("\n")).toContain("coverage-not-found");
  });
});
