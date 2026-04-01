import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { startServer, stopServer, captureConsole, errorServer, disableColor } from "./helpers/mock-server.mjs";

describe("sandbox paths and bulk assertions", () => {
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

  // NOTE: setupMock and mockSandboxPaths use vi.doMock with relative paths —
  // vitest resolves these relative to THIS file, so they must stay inline here.

  function mockSandboxPaths() {
    vi.doMock("../src/paths.mjs", async (importOriginal) => {
      const orig = await importOriginal();
      return { ...orig, toSandboxPath: (p) => p && /^[A-Z]:[/\\]/.test(p) ? "/" + p[0].toLowerCase() + p.slice(2).replace(/\\/g, "/") : p };
    });
  }

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

  // ---- Sandbox path conversion (Linux) ----

  it("find converts source path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "D:/git/project/src/Foo.java", kind: "class" },
      ]));
    });
    mockSandboxPaths();
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    const out = io.logs[0];
    expect(out).toContain("/d/git/project/src/Foo.java");
  });

  it("find binary shows origin not path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "javax.swing.table.TableModel", file: "D:/m8/m8-client", binary: true, kind: "interface", origin: "rt.jar" },
      ]));
    });
    mockSandboxPaths();
    const { find } = await import("../src/commands/find.mjs");
    await find(["TableModel"]);
    const out = io.logs[0];
    expect(out).toContain("rt.jar");
    expect(out).not.toContain("/d/m8");
  });

  it("find keeps workspace-relative path unchanged in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "/my-server/src/Foo.java", kind: "class" },
      ]));
    });
    mockSandboxPaths();
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    const out = io.logs[0];
    expect(out).toContain("my-server/src/Foo.java");
  });

  it("errors converts path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/git/project/src/Foo.java", line: 10, severity: "ERROR", message: "bad" },
      ]));
    });
    mockSandboxPaths();
    const { errors } = await import("../src/commands/errors.mjs");
    await errors([]);
    expect(io.logs[0]).toContain("/d/git/project/src/Foo.java:10");
  });

  it("errors keeps workspace-relative path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "/my-server/src/Foo.java", line: 5, severity: "ERROR", message: "err" },
      ]));
    });
    mockSandboxPaths();
    const { errors } = await import("../src/commands/errors.mjs");
    await errors([]);
    expect(io.logs[0]).toContain("/my-server/src/Foo.java:5");
  });

  it("subtypes converts path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Dog", file: "D:/git/project/src/Dog.java" },
      ]));
    });
    mockSandboxPaths();
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["com.example.Animal"]);
    expect(io.logs[0]).toContain("/d/git/project/src/Dog.java");
  });

  it("subtypes converts JAR path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Impl", file: "D:/git/m8/m8-core" },
      ]));
    });
    mockSandboxPaths();
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["com.example.Base"]);
    expect(io.logs[0]).toContain("/d/git/m8/m8-core");
  });

  it("implementors converts path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.FooImpl", file: "D:/git/project/src/FooImpl.java", line: 15 },
      ]));
    });
    mockSandboxPaths();
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["com.example.Foo#bar"]);
    expect(io.logs[0]).toContain("/d/git/project/src/FooImpl.java:15");
  });

  it("type-info converts path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        kind: "class", fqn: "com.example.Foo", file: "D:/git/project/src/Foo.java",
        superclass: "java.lang.Object", interfaces: [],
        fields: [], methods: [],
      }));
    });
    mockSandboxPaths();
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await typeInfo(["com.example.Foo"]);
    expect(io.logs[0]).toContain("/d/git/project/src/Foo.java");
  });

  it("type-info converts JAR path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        kind: "class", fqn: "javax.swing.JPanel", file: "D:/git/m8/m8-client",
        superclass: "javax.swing.JComponent", interfaces: [],
        fields: [], methods: [],
      }));
    });
    mockSandboxPaths();
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await typeInfo(["javax.swing.JPanel"]);
    expect(io.logs[0]).toContain("/d/git/m8/m8-client");
  });

  it("references converts source path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/git/project/src/Caller.java", line: 42, in: "doStuff()", content: "foo.bar();" },
      ]));
    });
    mockSandboxPaths();
    const { references } = await import("../src/commands/references.mjs");
    await references(["com.example.Foo"]);
    expect(io.logs[0]).toContain("/d/git/project/src/Caller.java:42");
  });

  it("references converts binary project path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/git/m8/m8-core/lib/dep.jar", line: -1, project: "m8-core", in: "com.Dep#use()" },
      ]));
    });
    mockSandboxPaths();
    const { references } = await import("../src/commands/references.mjs");
    await references(["com.example.Foo"]);
    expect(io.logs[0]).toContain("m8-core");
    expect(io.logs[0]).toContain("dep.jar");
  });

  it("hierarchy converts file path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        supertypes: [{ fqn: "java.lang.Object", kind: "class" }],
        subtypes: [{ fqn: "com.example.Dog", kind: "class", file: "D:/git/project/src/Dog.java", line: 5 }],
      }));
    });
    mockSandboxPaths();
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["com.example.Animal"]);
    const out = io.logs.join("\n");
    expect(out).toContain("/d/git/project/src/Dog.java");
  });

  it("project-info converts location in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "m8-server", location: "D:/git/m8/m8-server",
        natures: [], dependencies: [], totalTypes: 10, sourceRoots: [],
      }));
    });
    mockSandboxPaths();
    const { projectInfo } = await import("../src/commands/project-info.mjs");
    await projectInfo(["m8-server"]);
    const out = io.logs.join("\n");
    expect(out).toContain("/d/git/m8/m8-server");
  });

  // ---- Empty states: consistent (no <entity>) format ----

  it("all empty states use (no <entity>) format", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });

    const cases = [
      { mod: "../src/commands/find.mjs", fn: "find", args: ["X"], expected: "(no results)" },
      { mod: "../src/commands/subtypes.mjs", fn: "subtypes", args: ["X"], expected: "(no subtypes)" },
      { mod: "../src/commands/implementors.mjs", fn: "implementors", args: ["X", "m"], expected: "(no implementors)" },
      { mod: "../src/commands/references.mjs", fn: "references", args: ["X"], expected: "(no references)" },
      { mod: "../src/commands/errors.mjs", fn: "errors", args: [], expected: "(no errors)" },
    ];

    for (const { mod, fn, args, expected } of cases) {
      io.logs.length = 0;
      vi.resetModules();
      vi.doMock("../src/bridge-env.mjs", () => ({ getPinnedBridge: () => null }));
      vi.doMock("../src/discovery.mjs", () => ({
        discoverInstances: async () => [],
        findInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1" }),
      }));
      const module = await import(mod);
      await module[fn](args);
      expect(io.logs[0]).toBe(expected);
    }
  });

  // ---- Domain errors: exit 0, not exit 1 ----

  it("domain errors do not call process.exit", async () => {
    await setupMock(errorServer());

    const cases = [
      { mod: "../src/commands/find.mjs", fn: "find", args: ["X"] },
      { mod: "../src/commands/subtypes.mjs", fn: "subtypes", args: ["X"] },
      { mod: "../src/commands/references.mjs", fn: "references", args: ["X"] },
      { mod: "../src/commands/errors.mjs", fn: "errors", args: [] },
      { mod: "../src/commands/type-info.mjs", fn: "typeInfo", args: ["X"] },
      { mod: "../src/commands/hierarchy.mjs", fn: "hierarchy", args: ["X"] },
      { mod: "../src/commands/projects.mjs", fn: "projects", args: [] },
    ];

    for (const { mod, fn, args } of cases) {
      io.errors.length = 0;
      vi.resetModules();
      vi.doMock("../src/bridge-env.mjs", () => ({ getPinnedBridge: () => null }));
      vi.doMock("../src/discovery.mjs", () => ({
        discoverInstances: async () => [],
        findInstance: async () => ({ port, token: null, pid: process.pid, workspace: "/test", host: "127.0.0.1" }),
      }));
      const module = await import(mod);
      // Should NOT throw — returns normally (exit 0)
      await module[fn](args);
      expect(io.errors[0]).toContain("Something went wrong");
    }
  });

  // ---- Missing args: still exit 1 ----

  it("missing args still exit 1", async () => {
    const cases = [
      { mod: "../src/commands/find.mjs", fn: "find", args: [] },
      { mod: "../src/commands/subtypes.mjs", fn: "subtypes", args: [] },
      { mod: "../src/commands/type-info.mjs", fn: "typeInfo", args: [] },
      { mod: "../src/commands/hierarchy.mjs", fn: "hierarchy", args: [] },
      { mod: "../src/commands/references.mjs", fn: "references", args: [] },
    ];

    for (const { mod, fn, args } of cases) {
      vi.resetModules();
      const module = await import(mod);
      await expect(module[fn](args)).rejects.toThrow("exit(1)");
    }
  });
});
