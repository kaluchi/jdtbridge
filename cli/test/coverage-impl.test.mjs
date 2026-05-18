// HTTP-path verification for the :jdt/coverage primitives.
//
// Mocks client.mjs#get so each call's URL is observable, then walks
// every code path of @coverage (0-arity / 1-arity / Map subject /
// missing-fqn / no-active-session) and @activeCoverageId.

import { describe, it, expect, vi, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { keyword, makeTagKeyword } from "@kaluchi/qlang-core";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, "..", "lib");

async function loadSession(mockedGet) {
    process.env.JDT_GRAPH_CACHE = "0";
    // JDT_COVERAGE_CACHE left enabled — module-level cache lives
    // inside the freshly-imported coverage.impl.mjs and dies with
    // vi.resetModules between tests, which is the right scope.
    vi.resetModules();
    vi.doMock("../src/client.mjs", () => ({
        get: mockedGet,
        currentInstance: () => null,
        BridgeNotRunningError: class extends Error {},
        isConnectionError: () => false,
    }));
    const graphImpl    = await import(
            "../lib/jdt/graph.impl.mjs?v=" + Date.now());
    const coverageImpl = await import(
            "../lib/jdt/coverage.impl.mjs?v=" + Date.now());
    const { createSession } = await import(
            "@kaluchi/qlang-core/session");
    return createSession({
        locator: (ns) => {
            const path = join(MODULE_LIB, ...ns.split("/")) + ".qlang";
            const source = readFileSync(path, "utf8");
            const impls = ns === "jdt/graph"
                    ? graphImpl.createImpls()
                    : ns === "jdt/coverage"
                    ? coverageImpl.createImpls()
                    : undefined;
            return { source, impls };
        },
    });
}

afterEach(() => {
    delete process.env.JDT_GRAPH_CACHE;
    vi.resetModules();
    vi.doUnmock("../src/client.mjs");
});

describe("@coverage routing — 0-arity (active session)", () => {
    it("hits /coverage/active first, then /coverage/node", async () => {
        const calls = [];
        const session = await loadSession(async (path) => {
            calls.push(path);
            if (path === "/coverage/active") {
                return { activeCoverageId: "MyTest:1234" };
            }
            return { fqn: "pkg.Foo", elementKind: "type",
                     counters: {} };
        });
        await session.evalCell(
            'use(:jdt/coverage) | "pkg.Foo" | @coverage');
        expect(calls).toEqual([
            "/coverage/active",
            "/coverage/node?coverageId=MyTest%3A1234&fqn=pkg.Foo",
        ]);
    });

    it("caches /coverage/active across calls in one session",
            async () => {
        const calls = [];
        const session = await loadSession(async (path) => {
            calls.push(path);
            if (path === "/coverage/active") {
                return { activeCoverageId: "X:1" };
            }
            return { fqn: "x", counters: {} };
        });
        await session.evalCell(
            'use(:jdt/coverage) | ["pkg.A" "pkg.B" "pkg.C"] '
          + '* @coverage');
        const activeCalls = calls.filter(
                p => p === "/coverage/active");
        expect(activeCalls.length).toBe(1);
        const nodeCalls = calls.filter(
                p => p.startsWith("/coverage/node"));
        expect(nodeCalls.length).toBe(3);
    });

    it("emits no-active-session error when active is null",
            async () => {
        const session = await loadSession(async (path) => {
            if (path === "/coverage/active") {
                return { activeCoverageId: null };
            }
            throw new Error("unexpected " + path);
        });
        const { result } = await session.evalCell(
            'use(:jdt/coverage) '
          + '| "pkg.Foo" | @coverage !| type');
        expect(result).toEqual(makeTagKeyword("CoverageNoActiveSession"));
    });
});

describe("@coverage routing — 1-arity (explicit coverageId)", () => {
    it("skips /coverage/active and uses captured id", async () => {
        const calls = [];
        const session = await loadSession(async (path) => {
            calls.push(path);
            return { fqn: "pkg.Foo", counters: {} };
        });
        await session.evalCell(
            'use(:jdt/coverage) | "pkg.Foo" '
          + '| @coverage("OtherTest:9999")');
        expect(calls).toEqual([
            "/coverage/node?coverageId=OtherTest%3A9999&fqn=pkg.Foo",
        ]);
    });

    it("evaluates captured-arg as a sub-pipeline against pipeValue",
            async () => {
        const calls = [];
        const session = await loadSession(async (path) => {
            calls.push(path);
            return { fqn: "pkg.Foo", counters: {} };
        });
        // Captured-arg references an `as` snapshot from earlier in
        // the pipeline. Sub-pipeline evaluation against pipeValue.
        await session.evalCell(
            'use(:jdt/coverage) '
          + '| "MyTest:1" | as(:cov) '
          + '| "pkg.Foo" | @coverage(cov)');
        expect(calls).toEqual([
            "/coverage/node?coverageId=MyTest%3A1&fqn=pkg.Foo",
        ]);
    });

    it("emits coverage-id-not-string when captured-arg is not "
            + "a String", async () => {
        const session = await loadSession(async () => {
            throw new Error("should not be called");
        });
        const { result } = await session.evalCell(
            'use(:jdt/coverage) | "pkg.Foo" '
          + '| @coverage(42) !| type');
        expect(result).toEqual(makeTagKeyword("CoverageIdNotString"));
    });
});

describe("@coverage routing — subject shapes", () => {
    it("Map subject — extracts :fqn", async () => {
        const calls = [];
        const session = await loadSession(async (path) => {
            calls.push(path);
            if (path === "/coverage/active") {
                return { activeCoverageId: "X:1" };
            }
            return { fqn: "pkg.Foo", counters: {} };
        });
        await session.evalCell(
            'use(:jdt/coverage) '
          + '| {:fqn "pkg.Foo" :kind "type"} | @coverage');
        expect(calls).toContain(
            "/coverage/node?coverageId=X%3A1&fqn=pkg.Foo");
    });

    it("subject without fqn — missing-subject error, no HTTP",
            async () => {
        const calls = [];
        const session = await loadSession(async (path) => {
            calls.push(path);
            return null;
        });
        const { result } = await session.evalCell(
            'use(:jdt/coverage) | 42 | @coverage !| type');
        expect(result).toEqual(makeTagKeyword("MissingSubjectFqn"));
        expect(calls).toEqual([]);
    });
});

describe("@activeCoverageId", () => {
    it("returns String id when active", async () => {
        const session = await loadSession(async () => ({
                activeCoverageId: "MyTest:1" }));
        const { result } = await session.evalCell(
            'use(:jdt/coverage) | @activeCoverageId');
        expect(result).toBe("MyTest:1");
    });

    it("returns null when no active session", async () => {
        const session = await loadSession(async () => ({
                activeCoverageId: null }));
        const { result } = await session.evalCell(
            'use(:jdt/coverage) | @activeCoverageId');
        expect(result).toBeNull();
    });
});
