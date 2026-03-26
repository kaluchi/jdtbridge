import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { setColorEnabled } from "../src/color.mjs";
import { formatTestStatus, formatTestEvent, formatTestRunHeader, testRunGuide } from "../src/format/test-status.mjs";

describe("formatTestStatus", () => {
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

  it("shows label, total/total, counts, time for finished", () => {
    formatTestStatus({
      session: "test-1", label: "FooTest", state: "finished",
      total: 5, completed: 5, passed: 5, failed: 0, errors: 0, ignored: 0,
      time: 2.3, entries: [],
    });
    expect(logs[0]).toContain("FooTest");
    expect(logs[0]).toContain("5/5");
    expect(logs[0]).toContain("5 tests");
    expect(logs[0]).toContain("5 passed");
    expect(logs[0]).toContain("2.3s");
  });

  it("shows (running) and completed/total for running", () => {
    formatTestStatus({
      session: "test-1", label: "FooTest", state: "running",
      total: 10, completed: 3, passed: 3, failed: 0, errors: 0, ignored: 0,
      time: 1.0, entries: [],
    });
    expect(logs[0]).toContain("(running)");
    expect(logs[0]).toContain("3/10");
  });

  it("shows (stopped) for stopped", () => {
    formatTestStatus({
      session: "test-1", label: "FooTest", state: "stopped",
      total: 5, completed: 5, passed: 5, failed: 0, errors: 0, ignored: 0,
      time: 1.0, entries: [],
    });
    expect(logs[0]).toContain("(stopped)");
  });

  it("shows FAIL with FQMN # format and [M] badge", () => {
    formatTestStatus({
      session: "test-1", label: "FooTest", state: "finished",
      total: 2, completed: 2, passed: 1, failed: 1, errors: 0, ignored: 0,
      time: 1.0,
      entries: [{
        fqmn: "com.example.FooTest#testBar", status: "FAIL", time: 0.1, trace: "AssertionError",
      }],
    });
    expect(logs.some((l) => l.includes("FAIL"))).toBe(true);
    expect(logs.some((l) => l.includes("com.example.FooTest#testBar"))).toBe(true);
    expect(logs.some((l) => l.includes("[M]"))).toBe(true);
  });

  it("shows ERROR with status", () => {
    formatTestStatus({
      session: "test-1", label: "FooTest", state: "finished",
      total: 1, completed: 1, passed: 0, failed: 0, errors: 1, ignored: 0,
      time: 0.1,
      entries: [{
        fqmn: "com.example.FooTest#testBoom", status: "ERROR", time: 0.05, trace: "NPE",
      }],
    });
    expect(logs.some((l) => l.includes("ERROR"))).toBe(true);
    expect(logs.some((l) => l.includes("com.example.FooTest#testBoom"))).toBe(true);
  });

  it("shows IGNORED entries", () => {
    formatTestStatus({
      session: "test-1", label: "FooTest", state: "finished",
      total: 1, completed: 1, passed: 0, failed: 0, errors: 0, ignored: 1,
      time: 0.1,
      entries: [{
        fqmn: "com.example.FooTest#testSkipped", status: "IGNORED",
      }],
    });
    expect(logs.some((l) => l.includes("IGNORED"))).toBe(true);
    expect(logs.some((l) => l.includes("com.example.FooTest#testSkipped"))).toBe(true);
  });

  it("handles empty failures (summary only, no extra lines)", () => {
    formatTestStatus({
      session: "test-1", label: "FooTest", state: "finished",
      total: 3, completed: 3, passed: 3, failed: 0, errors: 0, ignored: 0,
      time: 0.5, entries: [],
    });
    expect(logs).toHaveLength(1);
    expect(logs[0]).toContain("3 tests");
  });
});

describe("formatTestEvent", () => {
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

  it("PASS: logs line containing PASS, [M], backtick-wrapped FQMN with #, time", () => {
    const result = formatTestEvent(JSON.stringify({
      event: "case", fqmn: "com.example.FooTest#testBar", status: "PASS", time: 0.123,
    }));
    expect(result).toBe(true);
    expect(logs[0]).toContain("PASS");
    expect(logs[0]).toContain("[M]");
    expect(logs[0]).toContain("`com.example.FooTest#testBar`");
    expect(logs[0]).toContain("0.123s");
  });

  it("FAIL: logs FAIL, FQMN, trace lines", () => {
    const result = formatTestEvent(JSON.stringify({
      event: "case", fqmn: "com.example.FooTest#testBar", status: "FAIL", time: 0.1,
      trace: "AssertionError: expected 1 but was 2\n  at FooTest.testBar(FooTest.java:10)",
    }));
    expect(result).toBe(true);
    expect(logs[0]).toContain("FAIL");
    expect(logs[0]).toContain("com.example.FooTest#testBar");
    expect(logs.some((l) => l.includes("AssertionError"))).toBe(true);
  });

  it("ERROR: logs ERROR, FQMN", () => {
    const result = formatTestEvent(JSON.stringify({
      event: "case", fqmn: "com.example.FooTest#testBoom", status: "ERROR", time: 0.05,
      trace: "NullPointerException",
    }));
    expect(result).toBe(true);
    expect(logs[0]).toContain("ERROR");
    expect(logs[0]).toContain("com.example.FooTest#testBoom");
  });

  it("IGNORED: logs IGNORED, FQMN", () => {
    const result = formatTestEvent(JSON.stringify({
      event: "case", fqmn: "com.example.FooTest#testSkipped", status: "IGNORED",
    }));
    expect(result).toBe(true);
    expect(logs[0]).toContain("IGNORED");
    expect(logs[0]).toContain("com.example.FooTest#testSkipped");
  });

  it("includes expected/actual when present", () => {
    formatTestEvent(JSON.stringify({
      event: "case", fqmn: "com.example.FooTest#testBar", status: "FAIL", time: 0.1,
      expected: "42", actual: "99", trace: "AssertionError",
    }));
    expect(logs.some((l) => l.includes("Expected: 42"))).toBe(true);
    expect(logs.some((l) => l.includes("Actual: 99"))).toBe(true);
  });

  it("truncates trace at 10 lines", () => {
    const longTrace = Array.from({ length: 20 }, (_, i) => `  at line ${i}`).join("\n");
    formatTestEvent(JSON.stringify({
      event: "case", fqmn: "T#m", status: "FAIL", time: 0.1, trace: longTrace,
    }));
    expect(logs.some((l) => l.includes("..."))).toBe(true);
    expect(logs.some((l) => l.includes("at line 15"))).toBe(false);
  });

  it("returns false for started event", () => {
    const result = formatTestEvent(JSON.stringify({ event: "started" }));
    expect(result).toBe(false);
    expect(logs).toHaveLength(0);
  });

  it("returns true for finished event, logs summary", () => {
    const result = formatTestEvent(JSON.stringify({
      event: "finished", total: 5, passed: 4, failed: 1, errors: 0, ignored: 0, time: 2.3,
    }));
    expect(result).toBe(true);
    expect(logs.some((l) => l.includes("5 tests"))).toBe(true);
  });

  it("returns false for invalid JSON", () => {
    const result = formatTestEvent("not valid json {{{");
    expect(result).toBe(false);
  });
});

describe("formatTestRunHeader", () => {
  it("includes markdown heading with label", () => {
    const header = formatTestRunHeader({ session: "test-1", label: "FooTest" });
    expect(header).toContain("#### Test: FooTest");
  });

  it("includes launch session in backticks", () => {
    const header = formatTestRunHeader({ session: "jdtbridge-test-123" });
    expect(header).toContain("`jdtbridge-test-123`");
  });

  it("includes project and runner", () => {
    const header = formatTestRunHeader({
      session: "test-1", label: "FooTest", project: "my-project", runner: "JUnit 5",
    });
    expect(header).toContain("Project: `my-project`");
    expect(header).toContain("Runner: JUnit 5");
  });

  it("includes total count", () => {
    const header = formatTestRunHeader({
      session: "test-1", label: "FooTest", total: 42,
    });
    expect(header).toContain("42 tests");
  });

  it("handles missing optional fields", () => {
    const header = formatTestRunHeader({ session: "test-1" });
    expect(header).toContain("#### Test:");
    expect(header).toContain("`test-1`");
    expect(header).not.toContain("Project:");
    expect(header).not.toContain("Runner:");
  });
});

describe("testRunGuide", () => {
  it("includes session name in status commands", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("jdt test status my-session");
    expect(guide).toContain("jdt test status my-session -f");
  });

  it("includes 'jdt launch logs' command", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("jdt launch logs my-session");
  });

  it("includes 'jdt launch stop' command", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("jdt launch stop my-session");
  });

  it("includes 'jdt source' hint", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("jdt source");
  });

  it("includes --all flag explanation", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("--all");
    expect(guide).toContain("including passed");
  });

  it("includes --ignored flag explanation", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("--ignored");
    expect(guide).toContain("skipped");
  });

  it("includes jdt launch clear command", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("jdt launch clear my-session");
  });

  it("includes jdt test run rerun command", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("jdt test run <FQMN> -f");
  });

  it("includes -q suppression note", () => {
    const guide = testRunGuide("my-session");
    expect(guide).toContain("-q");
    expect(guide).toContain("suppress");
  });
});
