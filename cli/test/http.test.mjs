import { describe, it, expect, afterEach } from "vitest";
import { createServer } from "node:http";
import { httpRequest } from "../src/http.mjs";

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

describe("httpRequest", () => {
  let server;

  afterEach(async () => {
    if (server) {
      await stopServer(server);
      server = null;
    }
  });

  it("returns { status, body } on 200", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(200, { "Content-Type": "text/plain" });
      res.end("ok");
    }));
    const res = await httpRequest({ host: "127.0.0.1", port }, "/foo");
    expect(res.status).toBe(200);
    expect(res.body).toBe("ok");
    expect(res.error).toBeUndefined();
  });

  it("returns non-200 status without throwing", async () => {
    let port;
    ({ server, port } = await startServer((req, res) => {
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("nope");
    }));
    const res = await httpRequest({ host: "127.0.0.1", port }, "/foo");
    expect(res.status).toBe(404);
    expect(res.body).toBe("nope");
    expect(res.error).toBeUndefined();
  });

  it("connection refused → status 0 + error", async () => {
    // Pick an unused port by starting and immediately stopping a server
    const { server: s, port } = await startServer(() => {});
    await stopServer(s);
    const res = await httpRequest({ host: "127.0.0.1", port }, "/foo");
    expect(res.status).toBe(0);
    expect(res.error).toBeInstanceOf(Error);
  });

  it("timeout → status 0 + 'Request timed out'", async () => {
    let port;
    ({ server, port } = await startServer(() => {
      // Never respond — client will time out
    }));
    const res = await httpRequest(
      { host: "127.0.0.1", port }, "/slow", "GET", 100);
    expect(res.status).toBe(0);
    expect(res.error?.message).toBe("Request timed out");
  });

  it("sends Authorization header when token present", async () => {
    let port;
    let received;
    ({ server, port } = await startServer((req, res) => {
      received = req.headers.authorization;
      res.writeHead(200);
      res.end("ok");
    }));
    await httpRequest(
      { host: "127.0.0.1", port, token: "abc123" }, "/foo");
    expect(received).toBe("Bearer abc123");
  });

  it("sends X-Bridge-Session header when session present", async () => {
    let port;
    let received;
    ({ server, port } = await startServer((req, res) => {
      received = req.headers["x-bridge-session"];
      res.writeHead(200);
      res.end("ok");
    }));
    await httpRequest(
      { host: "127.0.0.1", port, session: "sess-42" }, "/foo");
    expect(received).toBe("sess-42");
  });

  it("no auth header when token absent", async () => {
    let port;
    let received;
    ({ server, port } = await startServer((req, res) => {
      received = req.headers.authorization;
      res.writeHead(200);
      res.end("ok");
    }));
    await httpRequest({ host: "127.0.0.1", port }, "/foo");
    expect(received).toBeUndefined();
  });

  it("method defaults to GET", async () => {
    let port;
    let received;
    ({ server, port } = await startServer((req, res) => {
      received = req.method;
      res.writeHead(200);
      res.end("ok");
    }));
    await httpRequest({ host: "127.0.0.1", port }, "/foo");
    expect(received).toBe("GET");
  });

  it("passes method argument through", async () => {
    let port;
    let received;
    ({ server, port } = await startServer((req, res) => {
      received = req.method;
      res.writeHead(200);
      res.end("ok");
    }));
    await httpRequest({ host: "127.0.0.1", port }, "/foo", "POST");
    expect(received).toBe("POST");
  });
});
