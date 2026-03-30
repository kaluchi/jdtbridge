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

describe("source format", () => {
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

  // ---- Mock data ----

  const METHOD_RESPONSE = {
    fqmn: "pkg.Foo#bar()",
    file: "D:/src/Foo.java",
    startLine: 10,
    endLine: 15,
    source: "public void bar() { baz(); }",
    overrideTarget: { fqmn: "pkg.IFoo#bar()", kind: "method", typeKind: "interface" },
    refs: [
      { fqmn: "pkg.Foo#baz()", direction: "outgoing", kind: "method", typeKind: "class", scope: "class", type: "void" },
      { fqmn: "pkg.Util#helper(String)", direction: "outgoing", kind: "method", typeKind: "class", scope: "project",
        type: "List", returnTypeFqn: "java.util.List", returnTypeKind: "class", static: true, doc: "Helps." },
      { fqmn: "pkg.IService#run()", direction: "outgoing", kind: "method", typeKind: "interface", scope: "project", type: "void" },
      { fqmn: "pkg.ServiceImpl#run()", direction: "outgoing", kind: "method", typeKind: "class", scope: "project",
        implementationOf: "pkg.IService#run()" },
      { fqmn: "pkg.Base#getId()", direction: "outgoing", kind: "method", typeKind: "class", scope: "project",
        type: "int", inherited: true, inheritedFrom: "pkg.Base" },
      { fqmn: "pkg.Constants#MAX", direction: "outgoing", kind: "constant", typeKind: "class", scope: "project",
        type: "int", static: true },
      { fqmn: "org.slf4j.Logger", direction: "outgoing", kind: "type", typeKind: "interface", scope: "dependency" },
      { fqmn: "org.slf4j.Logger#info(String)", direction: "outgoing", kind: "method", typeKind: "interface",
        scope: "dependency", type: "void", doc: "Log info message." },
      { fqmn: "pkg.Caller#test()", direction: "incoming", kind: "method", typeKind: "class", scope: "project",
        file: "D:/src/Caller.java", line: 42 },
      { fqmn: "pkg.OtherTest#verify()", direction: "incoming", kind: "method", typeKind: "class", scope: "project",
        file: "D:/src/OtherTest.java", line: 18 },
    ],
  };

  const TYPE_RESPONSE = {
    fqmn: "pkg.Animal",
    file: "D:/src/Animal.java",
    startLine: 1,
    endLine: 5,
    source: "public interface Animal { String name(); }",
    supertypes: [],
    subtypes: [
      { fqn: "pkg.Dog", kind: "class" },
      { fqn: "pkg.Cat", kind: "class" },
    ],
  };

  const NESTED_TYPE_RESPONSE = {
    fqmn: "pkg.Outer.Inner",
    file: "D:/src/Outer.java",
    startLine: 10,
    endLine: 15,
    source: "public class Inner {}",
    supertypes: [],
    subtypes: [],
    enclosingType: { fqn: "pkg.Outer", kind: "class" },
  };

  // ---- Header tests ----

  it("method header has [M] badge", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("#### [M] pkg.Foo#bar()");
  });

  it("class header has [C] badge", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(TYPE_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Animal"]);
    const out = io.logs.join("\n");
    expect(out).toContain("#### [C] pkg.Animal");
  });

  // ---- Override target ----

  it("shows override target with [M] badge", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("overrides [M] `pkg.IFoo#bar()`");
  });

  it("no override line when absent", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ...METHOD_RESPONSE, overrideTarget: undefined }));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).not.toContain("overrides");
  });

  // ---- Outgoing calls grouping ----

  it("outgoing calls — no redundant type headers when members present", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("#### Outgoing Calls:");
    // Members visible by FQMN — no separate type headers
    expect(out).toContain("[M] `pkg.Foo#baz()`");
    expect(out).toContain("[M] `pkg.Util#helper(String)`");
    // Self-reference type ref filtered
    expect(out).not.toMatch(/\[C\] `pkg\.Foo`\n/);
  });

  // ---- Badges ----

  it("method ref has [M] badge", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("[M] `pkg.Foo#baz()`");
  });

  it("constant ref has [K] badge", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("[K] `pkg.Constants#MAX`");
  });

  it("type header suppressed when group has members", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    // Logger type header suppressed — method ref sufficient
    expect(out).not.toContain("[I] `org.slf4j.Logger`\n");
    expect(out).toContain("`org.slf4j.Logger#info(String)`");
  });

  // ---- Return types ----

  it("return type FQN in backticks", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("→ [C] `java.util.List`");
  });

  it("void return type in backticks", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("→ `void`");
  });

  // ---- Annotations ----

  it("static annotation shown", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("(static)");
  });

  it("inherited annotation shown", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("(inherited)");
  });

  // ---- Javadoc inline ----

  it("javadoc after dash", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("— Helps.");
  });

  // ---- Implementations inline ----

  it("implementation shown after interface method", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    // Interface method
    expect(out).toContain("[M] `pkg.IService#run()`");
    // Implementation indented with →
    expect(out).toContain("→ [M] `pkg.ServiceImpl#run()`");
  });

  it("implementation not in main list", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    // ServiceImpl should only appear as → impl, not as a standalone group header
    const lines = out.split("\n");
    const implLines = lines.filter(l => l.includes("ServiceImpl"));
    expect(implLines.length).toBe(1);
    expect(implLines[0].trim()).toMatch(/^→/);
  });

  // ---- Incoming calls ----

  it("incoming calls section shown", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("#### Incoming Calls:");
    expect(out).toContain("`pkg.Caller#test()`");
    expect(out).toContain("`pkg.OtherTest#verify()`");
  });

  it("incoming calls have no line numbers — FQMNs only", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    // Line numbers are noise for incoming calls — navigable by FQMN
    expect(out).not.toContain(":42");
    expect(out).not.toContain(":18");
    expect(out).toContain("`pkg.Caller#test()`");
    expect(out).toContain("`pkg.OtherTest#verify()`");
  });

  // ---- Type-level hierarchy ----

  it("type-level shows hierarchy not refs", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(TYPE_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Animal"]);
    const out = io.logs.join("\n");
    expect(out).toContain("#### Hierarchy:");
    expect(out).not.toContain("Outgoing Calls:");
    expect(out).not.toContain("Incoming Calls:");
  });

  it("subtypes shown as markdown list", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(TYPE_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Animal"]);
    const out = io.logs.join("\n");
    expect(out).toContain("[C] `pkg.Dog`");
    expect(out).toContain("[C] `pkg.Cat`");
  });

  it("enclosing type shown", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(NESTED_TYPE_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Outer.Inner"]);
    const out = io.logs.join("\n");
    expect(out).toContain("#### Enclosing Type:");
    expect(out).toContain("`pkg.Outer`");
  });

  it("no hierarchy section when empty", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ...TYPE_RESPONSE, supertypes: [], subtypes: [] }));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Animal"]);
    const out = io.logs.join("\n");
    expect(out).not.toContain("Hierarchy:");
  });

  // ---- --json flag ----

  it("--json outputs raw JSON", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar", "--json"]);
    const out = io.logs.join("\n");
    const parsed = JSON.parse(out);
    expect(parsed.fqmn).toBe("pkg.Foo#bar()");
    expect(parsed.refs).toBeInstanceOf(Array);
  });

  it("--json does not show markdown", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar", "--json"]);
    const out = io.logs.join("\n");
    expect(out).not.toContain("####");
    expect(out).not.toContain("Outgoing Calls:");
  });

  it("file path converted in sandbox (Linux)", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(METHOD_RESPONSE));
    });
    mockSandboxPaths();
    const { source } = await import("../src/commands/source.mjs");
    await source(["pkg.Foo#bar"]);
    const out = io.logs.join("\n");
    expect(out).toContain("/d/src/Foo.java:10-15");
  });
});
