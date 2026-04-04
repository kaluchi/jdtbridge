import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  startServer, stopServer, captureConsole, errorServer, disableColor,
} from "./helpers/mock-server.mjs";

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

  // --- Usage validation (missing args) ---

  it("find exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { find } = await import("../src/commands/find.mjs");
    await expect(find([])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Usage");
  });

  it("references exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { references } = await import("../src/commands/references.mjs");
    await expect(references([])).rejects.toThrow("exit(1)");
  });

  it("rename exits on missing newName", async () => {
    await setupMock((req, res) => res.end());
    const { rename } = await import("../src/commands/refactoring.mjs");
    await expect(rename(["com.example.Foo"])).rejects.toThrow("exit(1)");
  });

  it("implementors exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { implementors } = await import("../src/commands/implementors.mjs");
    await expect(implementors([])).rejects.toThrow("exit(1)");
  });

  it("hierarchy exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await expect(hierarchy([])).rejects.toThrow("exit(1)");
  });

  it("type-info exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await expect(typeInfo([])).rejects.toThrow("exit(1)");
  });

  it("source exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { source } = await import("../src/commands/source.mjs");
    await expect(source([])).rejects.toThrow("exit(1)");
  });

  it("project-info exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { projectInfo } = await import("../src/commands/project-info.mjs");
    await expect(projectInfo([])).rejects.toThrow("exit(1)");
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

  // --- Server error responses ---

  it("projects exits on server error", async () => {
    await setupMock(errorServer());
    const { projects } = await import("../src/commands/projects.mjs");
    await projects([]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("find exits on server error", async () => {
    await setupMock(errorServer());
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("implementors exits on server error", async () => {
    await setupMock(errorServer());
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["app.Foo"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("hierarchy exits on server error", async () => {
    await setupMock(errorServer());
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["app.Foo"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("implementors exits on server error", async () => {
    await setupMock(errorServer());
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["app.Foo", "m"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("type-info exits on server error", async () => {
    await setupMock(errorServer());
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await typeInfo(["app.Foo"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("problems exits on server error", async () => {
    await setupMock(errorServer());
    const { problems } = await import("../src/commands/problems.mjs");
    await problems([]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("build exits on server error", async () => {
    await setupMock(errorServer());
    const { build } = await import("../src/commands/build.mjs");
    await build(["--project", "my-client"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("project-info exits on server error", async () => {
    await setupMock(errorServer());
    const { projectInfo } = await import("../src/commands/project-info.mjs");
    await projectInfo(["proj"]);
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

  it("references exits on server error", async () => {
    await setupMock(errorServer());
    const { references } = await import("../src/commands/references.mjs");
    await references(["app.Foo"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });
});
