// Descriptor consistency for the :jdt/coverage module — checks that
// what coverage.qlang advertises in :modifiers, :returns, :kind and
// :params matches the underlying coverage.impl.mjs primitives and
// the qlang conduits' actual params lists.

import { describe, it, expect, vi, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { keyword } from "@kaluchi/qlang-core";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, "..", "lib");

async function loadSession() {
    process.env.JDT_GRAPH_CACHE = "0";
    process.env.JDT_COVERAGE_CACHE = "0";
    vi.resetModules();
    vi.doMock("../src/client.mjs", () => ({
        get: async () => null,
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
    delete process.env.JDT_COVERAGE_CACHE;
    vi.resetModules();
    vi.doUnmock("../src/client.mjs");
});

async function descriptor(name) {
    const session = await loadSession();
    const { result, error } = await session.evalCell(
        `use(:jdt/coverage) | reify(:${name})`);
    if (error) throw error;
    return result;
}

function field(desc, key) {
    return desc.get(keyword(key));
}

describe(":jdt/coverage primitive descriptors", () => {
    it("@coverage :returns :map, :modifiers carries :string", async () => {
        const d = await descriptor("@coverage");
        expect(field(d, "kind")).toBe(keyword("builtin"));
        expect(field(d, "returns")).toBe(keyword("map"));
        expect(field(d, "modifiers"))
                .toEqual([keyword("string")]);
    });

    it("@activeCoverageId :returns :string, :modifiers empty",
            async () => {
        const d = await descriptor("@activeCoverageId");
        expect(field(d, "kind")).toBe(keyword("builtin"));
        expect(field(d, "returns")).toBe(keyword("string"));
        expect(field(d, "modifiers")).toEqual([]);
    });

    it("@coverage :captured spans 0..1 (overloaded)", async () => {
        const d = await descriptor("@coverage");
        expect(field(d, "captured")).toEqual([0, 1]);
    });

    it("@activeCoverageId :captured is [0 0] (nullary)",
            async () => {
        const d = await descriptor("@activeCoverageId");
        expect(field(d, "captured")).toEqual([0, 0]);
    });
});

describe(":jdt/coverage conduits", () => {
    for (const name of [
            "@uncovered", "@partial", "@fullyCovered",
            "@coveredLines", "@uncoveredLines", "@partialLines",
            "@coverageCard"]) {
        it(`${name} is a conduit with empty params`, async () => {
            const d = await descriptor(name);
            expect(field(d, "kind")).toBe(keyword("conduit"));
            expect(field(d, "params")).toEqual([]);
        });
    }
});
