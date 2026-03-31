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
    expect(io.logs).toEqual(["my-server", "my-client"]);
  });

  it("find shows FQN and file", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "/my-server/src/main/java/app/my/Foo.java" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    expect(io.logs[0]).toContain("com.example.Foo");
    expect(io.logs[0]).toContain("my-server/src/main/java/app/my/Foo.java");
  });

  it("find shows absolute Windows path on host", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "D:/git/project/src/Foo.java" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    expect(io.logs[0]).toContain(toSandboxPath("D:/git/project/src/Foo.java"));
  });

  it("find shows JAR project path on host", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "javax.swing.JPanel", file: "D:/git/m8/m8-client" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["JPanel"]);
    expect(io.logs[0]).toContain(toSandboxPath("D:/git/m8/m8-client"));
  });

  it("find shows backslash Windows path", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Bar", file: "D:\\git\\project\\src\\Bar.java" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Bar"]);
    expect(io.logs[0]).toContain(toSandboxPath("D:\\git\\project\\src\\Bar.java"));
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
        { severity: "ERROR", source: "JDT", file: "/my-server/src/Foo.java", line: 10, message: "cannot resolve symbol" },
      ]));
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors([]);
    expect(io.logs[0]).toContain("ERROR");
    expect(io.logs[0]).toContain("[JDT]");
    expect(io.logs[0]).toContain("cannot resolve symbol");
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

  it("editors lists open files", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/projects/src/Foo.java" },
        { file: "D:/projects/src/Bar.java" },
      ]));
    });
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    expect(io.logs[0]).toBe(toSandboxPath("D:/projects/src/Foo.java"));
    expect(io.logs[1]).toBe(toSandboxPath("D:/projects/src/Bar.java"));
  });

  it("editors converts paths in sandbox (Linux)", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/projects/src/Foo.java" },
      ]));
    });
    mockSandboxPaths();
    const { editors } = await import("../src/commands/editor.mjs");
    await editors();
    expect(io.logs[0]).toBe("/d/projects/src/Foo.java");
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

  it("subtypes exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await expect(subtypes([])).rejects.toThrow("exit(1)");
  });

  it("hierarchy exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await expect(hierarchy([])).rejects.toThrow("exit(1)");
  });

  it("implementors exits on missing method", async () => {
    await setupMock((req, res) => res.end());
    const { implementors } = await import("../src/commands/implementors.mjs");
    await expect(implementors(["com.example.Foo"])).rejects.toThrow("exit(1)");
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

  function errorServer() {
    return (req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Something went wrong" }));
    };
  }

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

  it("subtypes exits on server error", async () => {
    await setupMock(errorServer());
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["app.Foo"]);
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

  it("errors exits on server error", async () => {
    await setupMock(errorServer());
    const { errors } = await import("../src/commands/errors.mjs");
    await errors([]);
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

  // ---- launch ----

  it("launch list shows launches", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", type: "Java Application", mode: "run", terminated: false, pid: "12345" },
        { name: "ObjectMapperTest", type: "JUnit", mode: "run", terminated: true, exitCode: 0 },
      ]));
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    expect(io.logs[0]).toContain("my-server");
    expect(io.logs[0]).toContain("running");
    expect(io.logs[1]).toContain("ObjectMapperTest");
    expect(io.logs[1]).toContain("terminated");
  });

  it("launch list empty", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    expect(io.logs[0]).toBe("(no launches)");
  });

  it("launch logs shows output", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "my-server",
        terminated: false,
        output: "Server started on port 8080\nReady.\n",
      }));
    });
    const { launchLogs } = await import("../src/commands/launch.mjs");
    await launchLogs(["my-server"]);
    expect(io.logs[0]).toContain("Server started");
  });

  it("launch console alias works", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "my-server",
        terminated: false,
        output: "Server started on port 8080\nReady.\n",
      }));
    });
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["my-server"]);
    expect(io.logs[0]).toContain("Server started");
  });

  it("launch console with tail", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("tail=10");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ name: "my-server", terminated: false, output: "last line\n" }));
    });
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["my-server", "--tail", "10"]);
    expect(io.logs[0]).toContain("last line");
  });

  it("launch configs shows configurations", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", type: "Java Application" },
        { name: "AllTests", type: "JUnit" },
      ]));
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs();
    expect(io.logs[0]).toContain("my-server");
    expect(io.logs[0]).toContain("Java Application");
    expect(io.logs[1]).toContain("AllTests");
  });

  it("launch configs empty", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs();
    expect(io.logs[0]).toBe("(no launch configurations)");
  });

  it("launch clear shows count", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ removed: 3 }));
    });
    const { launchClear } = await import("../src/commands/launch.mjs");
    await launchClear([]);
    expect(io.logs[0]).toContain("3");
    expect(io.logs[0]).toContain("launches");
  });

  it("launch clear by name", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("name=old-test");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ removed: 1 }));
    });
    const { launchClear } = await import("../src/commands/launch.mjs");
    await launchClear(["old-test"]);
    expect(io.logs[0]).toContain("1");
  });

  it("launch run shows launched with guide", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "run" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server"]);
    expect(io.logs[0]).toContain("Launched");
    // Onboarding guide shown
    const all = io.logs.join("\n");
    expect(all).toContain("jdt launch logs");
    expect(all).toContain("jdt launch stop");
  });

  it("launch run -q suppresses guide", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "run" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server", "-q"]);
    expect(io.logs).toHaveLength(1);
    expect(io.logs[0]).toContain("Launched");
  });

  it("launch debug sends debug flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("debug");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "debug" }));
    });
    const { launchDebug } = await import("../src/commands/launch.mjs");
    await launchDebug(["my-server"]);
    expect(io.logs[0]).toContain("debug");
  });

  it("launch run missing name exits", async () => {
    const { launchRun } = await import("../src/commands/launch.mjs");
    await expect(launchRun([])).rejects.toThrow("exit(1)");
  });

  it("launch stop shows stopped", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server" }));
    });
    const { launchStop } = await import("../src/commands/launch.mjs");
    await launchStop(["my-server"]);
    expect(io.logs[0]).toContain("Stopped");
  });

  it("launch stop missing name exits", async () => {
    const { launchStop } = await import("../src/commands/launch.mjs");
    await expect(launchStop([])).rejects.toThrow("exit(1)");
  });

  it("launch console missing name exits", async () => {
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await expect(launchConsole([])).rejects.toThrow("exit(1)");
  });

  it("launch console error does not exit 1", async () => {
    await setupMock(errorServer());
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["my-server"]);
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("launch list error does not exit 1", async () => {
    await setupMock(errorServer());
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("launch console --follow streams text/plain", async () => {
    let requestCount = 0;
    await setupMock((req, res) => {
      requestCount++;
      if (req.url.includes("/launch/console/stream")) {
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.write("line1\n");
        res.write("line2\n");
        res.end();
      } else {
        // Exit code fetch after stream ends
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "test", terminated: true, output: "" }));
      }
    });
    // Capture stdout writes instead of console.log
    const chunks = [];
    const origWrite = process.stdout.write;
    process.stdout.write = (chunk) => { chunks.push(chunk.toString()); return true; };
    try {
      const { launchConsole } = await import("../src/commands/launch.mjs");
      await expect(launchConsole(["test", "--follow"])).rejects.toThrow("exit(0)");
      const output = chunks.join("");
      expect(output).toContain("line1");
      expect(output).toContain("line2");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch console --follow passes stream filter", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/launch/console/stream")) {
        expect(req.url).toContain("stream=stderr");
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("error output");
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "test", terminated: true, output: "" }));
      }
    });
    const origWrite = process.stdout.write;
    process.stdout.write = () => true;
    try {
      const { launchConsole } = await import("../src/commands/launch.mjs");
      await expect(launchConsole(["test", "-f", "--stderr"])).rejects.toThrow("exit(0)");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch console --follow passes tail param", async () => {
    await setupMock((req, res) => {
      if (req.url.includes("/launch/console/stream")) {
        expect(req.url).toContain("tail=20");
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("tail output");
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "test", terminated: true, output: "" }));
      }
    });
    const origWrite = process.stdout.write;
    process.stdout.write = () => true;
    try {
      const { launchConsole } = await import("../src/commands/launch.mjs");
      await expect(launchConsole(["test", "--follow", "--tail", "20"])).rejects.toThrow("exit(0)");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch run --follow launches then streams", async () => {
    let urls = [];
    await setupMock((req, res) => {
      urls.push(req.url);
      if (req.url.includes("/launch/run")) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, name: "my-build", mode: "run" }));
      } else if (req.url.includes("/launch/console/stream")) {
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("BUILD SUCCESS\n");
      } else {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ name: "my-build", terminated: true, output: "" }));
      }
    });
    const origWrite = process.stdout.write;
    process.stdout.write = () => true;
    try {
      const { launchRun } = await import("../src/commands/launch.mjs");
      await expect(launchRun(["my-build", "--follow"])).rejects.toThrow("exit(0)");
      // Should have called run first, then stream
      expect(urls[0]).toContain("/launch/run");
      expect(urls[1]).toContain("/launch/console/stream");
      // "Launched" message goes to stderr, not stdout
      expect(io.errors[0]).toContain("Launched");
    } finally {
      process.stdout.write = origWrite;
    }
  });

  it("launch run without --follow prints launched", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "my-server", mode: "run" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["my-server"]);
    expect(io.logs[0]).toContain("Launched");
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

  // --- Edge cases ---

  it("subtypes shows no subtypes message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["app.Foo"]);
    expect(io.logs[0]).toBe("(no subtypes)");
  });

  it("implementors shows no implementors message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["app.Foo", "m"]);
    expect(io.logs[0]).toBe("(no implementors)");
  });

  it("references shows no references message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { references } = await import("../src/commands/references.mjs");
    await references(["app.Foo"]);
    expect(io.logs[0]).toBe("(no references)");
  });

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

  it("hierarchy shows subtypes section", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        supers: [],
        interfaces: [],
        subtypes: [{ fqn: "app.Sub", binary: false, file: "/my-app/src/Sub.java" }],
      }));
    });
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["app.Foo"]);
    expect(io.logs.some((l) => l.includes("Subtypes"))).toBe(true);
    expect(io.logs.some((l) => l.includes("app.Sub"))).toBe(true);
  });

  it("source handles multiple overloads as array", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqmn: "com.example.Foo#bar()", file: "D:/src/Foo.java", startLine: 10, endLine: 15, source: "void bar() {}", refs: [] },
        { fqmn: "com.example.Foo#bar(int)", file: "D:/src/Foo.java", startLine: 20, endLine: 25, source: "void bar(int x) {}", refs: [] },
      ]));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("[M] com.example.Foo#bar()");
    expect(out).toContain("[M] com.example.Foo#bar(int)");
    expect(out).toContain("---");
  });

  it("type-info shows interface without fields", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        kind: "interface", fqn: "app.HasId", file: "/my-app/src/HasId.java",
        superclass: null, interfaces: [],
        fields: [], methods: [{ signature: "String getId()", line: 3 }],
      }));
    });
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await typeInfo(["app.HasId"]);
    expect(io.logs[0]).toContain("interface app.HasId");
    expect(io.logs.some((l) => l.includes("extends"))).toBe(false);
  });

  it("errors with --warnings flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("warnings");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { severity: "WARNING", source: null, file: "/my-app/src/A.java", line: 1, message: "unused" },
      ]));
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors(["--warnings"]);
    expect(io.logs[0]).toContain("WARN");
  });

  // ---- source ----

  it("source renders markdown with refs", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("class=com.example.Foo");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        fqmn: "com.example.Foo#bar()",
        file: "D:/project/src/com/example/Foo.java",
        startLine: 10,
        endLine: 20,
        source: "public void bar() {\n    baz();\n}",
        refs: [
          { fqmn: "com.example.Foo#baz()", kind: "method", scope: "class", type: "void", line: 25, doc: "Does baz." },
          { fqmn: "com.example.Util", kind: "type", scope: "project", file: "D:/project/src/com/example/Util.java", doc: "Utilities." },
          { fqmn: "org.eclipse.core.runtime.CoreException", kind: "type", scope: "dependency" },
        ],
      }));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Foo#bar"]);
    const out = io.logs.join("\n");
    // Header
    expect(out).toContain("[M] com.example.Foo#bar()");
    expect(out).toContain(`${toSandboxPath("D:/project/src/com/example/Foo.java")}:10-20`);
    // Code block
    expect(out).toContain("```java");
    expect(out).toContain("public void bar()");
    // Outgoing calls with badges
    expect(out).toContain("Outgoing Calls:");
    expect(out).toContain("`com.example.Foo#baz()`");
    expect(out).toContain("`com.example.Util`");
    expect(out).toContain("`org.eclipse.core.runtime.CoreException`");
  });

  it("source with error does not exit 1", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Type not found: Bogus" }));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["Bogus"]);
    expect(io.errors[0]).toContain("not found");
  });

  it("source missing arg exits", async () => {
    const { source } = await import("../src/commands/source.mjs");
    await expect(source([])).rejects.toThrow("exit(1)");
  });

  it("source with no refs renders clean", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        fqmn: "com.example.Simple",
        file: "D:/project/src/Simple.java",
        startLine: 1,
        endLine: 5,
        source: "public class Simple {}",
        refs: [],
      }));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Simple"]);
    const out = io.logs.join("\n");
    expect(out).toContain("```java");
    expect(out).not.toContain("Outgoing Calls:");
  });

  it("source batch fetches multiple FQMNs", async () => {
    let requestCount = 0;
    await setupMock((req, res) => {
      requestCount++;
      res.writeHead(200, { "Content-Type": "application/json" });
      if (req.url.includes("class=com.example.Foo")) {
        res.end(JSON.stringify({
          fqmn: "com.example.Foo", file: "D:/src/Foo.java",
          startLine: 1, endLine: 10, source: "class Foo {}", refs: [],
        }));
      } else {
        res.end(JSON.stringify({
          fqmn: "com.example.Bar", file: "D:/src/Bar.java",
          startLine: 1, endLine: 5, source: "class Bar {}", refs: [],
        }));
      }
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Foo", "com.example.Bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("[C] com.example.Foo");
    expect(out).toContain("[C] com.example.Bar");
    expect(out).toContain("---");
    expect(requestCount).toBe(2);
  });

  it("source batch skips errors continues others", async () => {
    let count = 0;
    await setupMock((req, res) => {
      count++;
      res.writeHead(200, { "Content-Type": "application/json" });
      if (count === 1) {
        res.end(JSON.stringify({ error: "Not found" }));
      } else {
        res.end(JSON.stringify({
          fqmn: "com.example.Ok", file: "D:/src/Ok.java",
          startLine: 1, endLine: 3, source: "class Ok {}", refs: [],
        }));
      }
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Bad", "com.example.Ok"]);
    const out = io.logs.join("\n");
    expect(out).toContain("[C] com.example.Ok");
    expect(io.errors[0]).toContain("Not found");
  });

  it("find with --source-only flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("source");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo", "--source-only"]);
  });

  it("references with --field flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("field=myField");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { references } = await import("../src/commands/references.mjs");
    await references(["app.Foo", "--field", "myField"]);
  });

  // ---- Sandbox path conversion (Linux) ----

  it("find converts source path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "D:/git/project/src/Foo.java" },
      ]));
    });
    mockSandboxPaths();
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    expect(io.logs[0]).toContain("/d/git/project/src/Foo.java");
  });

  it("find converts JAR project path in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "javax.swing.table.TableModel", file: "D:/git/m8/m8-client" },
      ]));
    });
    mockSandboxPaths();
    const { find } = await import("../src/commands/find.mjs");
    await find(["TableModel"]);
    expect(io.logs[0]).toContain("/d/git/m8/m8-client");
  });

  it("find keeps workspace-relative path unchanged in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "/my-server/src/Foo.java" },
      ]));
    });
    mockSandboxPaths();
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    expect(io.logs[0]).toContain("my-server/src/Foo.java");
    expect(io.logs[0]).not.toContain("/d/");
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
    expect(io.logs[0]).toContain("my-server/src/Foo.java:5");
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
