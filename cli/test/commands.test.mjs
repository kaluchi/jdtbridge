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

describe("commands (integration)", () => {
  let server, port, io;

  beforeEach(() => {
    setColorEnabled(false);
    io = captureConsole();
  });

  afterEach(async () => {
    io.restore();
    if (server) await stopServer(server);
    vi.resetModules();
  });

  async function setupMock(handler) {
    ({ server, port } = await startServer(handler));
    vi.doMock("../src/discovery.mjs", () => ({
      discoverInstances: () => [],
      findInstance: () => ({ port, token: null, pid: process.pid, workspace: "/test" }),
      isPidAlive: () => true,
    }));
  }

  it("projects lists project names", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(["m8-server", "m8-client"]));
    });
    const { projects } = await import("../src/commands/projects.mjs");
    await projects([]);
    expect(io.logs).toEqual(["m8-server", "m8-client"]);
  });

  it("find shows FQN and file", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "app.m8.Foo", file: "/m8-server/src/main/java/app/m8/Foo.java" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo"]);
    expect(io.logs[0]).toContain("app.m8.Foo");
    expect(io.logs[0]).toContain("m8-server/src/main/java/app/m8/Foo.java");
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
        { fqn: "app.m8.FooImpl", file: "/m8-server/src/FooImpl.java" },
      ]));
    });
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["app.m8.Foo"]);
    expect(io.logs[0]).toContain("app.m8.FooImpl");
  });

  it("hierarchy shows all sections", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        supers: [{ fqn: "java.lang.Object", binary: true }],
        interfaces: [{ fqn: "app.m8.HasId", binary: false, file: "/m8-shared/src/HasId.java" }],
        subtypes: [],
      }));
    });
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["app.m8.Foo"]);
    expect(io.logs.some((l) => l.includes("Superclasses"))).toBe(true);
    expect(io.logs.some((l) => l.includes("java.lang.Object"))).toBe(true);
    expect(io.logs.some((l) => l.includes("Interfaces"))).toBe(true);
    expect(io.logs.some((l) => l.includes("app.m8.HasId"))).toBe(true);
  });

  it("implementors shows FQN, file, and line", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "app.m8.FooImpl", file: "/m8-server/src/FooImpl.java", line: 25 },
      ]));
    });
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["app.m8.Foo", "doStuff"]);
    expect(io.logs[0]).toContain("app.m8.FooImpl");
    expect(io.logs[0]).toContain(":25");
  });

  it("errors shows formatted error lines", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { severity: "ERROR", source: "JDT", file: "/m8-server/src/Foo.java", line: 10, message: "cannot resolve symbol" },
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
    await organizeImports(["m8-server/src/Foo.java"]);
    expect(io.logs[0]).toBe("Imports: +2 -1");
  });

  it("format shows Formatted on modification", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ modified: true }));
    });
    const { format } = await import("../src/commands/refactoring.mjs");
    await format(["m8-server/src/Foo.java"]);
    expect(io.logs[0]).toBe("Formatted");
  });

  it("rename shows Renamed with warnings", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, warnings: ["shadow detected"] }));
    });
    const { rename } = await import("../src/commands/refactoring.mjs");
    await rename(["app.m8.Foo", "Bar", "--method", "old"]);
    expect(io.logs[0]).toBe("Renamed");
    expect(io.logs[1]).toContain("shadow detected");
  });

  it("move shows Moved", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
    });
    const { move } = await import("../src/commands/refactoring.mjs");
    await move(["app.m8.Foo", "app.m8.bar"]);
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
    expect(io.logs[0]).toBe("D:/projects/src/Foo.java");
    expect(io.logs[1]).toBe("D:/projects/src/Bar.java");
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
    await open(["app.m8.Foo"]);
    expect(io.logs[0]).toBe("Opened");
  });

  it("test shows summary and failures", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        total: 3, passed: 2, failed: 1, errors: 0, ignored: 0, time: 1.5,
        failures: [{ status: "FAILURE", class: "app.FooTest", method: "testBar", trace: "AssertionError\n  at ..." }],
      }));
    });
    const { test } = await import("../src/commands/test.mjs");
    await test(["app.FooTest"]);
    expect(io.logs[0]).toContain("3 tests");
    expect(io.logs[0]).toContain("1 failed");
    expect(io.logs.some((l) => l.includes("app.FooTest.testBar"))).toBe(true);
  });

  it("build shows success with 0 errors", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ errors: 0 }));
    });
    const { build } = await import("../src/commands/build.mjs");
    await build(["--project", "m8-client"]);
    expect(io.logs[0]).toBe("Build complete (0 errors)");
  });

  it("build exits 1 on compilation errors", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ errors: 3 }));
    });
    const { build } = await import("../src/commands/build.mjs");
    await expect(build(["--project", "m8-client"])).rejects.toThrow("exit(1)");
    expect(io.logs[0]).toBe("Build complete (3 errors)");
  });

  it("build with --clean flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("clean");
      expect(req.url).toContain("project=m8-client");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ errors: 0 }));
    });
    const { build } = await import("../src/commands/build.mjs");
    await build(["--project", "m8-client", "--clean"]);
    expect(io.logs[0]).toBe("Build complete (0 errors)");
  });

  it("source prints file header and body", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, {
        "Content-Type": "text/plain",
        "X-File": "/m8-server/src/Foo.java",
        "X-Start-Line": "5",
        "X-End-Line": "15",
      });
      res.end("public class Foo {\n}\n");
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["app.m8.Foo"]);
    expect(io.logs[0]).toBe("m8-server/src/Foo.java:5-15");
    expect(io.logs[1]).toContain("public class Foo");
  });

  it("type-info shows class details", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        kind: "class", fqn: "app.m8.Foo", file: "/m8-server/src/Foo.java",
        superclass: "java.lang.Object", interfaces: ["app.m8.HasId"],
        fields: [{ modifiers: "private", type: "String", name: "id", line: 5 }],
        methods: [{ signature: "String getId()", line: 7 }],
      }));
    });
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await typeInfo(["app.m8.Foo"]);
    expect(io.logs[0]).toContain("class app.m8.Foo");
    expect(io.logs.some((l) => l.includes("extends java.lang.Object"))).toBe(true);
    expect(io.logs.some((l) => l.includes("implements app.m8.HasId"))).toBe(true);
    expect(io.logs.some((l) => l.includes("String id"))).toBe(true);
    expect(io.logs.some((l) => l.includes("String getId()"))).toBe(true);
  });

  it("project-info shows formatted output", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "test-proj", location: "/ws/test-proj",
        natures: ["org.eclipse.jdt.core.javanature"],
        dependencies: ["m8-core"], totalTypes: 2, membersIncluded: false,
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
        { file: "/m8-server/src/Bar.java", line: 10, in: "Bar.init()", content: "new Foo();" },
        { file: "/m8-server/src/Bar.java", line: 20, in: "Bar.run()", content: "foo.go();" },
      ]));
    });
    const { references } = await import("../src/commands/references.mjs");
    await references(["app.m8.Foo"]);
    expect(io.logs[0]).toBe("m8-server/src/Bar.java:10");
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
    await expect(rename(["app.m8.Foo"])).rejects.toThrow("exit(1)");
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
    await expect(implementors(["app.m8.Foo"])).rejects.toThrow("exit(1)");
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
    await expect(move(["app.m8.Foo"])).rejects.toThrow("exit(1)");
  });

  it("open exits on missing args", async () => {
    await setupMock((req, res) => res.end());
    const { open } = await import("../src/commands/editor.mjs");
    await expect(open([])).rejects.toThrow("exit(1)");
  });

  it("test exits on missing args (no FQN, no --project)", async () => {
    await setupMock((req, res) => res.end());
    const { test } = await import("../src/commands/test.mjs");
    await expect(test([])).rejects.toThrow("exit(1)");
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
    await expect(projects([])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("find exits on server error", async () => {
    await setupMock(errorServer());
    const { find } = await import("../src/commands/find.mjs");
    await expect(find(["Foo"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("subtypes exits on server error", async () => {
    await setupMock(errorServer());
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await expect(subtypes(["app.Foo"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("hierarchy exits on server error", async () => {
    await setupMock(errorServer());
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await expect(hierarchy(["app.Foo"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("implementors exits on server error", async () => {
    await setupMock(errorServer());
    const { implementors } = await import("../src/commands/implementors.mjs");
    await expect(implementors(["app.Foo", "m"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("type-info exits on server error", async () => {
    await setupMock(errorServer());
    const { typeInfo } = await import("../src/commands/type-info.mjs");
    await expect(typeInfo(["app.Foo"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("errors exits on server error", async () => {
    await setupMock(errorServer());
    const { errors } = await import("../src/commands/errors.mjs");
    await expect(errors([])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("build exits on server error", async () => {
    await setupMock(errorServer());
    const { build } = await import("../src/commands/build.mjs");
    await expect(build(["--project", "m8-client"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("project-info exits on server error", async () => {
    await setupMock(errorServer());
    const { projectInfo } = await import("../src/commands/project-info.mjs");
    await expect(projectInfo(["proj"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("organize-imports exits on server error", async () => {
    await setupMock(errorServer());
    const { organizeImports } = await import("../src/commands/refactoring.mjs");
    await expect(organizeImports(["f.java"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("format exits on server error", async () => {
    await setupMock(errorServer());
    const { format } = await import("../src/commands/refactoring.mjs");
    await expect(format(["f.java"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("rename exits on server error", async () => {
    await setupMock(errorServer());
    const { rename } = await import("../src/commands/refactoring.mjs");
    await expect(rename(["app.Foo", "Bar"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("move exits on server error", async () => {
    await setupMock(errorServer());
    const { move } = await import("../src/commands/refactoring.mjs");
    await expect(move(["app.Foo", "app.bar"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  // ---- launch ----

  it("launch list shows launches", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "m8-server", type: "Java Application", mode: "run", terminated: false, pid: "12345" },
        { name: "ObjectMapperTest", type: "JUnit", mode: "run", terminated: true, exitCode: 0 },
      ]));
    });
    const { launchList } = await import("../src/commands/launch.mjs");
    await launchList();
    expect(io.logs[0]).toContain("m8-server");
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

  it("launch console shows output", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "m8-server",
        terminated: false,
        output: "Server started on port 8080\nReady.\n",
      }));
    });
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["m8-server"]);
    expect(io.logs[0]).toContain("Server started");
  });

  it("launch console with tail", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("tail=10");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ name: "m8-server", terminated: false, output: "last line\n" }));
    });
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await launchConsole(["m8-server", "--tail", "10"]);
    expect(io.logs[0]).toContain("last line");
  });

  it("launch configs shows configurations", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "m8-server", type: "Java Application" },
        { name: "AllTests", type: "JUnit" },
      ]));
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs();
    expect(io.logs[0]).toContain("m8-server");
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

  it("launch run shows launched", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("name=m8-server");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "m8-server", mode: "run" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["m8-server"]);
    expect(io.logs[0]).toContain("Launched");
    expect(io.logs[0]).toContain("m8-server");
  });

  it("launch run with debug flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("debug");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "m8-server", mode: "debug" }));
    });
    const { launchRun } = await import("../src/commands/launch.mjs");
    await launchRun(["m8-server", "--debug"]);
    expect(io.logs[0]).toContain("debug");
  });

  it("launch run missing name exits", async () => {
    const { launchRun } = await import("../src/commands/launch.mjs");
    await expect(launchRun([])).rejects.toThrow("exit(1)");
  });

  it("launch stop shows stopped", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "m8-server" }));
    });
    const { launchStop } = await import("../src/commands/launch.mjs");
    await launchStop(["m8-server"]);
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

  it("launch console error exits", async () => {
    await setupMock(errorServer());
    const { launchConsole } = await import("../src/commands/launch.mjs");
    await expect(launchConsole(["m8-server"])).rejects.toThrow("exit(1)");
  });

  it("launch list error exits", async () => {
    await setupMock(errorServer());
    const { launchList } = await import("../src/commands/launch.mjs");
    await expect(launchList()).rejects.toThrow("exit(1)");
  });

  it("editors exits on server error", async () => {
    await setupMock(errorServer());
    const { editors } = await import("../src/commands/editor.mjs");
    await expect(editors()).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("open exits on server error", async () => {
    await setupMock(errorServer());
    const { open } = await import("../src/commands/editor.mjs");
    await expect(open(["app.Foo"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("references exits on server error", async () => {
    await setupMock(errorServer());
    const { references } = await import("../src/commands/references.mjs");
    await expect(references(["app.Foo"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("Something went wrong");
  });

  it("test exits on server error", async () => {
    await setupMock(errorServer());
    const { test } = await import("../src/commands/test.mjs");
    await expect(test(["app.FooTest"])).rejects.toThrow("exit(1)");
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
        subtypes: [{ fqn: "app.Sub", binary: false, file: "/m8/src/Sub.java" }],
      }));
    });
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["app.Foo"]);
    expect(io.logs.some((l) => l.includes("Subtypes"))).toBe(true);
    expect(io.logs.some((l) => l.includes("app.Sub"))).toBe(true);
  });

  it("source handles multiple overloads (startLine=-1)", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, {
        "Content-Type": "text/plain",
        "X-File": "/m8-server/src/Foo.java",
        "X-Start-Line": "-1",
        "X-End-Line": "-1",
      });
      res.end(":10-15\npublic void a() {}\n:20-25\npublic void a(int x) {}\n");
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["app.Foo", "a"]);
    expect(io.logs[0]).toContain("m8-server/src/Foo.java:10-15");
  });

  it("type-info shows interface without fields", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        kind: "interface", fqn: "app.HasId", file: "/m8/src/HasId.java",
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
        { severity: "WARNING", source: null, file: "/m8/src/A.java", line: 1, message: "unused" },
      ]));
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors(["--warnings"]);
    expect(io.logs[0]).toContain("WARN");
  });

  it("test with --project flag", async () => {
    await setupMock((req, res) => {
      expect(req.url).toContain("project=m8-server");
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ total: 10, passed: 10, failed: 0, errors: 0, ignored: 0, time: 5.0, failures: [] }));
    });
    const { test } = await import("../src/commands/test.mjs");
    await test(["--project", "m8-server"]);
    expect(io.logs[0]).toContain("10 tests");
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
});
