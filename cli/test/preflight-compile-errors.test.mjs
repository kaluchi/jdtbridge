import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createServer } from "node:http";
import { setColorEnabled } from "../src/color.mjs";

function startServer(handler) {
  return new Promise((resolve) => {
    const server = createServer(handler);
    server.listen(0, "127.0.0.1", () =>
      resolve({ server, port: server.address().port }),
    );
  });
}

function stopServer(server) {
  return new Promise((resolve) => server.close(resolve));
}

function mockResolve(port) {
  vi.doMock("../src/resolve.mjs", () => ({
    resolveInstance: async () => ({
      port,
      token: null,
      pid: process.pid,
      workspace: "/test",
      host: "127.0.0.1",
      file: "",
    }),
  }));
}

function problem(file, line, message, severity = "error") {
  return {
    severity,
    message,
    location: { file, startLine: line, endLine: line },
  };
}

describe("preflightCompileErrors", () => {
  let server;
  let stderr;
  let stdout;
  const origErr = console.error;
  const origLog = console.log;

  beforeEach(() => {
    setColorEnabled(false);
    stderr = [];
    stdout = [];
    console.error = (...a) => stderr.push(a.join(" "));
    console.log = (...a) => stdout.push(a.join(" "));
  });

  afterEach(async () => {
    console.error = origErr;
    console.log = origLog;
    if (server) {
      await stopServer(server);
      server = null;
    }
    vi.resetModules();
  });

  function serve(problems) {
    return startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(problems));
    });
  }

  it("clean workspace → proceed, no output", async () => {
    let port;
    ({ server, port } = await serve([]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    const cleared = await preflightCompileErrors([]);
    expect(cleared).toBe(true);
    expect(stderr).toEqual([]);
    expect(stdout).toEqual([]);
  });

  it("only warnings → proceed (severity filter)", async () => {
    let port;
    ({ server, port } = await serve([
      problem("/x/Foo.java", 1, "unused import", "warning"),
      problem("/x/Bar.java", 2, "deprecated", "warning"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    expect(await preflightCompileErrors([])).toBe(true);
    expect(stderr).toEqual([]);
  });

  it("1 error, no flag → refuse, singular wording", async () => {
    let port;
    ({ server, port } = await serve([
      problem("/x/Foo.java", 42, "cannot find symbol Bar"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    expect(await preflightCompileErrors([])).toBe(false);
    const text = stderr.join("\n");
    expect(text).toContain("workspace has 1 compile error");
    expect(text).not.toContain("compile errors");
    expect(text).toContain("Foo.java:42");
    expect(text).toContain("cannot find symbol Bar");
    expect(text).toContain("--ignore-compile-errors");
    expect(text).not.toContain("showing");
  });

  it("3 errors, no flag → all listed, no truncation banner", async () => {
    let port;
    ({ server, port } = await serve([
      problem("/x/A.java", 1, "msg-A"),
      problem("/x/B.java", 2, "msg-B"),
      problem("/x/C.java", 3, "msg-C"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    expect(await preflightCompileErrors([])).toBe(false);
    const text = stderr.join("\n");
    expect(text).toContain("3 compile errors");
    expect(text).toContain("A.java:1");
    expect(text).toContain("B.java:2");
    expect(text).toContain("C.java:3");
    expect(text).not.toContain("showing");
  });

  it("137 errors → first 5 listed + truncation banner", async () => {
    const many = Array.from({ length: 137 }, (_, i) =>
      problem(`/x/F${i}.java`, i + 1, `m-${i}`));
    let port;
    ({ server, port } = await serve(many));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    expect(await preflightCompileErrors([])).toBe(false);
    const text = stderr.join("\n");
    expect(text).toContain("137 compile errors");
    expect(text).toContain("F0.java:1");
    expect(text).toContain("F4.java:5");
    expect(text).not.toContain("F5.java:6");
    expect(text).toContain("(showing 5 of 137");
    expect(text).toContain("jdt q '@problems | filter(/error)'");
  });

  it("errors + --ignore-compile-errors → bypass with dim warning", async () => {
    let port;
    ({ server, port } = await serve([
      problem("/x/Foo.java", 1, "boom"),
      problem("/x/Bar.java", 2, "boom"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    expect(
      await preflightCompileErrors(["--ignore-compile-errors"]),
    ).toBe(true);
    const text = stderr.join("\n");
    expect(text).toContain("--ignore-compile-errors");
    expect(text).toContain("workspace has 2 compile errors");
    expect(text).toContain("expect missing/empty results");
    expect(text).not.toContain("Refused");
    expect(stdout).toEqual([]);
  });

  it("errors + --json → JSON to stdout, no human text", async () => {
    let port;
    ({ server, port } = await serve([
      problem("/x/Foo.java", 7, "boom"),
      problem("/x/Bar.java", 9, "bang"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    expect(
      await preflightCompileErrors([], { json: true }),
    ).toBe(false);
    expect(stderr).toEqual([]);
    expect(stdout).toHaveLength(1);
    const payload = JSON.parse(stdout[0]);
    expect(payload).toMatchObject({
      error: "workspace-has-compile-errors",
      count: 2,
      hint: "pass --ignore-compile-errors to launch anyway",
    });
    expect(payload.markers).toHaveLength(2);
    expect(payload.markers[0]).toEqual({
      file: "/x/Foo.java",
      line: 7,
      message: "boom",
    });
  });

  it("errors + --ignore-compile-errors + --json → silent bypass", async () => {
    let port;
    ({ server, port } = await serve([
      problem("/x/Foo.java", 1, "boom"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    expect(
      await preflightCompileErrors(
        ["--ignore-compile-errors"],
        { json: true },
      ),
    ).toBe(true);
    expect(stderr).toEqual([]);
    expect(stdout).toEqual([]);
  });

  it("file under cwd renders as relative path", async () => {
    const cwd = process.cwd().replace(/\\/g, "/");
    let port;
    ({ server, port } = await serve([
      problem(`${cwd}/sub/dir/Foo.java`, 5, "msg"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    await preflightCompileErrors([]);
    const text = stderr.join("\n");
    expect(text).toContain("sub/dir/Foo.java:5");
    expect(text).not.toContain(cwd + "/sub");
  });

  it("file outside cwd kept as absolute path", async () => {
    let port;
    ({ server, port } = await serve([
      problem("/elsewhere/Foo.java", 1, "msg"),
    ]));
    mockResolve(port);
    const { preflightCompileErrors } = await import(
      "../src/preflight-compile-errors.mjs"
    );
    await preflightCompileErrors([]);
    const text = stderr.join("\n");
    expect(text).toContain("/elsewhere/Foo.java:1");
  });
});
