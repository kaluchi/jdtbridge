import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  startServer, stopServer, captureConsole, errorServer, parseJsonOutput, disableColor,
} from "./helpers/mock-server.mjs";
import { toSandboxPath } from "../src/paths.mjs";

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

  it("find --json outputs valid JSON with key fields", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "/my-server/src/Foo.java", kind: "class" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data).toHaveLength(1);
    expect(data[0].fqn).toBe("com.example.Foo");
    expect(data[0].kind).toBe("class");
  });

  it("find --json deduplicates binary types", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "javax.swing.JPanel", binary: true, origin: "rt.jar" },
        { fqn: "javax.swing.JPanel", binary: true, origin: "rt.jar" },
      ]));
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["JPanel", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toHaveLength(1);
  });

  it("find --json returns [] for no results", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { find } = await import("../src/commands/find.mjs");
    await find(["NonExistent", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("find --json returns error object on server error", async () => {
    await setupMock(errorServer());
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.error).toBe("Something went wrong");
  });

  it("find --json remaps paths in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.Foo", file: "D:/project/src/Foo.java", kind: "class" },
      ]));
    });
    mockSandboxPaths();
    const { find } = await import("../src/commands/find.mjs");
    await find(["Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data[0].file).toBe("/d/project/src/Foo.java");
  });

  it("references --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "/my-server/src/Bar.java", line: 10, in: "Bar.init()", content: "new Foo();" },
      ]));
    });
    const { references } = await import("../src/commands/references.mjs");
    await references(["com.example.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].file).toBe("/my-server/src/Bar.java");
    expect(data[0].line).toBe(10);
  });

  it("references --json returns [] for no results", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { references } = await import("../src/commands/references.mjs");
    await references(["com.example.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("subtypes --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.FooImpl", file: "/my-server/src/FooImpl.java" },
      ]));
    });
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["com.example.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].fqn).toBe("com.example.FooImpl");
  });

  it("subtypes --json returns [] for no results", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { subtypes } = await import("../src/commands/subtypes.mjs");
    await subtypes(["com.example.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("hierarchy --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        fqn: "com.example.Foo",
        supertypes: [{ fqn: "com.example.Base", kind: "class", depth: 0 }],
        subtypes: [{ fqn: "com.example.Bar", kind: "class", depth: 0 }],
      }));
    });
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["com.example.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.fqn).toBe("com.example.Foo");
    expect(data.supertypes).toBeInstanceOf(Array);
    expect(data.subtypes).toBeInstanceOf(Array);
  });

  it("hierarchy --json returns error on server error", async () => {
    await setupMock(errorServer());
    const { hierarchy } = await import("../src/commands/hierarchy.mjs");
    await hierarchy(["app.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.error).toBe("Something went wrong");
  });

  it("implementors --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { fqn: "com.example.FooImpl", file: "/my-server/src/FooImpl.java", line: 25 },
      ]));
    });
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["com.example.Foo#doStuff", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].fqn).toBe("com.example.FooImpl");
    expect(data[0].line).toBe(25);
  });

  it("implementors --json returns [] for no results", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["com.example.Foo#doStuff", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("type-info --json outputs valid JSON", async () => {
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
    await typeInfo(["com.example.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.fqn).toBe("com.example.Foo");
    expect(data.kind).toBe("class");
    expect(data.fields).toBeInstanceOf(Array);
    expect(data.methods).toBeInstanceOf(Array);
    expect(data.interfaces).toContain("com.example.HasId");
  });

  it("errors --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { severity: "ERROR", source: "JDT", file: "D:/projects/my-server/src/Foo.java", line: 10, message: "cannot resolve symbol" },
      ]));
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].severity).toBe("ERROR");
    expect(data[0].message).toBe("cannot resolve symbol");
    expect(data[0].line).toBe(10);
    expect(data[0].file).toBe(toSandboxPath("D:/projects/my-server/src/Foo.java"));
  });

  it("errors --json returns [] for no errors", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("projects --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", location: "D:/projects/my-server", repo: "D:/projects" },
      ]));
    });
    const { projects } = await import("../src/commands/projects.mjs");
    await projects(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].name).toBe("my-server");
  });

  it("projects --json returns [] for no projects", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { projects } = await import("../src/commands/projects.mjs");
    await projects(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("projects --json remaps paths in sandbox", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", location: "D:/projects/my-server", repo: "D:/projects" },
      ]));
    });
    mockSandboxPaths();
    const { projects } = await import("../src/commands/projects.mjs");
    await projects(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data[0].location).toBe("/d/projects/my-server");
  });

  it("editors --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { file: "D:/projects/src/Foo.java", fqn: "com.example.Foo", project: "my-server", active: true },
        { file: "D:/projects/src/Bar.java", fqn: "com.example.Bar", project: "my-server" },
      ]));
    });
    const { editors } = await import("../src/commands/editor.mjs");
    await editors(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data).toHaveLength(2);
    expect(data[0].fqn).toBe("com.example.Foo");
    expect(data[0].active).toBe(true);
  });

  it("editors --json returns [] for no editors", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { editors } = await import("../src/commands/editor.mjs");
    await editors(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toEqual([]);
  });

  it("test sessions --json outputs valid JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { session: "test-123", label: "MyTest", total: 5, passed: 4, failed: 1, time: 2.5, state: "finished" },
      ]));
    });
    const { testSessions } = await import("../src/commands/test-sessions.mjs");
    await testSessions(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data[0].session).toBe("test-123");
    expect(data[0].total).toBe(5);
    expect(data[0].passed).toBe(4);
  });

  it("test sessions --json returns [] for no sessions", async () => {
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

  it("source --json outputs valid JSON", async () => {
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
    const { source } = await import("../src/commands/source.mjs");
    await source(["com.example.Foo", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.fqmn).toBe("com.example.Foo");
    expect(data.source).toBe("public class Foo {}");
  });

  // ---- Tier 2 --json ----

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
        { name: "my-server", type: "Java Application", project: "m8-server", mainClass: "app.m8.Main" },
        { name: "jdtbridge-verify", type: "Maven Build", goals: "clean verify" },
        { name: "AllTests", type: "JUnit", project: "m8-server", class: "app.m8.AllTests", runner: "JUnit 5" },
      ]));
    });
    const { launchConfigs } = await import("../src/commands/launch.mjs");
    await launchConfigs(["--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data).toBeInstanceOf(Array);
    expect(data).toHaveLength(3);
    expect(data[0].project).toBe("m8-server");
    expect(data[0].mainClass).toBe("app.m8.Main");
    expect(data[1].goals).toBe("clean verify");
    expect(data[2].runner).toBe("JUnit 5");
  });

  it("project-info --json outputs raw server response", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        name: "test-proj", location: "/ws/test-proj",
        natures: ["org.eclipse.jdt.core.javanature"],
        dependencies: ["my-core"], totalTypes: 2,
      }));
    });
    const { projectInfo } = await import("../src/commands/project-info.mjs");
    await projectInfo(["test-proj", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.name).toBe("test-proj");
    expect(data.totalTypes).toBe(2);
    expect(data.natures).toContain("org.eclipse.jdt.core.javanature");
  });

  it("project-info --json returns error on server error", async () => {
    await setupMock(errorServer());
    const { projectInfo } = await import("../src/commands/project-info.mjs");
    await projectInfo(["proj", "--json"]);
    const data = parseJsonOutput(io.logs);
    expect(data.error).toBe("Something went wrong");
  });

  it("git --json outputs structured repo data", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { name: "my-server", location: "D:/projects/my-server", repo: "D:/projects", branch: "main" },
      ]));
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

  // ---- --json does not break normal output ----

  it("find without --json still shows table", async () => {
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
    expect(out).toContain("[C]");
    expect(() => JSON.parse(out)).toThrow(); // not JSON
  });

  it("errors without --json still shows colored output", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify([
        { severity: "ERROR", source: "JDT", file: "D:/projects/my-server/src/Foo.java", line: 10, message: "bad" },
      ]));
    });
    const { errors } = await import("../src/commands/errors.mjs");
    await errors([]);
    expect(io.logs[0]).toContain("ERROR");
    expect(io.logs[0]).toContain("bad");
    expect(() => JSON.parse(io.logs.join("\n"))).toThrow(); // not JSON
  });
});
