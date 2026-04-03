import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { run, helpLine, stripAnsi, HELP_COL } from "../src/cli.mjs";

describe("cli dispatcher", () => {
  let logs;
  let errors;
  let exitCode;
  const origLog = console.log;
  const origError = console.error;
  const origExit = process.exit;

  beforeEach(() => {
    logs = [];
    errors = [];
    exitCode = null;
    console.log = (...args) => logs.push(args.join(" "));
    console.error = (...args) => errors.push(args.join(" "));
    process.exit = (code) => {
      exitCode = code;
      throw new Error(`exit(${code})`);
    };
  });

  afterEach(() => {
    console.log = origLog;
    console.error = origError;
    process.exit = origExit;
  });

  it("shows help with --help", async () => {
    await run(["--help"]);
    expect(logs.some((l) => l.includes("Eclipse JDT Bridge"))).toBe(true);
  });

  it("shows help with no args", async () => {
    await run([]);
    expect(logs.some((l) => l.includes("Eclipse JDT Bridge"))).toBe(true);
  });

  it("shows command help for known command", async () => {
    await run(["help", "find"]);
    expect(logs.some((l) => l.includes("find"))).toBe(true);
  });

  it("exits with error for unknown command", async () => {
    try {
      await run(["nonexistent-command"]);
    } catch {}
    expect(exitCode).toBe(1);
    expect(errors.some((l) => l.includes("Unknown command"))).toBe(true);
  });

  it("shows unknown command in help topic error", async () => {
    await run(["help", "nonexistent"]);
    expect(errors.some((l) => l.includes("Unknown command: nonexistent"))).toBe(true);
  });

  it("resolves alias in help", async () => {
    await run(["help", "refs"]);
    expect(logs.some((l) => l.includes("references"))).toBe(true);
  });

  it("shows aliases in overview", async () => {
    await run(["--help"]);
    const output = logs.join("\n");
    expect(output).toContain("refs");
    expect(output).toContain("impl");
    expect(output).toContain("impl");
    expect(output).toContain("hier");
    expect(output).toContain("src");
    expect(output).toContain("err");
    expect(output).toContain("fmt");
  });

  it("'test' with no subcommand shows help", async () => {
    await run(["test"]);
    expect(logs.some((l) => l.includes("Subcommands"))).toBe(true);
  });

  it("'help test' shows test help", async () => {
    await run(["help", "test"]);
    expect(logs.some((l) => l.includes("Subcommands"))).toBe(true);
  });

  it("'help test run' shows test run help", async () => {
    await run(["help", "test", "run"]);
    expect(logs.some((l) => l.includes("Launch tests non-blocking"))).toBe(true);
  });

  it("'help test status' shows test status help", async () => {
    await run(["help", "test", "status"]);
    expect(logs.some((l) => l.includes("snapshot or live stream"))).toBe(true);
  });

  it("help overview has aligned descriptions", async () => {
    await run(["--help"]);
    const output = logs.join("\n");
    // Command lines: indented 2 spaces, longer than HELP_COL
    const cmdLines = output.split("\n").filter((line) => {
      const plain = stripAnsi(line);
      return /^  \S/.test(plain) && plain.length > HELP_COL;
    });
    expect(cmdLines.length).toBeGreaterThan(20);
    for (const line of cmdLines) {
      const plain = stripAnsi(line);
      // Find description start: after last 2+ space gap
      const match = plain.match(/^(.+?)\s{2,}(\S.*)$/);
      expect(match).not.toBeNull();
      const descStart = plain.indexOf(match[2], match[1].length);
      // Description never starts before HELP_COL
      expect(descStart).toBeGreaterThanOrEqual(HELP_COL);
    }
  });
});

describe("helpLine", () => {
  it("pads short left to HELP_COL", () => {
    const line = helpLine("  status [-q]", "dashboard");
    const plain = stripAnsi(line);
    expect(plain.indexOf("dashboard")).toBe(HELP_COL);
  });

  it("uses minimum 2-space gap for long left", () => {
    const longLeft = "  " + "x".repeat(HELP_COL);
    const line = helpLine(longLeft, "desc");
    const plain = stripAnsi(line);
    expect(plain.indexOf("desc")).toBe(HELP_COL + 2 + 2); // 2 indent + HELP_COL x's + 2 gap
  });

  it("handles ANSI codes in left part", () => {
    const left = "  cmd \x1b[2m(alias)\x1b[22m <arg>";
    const line = helpLine(left, "description");
    const plain = stripAnsi(line);
    // Visible: "  cmd (alias) <arg>" = 19 chars → description at HELP_COL
    expect(plain.indexOf("description")).toBe(HELP_COL);
  });
});

describe("stripAnsi", () => {
  it("strips dim codes", () => {
    expect(stripAnsi("\x1b[2mtext\x1b[22m")).toBe("text");
  });

  it("returns plain text unchanged", () => {
    expect(stripAnsi("hello")).toBe("hello");
  });
});
