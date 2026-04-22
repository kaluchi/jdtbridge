import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { startServer, stopServer, captureConsole, disableColor } from "./helpers/mock-server.mjs";

// Sandbox path conversion across non-graph commands. Graph-axis
// path-conversion behavior is exercised by GraphHandlerTest's
// :location :file assertions plus the printValue path on the
// CLI side, both of which return raw OS paths to the user.

describe("sandbox paths and bulk assertions", () => {
  let server, port, io;

  beforeEach(() => {
    disableColor();
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    vi.doUnmock("../src/path-translate.mjs");
    vi.resetModules();
  });

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

  it("editors converts source path in sandbox", async () => {
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

  it("editors keeps workspace-relative path unchanged in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "/my-server/src/Foo.java", fqn: "com.example.Foo", project: "my-server" },
      ]));
    });
    mockSandboxPaths();
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    const out = io.logs[0];
    expect(out).toContain("my-server/src/Foo.java");
  });
});
