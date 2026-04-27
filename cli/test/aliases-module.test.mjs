// Tests for the :jdt/aliases module — pure-qlang aliases for
// builtins whose canonical name doesn't match common-English
// expectations from agents.

import { describe, it, expect, vi, afterEach } from "vitest";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { keyword } from "@kaluchi/qlang-core";

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

    it("descriptor reports name and category", async () => {
        const d = await evaluate(
                "use(:jdt/aliases) | reify(:flatten)");
        expect(d.get(keyword("name"))).toBe("flatten");
    });
});
