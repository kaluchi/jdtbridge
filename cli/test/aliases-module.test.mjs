// Tests for the :jdt/aliases module — pure-qlang aliases for
// builtins whose canonical name doesn't match common-English
// expectations from agents.

import { describe, it, expect, vi, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, "..", "lib");

async function loadSession() {
    vi.resetModules();
    vi.doMock("../src/client.mjs", () => ({
        get: async () => null,
        currentInstance: () => null,
        BridgeNotRunningError: class extends Error {},
        isConnectionError: () => false,
    }));
    const { createSession } = await import(
            "@kaluchi/qlang-core/session");
    return createSession({
        locator: (ns) => {
            const path = join(MODULE_LIB, ...ns.split("/"))
                    + ".qlang";
            const source = readFileSync(path, "utf8");
            return { source };
        },
    });
}

afterEach(() => {
    vi.resetModules();
    vi.doUnmock("../src/client.mjs");
});

async function evaluate(expr) {
    const session = await loadSession();
    const { result, error } = await session.evalCell(expr);
    if (error) throw error;
    return result;
}

describe("jdt/aliases module — flatten", () => {
    it("flattens one level like flat", async () => {
        const r = await evaluate(
                "use(:jdt/aliases) | [[1 2] [3] [4 5]] | flatten");
        expect(r).toEqual([1, 2, 3, 4, 5]);
    });

    it("on already-flat input is identity", async () => {
        const r = await evaluate(
                "use(:jdt/aliases) | [1 2 3] | flatten");
        expect(r).toEqual([1, 2, 3]);
    });

    it("composes with sortWith and projection", async () => {
        const r = await evaluate(
                "use(:jdt/aliases) | "
                + "[[{:n 3} {:n 1}] [{:n 2}]] | flatten "
                + "| sortWith(desc(/n)) * /n");
        expect(r).toEqual([3, 2, 1]);
    });

    it(":flatten | spec returns its conduit descriptor", async () => {
        // qlang 0.7 axis-operand `spec` returns the env-side
        // declaration descriptor (the Map every binding lives
        // behind after BindStep evaluation). For a conduit it
        // carries the captured body source + params; for a
        // builtin it carries the catalog `::builtin{…}` body.
        const d = await evaluate(
                "use(:jdt/aliases) | :flatten | spec");
        // :flatten is a zero-arg conduit aliasing `flat`, so the
        // descriptor is a Conduit value-class Map with
        // :kind ::conduit and :name "flatten".
        expect(d.get("name")).toBe("flatten");
    });

    it(":flatten | source returns the BindStep source as Quote", async () => {
        const q = await evaluate(
                "use(:jdt/aliases) | :flatten | source");
        // source axis returns a Quote-value carrying the
        // verbatim BindStep text; `/source` projects the raw
        // String off it.
        expect(q.source).toContain(":flatten");
        expect(q.source).toContain("flat");
    });

    it(":flatten | docs returns the attached prose Vec", async () => {
        const docs = await evaluate(
                "use(:jdt/aliases) | :flatten | docs");
        expect(Array.isArray(docs)).toBe(true);
        expect(docs.length).toBeGreaterThan(0);
        expect(docs[0].content).toContain("flatten");
    });
});
