import { describe, it, expect } from "vitest";
import { composeStatus } from "../src/format/coverage-state.mjs";

describe("composeStatus — STATUS line composition", () => {
  describe("Group A — origin / launch state", () => {
    it("merged kind emits 'merged N sessions'", () => {
      const status = composeStatus({
        coverageSessionKind: "merged",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: false,
        dumpCount: 1,
        consumedCoverageIds: ["a", "b", "c"],
      });
      expect(status).toContain("merged 3 sessions");
    });

    it("merged with no consumed list defaults to 0", () => {
      const status = composeStatus({
        coverageSessionKind: "merged",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: false,
        dumpCount: 1,
      });
      expect(status).toContain("merged 0 sessions");
    });

    it("imported kind emits 'imported'", () => {
      const status = composeStatus({
        coverageSessionKind: "imported",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: false,
        dumpCount: 1,
      });
      expect(status).toContain("imported");
    });

    it("live + not terminated emits 'running'", () => {
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: false,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        launchTimestamp: Date.now() - 30_000,
      });
      expect(status).toMatch(/^running/);
    });

    it("live + terminated emits 'finished Xm ago'", () => {
      const now = Date.now();
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        terminatedAt: now - 5 * 60_000,
      }, now);
      expect(status).toContain("finished 5m ago");
    });
  });

  describe("Group B — data / analysis", () => {
    it("dataReceived=false + terminated → 'no data received'", () => {
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: true,
        dataReceived: false,
        analysisLoading: false,
        analysisReady: false,
        dumpCount: 0,
        terminatedAt: Date.now(),
      });
      expect(status).toContain("no data received");
    });

    it("dumpCount > 1 → '<N> dumps'", () => {
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: false,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 3,
        launchTimestamp: Date.now(),
      });
      expect(status).toContain("3 dumps");
    });

    it("analysisLoading → 'analysis loading'", () => {
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: false,
        dataReceived: true,
        analysisLoading: true,
        analysisReady: false,
        dumpCount: 1,
        launchTimestamp: Date.now(),
      });
      expect(status).toContain("analysis loading");
    });

    it("analysisReady → 'analysis ready'", () => {
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        terminatedAt: Date.now() - 1000,
      });
      expect(status).toContain("analysis ready");
    });

    it("terminated + data + neither analysis flag → 'analysis pending'", () => {
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: false,
        dumpCount: 1,
        terminatedAt: Date.now(),
      });
      expect(status).toContain("analysis pending");
    });
  });

  describe("Group C — relative time (live, not terminated)", () => {
    it("appends 'started Xs ago' for running live", () => {
      const now = Date.now();
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: false,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        launchTimestamp: now - 30_000,
      }, now);
      expect(status).toContain("started 30s ago");
    });

    it("does NOT append relative time for terminated live", () => {
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        launchTimestamp: Date.now() - 60_000,
        terminatedAt: Date.now() - 30_000,
      });
      expect(status).not.toContain("started");
    });

    it("does NOT append relative time for merged", () => {
      const status = composeStatus({
        coverageSessionKind: "merged",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        consumedCoverageIds: ["a", "b"],
      });
      expect(status).not.toContain("started");
    });
  });

  describe("Token order and joining", () => {
    it("joins tokens with ', ' in A → B → C order", () => {
      const now = Date.now();
      const status = composeStatus({
        coverageSessionKind: "live",
        terminated: false,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        launchTimestamp: now - 30_000,
      }, now);
      expect(status).toBe("running, analysis ready, started 30s ago");
    });

    it("merged with analysis ready emits both tokens", () => {
      const status = composeStatus({
        coverageSessionKind: "merged",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: true,
        dumpCount: 1,
        consumedCoverageIds: ["a", "b"],
      });
      expect(status).toBe("merged 2 sessions, analysis ready");
    });

    it("imported with pending analysis", () => {
      const status = composeStatus({
        coverageSessionKind: "imported",
        terminated: true,
        dataReceived: true,
        analysisLoading: false,
        analysisReady: false,
        dumpCount: 1,
      });
      expect(status).toBe("imported, analysis pending");
    });
  });
});
