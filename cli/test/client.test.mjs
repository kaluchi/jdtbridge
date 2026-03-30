import { describe, it, expect, vi, afterEach } from "vitest";
import { createServer } from "node:http";

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

function mockDiscovery(port, token = null) {
  vi.doMock("../src/bridge-env.mjs", () => ({
    getPinnedBridge: () => null,
  }));
  vi.doMock("../src/discovery.mjs", () => ({
    discoverInstances: async () => [],
    findInstance: async () => ({
      port,
      token,
      pid: process.pid,
      workspace: "/test",
      host: "127.0.0.1",
    }),
  }));
}

describe("client", () => {
  let server;

  afterEach(async () => {
    if (server) {
      await stopServer(server);
      server = null;
    }
    vi.resetModules();
  });

  // --- get() ---

  it("get() parses JSON response", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(["my-server", "my-client"]));
    }));
    mockDiscovery(port);
    const { get } = await import("../src/client.mjs");
    const result = await get("/projects");
    expect(result).toEqual(["my-server", "my-client"]);
  });

  it("get() rejects on non-200 status", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(500);
      res.end("Internal Server Error");
    }));
    mockDiscovery(port);
    const { get } = await import("../src/client.mjs");
    await expect(get("/fail")).rejects.toThrow("HTTP 500");
  });

  it("get() rejects on invalid JSON", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("not json{{{");
    }));
    mockDiscovery(port);
    const { get } = await import("../src/client.mjs");
    await expect(get("/bad")).rejects.toThrow("Invalid JSON");
  });

  it("get() tolerates NaN in JSON response", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"total":0,"passed":0,"failed":0,"errors":0,"ignored":0,"time":NaN,"failures":[]}');
    }));
    mockDiscovery(port);
    const { get } = await import("../src/client.mjs");
    const result = await get("/test");
    expect(result.time).toBeNull();
  });

  it("get() sends Bearer token when present", async () => {
    let receivedAuth;
    let port;
    ({ server, port } = await startServer((req, res) => {
      receivedAuth = req.headers.authorization;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    }));
    mockDiscovery(port, "secret123");
    const { get } = await import("../src/client.mjs");
    await get("/projects");
    expect(receivedAuth).toBe("Bearer secret123");
  });

  it("get() sends no auth header when token is null", async () => {
    let receivedAuth;
    let port;
    ({ server, port } = await startServer((req, res) => {
      receivedAuth = req.headers.authorization;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    }));
    mockDiscovery(port, null);
    const { get } = await import("../src/client.mjs");
    await get("/projects");
    expect(receivedAuth).toBeUndefined();
  });

  it("get() rejects on timeout", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      // Never respond — let it time out
    }));
    mockDiscovery(port);
    const { get } = await import("../src/client.mjs");
    await expect(get("/slow", 100)).rejects.toThrow("timed out");
  });

  it("get() rejects on connection refused", async () => {
    mockDiscovery(1); // port 1 — nothing listening
    const { get, isConnectionError } = await import("../src/client.mjs");
    try {
      await get("/fail");
      expect.unreachable("should have rejected");
    } catch (e) {
      expect(isConnectionError(e)).toBe(true);
    }
  });

  // --- getRaw() ---

  it("getRaw() returns headers and body for text/plain", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, {
        "Content-Type": "text/plain",
        "X-File": "/my-server/src/Foo.java",
        "X-Start-Line": "10",
        "X-End-Line": "20",
      });
      res.end("public class Foo {}");
    }));
    mockDiscovery(port);
    const { getRaw } = await import("../src/client.mjs");
    const result = await getRaw("/source?class=Foo");
    expect(result.body).toBe("public class Foo {}");
    expect(result.headers["x-file"]).toBe("/my-server/src/Foo.java");
    expect(result.headers["x-start-line"]).toBe("10");
  });

  it("getRaw() resolves for valid JSON without error field", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ data: "ok" }));
    }));
    mockDiscovery(port);
    const { getRaw } = await import("../src/client.mjs");
    const result = await getRaw("/test");
    expect(result.body).toContain("ok");
  });

  it("getRaw() rejects on JSON error field", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Type not found" }));
    }));
    mockDiscovery(port);
    const { getRaw } = await import("../src/client.mjs");
    await expect(getRaw("/source?class=Missing")).rejects.toThrow(
      "Type not found",
    );
  });

  it("getRaw() rejects on invalid JSON with json content-type", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("broken{{{");
    }));
    mockDiscovery(port);
    const { getRaw } = await import("../src/client.mjs");
    await expect(getRaw("/bad")).rejects.toThrow("Invalid JSON");
  });

  it("getRaw() tolerates NaN in JSON content", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end('{"time":NaN}');
    }));
    mockDiscovery(port);
    const { getRaw } = await import("../src/client.mjs");
    const result = await getRaw("/test");
    expect(result.body).toContain('"time":NaN');
  });

  it("getRaw() rejects on non-200", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(404);
      res.end("Not Found");
    }));
    mockDiscovery(port);
    const { getRaw } = await import("../src/client.mjs");
    await expect(getRaw("/missing")).rejects.toThrow("HTTP 404");
  });

  it("getRaw() rejects on timeout", async () => {
    let port;
    ({ server, port } = await startServer(() => {}));
    mockDiscovery(port);
    const { getRaw } = await import("../src/client.mjs");
    await expect(getRaw("/slow", 100)).rejects.toThrow("timed out");
  });

  // --- isConnectionError() ---

  it("isConnectionError detects ECONNREFUSED", async () => {
    const { isConnectionError } = await import("../src/client.mjs");
    const err = new Error("fail");
    err.code = "ECONNREFUSED";
    expect(isConnectionError(err)).toBe(true);
  });

  it("isConnectionError detects ECONNRESET", async () => {
    const { isConnectionError } = await import("../src/client.mjs");
    const err = new Error("fail");
    err.code = "ECONNRESET";
    expect(isConnectionError(err)).toBe(true);
  });

  it("isConnectionError returns false for other errors", async () => {
    const { isConnectionError } = await import("../src/client.mjs");
    expect(isConnectionError(new Error("generic"))).toBe(false);
  });

  // --- getStreamLines() ---

  it("getStreamLines calls onLine for each JSONL line", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/x-ndjson" });
      res.write('{"a":1}\n');
      res.write('{"b":2}\n');
      res.end();
    }));
    mockDiscovery(port);
    const { getStreamLines } = await import("../src/client.mjs");
    const lines = [];
    await getStreamLines("/test", (line) => lines.push(line));
    expect(lines).toEqual(['{"a":1}', '{"b":2}']);
  });

  it("getStreamLines handles partial chunks", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/x-ndjson" });
      // Send a line split across two chunks
      res.write('{"split":');
      res.write('"value"}\n');
      res.end();
    }));
    mockDiscovery(port);
    const { getStreamLines } = await import("../src/client.mjs");
    const lines = [];
    await getStreamLines("/test", (line) => lines.push(line));
    expect(lines).toEqual(['{"split":"value"}']);
  });

  it("getStreamLines rejects on non-200 status", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Not found");
    }));
    mockDiscovery(port);
    const { getStreamLines } = await import("../src/client.mjs");
    await expect(getStreamLines("/test", () => {})).rejects.toThrow("Not found");
  });

  it("getStreamLines handles trailing data without newline", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "application/x-ndjson" });
      res.write('{"a":1}\n');
      res.write('{"b":2}');  // no trailing newline
      res.end();
    }));
    mockDiscovery(port);
    const { getStreamLines } = await import("../src/client.mjs");
    const lines = [];
    await getStreamLines("/test", (line) => lines.push(line));
    expect(lines).toEqual(['{"a":1}', '{"b":2}']);
  });

  // --- connect() ---

  it("connect() exits when no instance found", async () => {
    vi.doMock("../src/bridge-env.mjs", () => ({
      getPinnedBridge: () => null,
    }));
    vi.doMock("../src/discovery.mjs", () => ({
      discoverInstances: async () => [],
      findInstance: async () => null,
    }));
    const origExit = process.exit;
    const origError = console.error;
    let exitCode;
    let errorMsg = "";
    process.exit = (code) => {
      exitCode = code;
      throw new Error(`exit(${code})`);
    };
    console.error = (msg) => {
      errorMsg += msg;
    };
    try {
      const { connect } = await import("../src/client.mjs");
      await connect();
    } catch {
      // expected
    }
    process.exit = origExit;
    console.error = origError;
    expect(exitCode).toBe(1);
    expect(errorMsg).toContain("not running");
  });

  // --- requestOptions() ---

  it("requestOptions direct when no proxy", async () => {
    vi.resetModules();
    delete process.env.http_proxy;
    delete process.env.HTTP_PROXY;
    const { proxyAwareOptions } = await import("../src/proxy.mjs");
    const opts = proxyAwareOptions(
      "host.docker.internal", 61180, "/projects", "GET", 10000);
    expect(opts.hostname).toBe("host.docker.internal");
    expect(opts.port).toBe(61180);
    expect(opts.path).toBe("/projects");
  });

  it("requestOptions routes through proxy when http_proxy set", async () => {
    vi.resetModules();
    process.env.http_proxy = "http://proxy.local:3128";
    process.env.no_proxy = "localhost,127.0.0.1";
    const { proxyAwareOptions } = await import("../src/proxy.mjs");
    const opts = proxyAwareOptions(
      "host.docker.internal", 61180, "/projects", "GET", 10000);
    expect(opts.hostname).toBe("proxy.local");
    expect(opts.port).toBe(3128);
    expect(opts.path).toBe("http://host.docker.internal:61180/projects");
    delete process.env.http_proxy;
    delete process.env.no_proxy;
  });

  it("requestOptions skips proxy when host in no_proxy", async () => {
    vi.resetModules();
    process.env.http_proxy = "http://proxy.local:3128";
    process.env.no_proxy = "localhost,127.0.0.1,host.docker.internal";
    const { proxyAwareOptions } = await import("../src/proxy.mjs");
    const opts = proxyAwareOptions(
      "host.docker.internal", 61180, "/projects", "GET", 10000);
    expect(opts.hostname).toBe("host.docker.internal");
    expect(opts.port).toBe(61180);
    expect(opts.path).toBe("/projects");
    delete process.env.http_proxy;
    delete process.env.no_proxy;
  });

  // --- resetClient() ---

  it("resetClient() clears cached instance", async () => {
    let port;
    let requestCount = 0;
    ({ server, port } = await startServer((req, res) => {
      requestCount++;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("[]");
    }));
    mockDiscovery(port);
    const { get, resetClient } = await import("../src/client.mjs");
    await get("/a");
    resetClient();
    await get("/b");
    expect(requestCount).toBe(2);
  });
});
