import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createServer } from "node:http";
import { setColorEnabled } from "../src/color.mjs";
import { toSandboxPath } from "../src/paths.mjs";

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

describe("commands (integration)", () => {
  let server, port, io;

  beforeEach(() => {
    setColorEnabled(false);
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    vi.doUnmock("../src/paths.mjs");
    vi.resetModules();
  });

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

  it("projects lists project names", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(["my-server", "my-client"]));
    });
    const { projects } = await import("../src/commands/projects.mjs");
    await projects([]);
    const out = io.logs.join("\n");
    expect(out).toContain("2 projects");
    expect(out).toContain("`my-server`");
    expect(out).toContain("`my-client`");
  });

  it("find shows table with headers", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "/my-server/src/Foo.java", kind: "class" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    const out = io.logs[0];
    expect(out).toContain("KIND");
    expect(out).toContain("FQN");
    expect(out).toContain("ORIGIN");
    expect(out).toContain("[C]");
    expect(out).toContain("`com.example.Foo`");
    expect(out).toContain("my-server/src/Foo.java");
  });

  it("find deduplicates binary types", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "javax.swing.JPanel", file: "D:/m8/m8-client", binary: true, origin: "rt.jar" },
        { fqn: "javax.swing.JPanel", file: "D:/m8/m8-server", binary: true, origin: "rt.jar" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["JPanel"]);
    const out = io.logs[0];
    expect(out.match(/javax\.swing\.JPanel/g).length).toBe(1);
    expect(out).toContain("rt.jar");
  });

  it("find shows source path and binary origin", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "D:/git/project/src/Foo.java", kind: "class" },
        { fqn: "javax.swing.JPanel", file: "D:/m8/m8-client", kind: "class", binary: true, origin: "rt.jar" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    const out = io.logs[0];
    expect(out).toContain(toSandboxPath("D:/git/project/src/Foo.java"));
    expect(out).toContain("rt.jar");
  });

  it("find shows interface badge", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Service", file: "/my-server/src/Service.java", kind: "interface" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Service"]);
    const out = io.logs[0];
    expect(out).toContain("[I]");
  });

  it("find shows annotation badge", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "org.junit.Test", kind: "annotation", binary: true, origin: "junit-4.13.2.jar" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Test"]);
    const out = io.logs[0];
    expect(out).toContain("[A]");
    expect(out).toContain("junit-4.13.2.jar");
  });

  it("find shows no results message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["NonExistent"]);
    expect(io.logs[0]).toBe("(no results)");
  });

  it("subtypes shows FQN and file", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.FooImpl", file: "/my-server/src/FooImpl.java" },
      ]));
    });
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["com.example.Foo"]);
    expect(io.logs[0]).toContain("com.example.FooImpl");
  });

  it("hierarchy shows all sections", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        fqn: "com.example.Foo",
        supertypes: [{ fqn: "com.example.Base", kind: "class", depth: 0 },
                     { fqn: "com.example.HasId", kind: "interface", depth: 0 }],
        subtypes: [{ fqn: "com.example.Bar", kind: "class", depth: 0 }],
      }));
    });
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["com.example.Foo"]);
    const out = io.logs.join("\n");
    expect(out).toContain("Supertypes");
    expect(out).toContain("com.example.Base");
    expect(out).toContain("com.example.HasId");
    expect(out).toContain("Subtypes");
    expect(out).toContain("com.example.Bar");
  });

  it("implementors shows FQN, file, and line", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.FooImpl", file: "/my-server/src/FooImpl.java", line: 25 },
      ]));
    });
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["com.example.Foo", "doStuff"]);
    expect(io.logs[0]).toContain("com.example.FooImpl");
    expect(io.logs[0]).toContain(":25");
  });

  it("errors shows formatted error lines", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { severity: "ERROR", source: "JDT", file: "D:/projects/my-server/src/Foo.java", line: 10, message: "cannot resolve symbol" },
      ]));
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors([]);
    expect(io.logs[0]).toContain("ERROR");
    expect(io.logs[0]).toContain("[JDT]");
    expect(io.logs[0]).toContain("cannot resolve symbol");
    expect(io.logs[0]).toContain(toSandboxPath("D:/projects/my-server/src/Foo.java") + ":10");
  });

  it("errors shows no errors message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors([]);
    expect(io.logs[0]).toBe("(no errors)");
  });

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
    expect(out).toContain(toSandboxPath("D:/projects/src/Foo.java"));
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

  it("source prints markdown with refs", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        fqmn: "com.example.Foo",
        file: "D:/project/src/Foo.java",
        startLine: 5, endLine: 15,
        source: "public class Foo {\n}\n",
        refs: [],
      }));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Foo"]);
    const out = io.logs.join("\n");
    expect(out).toContain("[C] com.example.Foo");
    expect(out).toContain(`${toSandboxPath("D:/project/src/Foo.java")}:5-15`);
    expect(out).toContain("public class Foo");
  });

  it("source converts paths in sandbox (Linux)", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        fqmn: "com.example.Foo",
        file: "D:/project/src/Foo.java",
        startLine: 5, endLine: 15,
        source: "public class Foo {}",
        refs: [],
      }));
    });
    mockSandboxPaths();
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Foo"]);
    const out = io.logs.join("\n");
    expect(out).toContain("/d/project/src/Foo.java:5-15");
  });

  it("type-info shows class details", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        kind: "class", fqn: "com.example.Foo", file: "/my-server/src/Foo.java",
        superclass: "java.lang.Object", interfaces: ["com.example.HasId"],
        fields: [{ modifiers: "private", type: "String", name: "id", line: 5 }],
        methods: [{ signature: "String getId()", line: 7 }],
      }));
    });
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await typeInfo(["com.example.Foo"]);
    expect(io.logs[0]).toContain("class com.example.Foo");
    expect(io.logs.some((l) => l.includes("extends java.lang.Object"))).toBe(true);
    expect(io.logs.some((l) => l.includes("implements com.example.HasId"))).toBe(true);
    expect(io.logs.some((l) => l.includes("String id"))).toBe(true);
    expect(io.logs.some((l) => l.includes("String getId()"))).toBe(true);
  });

  it("project-info shows formatted output", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "test-proj", location: "/ws/test-proj",
        natures: ["org.eclipse.jdt.core.javanature"],
        dependencies: ["my-core"], totalTypes: 2, membersIncluded: false,
        sourceRoots: [{
          path: "src/main/java", typeCount: 2,
          packages: [{ name: "app.test", types: [{ name: "A", kind: "class", fields: 0, methods: {} }] }],
        }],
      }));
    });
    const { projectInfo } = await import("../src/commands/project-info.mjs");
    await projectInfo(["test-proj"]);
    expect(io.logs[0]).toContain("test-proj");
    expect(io.logs[0]).toContain("Total: 2 types");
  });

  it("references shows grouped output", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "/my-server/src/Bar.java", line: 10, in: "Bar.init()", content: "new Foo();" },
        { file: "/my-server/src/Bar.java", line: 20, in: "Bar.run()", content: "foo.go();" },
      ]));
    });
    const { references } = await import("../src/commands/references.mjs");
    await references(["com.example.Foo"]);
    expect(io.logs[0]).toBe("my-server/src/Bar.java:10");
    expect(io.logs[1]).toContain("in Bar.init()");
  });


  // validation tests → commands.validation.test.mjs
  // edge case tests → commands.edge.test.mjs
  // sandbox + bulk tests → commands.sandbox.test.mjs
});
