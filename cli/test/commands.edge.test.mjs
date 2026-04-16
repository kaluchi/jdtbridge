import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { startServer, stopServer, captureConsole, disableColor } from "./helpers/mock-server.mjs";

// Edge cases for non-graph commands. Graph-axis edge cases live in
// GraphHandlerTest (plugin.tests).

describe("command edge cases", () => {
  let server, port, io;

  beforeEach(() => {
    disableColor();
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
      resolveInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1", file: "" }),
    }));
  }

  it("format shows 'No changes' when not modified", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ modified: false, reason: "already formatted" }));
    });
    const { format } = await import("../src/commands/refactoring.mjs");
    await format(["f.java"]);
    expect(io.logs[0]).toContain("No changes");
    expect(io.logs[0]).toContain("already formatted");
  });

  it("rename shows Renamed without warnings", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
    });
    const { rename } = await import("../src/commands/refactoring.mjs");
    await rename(["app.Foo", "Bar"]);
    expect(io.logs[0]).toBe("Renamed");
    expect(io.logs).toHaveLength(1);
  });

  it("move shows Moved with warnings", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, warnings: ["may break imports"] }));
    });
    const { move } = await import("../src/commands/refactoring.mjs");
    await move(["app.Foo", "app.bar"]);
    expect(io.logs[0]).toBe("Moved");
    expect(io.logs[1]).toContain("may break imports");
  });
});
