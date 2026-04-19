import { describe, it, expect, vi, afterEach } from "vitest";

// Unit coverage for graph.impl.mjs transport invariants —
// request cache and concurrency semaphore. Mocks
// `../src/client.mjs` so tests stay pure (no HTTP, no Eclipse)
// and run a real qlang session against the graph impls to
// exercise the full dispatch path.

async function loadGraphSession({ concurrency, cache } = {}) {
  if (concurrency !== undefined) {
    process.env.JDT_GRAPH_CONCURRENCY = String(concurrency);
  }
  if (cache !== undefined) {
    process.env.JDT_GRAPH_CACHE = cache ? "" : "0";
  }
  vi.resetModules();
  const graphImpl = await import("../lib/jdt/graph.impl.mjs");
  const { createSession } = await import(
      "@kaluchi/qlang-core/session");
  const session = await createSession();
  const impls = graphImpl.createImpls();
  for (const [name, fn] of Object.entries(impls)) {
    session.bind(name, fn);
  }
  return session;
}

afterEach(() => {
  delete process.env.JDT_GRAPH_CONCURRENCY;
  delete process.env.JDT_GRAPH_CACHE;
  vi.resetModules();
  vi.doUnmock("../src/client.mjs");
});

describe("graph.impl — request cache", () => {
  it("dedupes identical in-flight requests to one HTTP call", async () => {
    const calls = [];
    vi.doMock("../src/client.mjs", () => ({
      get: async (path) => {
        calls.push(path);
        return { fqn: "pkg.Foo", kind: "type", origin: "source" };
      },
    }));
    const session = await loadGraphSession({ cache: true });
    // Ten concurrent lookups of the same fqn — cache should
    // collapse them to one HTTP call.
    await Promise.all(
        Array.from({ length: 10 },
                () => session.evalCell('"pkg.Foo" | @type')));
    expect(calls.length).toBe(1);
  });

  it("JDT_GRAPH_CACHE=0 disables the cache", async () => {
    const calls = [];
    vi.doMock("../src/client.mjs", () => ({
      get: async (path) => {
        calls.push(path);
        return { fqn: "pkg.Foo", kind: "type", origin: "source" };
      },
    }));
    const session = await loadGraphSession({ cache: false });
    await session.evalCell('"pkg.Foo" | @type');
    await session.evalCell('"pkg.Foo" | @type');
    await session.evalCell('"pkg.Foo" | @type');
    expect(calls.length).toBe(3);
  });

  it("different paths miss the cache independently", async () => {
    const calls = [];
    vi.doMock("../src/client.mjs", () => ({
      get: async (path) => {
        calls.push(path);
        return {
          fqn: path.includes("Foo") ? "pkg.Foo" : "pkg.Bar",
          kind: "type",
          origin: "source",
        };
      },
    }));
    const session = await loadGraphSession({ cache: true });
    await session.evalCell('"pkg.Foo" | @type');
    await session.evalCell('"pkg.Bar" | @type');
    expect(calls.length).toBe(2);
    expect(calls[0]).toContain("Foo");
    expect(calls[1]).toContain("Bar");
  });
});

describe("graph.impl — concurrency semaphore", () => {
  it("caps simultaneous in-flight HTTP calls at JDT_GRAPH_CONCURRENCY", async () => {
    let active = 0;
    let peak = 0;
    let release;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    vi.doMock("../src/client.mjs", () => ({
      get: async (path) => {
        active++;
        if (active > peak) peak = active;
        await gate;
        active--;
        return { fqn: path, kind: "type", origin: "source" };
      },
    }));
    const session = await loadGraphSession({
      concurrency: 3,
      cache: false,   // each request must acquire its own slot
    });

    const pending = Array.from({ length: 10 }, (_, i) =>
        session.evalCell(`"pkg.T${i}" | @type`));

    // Let the microtask queue flush so the semaphore admits the
    // first batch before we observe `peak`.
    for (let i = 0; i < 20; i++) await Promise.resolve();

    expect(peak).toBeLessThanOrEqual(3);
    expect(peak).toBeGreaterThan(0);
    release();
    await Promise.all(pending);
    expect(peak).toBeLessThanOrEqual(3);
  });

  it("releases a slot to the next waiter in FIFO order", async () => {
    const started = [];
    const resolvers = new Map();
    vi.doMock("../src/client.mjs", () => ({
      get: async (path) => {
        started.push(path);
        await new Promise((resolve) => resolvers.set(path, resolve));
        return { fqn: path, kind: "type", origin: "source" };
      },
    }));
    const session = await loadGraphSession({
      concurrency: 2,
      cache: false,
    });

    const pending = ["pkg.A", "pkg.B", "pkg.C", "pkg.D"]
        .map((fqn) => session.evalCell(`"${fqn}" | @type`));

    for (let i = 0; i < 20; i++) await Promise.resolve();
    expect(started.length).toBe(2);
    expect(started.sort()).toEqual(
            ["/type?of=pkg.A", "/type?of=pkg.B"]);

    // Release A — C enters.
    resolvers.get("/type?of=pkg.A")();
    for (let i = 0; i < 20; i++) await Promise.resolve();
    expect(started).toContain("/type?of=pkg.C");
    expect(started).not.toContain("/type?of=pkg.D");

    // Release B — D enters.
    resolvers.get("/type?of=pkg.B")();
    for (let i = 0; i < 20; i++) await Promise.resolve();
    expect(started).toContain("/type?of=pkg.D");

    for (const path of ["/type?of=pkg.C", "/type?of=pkg.D"]) {
      resolvers.get(path)();
    }
    await Promise.all(pending);
  });
});

describe("graph.impl — path remapping at the plugin boundary", () => {
  // Stub translateHostPath with a deterministic D:\→/d/ mapping
  // so these tests don't depend on per-instance cache fixtures.
  function mockTranslator() {
    vi.doMock("../src/path-translate.mjs",
        async (importOriginal) => {
          const orig = await importOriginal();
          return { ...orig, translateHostPath: (p) =>
              typeof p === "string" && /^[A-Z]:[\\/]/.test(p)
                  ? "/" + p[0].toLowerCase()
                      + p.slice(2).replace(/\\/g, "/")
                  : p };
        });
  }

  it("rewrites :path on classpath responses via translator", async () => {
    process.env.JDT_GRAPH_CACHE = "0";
    mockTranslator();
    vi.doMock("../src/client.mjs", () => ({
      get: async () => [{
        fqn: "proj#source#D:\\git\\proj\\src",
        kind: "classpathEntry",
        entryKind: "source",
        origin: "source",
        path: "D:\\git\\proj\\src",
      }],
    }));
    vi.resetModules();
    const graphImpl = await import(
        "../lib/jdt/graph.impl.mjs?pathremap=" + Date.now());
    const { createSession } = await import(
        "@kaluchi/qlang-core/session");
    const session = await createSession();
    const impls = graphImpl.createImpls();
    for (const [name, fn] of Object.entries(impls)) {
      session.bind(name, fn);
    }
    const { result } = await session.evalCell(
        '"proj" | @classpath * /path');
    expect(result).toEqual(["/d/git/proj/src"]);
    vi.doUnmock("../src/path-translate.mjs");
  });

  it("does NOT rewrite :fqn even if it contains a path", async () => {
    process.env.JDT_GRAPH_CACHE = "0";
    mockTranslator();
    vi.doMock("../src/client.mjs", () => ({
      get: async () => ({
        fqn: "D:\\git\\proj\\src\\Foo.java",
        kind: "file",
        origin: "source",
      }),
    }));
    vi.resetModules();
    const graphImpl = await import(
        "../lib/jdt/graph.impl.mjs?fqnremap=" + Date.now());
    const { createSession } = await import(
        "@kaluchi/qlang-core/session");
    const session = await createSession();
    const impls = graphImpl.createImpls();
    for (const [name, fn] of Object.entries(impls)) {
      session.bind(name, fn);
    }
    const { result } = await session.evalCell(
        '"D:\\\\git\\\\proj\\\\src\\\\Foo.java" | @file | /fqn');
    // :fqn is an identifier — server echoes the host path as-is
    // so the client can round-trip it back.
    expect(result).toBe("D:\\git\\proj\\src\\Foo.java");
    vi.doUnmock("../src/path-translate.mjs");
  });

  it("walks nested :location/:file", async () => {
    process.env.JDT_GRAPH_CACHE = "0";
    mockTranslator();
    vi.doMock("../src/client.mjs", () => ({
      get: async () => ({
        fqn: "pkg.Foo",
        kind: "type",
        origin: "source",
        location: {
          file: "D:\\git\\proj\\src\\pkg\\Foo.java",
          startLine: 1,
          endLine: 10,
        },
      }),
    }));
    vi.resetModules();
    const graphImpl = await import(
        "../lib/jdt/graph.impl.mjs?locremap=" + Date.now());
    const { createSession } = await import(
        "@kaluchi/qlang-core/session");
    const session = await createSession();
    const impls = graphImpl.createImpls();
    for (const [name, fn] of Object.entries(impls)) {
      session.bind(name, fn);
    }
    const { result } = await session.evalCell(
        '"pkg.Foo" | @type | /location/file');
    expect(result).toBe("/d/git/proj/src/pkg/Foo.java");
    vi.doUnmock("../src/path-translate.mjs");
  });
});
