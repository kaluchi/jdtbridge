import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { resetHome } from "../src/home.mjs";
import { setColorEnabled } from "../src/color.mjs";

function captureConsole() {
  const logs = [];
  const errors = [];
  const origLog = console.log;
  const origError = console.error;
  const origExit = process.exit;
  console.log = (...args) => logs.push(args.join(" "));
  console.error = (...args) => errors.push(args.join(" "));
  process.exit = (code) => { throw new Error(`exit(${code})`); };
  return {
    logs, errors,
    restore() {
      console.log = origLog;
      console.error = origError;
      process.exit = origExit;
    },
  };
}

describe("jdt use", () => {
  let testDir, instDir;
  let origEnv, io;

  beforeEach(() => {
    setColorEnabled(false);
    testDir = mkdtempSync(join(tmpdir(), "jdtbridge-use-"));
    instDir = join(testDir, "instances");
    mkdirSync(instDir, { recursive: true });
    origEnv = {
      JDTBRIDGE_HOME: process.env.JDTBRIDGE_HOME,
      WT_SESSION: process.env.WT_SESSION,
    };
    process.env.JDTBRIDGE_HOME = testDir;
    delete process.env.WT_SESSION;
    resetHome();
    io = captureConsole();
  });

  afterEach(() => {
    io.restore();
    for (const [k, v] of Object.entries(origEnv)) {
      if (v !== undefined) process.env[k] = v;
      else delete process.env[k];
    }
    resetHome();
    vi.resetModules();
  });

  function writeInstance(name, data) {
    writeFileSync(join(instDir, `${name}.json`), JSON.stringify(data));
  }

  // --- list ---

  it("list shows empty message when no instances", async () => {
    const { use } = await import("../src/commands/use.mjs");
    await use([]);
    expect(io.logs[0]).toContain("No workspaces registered");
  });

  it("list shows discovered instances", async () => {
    writeInstance("inst1", {
      port: 58800, token: "a", pid: process.pid,
      workspace: "D:\\ws\\alpha", version: "2.4.0",
    });
    const { use } = await import("../src/commands/use.mjs");
    await use([]);
    const out = io.logs.join("\n");
    expect(out).toContain("alpha");
    expect(out).toContain("58800");
    expect(out).toContain("*new*");
  });

  it("list --json outputs JSON array", async () => {
    writeInstance("inst1", {
      port: 58800, token: "a", pid: process.pid,
      workspace: "D:\\ws\\alpha", version: "2.4.0",
    });
    const { use } = await import("../src/commands/use.mjs");
    await use(["--json"]);
    const json = JSON.parse(io.logs.join(""));
    expect(json).toHaveLength(1);
    expect(json[0].workspace).toBe("D:\\ws\\alpha");
    expect(json[0].status).toBe("new");
    expect(json[0].port).toBe(58800);
  });

  it("list marks online on second call", async () => {
    writeInstance("inst1", {
      port: 58800, token: "a", pid: process.pid,
      workspace: "D:\\ws\\alpha", version: "2.4.0",
    });
    const { use } = await import("../src/commands/use.mjs");
    await use([]); // first call — *new*
    io.logs.length = 0;
    await use([]); // second call — online
    const out = io.logs.join("\n");
    expect(out).toContain("online");
    expect(out).not.toContain("*new*");
  });

  // --- pin ---

  it("pin by number writes pin files", async () => {
    writeInstance("inst1", {
      port: 58800, token: "a", pid: process.pid,
      workspace: "D:\\ws\\alpha",
    });
    // Seed the registry first
    const { use } = await import("../src/commands/use.mjs");
    await use([]); // discover
    io.logs.length = 0;

    await use(["1"]);
    expect(io.logs[0]).toContain("Pinned to");
    expect(io.logs[0]).toContain("alpha");
  });

  it("pin fails when workspace offline", async () => {
    const { writeWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([{ workspace: "D:\\ws\\gone", addedAt: "" }]);
    const { use } = await import("../src/commands/use.mjs");
    await expect(use(["1"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("offline");
  });

  it("pin by alias works", async () => {
    writeInstance("inst1", {
      port: 58800, token: "a", pid: process.pid,
      workspace: "D:\\ws\\alpha",
    });
    const { writeWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([{
      workspace: "D:\\ws\\alpha", alias: "dev", addedAt: "",
    }]);
    const { use } = await import("../src/commands/use.mjs");
    await use(["dev"]);
    expect(io.logs[0]).toContain("Pinned to");
    expect(io.logs[0]).toContain("dev");
  });

  it("pin by path substring works", async () => {
    writeInstance("inst1", {
      port: 58800, token: "a", pid: process.pid,
      workspace: "D:\\ws\\my-long-workspace-name",
    });
    const { writeWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([{
      workspace: "D:\\ws\\my-long-workspace-name", addedAt: "",
    }]);
    const { use } = await import("../src/commands/use.mjs");
    await use(["my-long"]);
    expect(io.logs[0]).toContain("Pinned to");
  });

  it("pin fails for unknown target", async () => {
    const { use } = await import("../src/commands/use.mjs");
    await expect(use(["nonexistent"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("No workspace matching");
  });

  // --- alias ---

  it("set alias", async () => {
    const { writeWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([{ workspace: "D:\\ws\\alpha", addedAt: "" }]);
    const { use } = await import("../src/commands/use.mjs");
    await use(["1", "--alias", "dev"]);
    expect(io.logs[0]).toContain("Alias set");
    expect(io.logs[0]).toContain("dev");

    const { readWorkspaces } = await import("../src/home.mjs");
    const ws = readWorkspaces();
    expect(ws[0].alias).toBe("dev");
  });

  it("rejects invalid alias", async () => {
    const { writeWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([{ workspace: "D:\\ws\\alpha", addedAt: "" }]);
    const { use } = await import("../src/commands/use.mjs");
    await expect(use(["1", "--alias", "no spaces!"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("alphanumeric");
  });

  it("rejects duplicate alias", async () => {
    const { writeWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([
      { workspace: "D:\\ws\\a", alias: "dev", addedAt: "" },
      { workspace: "D:\\ws\\b", addedAt: "" },
    ]);
    const { use } = await import("../src/commands/use.mjs");
    await expect(use(["2", "--alias", "dev"])).rejects.toThrow("exit(1)");
    expect(io.errors[0]).toContain("already used");
  });

  // --- delete ---

  it("delete removes entry", async () => {
    const { writeWorkspaces, readWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([
      { workspace: "D:\\ws\\a", addedAt: "" },
      { workspace: "D:\\ws\\b", addedAt: "" },
    ]);
    const { use } = await import("../src/commands/use.mjs");
    await use(["--delete", "1"]);
    expect(io.logs[0]).toContain("Removed");
    const ws = readWorkspaces();
    expect(ws).toHaveLength(1);
    expect(ws[0].workspace).toBe("D:\\ws\\b");
  });

  // --- pins ---

  it("--pins shows no pins when empty", async () => {
    const { use } = await import("../src/commands/use.mjs");
    await use(["--pins"]);
    expect(io.logs[0]).toContain("No active pins");
  });

  it("--pins shows pin files", async () => {
    const { writePin } = await import("../src/home.mjs");
    writePin("term-test-sess.json", {
      workspace: "D:\\ws\\alpha", pinnedAt: "2026-01-01T00:00:00Z",
    });
    const { use } = await import("../src/commands/use.mjs");
    await use(["--pins"]);
    const out = io.logs.join("\n");
    expect(out).toContain("terminal");
    expect(out).toContain("alpha");
  });

  it("--pins --json outputs JSON", async () => {
    const { writePin } = await import("../src/home.mjs");
    writePin("ppid-12345.json", {
      workspace: "D:\\ws\\alpha", pinnedAt: "2026-01-01T00:00:00Z",
    });
    const { use } = await import("../src/commands/use.mjs");
    await use(["--pins", "--json"]);
    const json = JSON.parse(io.logs.join(""));
    expect(json).toHaveLength(1);
    expect(json[0].pinType).toBe("ppid");
    expect(json[0].workspace).toBe("D:\\ws\\alpha");
  });

  // --- pinned indicator ---

  it("list shows pinned column for active workspace", async () => {
    process.env.WT_SESSION = "pin-indicator-test";
    writeInstance("inst1", {
      port: 58800, token: "a", pid: process.pid,
      workspace: "D:\\ws\\alpha",
    });
    writeInstance("inst2", {
      port: 58900, token: "b", pid: process.pid,
      workspace: "D:\\ws\\beta",
    });
    const { writePin, writeWorkspaces } = await import("../src/home.mjs");
    writeWorkspaces([
      { workspace: "D:\\ws\\alpha", addedAt: "" },
      { workspace: "D:\\ws\\beta", addedAt: "" },
    ]);
    writePin("term-pin-indicator-test.json", {
      workspace: "D:\\ws\\beta", pinnedAt: "",
    });

    const { use } = await import("../src/commands/use.mjs");
    await use([]);
    const out = io.logs.join("\n");
    // beta should have "pinned", alpha should not
    const lines = out.split("\n");
    const alphaLine = lines.find(l => l.includes("alpha"));
    const betaLine = lines.find(l => l.includes("beta"));
    expect(betaLine).toContain("pinned");
    expect(alphaLine).not.toContain("pinned");
  });
});
