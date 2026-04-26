// Loader for the :jdt/coverage qlang module under test, mirroring
// graph-session.mjs but pulling in BOTH :jdt/graph and :jdt/coverage
// (coverage conduits like @coverageCard reach into :jdt/graph axes
// such as @asNode and @detail).
//
//   mockedGet      — async (path) => responseValue. Stubs
//                    client.mjs#get at the HTTP-path layer; tests
//                    that verify URL formation observe `path` per
//                    call.
//
//   implsOverrides — { '@operandName': stubImpl } merged over the
//                    per-namespace createImpls() maps. Spreads into
//                    BOTH namespace impl maps; conflicts go to the
//                    one whose operand name matches.
//
// When neither is supplied, get() throws loud — no test silently
// hits the real bridge.

import { vi } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB     = join(__dirname, "..", "..", "lib");
const GRAPH_QLANG    = join(MODULE_LIB, "jdt", "graph.qlang");
const COVERAGE_QLANG = join(MODULE_LIB, "jdt", "coverage.qlang");

export async function loadCoverageSession({
        implsOverrides = {}, mockedGet } = {}) {
    process.env.JDT_GRAPH_CACHE = "0";
    process.env.JDT_COVERAGE_CACHE = "0";
    vi.resetModules();
    vi.doMock("../../src/client.mjs", () => ({
        get: mockedGet ?? (async () => {
            throw new Error(
                "HTTP must not be hit; supply mockedGet "
                + "or implsOverrides");
        }),
        currentInstance: () => null,
        BridgeNotRunningError: class extends Error {},
        isConnectionError: () => false,
    }));
    const graphImpl = await import(
            "../../lib/jdt/graph.impl.mjs?v=" + Date.now());
    const coverageImpl = await import(
            "../../lib/jdt/coverage.impl.mjs?v=" + Date.now());
    const { createSession } = await import(
            "@kaluchi/qlang-core/session");

    const ENTRIES = {
        "jdt/graph": {
            source: readFileSync(GRAPH_QLANG, "utf8"),
            factory: graphImpl.createImpls,
        },
        "jdt/coverage": {
            source: readFileSync(COVERAGE_QLANG, "utf8"),
            factory: coverageImpl.createImpls,
        },
    };
    const session = await createSession({
        locator: (ns) => {
            const entry = ENTRIES[ns];
            if (!entry) return null;
            return {
                source: entry.source,
                impls: { ...entry.factory(), ...implsOverrides },
            };
        },
    });
    const { error } = await session.evalCell(
            "use(:jdt/graph) | use(:jdt/coverage)");
    if (error) throw error;
    return session;
}

export function resetCoverageSession() {
    delete process.env.JDT_GRAPH_CACHE;
    delete process.env.JDT_COVERAGE_CACHE;
    vi.resetModules();
    vi.doUnmock("../../src/client.mjs");
}
