import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createServer } from "node:http";
import { setColorEnabled } from "../src/color.mjs";

function startServer(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port }));
  });
}
function stopServer(server) {
  return new Promise((resolve) => server.close(resolve));
}

// Helpers for capturing console output and preventing process.exit
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

describe("test commands", () => {
  let server, port, io;

  beforeEach(() => {
    setColorEnabled(false);
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    server = null;
    vi.resetModules();
  });

  async function setupMock(handler) {
    ({ server, port } = await startServer(handler));
    vi.doMock("../src/bridge-env.mjs", () => ({
      getPinnedBridge: () => null,
    }));
    vi.doMock("../src/discovery.mjs", () => ({
      discoverInstances: async () => [],
      findInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1" }),
    }));
  }

  // --- test run ---

  it("test run sends class param", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        expect(req.url).toContain("class=com.example.FooTest");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "FooTest", testRunId: "FooTest:1775000", launchId: "FooTest:100", pid: "100", reused: false, project: "my-project", runner: "JUnit 5" }));
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 5, label: "FooTest" }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await testRun(["com.example.FooTest", "-q"]);
    expect(io.logs.some((l) => l.includes("FooTest"))).toBe(true);
  });

  it("test run sends project param", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        expect(req.url).toContain("project=my-project");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "my-project", testRunId: "my-project:1775001", launchId: "my-project:200", pid: "200", reused: false }));
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 10, label: "my-project" }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await testRun(["--project", "my-project", "-q"]);
    expect(io.logs.some((l) => l.includes("my-project"))).toBe(true);
  });

  it("test run prints onboarding guide without -q", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "FooTest", testRunId: "FooTest:1775002", launchId: "FooTest:300", pid: "300", reused: false }));
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 3, label: "FooTest" }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await testRun(["com.example.FooTest"]);
    const all = io.logs.join("\n");
    expect(all).toContain("jdt test status");
    expect(all).toContain("jdt launch logs");
    expect(all).toContain("jdt launch stop");
  });

  it("test run exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { testRun } = await import("../src/commands/test-run.mjs");
    await expect(testRun([])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Usage");
  });

  it("test run does not exit 1 on server error", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Something went wrong" }));
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await testRun(["com.example.FooTest", "-q"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("test run sends method param with FQMN#method", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        expect(req.url).toContain("class=com.example.FooTest");
        expect(req.url).toContain("method=testBar");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "FooTest", testRunId: "FooTest:1775003", launchId: "FooTest:400", pid: "400", reused: false }));
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 1 }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await testRun(["com.example.FooTest#testBar", "-q"]);
    expect(io.logs.some((l) => l.includes("FooTest"))).toBe(true);
  });

  it("test run sends package param", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        expect(req.url).toContain("project=my-project");
        expect(req.url).toContain("package=com.example.dao");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "com.example.service", testRunId: "com.example.service:1775004", launchId: "com.example.service:500", pid: "500", reused: false }));
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 5 }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await testRun(["--project", "my-project", "--package", "com.example.dao", "-q"]);
  });

  it("test run sends no-refresh param", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        expect(req.url).toContain("no-refresh");
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "FooTest", testRunId: "FooTest:1775005", launchId: "FooTest:600", pid: "600", reused: false }));
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 1 }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await testRun(["com.example.FooTest", "--no-refresh", "-q"]);
  });

  it("test run -f exits 1 on test failures", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "FailTest", testRunId: "FailTest:1775006", launchId: "FailTest:700", pid: "700", reused: false }));
      } else if (req.url.includes("/test/status/stream")) {
        res.writeHead(200, { "Content-Type": "application/x-ndjson" });
        res.write(JSON.stringify({ event: "finished", total: 2, passed: 1, failed: 1, errors: 0, ignored: 0, time: 1.0 }) + "\n");
        res.end();
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 2 }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await expect(testRun(["com.example.FooTest", "-f", "-q"])).rejects.toThrow("exit(1)");
  });

  it("test run -f exits 0 on all passing", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "PassTest", testRunId: "PassTest:1775007", launchId: "PassTest:800", pid: "800", reused: false }));
      } else if (req.url.includes("/test/status/stream")) {
        res.writeHead(200, { "Content-Type": "application/x-ndjson" });
        res.write(JSON.stringify({ event: "finished", total: 2, passed: 2, failed: 0, errors: 0, ignored: 0, time: 1.0 }) + "\n");
        res.end();
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 2 }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await expect(testRun(["com.example.FooTest", "-f", "-q"])).rejects.toThrow("exit(0)");
  });

  it("test run -f streams JSONL", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "test-stream-1", pid: "900" }));
      } else if (req.url.includes("/test/status/stream")) {
        res.writeHead(200, { "Content-Type": "application/x-ndjson" });
        res.write(JSON.stringify({ event: "case", fqmn: "Foo#bar", status: "FAIL", time: 0.1, trace: "err" }) + "\n");
        res.write(JSON.stringify({ event: "finished", total: 1, passed: 0, failed: 1, errors: 0, ignored: 0, time: 0.1 }) + "\n");
        res.end();
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 1, label: "FooTest" }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    // -f causes process.exit(1) when tests fail
    await expect(testRun(["com.example.FooTest", "-f", "-q"])).rejects.toThrow("exit(1)");
    expect(io.logs.some((l) => l.includes("FAIL"))).toBe(true);
    expect(io.logs.some((l) => l.includes("Foo#bar"))).toBe(true);
  });

  it("test run -f --json outputs raw JSONL", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "JsonlTest", testRunId: "JsonlTest:1775009", launchId: "JsonlTest:901", pid: "901", reused: false }));
      } else if (req.url.includes("/test/status/stream")) {
        res.writeHead(200, { "Content-Type": "application/x-ndjson" });
        res.write(JSON.stringify({ event: "case", fqmn: "Foo#bar", status: "PASS", time: 0.1 }) + "\n");
        res.write(JSON.stringify({ event: "finished", total: 1, passed: 1, failed: 0, errors: 0, ignored: 0, time: 0.1 }) + "\n");
        res.end();
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 1 }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await expect(testRun(["com.example.FooTest", "-f", "-q", "--json"])).rejects.toThrow("exit(0)");
    // Each line should be valid JSON
    for (const line of io.logs) {
      expect(() => JSON.parse(line)).not.toThrow();
    }
    // No ANSI codes — raw JSONL
    expect(io.logs.join("\n")).not.toMatch(/\x1b\[/);
    // Should contain the events
    const events = io.logs.map((l) => JSON.parse(l));
    expect(events.some((e) => e.event === "case")).toBe(true);
    expect(events.some((e) => e.event === "finished")).toBe(true);
  });

  it("test run -f --json suppresses header", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/test/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, configId: "HdrTest", testRunId: "HdrTest:1775010", launchId: "HdrTest:902", pid: "902", reused: false }));
      } else if (req.url.includes("/test/status/stream")) {
        res.writeHead(200, { "Content-Type": "application/x-ndjson" });
        res.write(JSON.stringify({ event: "finished", total: 0, passed: 0, failed: 0, errors: 0, ignored: 0, time: 0 }) + "\n");
        res.end();
      } else if (req.url.includes("/test/status")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ total: 0 }));
      }
    });
    const { testRun } = await import("../src/commands/test-run.mjs");
    await expect(testRun(["com.example.FooTest", "-f", "--json"])).rejects.toThrow("exit(0)");
    // No "#### Test:" header — only JSON lines
    expect(io.logs.every((l) => l.startsWith("{"))).toBe(true);
  });

  // --- test status ---

  it("test status sends testRunId param", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("testRunId=test-123");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        configId: "test-123", state: "finished",
        total: 5, completed: 5, passed: 4, failed: 1, errors: 0, ignored: 0,
        time: 2.3,
        entries: [{ fqmn: "Foo#bar", status: "FAIL", time: 0.1, trace: "err" }],
      }));
    });
    const { testStatus } = await import("../src/commands/test-status.mjs");
    await testStatus(["test-123"]);
    expect(io.logs.some((l) => l.includes("5 tests"))).toBe(true);
  });

  it("test status exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { testStatus } = await import("../src/commands/test-status.mjs");
    await expect(testStatus([])).rejects.toThrow("exit(1)");
  });

  it("test status --ignored sends filter param", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("filter=ignored");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ configId: "s", state: "finished", total: 0, completed: 0, passed: 0, failed: 0, errors: 0, ignored: 0, time: 0, entries: [] }));
    });
    const { testStatus } = await import("../src/commands/test-status.mjs");
    await testStatus(["s", "--ignored"]);
  });

  it("test status --all passes filter param", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("filter=all");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        configId: "s", state: "finished",
        total: 0, completed: 0, passed: 0, failed: 0, errors: 0, ignored: 0,
        time: 0, entries: [],
      }));
    });
    const { testStatus } = await import("../src/commands/test-status.mjs");
    await testStatus(["s", "--all"]);
  });

  it("test status does not exit 1 on server error", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Session not found" }));
    });
    const { testStatus } = await import("../src/commands/test-status.mjs");
    await testStatus(["bad-session"]);
    expect(io.errors[0]).toContain("Session not found");
  });

  // --- test runs ---

  it("test runs lists runs with TESTRUNID and CONFIGID", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([{
        configId: "FooTest", testRunId: "FooTest:1775000", state: "finished", startedAt: 1775000,
        total: 5, completed: 5, passed: 4, failed: 1, errors: 0, ignored: 0, time: 2.3,
      }]));
    });
    const { testSessions } = await import("../src/commands/test-sessions.mjs");
    await testSessions();
    const out = io.logs[0];
    expect(out).toContain("TESTRUNID");
    expect(out).toContain("CONFIGID");
    expect(out).toContain("FooTest:1775000");
    expect(out).toContain("FooTest");
  });

  it("test runs shows empty message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([]));
    });
    const { testSessions } = await import("../src/commands/test-sessions.mjs");
    await testSessions();
    expect(io.logs[0]).toContain("no test runs");
  });

  it("test sessions does not exit 1 on error", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "fail" }));
    });
    const { testSessions } = await import("../src/commands/test-sessions.mjs");
    await testSessions();
    expect(io.errors[0]).toContain("fail");
  });
});
