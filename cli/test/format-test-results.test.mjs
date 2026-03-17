import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { setColorEnabled } from "../src/color.mjs";
import { formatTestResults } from "../src/format/test-results.mjs";

describe("formatTestResults", () => {
  let logs;
  const origLog = console.log;

  beforeEach(() => {
    setColorEnabled(false);
    logs = [];
    console.log = (...args) => logs.push(args.join(" "));
  });

  afterEach(() => {
    console.log = origLog;
  });

  it("shows all-passing summary", () => {
    formatTestResults({
      total: 5, passed: 5, failed: 0, errors: 0, ignored: 0,
      time: 1.234, failures: [],
    });
    expect(logs[0]).toContain("5 tests");
    expect(logs[0]).toContain("5 passed");
    expect(logs[0]).toContain("1.2s");
  });

  it("falls back to 0.0s when time is not finite", () => {
    formatTestResults({
      total: 0, passed: 0, failed: 0, errors: 0, ignored: 0,
      time: Number.NaN, failures: [],
    });
    expect(logs[0]).toContain("0.0s");
  });

  it("shows failed and error counts", () => {
    formatTestResults({
      total: 10, passed: 7, failed: 2, errors: 1, ignored: 0,
      time: 3.0, failures: [],
    });
    expect(logs[0]).toContain("2 failed");
    expect(logs[0]).toContain("1 errors");
  });

  it("shows ignored count", () => {
    formatTestResults({
      total: 3, passed: 2, failed: 0, errors: 0, ignored: 1,
      time: 0.5, failures: [],
    });
    expect(logs[0]).toContain("1 ignored");
  });

  it("shows failure details with trace", () => {
    formatTestResults({
      total: 2, passed: 1, failed: 1, errors: 0, ignored: 0,
      time: 0.5,
      failures: [{
        status: "FAILURE",
        class: "app.FooTest",
        method: "testBar",
        trace: "AssertionError: expected 1 but was 2\n  at app.FooTest.testBar(FooTest.java:10)",
      }],
    });
    expect(logs.some((l) => l.includes("app.FooTest.testBar"))).toBe(true);
    expect(logs.some((l) => l.includes("AssertionError"))).toBe(true);
  });

  it("shows ERROR status distinctly", () => {
    formatTestResults({
      total: 1, passed: 0, failed: 0, errors: 1, ignored: 0,
      time: 0.1,
      failures: [{
        status: "ERROR",
        class: "app.FooTest",
        method: "testBoom",
        trace: "NullPointerException\n  at app.Foo.bar(Foo.java:5)",
      }],
    });
    expect(logs.some((l) => l.includes("ERROR"))).toBe(true);
    expect(logs.some((l) => l.includes("NullPointerException"))).toBe(true);
  });

  it("truncates long traces at 10 lines", () => {
    const longTrace = Array.from({ length: 20 }, (_, i) => `  at line ${i}`).join("\n");
    formatTestResults({
      total: 1, passed: 0, failed: 1, errors: 0, ignored: 0,
      time: 0.1,
      failures: [{ status: "FAILURE", class: "T", method: "m", trace: longTrace }],
    });
    expect(logs.some((l) => l.includes("..."))).toBe(true);
    // Should not show line 15
    expect(logs.some((l) => l.includes("at line 15"))).toBe(false);
  });

  it("handles failure without trace", () => {
    formatTestResults({
      total: 1, passed: 0, failed: 1, errors: 0, ignored: 0,
      time: 0.1,
      failures: [{ status: "FAILURE", class: "T", method: "m", trace: null }],
    });
    expect(logs.some((l) => l.includes("T.m"))).toBe(true);
  });

  it("handles no failures array", () => {
    formatTestResults({
      total: 1, passed: 1, failed: 0, errors: 0, ignored: 0,
      time: 0.1,
    });
    expect(logs).toHaveLength(1);
    expect(logs[0]).toContain("1 tests");
  });
});
