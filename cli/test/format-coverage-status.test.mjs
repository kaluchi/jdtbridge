import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { setColorEnabled } from "../src/color.mjs";
import {
  formatRunHeader,
  formatStatusSnapshot,
  formatStreamEvent,
  runGuide,
} from "../src/format/coverage-status.mjs";

describe("formatRunHeader", () => {
  let logs;
  const origLog = console.log;
  beforeEach(() => {
    setColorEnabled(false);
    logs = [];
    console.log = (...a) => logs.push(a.join(" "));
  });
  afterEach(() => { console.log = origLog; });

  it("renders header with all wire fields", () => {
    const out = formatRunHeader({
      configId: "MyTest",
      coverageId: "MyTest:1700000000000",
      launchId: "MyTest:6408",
      configType: "JUnit Plug-in Test",
      coverageScope: ["=MyProject/src/main/java", "=MyProject/src/test/java"],
    });
    expect(out).toContain("#### Coverage: MyTest");
    expect(out).toContain("CoverageId:    `MyTest:1700000000000`");
    expect(out).toContain("LaunchId:      `MyTest:6408`");
    expect(out).toContain("ConfigId:      `MyTest`");
    expect(out).toContain("ConfigType:    JUnit Plug-in Test");
    expect(out).toContain("LaunchMode:    coverage");
    expect(out).toContain("=MyProject/src/main/java");
  });

  it("omits scope block when coverageScope empty", () => {
    const out = formatRunHeader({
      configId: "X", coverageId: "X:1", launchId: "X:1",
    });
    expect(out).not.toContain("CoverageScope:");
  });
});

describe("formatStatusSnapshot", () => {
  let logs;
  const origLog = console.log;
  beforeEach(() => {
    setColorEnabled(false);
    logs = [];
    console.log = (...a) => logs.push(a.join(" "));
  });
  afterEach(() => { console.log = origLog; });

  it("renders header line with id and status", () => {
    const out = formatStatusSnapshot({
      coverageId: "MyTest:1700000000000",
      configId: "MyTest",
      coverageSessionKind: "live",
      terminated: true,
      dataReceived: true,
      analysisLoading: false,
      analysisReady: true,
      dumpCount: 1,
      terminatedAt: Date.now() - 5 * 60_000,
      counters: {},
    });
    expect(out).toContain("MyTest:1700000000000");
    expect(out).toContain("MyTest");
    expect(out).toContain("finished");
    expect(out).toContain("analysis ready");
  });

  it("includes counter rows when analysisReady AND counters present", () => {
    const out = formatStatusSnapshot({
      coverageId: "X:1",
      coverageSessionKind: "live",
      terminated: true,
      dataReceived: true,
      analysisLoading: false,
      analysisReady: true,
      dumpCount: 1,
      counters: {
        instruction: { coveredCount: 12, missedCount: 3, totalCount: 15,
          coveredRatio: 0.8, missedRatio: 0.2, coverageStatus: "PARTLY_COVERED" },
        branch: { coveredCount: 0, missedCount: 0, totalCount: 0,
          coveredRatio: null, missedRatio: null, coverageStatus: "EMPTY" },
        line: { coveredCount: 5, missedCount: 0, totalCount: 5,
          coveredRatio: 1.0, missedRatio: 0.0, coverageStatus: "FULLY_COVERED" },
      },
    });
    expect(out).toContain("Instructions");
    expect(out).toContain("coveredCount=12");
    expect(out).toContain("missedCount=3");
    expect(out).toContain("PARTLY_COVERED");
    expect(out).toContain("Branches");
    expect(out).toContain("EMPTY"); // 0 totalCount → EMPTY row
    expect(out).toContain("FULLY_COVERED");
  });

  it("shows 'analysis loading' marker when analysisLoading", () => {
    const out = formatStatusSnapshot({
      coverageId: "X:1",
      coverageSessionKind: "live",
      terminated: false,
      dataReceived: true,
      analysisLoading: true,
      analysisReady: false,
      dumpCount: 1,
    });
    expect(out).toContain("analysis loading");
  });

  it("shows 'no data received' when terminated without data", () => {
    const out = formatStatusSnapshot({
      coverageId: "X:1",
      coverageSessionKind: "live",
      terminated: true,
      dataReceived: false,
      analysisLoading: false,
      analysisReady: false,
      dumpCount: 0,
      terminatedAt: Date.now(),
    });
    expect(out).toContain("no data received");
  });
});

describe("formatStreamEvent", () => {
  let logs;
  const origLog = console.log;
  beforeEach(() => {
    setColorEnabled(false);
    logs = [];
    console.log = (...a) => logs.push(a.join(" "));
  });
  afterEach(() => { console.log = origLog; });

  it("snapshot event prints status line", () => {
    const ok = formatStreamEvent(JSON.stringify({
      event: "snapshot", coverageId: "X:1",
      coverageSessionKind: "live", terminated: false,
      dataReceived: false, analysisLoading: false,
      analysisReady: false, dumpCount: 0,
    }));
    expect(ok).toBe(true);
    expect(logs[0]).toContain("snapshot");
  });

  it("dumped event prints dump index and timestamp", () => {
    formatStreamEvent(JSON.stringify({
      event: "dumped", coverageId: "X:1",
      dumpIndex: 2, dumpTimestamp: Date.now(),
    }));
    expect(logs[0]).toContain("dumped #2");
  });

  it("analysisLoading event prints analyzing line", () => {
    formatStreamEvent(JSON.stringify({
      event: "analysisLoading", coverageId: "X:1", dumpIndex: 1,
    }));
    expect(logs[0]).toContain("analyzing #1");
  });

  it("analysisReady event prints ready line", () => {
    formatStreamEvent(JSON.stringify({
      event: "analysisReady", coverageId: "X:1", dumpIndex: 1,
    }));
    expect(logs[0]).toContain("ready #1");
  });

  it("terminated event prints terminated line", () => {
    formatStreamEvent(JSON.stringify({
      event: "terminated", coverageId: "X:1",
      terminatedAt: Date.now(), dataReceived: true,
    }));
    expect(logs[0]).toContain("terminated");
  });

  it("terminated with no data tags '(no data)'", () => {
    formatStreamEvent(JSON.stringify({
      event: "terminated", coverageId: "X:1",
      terminatedAt: Date.now(), dataReceived: false,
    }));
    expect(logs[0]).toContain("no data");
  });

  it("failed event prints reason", () => {
    formatStreamEvent(JSON.stringify({
      event: "failed", reason: "analysis-cancelled",
    }));
    expect(logs[0]).toContain("failed");
    expect(logs[0]).toContain("analysis-cancelled");
  });

  it("invalid JSON returns false", () => {
    const ok = formatStreamEvent("not-json{");
    expect(ok).toBe(false);
  });

  it("unknown event returns false", () => {
    const ok = formatStreamEvent(JSON.stringify({ event: "weird" }));
    expect(ok).toBe(false);
  });
});

describe("runGuide", () => {
  it("includes coverageId and launchId in commands", () => {
    const guide = runGuide("MyTest:1", "MyTest:1234");
    expect(guide).toContain("jdt coverage status MyTest:1");
    expect(guide).toContain("jdt launch logs MyTest:1234");
    expect(guide).toContain("jdt coverage dump MyTest:1");
    expect(guide).toContain("jdt coverage stop MyTest:1");
    expect(guide).toContain("jdt coverage activate MyTest:1");
  });
});
