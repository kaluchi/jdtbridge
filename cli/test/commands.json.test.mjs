import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  startServer, stopServer, captureConsole, errorServer, parseJsonOutput, disableColor,
} from "./helpers/mock-server.mjs";

// --json output coverage for surviving non-graph commands.
// Graph queries (find/refs/impl/hier/outline/source/projects/
// project-info/problems/editors) were folded into `jdt q` —
// their JSON contracts now live in the qlang :jdt/graph module
// and the GraphHandler plugin tests, not here.

describe("--json output", () => {
  let server, port, io;

  beforeEach(() => {
    disableColor();
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    vi.doUnmock("../src/paths.mjs");
    vi.resetModules();
  });

  async function setupMock(handler) {
    ({ server, port } = await startServer(handler));
    vi.doMock("../src/resolve.mjs", () => ({
      resolveInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1", file: "" }),
    }));
  }

  it("test runs --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { configId: "MyTest", testRunId: "MyTest:1775000", total: 5, passed: 4, failed: 1, time: 2.5, state: "finished" },
      ]));
    });
    const { testSessions } = await import("../src/commands/test-sessions.mjs");
    await testSessions(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].configId).toBe("MyTest");
    expect(data[0].total).toBe(5);
    expect(data[0].passed).toBe(4);
  });

  it("test runs --json returns [] for no runs", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { testSessions } = await import("../src/commands/test-sessions.mjs");
    await testSessions(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("test status --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        session: "test-123", label: "MyTest", state: "finished",
        total: 5, completed: 5, passed: 4, failed: 1,
        entries: [{ name: "testFoo", status: "FAIL", time: 0.1 }],
      }));
    });
    const { testStatus } = await import("../src/commands/test-status.mjs");
    await testStatus(["test-123", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.session).toBe("test-123");
    expect(data.total).toBe(5);
    expect(data.entries).toBeInstanceOf(Array);
  });

  it("test status --json returns error on server error", async () => {
    await setupMock(errorServer());
    const { testStatus } = await import("../src/commands/test-status.mjs");
    await testStatus(["test-123", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.error).toBe("Something went wrong");
  });

  it("launch list --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", type: "Java Application", mode: "run", terminated: false, pid: "12345" },
      ]));
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].name).toBe("my-server");
    expect(data[0].pid).toBe("12345");
  });

  it("launch list --json returns [] when empty", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("launch configs --json outputs valid JSON with enriched fields", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { configId: "my-server", type: "Java Application", project: "my-project", mainClass: "com.example.Main" },
        { configId: "jdtbridge-verify", type: "Maven Build", goals: "clean verify" },
        { configId: "AllTests", type: "JUnit", project: "my-project", class: "com.example.AllTests", runner: "JUnit 5" },
      ]));
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data).toHaveLength(3);
    expect(data[0].project).toBe("my-project");
    expect(data[0].mainClass).toBe("com.example.Main");
    expect(data[1].goals).toBe("clean verify");
    expect(data[2].runner).toBe("JUnit 5");
  });

  it("git --json outputs structured repo data", async () => {
    // git.mjs hits /projects (skeletons) then /project?of=<fqn>
    // (detail) per project to read :repo / :branch from detail.
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      if (req.url === "/projects") {
        res.end(JSON.stringify([
          { fqn: "my-server", kind: "project", origin: "source" },
        ]));
        return;
      }
      if (req.url.startsWith("/project?of=")) {
        res.end(JSON.stringify({
          fqn: "my-server", kind: "project", origin: "source",
          repo: "D:/projects", branch: "main",
        }));
        return;
      }
      res.end("{}");
    });
    const { git } = await import("../src/commands/git.mjs");
    await git(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].name).toBe("projects");
    expect(data[0].branch).toBe("main");
  });

  it("git --json returns [] for no repos", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([]));
    });
    const { git } = await import("../src/commands/git.mjs");
    await git(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("git --json returns error on server error", async () => {
    await setupMock(errorServer());
    const { git } = await import("../src/commands/git.mjs");
    await git(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.error).toBe("Something went wrong");
  });
});
