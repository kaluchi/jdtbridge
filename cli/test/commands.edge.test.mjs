import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { startServer, stopServer, captureConsole, disableColor } from "./helpers/mock-server.mjs";

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

  it("implementors shows no implementors message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["app.Foo"]);
    expect(io.logs[0]).toBe("(no implementors)");
  });

  it("implementors with method shows no implementors message", async () => {
    await setupMock((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    });
    const { implementors } = await import("../src/commands/implementors.mjs");
    await implementors(["app.Foo#m"]);
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
    const { problems } = await import("../src/commands/problems.mjs");
    await problems(["--warnings"]);
    expect(io.logs[0]).toContain("WARN");
  });

  // source tests → commands.source.test.mjs
});
