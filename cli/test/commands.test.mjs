import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createServer } from "node:http";
import { setColorEnabled } from "../src/color.mjs";

// Behavior tests for non-graph CLI commands. Graph-axis endpoint
// coverage lives in GraphHandlerTest (plugin.tests).

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

describe("commands (integration)", () => {
  let server, port, io;

  beforeEach(() => {
    setColorEnabled(false);
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    vi.doUnmock("../src/path-translate.mjs");
    vi.resetModules();
  });

  // Stand-in for the real per-instance mount cache: rewrites
  // Windows drive letters to `/<drive>/…` without loading the
  // JSON cache file. Lets editor/git tests assert remap behaviour
  // without provisioning a full remote-instance fixture.
  function mockSandboxPaths() {
    vi.doMock("../src/path-translate.mjs", async (importOriginal) => {
      const orig = await importOriginal();
      return { ...orig, translateHostPath: (p) => p
          && /^[A-Z]:[/\\]/.test(p)
          ? "/" + p[0].toLowerCase() + p.slice(2).replace(/\\/g, "/")
          : p };
    });
  }

  async function setupMock(handler) {
    ({ server, port } = await startServer(handler));
    vi.doMock("../src/resolve.mjs", () => ({
      resolveInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1", file: "" }),
    }));
  }

  // ── Refactoring (organize-imports / format / rename / move) ──

  it("organize-imports shows result", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ added: 2, removed: 1 }));
    });
    const { organizeImports } = await import("../src/commands/refactoring.mjs");
    await organizeImports(["my-server/src/Foo.java"]);
    expect(io.logs[0]).toBe("Imports: +2 -1");
  });

  it("format shows Formatted on modification", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ modified: true }));
    });
    const { format } = await import("../src/commands/refactoring.mjs");
    await format(["my-server/src/Foo.java"]);
    expect(io.logs[0]).toBe("Formatted");
  });

  it("rename shows Renamed with warnings", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, warnings: ["shadow detected"] }));
    });
    const { rename } = await import("../src/commands/refactoring.mjs");
    await rename(["com.example.Foo", "Bar", "--method", "old"]);
    expect(io.logs[0]).toBe("Renamed");
    expect(io.logs[1]).toContain("shadow detected");
  });

  it("move shows Moved", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
    });
    const { move } = await import("../src/commands/refactoring.mjs");
    await move(["com.example.Foo", "com.example.bar"]);
    expect(io.logs[0]).toBe("Moved");
  });

  // ── Editor / open ──

  it("editors shows table with headers and active marker", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/projects/src/Foo.java", fqn: "com.example.Foo", project: "my-server", active: true },
        { file: "D:/projects/src/Bar.java", fqn: "com.example.Bar", project: "my-server" },
      ]));
    });
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    const out = io.logs[0];
    expect(out).toContain("FILE");
    expect(out).toContain("PROJECT");
    expect(out).toContain("PATH");
    expect(out).toContain("`com.example.Foo`");
    expect(out).toContain("`com.example.Bar`");
    expect(out).toContain(">");
    expect(out).toContain("my-server");
    // Without a remote-instance mount cache, translateHostPath
    // returns paths unchanged — the raw Eclipse path appears in
    // the output.
    expect(out).toContain("D:/projects/src/Foo.java");
  });

  it("editors shows basename for non-java files", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/projects/pom.xml", project: "my-server" },
      ]));
    });
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    const out = io.logs[0];
    expect(out).toContain("pom.xml");
    expect(out).not.toContain("`");
  });

  it("editors converts paths in sandbox (Linux)", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/projects/src/Foo.java", fqn: "com.example.Foo", project: "my-server" },
      ]));
    });
    mockSandboxPaths();
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    const out = io.logs[0];
    expect(out).toContain("/d/projects/src/Foo.java");
  });

  it("editors shows empty message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    expect(io.logs[0]).toBe("(no open editors)");
  });

  it("open shows Opened", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
    });
    const { open } = await import("../src/commands/editor.mjs");
    await open(["com.example.Foo"]);
    expect(io.logs[0]).toBe("Opened");
  });

  // ── Build ──

  it("build shows success with 0 errors", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ errors: 0 }));
    });
    const { build } = await import("../src/commands/build.mjs");
    await build(["--project", "my-client"]);
    expect(io.logs[0]).toBe("Build complete (0 errors)");
  });

  it("build exits 1 on compilation errors", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ errors: 3 }));
    });
    const { build } = await import("../src/commands/build.mjs");
    await expect(build(["--project", "my-client"])).rejects.toThrow("exit(1)");
    expect(io.logs[0]).toBe("Build complete (3 errors)");
  });

  it("build with --clean flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("clean");
      expect(req.url).toContain("project=my-client");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ errors: 0 }));
    });
    const { build } = await import("../src/commands/build.mjs");
    await build(["--project", "my-client", "--clean"]);
    expect(io.logs[0]).toBe("Build complete (0 errors)");
  });
});
