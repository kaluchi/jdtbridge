import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { startServer, stopServer, captureConsole, disableColor } from "./helpers/mock-server.mjs";
import { toSandboxPath } from "../src/paths.mjs";

describe("source and search commands", () => {
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
});
