import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  startServer, stopServer, captureConsole, errorServer, disableColor,
} from "./helpers/mock-server.mjs";

// Validation tests for non-graph CLI commands. Graph-axis input
// validation lives in the qlang :jdt/graph operand impls (subject-
// polymorphism + missing-fqn errors via fail-track).

describe("command validation", () => {
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

  // ── Usage validation (missing args) ──

  it("rename exits on missing newName", async () => {
    await setupMock((req, res) => res.end());
    const { rename } = await import("../src/commands/refactoring.mjs");
    await expect(rename(["com.example.Foo"])).rejects.toThrow("exit(1)");
  });

  it("organize-imports exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { organizeImports } = await import("../src/commands/refactoring.mjs");
    await expect(organizeImports([])).rejects.toThrow("exit(1)");
  });

  it("format exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { format } = await import("../src/commands/refactoring.mjs");
    await expect(format([])).rejects.toThrow("exit(1)");
  });

  it("move exits on missing target", async () => {
    await setupMock((req, res) => res.end());
    const { move } = await import("../src/commands/refactoring.mjs");
    await expect(move(["com.example.Foo"])).rejects.toThrow("exit(1)");
  });

  it("open exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { open } = await import("../src/commands/editor.mjs");
    await expect(open([])).rejects.toThrow("exit(1)");
  });

  // ── Server error responses ──

  it("build exits on server error", async () => {
    await setupMock(errorServer());
    const { build } = await import("../src/commands/build.mjs");
    await build(["--project", "my-client"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("organize-imports exits on server error", async () => {
    await setupMock(errorServer());
    const { organizeImports } = await import("../src/commands/refactoring.mjs");
    await organizeImports(["f.java"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("format exits on server error", async () => {
    await setupMock(errorServer());
    const { format } = await import("../src/commands/refactoring.mjs");
    await format(["f.java"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("rename exits on server error", async () => {
    await setupMock(errorServer());
    const { rename } = await import("../src/commands/refactoring.mjs");
    await rename(["app.Foo", "Bar"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("move exits on server error", async () => {
    await setupMock(errorServer());
    const { move } = await import("../src/commands/refactoring.mjs");
    await move(["app.Foo", "app.bar"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("editors exits on server error", async () => {
    await setupMock(errorServer());
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("open exits on server error", async () => {
    await setupMock(errorServer());
    const { open } = await import("../src/commands/editor.mjs");
    await open(["app.Foo"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });
});
