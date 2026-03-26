import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { run } from "../src/cli.mjs";

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
    expect(output).toContain("subt");
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
});
