// Descriptor consistency for the :jdt/coverage module — checks
// that what coverage.qlang advertises in :modifiers, :returns,
// :kind and :params matches the underlying coverage.impl.mjs
// primitives and the qlang conduits' actual params lists.
//
// qlang 0.7 surface: `:name | spec` returns the env-side
// declaration descriptor. For a `:builtin` catalog entry the
// descriptor carries `:kind ::builtin :impl <fn> :category … :subject
// … :modifiers … :returns … :throws … :captured [min max]
// :effectful <bool>`. For a `:conduit` BindStep the descriptor
// is a Conduit value-class Map with `:kind ::conduit :name … :params
// [...] :source <body-source>`.

import { describe, it, expect, vi, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { keyword, makeTagKeyword } from "@kaluchi/qlang-core";

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

async function spec(name) {
    const session = await loadSession();
    const { result, error } = await session.evalCell(
        `use(:jdt/coverage) | :${name} | spec`);
    if (error) throw error;
    return result;
}

describe(":jdt/coverage primitive descriptors", () => {
    it("@coverage :returns :map, :modifiers carries :string", async () => {
        const d = await spec("@coverage");
        expect(d.get("kind")).toEqual(makeTagKeyword("builtin"));
        expect(d.get("returns")).toEqual(keyword("map"));
        expect(d.get("modifiers"))
                .toEqual([keyword("string")]);
    });

    it("@activeCoverageId :returns :string, :modifiers empty",
            async () => {
        const d = await spec("@activeCoverageId");
        expect(d.get("kind")).toEqual(makeTagKeyword("builtin"));
        expect(d.get("returns")).toEqual(keyword("string"));
        expect(d.get("modifiers")).toEqual([]);
    });

    it("@coverage :captured spans 0..1 (overloaded)", async () => {
        const d = await spec("@coverage");
        expect(d.get("captured")).toEqual([0, 1]);
    });

    it("@activeCoverageId :captured is [0 0] (nullary)",
            async () => {
        const d = await spec("@activeCoverageId");
        expect(d.get("captured")).toEqual([0, 0]);
    });
});

describe(":jdt/coverage conduits", () => {
    for (const name of [
            "@uncovered", "@partial", "@fullyCovered",
            "@coveredLines", "@uncoveredLines", "@partialLines",
            "@coverageCard"]) {
        it(`${name} is a conduit with empty params`, async () => {
            const d = await spec(name);
            expect(d.get("kind")).toEqual(makeTagKeyword("conduit"));
            expect(d.get("params")).toEqual([]);
        });
    }
});
