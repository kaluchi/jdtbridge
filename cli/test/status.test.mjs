import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  formatSection, helpSection, guideSection, reposFromServer,
  buildDirtyMap, ago, cliCmd, SECTION_NAMES, JSON_COMMANDS,
} from "../src/commands/status.mjs";

// ---- formatSection ----

describe("formatSection", () => {
  const s = { title: "Problems", cmd: "jdt problems --json", body: "[]" };

  it("bare: body only, no header, no fence", () => {
    const out = formatSection(s, { bare: true, quiet: false });
    expect(out).toBe("[]");
  });

  it("bare ignores quiet", () => {
    const out = formatSection(s, { bare: true, quiet: true });
    expect(out).toBe("[]");
  });

  it("multi: has ## header and code fence", () => {
    const out = formatSection(s, { bare: false, quiet: false });
    expect(out).toContain("## Problems");
    expect(out).toContain("```bash");
    expect(out).toContain("$ jdt problems --json");
  });

  it("multi: code fence wraps body", () => {
    const out = formatSection(s, { bare: false, quiet: false });
    expect(out).toContain("[]");
    expect(out).toMatch(/```$/);
  });

  it("description shown when not quiet", () => {
    const section = { title: "Git", cmd: "jdt git", body: "output", description: "Git repos." };
    const out = formatSection(section, { bare: false, quiet: false });
    expect(out).toContain("Git repos.");
    expect(out).toContain("## Git");
    expect(out).toContain("output");
  });

  it("description suppressed when quiet", () => {
    const section = { title: "Git", cmd: "jdt git", body: "output", description: "Git repos." };
    const out = formatSection(section, { bare: false, quiet: true });
    expect(out).not.toContain("Git repos.");
    expect(out).toContain("## Git");
    expect(out).toContain("output");
  });

  it("description suppressed when bare", () => {
    const section = { title: "Git", cmd: "jdt git", body: "output", description: "Git repos." };
    const out = formatSection(section, { bare: true, quiet: false });
    expect(out).toBe("output");
  });

  it("no description field: no extra blank lines", () => {
    const out = formatSection(s, { bare: false, quiet: false });
    expect(out).toBe("## Problems\n\n```bash\n$ jdt problems --json\n[]\n```");
  });

  it("multiline body preserved", () => {
    const multi = { title: "T", cmd: "cmd", body: "line1\nline2\nline3" };
    const out = formatSection(multi, { bare: true, quiet: false });
    expect(out).toContain("line1\nline2\nline3");
  });

  it("empty body bare", () => {
    const empty = { title: "T", cmd: "cmd", body: "" };
    const out = formatSection(empty, { bare: true, quiet: false });
    expect(out).toBe("");
  });

  it("empty body multi has fence", () => {
    const empty = { title: "T", cmd: "cmd", body: "" };
    const out = formatSection(empty, { bare: false, quiet: false });
    expect(out).toContain("```bash");
  });
});

// ---- helpSection ----

describe("helpSection", () => {
  it("returns section object with title, cmd, body", () => {
    const h = helpSection();
    expect(h.title).toBe("Help");
    expect(h.cmd).toBe("jdt help");
    expect(typeof h.body).toBe("string");
  });

  it("body contains jdt help output", () => {
    const h = helpSection();
    // On CI, jdt may not be in PATH → cliCmd returns "(error)"
    if (h.body === "(error)") return;
    expect(h.body).toContain("Eclipse JDT Bridge");
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

  it("body contains specialized refresh", () => {
    const g = guideSection();
    expect(g.body).toContain("jdt problems --project X");
    expect(g.body).toContain("jdt test run FQN");
    expect(g.body).toContain("jdt build --project X");
  });

  it("body contains dashboard refresh hints", () => {
    const g = guideSection();
    expect(g.body).toContain("jdt status -q");
    expect(g.body).toContain("jdt help <command>");
  });
});

// ---- SECTION_NAMES ----

describe("SECTION_NAMES", () => {
  it("contains all sections", () => {
    expect(SECTION_NAMES).toContain("intro");
    expect(SECTION_NAMES).toContain("git");
    expect(SECTION_NAMES).toContain("editors");
    expect(SECTION_NAMES).toContain("problems");
    expect(SECTION_NAMES).toContain("launch-configs");
    expect(SECTION_NAMES).toContain("launches");
    expect(SECTION_NAMES).toContain("tests");
    expect(SECTION_NAMES).toContain("projects");
    expect(SECTION_NAMES).toContain("help");
    expect(SECTION_NAMES).toContain("guide");
  });

  it("has 10 sections", () => {
    expect(SECTION_NAMES.length).toBe(10);
  });

  it("help comes after projects and before guide", () => {
    const helpIdx = SECTION_NAMES.indexOf("help");
    const projectsIdx = SECTION_NAMES.indexOf("projects");
    const guideIdx = SECTION_NAMES.indexOf("guide");
    expect(helpIdx).toBeGreaterThan(projectsIdx);
    expect(helpIdx).toBeLessThan(guideIdx);
  });
});

// ---- JSON_COMMANDS ----

describe("JSON_COMMANDS", () => {
  const metaSections = new Set(["intro", "guide", "help"]);
  const dataSections = SECTION_NAMES.filter((s) => !metaSections.has(s));

  it("covers all data sections", () => {
    for (const name of dataSections) {
      expect(JSON_COMMANDS[name]).toBeDefined();
      expect(JSON_COMMANDS[name]).toContain("--json");
    }
  });

  it("excludes meta sections", () => {
    for (const name of metaSections) {
      expect(JSON_COMMANDS[name]).toBeUndefined();
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
      { name: "my-server", repo: "D:\\git\\my-app", branch: "master" },
      { name: "my-client", repo: "D:\\git\\my-app", branch: "master" },
      { name: "jdtbridge", repo: "D:\\git\\eclipse-jdt-search", branch: "main" },
    ];
    const repos = reposFromServer(projects);
    expect(repos.length).toBe(2);
    expect(repos[0].name).toBe("my-app");
    expect(repos[0].projects).toEqual(["my-server", "my-client"]);
    expect(repos[1].name).toBe("eclipse-jdt-search");
  });

  it("normalizes backslashes in repo path", () => {
    const repos = reposFromServer([
      { name: "p", repo: "D:\\git\\my-app", branch: "x" },
    ]);
    expect(repos[0].path).toBe("D:/git/my-app");
  });

  it("preserves branch per repo", () => {
    const repos = reposFromServer([
      { name: "p", repo: "D:\\git\\my-app", branch: "feature/123" },
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
      { name: "p", repo: "D:/git/my-app", branch: "x" },
    ]);
    expect(repos[0].path).toBe("D:/git/my-app");
    expect(repos[0].name).toBe("my-app");
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
  it("returns empty map for empty repos", () => {
    expect(buildDirtyMap([])).toEqual({});
  });

  it("returns empty map for repos with no dirty files", () => {
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

// ---- Integration: formatSection + sections ----

describe("integration", () => {
  it("guide as bare: body only", () => {
    const out = formatSection(guideSection(), { bare: true, quiet: false });
    expect(out).not.toContain("## Guide");
    expect(out).not.toContain("```");
    expect(out).toContain("jdt status");
  });

  it("guide as multi has header", () => {
    const out = formatSection(guideSection(), { bare: false, quiet: false });
    expect(out).toContain("## Guide");
  });

  it("section objects are composable", () => {
    const a = { title: "A", cmd: "cmd-a", body: "output-a" };
    const b = { title: "B", cmd: "cmd-b", body: "output-b" };
    const combined = [a, b].map((s) => formatSection(s, { bare: false, quiet: false })).join("\n\n");
    expect(combined).toContain("## A");
    expect(combined).toContain("## B");
    expect(combined).toContain("output-a");
    expect(combined).toContain("output-b");
  });

  it("bare section: just body", () => {
    const s = { title: "X", cmd: "c", body: "b" };
    const out = formatSection(s, { bare: true, quiet: false });
    expect(out).toBe("b");
  });

  it("two sections both get headers", () => {
    const results = [
      { title: "A", cmd: "a", body: "1" },
      { title: "B", cmd: "b", body: "2" },
    ];
    const parts = results.map((s) => formatSection(s, { bare: false, quiet: false }));
    expect(parts[0]).toContain("## A");
    expect(parts[1]).toContain("## B");
  });

  it("description appears between header and fence", () => {
    const s = { title: "T", cmd: "c", body: "b", description: "Desc here." };
    const out = formatSection(s, { bare: false, quiet: false });
    const headerIdx = out.indexOf("## T");
    const descIdx = out.indexOf("Desc here.");
    const fenceIdx = out.indexOf("```bash");
    expect(descIdx).toBeGreaterThan(headerIdx);
    expect(descIdx).toBeLessThan(fenceIdx);
  });
});
