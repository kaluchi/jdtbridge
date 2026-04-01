import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  formatSection, guideSection, reposFromServer,
  buildDirtyMap, ago, cliCmd, SECTION_NAMES, JSON_COMMANDS,
} from "../src/commands/status.mjs";

// ---- formatSection ----

describe("formatSection", () => {
  const s = { title: "Errors", cmd: "jdt errors --json", body: "[]" };

  it("single section: body only, no header, no fence, no command", () => {
    const out = formatSection(s, true);
    expect(out).toBe("[]");
  });

  it("multiple sections: has ## header", () => {
    const out = formatSection(s, false);
    expect(out).toContain("## Errors");
    expect(out).toContain("```bash");
    expect(out).toContain("$ jdt errors --json");
  });

  it("multiple sections: code fence wraps body", () => {
    const out = formatSection(s, false);
    expect(out).toContain("```bash");
    expect(out).toContain("$ jdt errors --json");
    expect(out).toContain("[]");
    expect(out).toMatch(/```$/);
  });

  it("multiline body preserved", () => {
    const multi = { title: "T", cmd: "cmd", body: "line1\nline2\nline3" };
    const out = formatSection(multi, true);
    expect(out).toContain("line1\nline2\nline3");
  });

  it("empty body single", () => {
    const empty = { title: "T", cmd: "cmd", body: "" };
    const out = formatSection(empty, true);
    expect(out).toBe("");
  });

  it("empty body multi has fence", () => {
    const empty = { title: "T", cmd: "cmd", body: "" };
    const out = formatSection(empty, false);
    expect(out).toContain("```bash");
  });
});

// ---- guideSection ----

describe("guideSection", () => {
  it("returns section object with title, cmd, body", () => {
    const g = guideSection();
    expect(g.title).toBe("Guide");
    expect(g.cmd).toBe("jdt status guide");
    expect(typeof g.body).toBe("string");
  });

  it("body contains all section names", () => {
    const g = guideSection();
    expect(g.body).toContain("jdt status git");
    expect(g.body).toContain("jdt status editors");
    expect(g.body).toContain("jdt status errors");
    expect(g.body).toContain("jdt status launches");
    expect(g.body).toContain("jdt status tests");
    expect(g.body).toContain("jdt status projects");
    expect(g.body).toContain("jdt status guide");
  });

  it("body contains standalone commands", () => {
    const g = guideSection();
    expect(g.body).toContain("jdt editors");
    expect(g.body).toContain("jdt errors");
    expect(g.body).toContain("jdt launch list");
    expect(g.body).toContain("jdt test sessions");
    expect(g.body).toContain("jdt projects");
  });

  it("body contains combined examples", () => {
    const g = guideSection();
    expect(g.body).toContain("jdt status editors errors");
    expect(g.body).toContain("jdt status git tests");
  });

  it("body contains specialized refresh", () => {
    const g = guideSection();
    expect(g.body).toContain("jdt errors --project X");
    expect(g.body).toContain("jdt test run FQN");
    expect(g.body).toContain("jdt build --project X");
  });

  it("body explains -q behavior", () => {
    const g = guideSection();
    expect(g.body).toContain("-q suppresses this guide");
  });
});

// ---- SECTION_NAMES ----

describe("SECTION_NAMES", () => {
  it("contains all sections", () => {
    expect(SECTION_NAMES).toContain("git");
    expect(SECTION_NAMES).toContain("editors");
    expect(SECTION_NAMES).toContain("errors");
    expect(SECTION_NAMES).toContain("launches");
    expect(SECTION_NAMES).toContain("tests");
    expect(SECTION_NAMES).toContain("projects");
    expect(SECTION_NAMES).toContain("guide");
  });

  it("has 8 sections", () => {
    expect(SECTION_NAMES.length).toBe(8);
  });

  it("contains intro", () => {
    expect(SECTION_NAMES).toContain("intro");
  });
});

// ---- JSON_COMMANDS ----

describe("JSON_COMMANDS", () => {
  const dataSections = SECTION_NAMES.filter((s) => s !== "intro" && s !== "guide");

  it("covers all data sections", () => {
    for (const name of dataSections) {
      expect(JSON_COMMANDS[name]).toBeDefined();
      expect(JSON_COMMANDS[name]).toContain("--json");
    }
  });

  it("each command starts with jdt", () => {
    for (const cmd of Object.values(JSON_COMMANDS)) {
      expect(cmd).toMatch(/^jdt /);
    }
  });
});

// ---- reposFromServer ----

describe("reposFromServer", () => {
  it("groups projects by repo", () => {
    const projects = [
      { name: "m8-server", repo: "D:\\git\\m8", branch: "master" },
      { name: "m8-client", repo: "D:\\git\\m8", branch: "master" },
      { name: "jdtbridge", repo: "D:\\git\\eclipse-jdt-search", branch: "main" },
    ];
    const repos = reposFromServer(projects);
    expect(repos.length).toBe(2);
    expect(repos[0].name).toBe("m8");
    expect(repos[0].projects).toEqual(["m8-server", "m8-client"]);
    expect(repos[1].name).toBe("eclipse-jdt-search");
  });

  it("normalizes backslashes in repo path", () => {
    const repos = reposFromServer([
      { name: "p", repo: "D:\\git\\m8", branch: "x" },
    ]);
    expect(repos[0].path).toBe("D:/git/m8");
  });

  it("preserves branch per repo", () => {
    const repos = reposFromServer([
      { name: "p", repo: "D:\\git\\m8", branch: "feature/123" },
    ]);
    expect(repos[0].branch).toBe("feature/123");
  });

  it("skips projects without repo", () => {
    const repos = reposFromServer([
      { name: "standalone", location: "D:\\git\\standalone" },
    ]);
    expect(repos.length).toBe(0);
  });

  it("empty input returns empty", () => {
    expect(reposFromServer([]).length).toBe(0);
  });

  it("deduplicates repos", () => {
    const projects = [
      { name: "a", repo: "D:\\git\\r", branch: "m" },
      { name: "b", repo: "D:\\git\\r", branch: "m" },
      { name: "c", repo: "D:\\git\\r", branch: "m" },
    ];
    expect(reposFromServer(projects).length).toBe(1);
  });

  it("handles forward slashes in repo", () => {
    const repos = reposFromServer([
      { name: "p", repo: "D:/git/m8", branch: "x" },
    ]);
    expect(repos[0].path).toBe("D:/git/m8");
    expect(repos[0].name).toBe("m8");
  });
});

// ---- ago ----

describe("ago", () => {
  it("seconds", () => {
    expect(ago(5000)).toBe("5s ago");
    expect(ago(59000)).toBe("59s ago");
  });

  it("minutes", () => {
    expect(ago(60000)).toBe("1m ago");
    expect(ago(120000)).toBe("2m ago");
    expect(ago(3540000)).toBe("59m ago");
  });

  it("hours", () => {
    expect(ago(3600000)).toBe("1h ago");
    expect(ago(7200000)).toBe("2h ago");
  });

  it("zero", () => {
    expect(ago(0)).toBe("0s ago");
  });

  it("boundary: 59.9s stays seconds", () => {
    expect(ago(59999)).toBe("59s ago");
  });

  it("boundary: 60s becomes minutes", () => {
    expect(ago(60000)).toBe("1m ago");
  });

  it("boundary: 59m stays minutes", () => {
    expect(ago(3599000)).toBe("59m ago");
  });

  it("boundary: 60m becomes hours", () => {
    expect(ago(3600000)).toBe("1h ago");
  });
});

// ---- buildDirtyMap ----

describe("buildDirtyMap", () => {
  // Mock gitCmd by providing repos with known paths
  // buildDirtyMap calls gitCmd internally — these tests verify
  // the mapping logic with the real gitCmd (which returns "" for non-existent repos)

  it("returns empty map for empty repos", () => {
    expect(buildDirtyMap([])).toEqual({});
  });

  it("returns empty map for repos with no dirty files", () => {
    // Non-existent repo path — gitCmd returns ""
    const repos = [{ path: "/nonexistent/repo", name: "r", branch: "m", projects: [] }];
    expect(buildDirtyMap(repos)).toEqual({});
  });
});

// ---- cliCmd ----

describe("cliCmd", () => {
  it("preserves leading whitespace", () => {
    const out = cliCmd("echo    hello");
    expect(out).toContain("hello");
  });

  it("strips trailing newlines", () => {
    const out = cliCmd("echo hello");
    expect(out).not.toMatch(/\n$/);
  });

  it("preserves leading spaces in output", () => {
    const out = cliCmd("node -e \"process.stdout.write('   indented')\"");
    expect(out).toBe("   indented");
  });

  it("returns (error) on failure", () => {
    const out = cliCmd("nonexistent-command-xyz-12345");
    expect(out).toBe("(error)");
  });
});

// ---- Integration: formatSection + guideSection ----

describe("integration", () => {
  it("guide as single section: body only", () => {
    const out = formatSection(guideSection(), true);
    expect(out).not.toContain("## Guide");
    expect(out).not.toContain("```");
    expect(out).toContain("jdt status");
  });

  it("guide as multi section has header", () => {
    const out = formatSection(guideSection(), false);
    expect(out).toContain("## Guide");
  });

  it("section objects are composable", () => {
    const a = { title: "A", cmd: "cmd-a", body: "output-a" };
    const b = { title: "B", cmd: "cmd-b", body: "output-b" };
    const combined = [a, b].map((s) => formatSection(s, false)).join("\n\n");
    expect(combined).toContain("## A");
    expect(combined).toContain("## B");
    expect(combined).toContain("output-a");
    expect(combined).toContain("output-b");
  });

  it("single section: just body", () => {
    const s = { title: "X", cmd: "c", body: "b" };
    const out = formatSection(s, true);
    expect(out).toBe("b");
  });

  it("two sections both get headers", () => {
    const results = [
      { title: "A", cmd: "a", body: "1" },
      { title: "B", cmd: "b", body: "2" },
    ];
    const single = results.length === 1; // false
    const parts = results.map((s) => formatSection(s, single));
    expect(parts[0]).toContain("## A");
    expect(parts[1]).toContain("## B");
  });
});
