// Behavioural tests for the :jdt/coverage status predicates —
// @uncovered, @partial, @fullyCovered.
//
// stubOp replaces @coverage with a fixed Map literal, so each
// predicate exercises pure qlang-side logic without HTTP.

import { describe, it, expect, afterEach } from "vitest";
import { loadCoverageSession, resetCoverageSession }
        from "./helpers/coverage-session.mjs";
import { stubOp } from "./helpers/operand-stub.mjs";

describe("@uncovered — coveredCount==0 predicate", () => {
    afterEach(resetCoverageSession);

    it("true when instruction.coveredCount is 0", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 0
                     :missedCount 100
                     :totalCount 100
                     :coverageStatus "NOT_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @uncovered`);
        expect(result).toBe(true);
    });

    it("false when instruction.coveredCount > 0", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 5
                     :missedCount 95
                     :totalCount 100
                     :coverageStatus "PARTLY_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @uncovered`);
        expect(result).toBe(false);
    });

    it("false when fully covered", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 100
                     :missedCount 0
                     :totalCount 100
                     :coverageStatus "FULLY_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @uncovered`);
        expect(result).toBe(false);
    });
});

describe("@partial — PARTLY_COVERED predicate", () => {
    afterEach(resetCoverageSession);

    it("true on PARTLY_COVERED status", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 30
                     :missedCount 70
                     :totalCount 100
                     :coverageStatus "PARTLY_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @partial`);
        expect(result).toBe(true);
    });

    it("false on FULLY_COVERED status", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 100
                     :missedCount 0
                     :totalCount 100
                     :coverageStatus "FULLY_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @partial`);
        expect(result).toBe(false);
    });

    it("false on NOT_COVERED status", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 0
                     :missedCount 100
                     :totalCount 100
                     :coverageStatus "NOT_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @partial`);
        expect(result).toBe(false);
    });
});

describe("@fullyCovered — FULLY_COVERED predicate", () => {
    afterEach(resetCoverageSession);

    it("true on FULLY_COVERED status", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 100
                     :missedCount 0
                     :totalCount 100
                     :coverageStatus "FULLY_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @fullyCovered`);
        expect(result).toBe(true);
    });

    it("false on partial coverage", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", `
                {:counters {:instruction
                    {:coveredCount 50
                     :missedCount 50
                     :totalCount 100
                     :coverageStatus "PARTLY_COVERED"}}}`),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @fullyCovered`);
        expect(result).toBe(false);
    });
});

describe("line axes — @coveredLines / @uncoveredLines / @partialLines",
        () => {
    afterEach(resetCoverageSession);

    const LINES_STUB = `
        {:counters {:instruction
            {:coveredCount 4
             :missedCount 3
             :totalCount 7
             :coverageStatus "PARTLY_COVERED"}}
         :lines {:firstLine 10 :lastLine 16
                 :entries [
                    {:line 10 :status "FULLY_COVERED"
                     :instructionCovered 5 :instructionMissed 0
                     :branchCovered 0 :branchMissed 0}
                    {:line 11 :status "FULLY_COVERED"
                     :instructionCovered 3 :instructionMissed 0
                     :branchCovered 0 :branchMissed 0}
                    {:line 12 :status "PARTLY_COVERED"
                     :instructionCovered 2 :instructionMissed 1
                     :branchCovered 1 :branchMissed 1}
                    {:line 14 :status "NOT_COVERED"
                     :instructionCovered 0 :instructionMissed 4
                     :branchCovered 0 :branchMissed 0}
                    {:line 16 :status "NOT_COVERED"
                     :instructionCovered 0 :instructionMissed 2
                     :branchCovered 0 :branchMissed 0}]}}`;

    it("@coveredLines returns FULLY_COVERED line numbers",
            async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", LINES_STUB),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @coveredLines`);
        expect(result).toEqual([10, 11]);
    });

    it("@uncoveredLines returns NOT_COVERED line numbers",
            async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", LINES_STUB),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @uncoveredLines`);
        expect(result).toEqual([14, 16]);
    });

    it("@partialLines returns full entry Maps for "
            + "PARTLY_COVERED lines", async () => {
        const { result } = await (await loadCoverageSession({
            implsOverrides: await stubOp("@coverage", LINES_STUB),
        })).evalCell(
            `{:fqn "pkg.Foo#bar()" :kind "method"} | @partialLines * /line`);
        expect(result).toEqual([12]);
    });
});
