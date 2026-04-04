import { describe, it, expect, beforeEach, afterEach } from "vitest";

describe("resolveTerminalId", () => {
  const saved = {};
  const VARS = [
    "WT_SESSION", "ITERM_SESSION_ID", "TERM_SESSION_ID",
    "TMUX_PANE", "STY", "ConEmuPID",
  ];

  beforeEach(() => {
    for (const v of VARS) {
      saved[v] = process.env[v];
      delete process.env[v];
    }
  });

  afterEach(() => {
    for (const v of VARS) {
      if (saved[v] !== undefined) process.env[v] = saved[v];
      else delete process.env[v];
    }
  });

  async function load() {
    // Dynamic import to pick up env changes
    const mod = await import("../src/terminal-id.mjs");
    return mod.resolveTerminalId;
  }

  it("returns WT_SESSION when set", async () => {
    process.env.WT_SESSION = "abc-123";
    const resolveTerminalId = await load();
    expect(resolveTerminalId()).toBe("abc-123");
  });

  it("returns ITERM_SESSION_ID when WT_SESSION absent", async () => {
    process.env.ITERM_SESSION_ID = "w0t1p0";
    const resolveTerminalId = await load();
    expect(resolveTerminalId()).toBe("w0t1p0");
  });

  it("WT_SESSION takes priority over ITERM_SESSION_ID", async () => {
    process.env.WT_SESSION = "wt-win";
    process.env.ITERM_SESSION_ID = "iterm-mac";
    const resolveTerminalId = await load();
    expect(resolveTerminalId()).toBe("wt-win");
  });

  it("returns TERM_SESSION_ID when higher-priority vars absent", async () => {
    process.env.TERM_SESSION_ID = "vscode-sess";
    const resolveTerminalId = await load();
    expect(resolveTerminalId()).toBe("vscode-sess");
  });

  it("returns TMUX_PANE when set", async () => {
    process.env.TMUX_PANE = "%3";
    const resolveTerminalId = await load();
    expect(resolveTerminalId()).toBe("%3");
  });

  it("returns STY when set", async () => {
    process.env.STY = "12345.pts-0.host";
    const resolveTerminalId = await load();
    expect(resolveTerminalId()).toBe("12345.pts-0.host");
  });

  it("returns ConEmuPID when set", async () => {
    process.env.ConEmuPID = "9999";
    const resolveTerminalId = await load();
    expect(resolveTerminalId()).toBe("9999");
  });

  it("returns null when no vars set on Windows", async () => {
    const origPlatform = Object.getOwnPropertyDescriptor(process, "platform");
    Object.defineProperty(process, "platform", { value: "win32" });
    const resolveTerminalId = await load();
    const result = resolveTerminalId();
    // Restore
    if (origPlatform) Object.defineProperty(process, "platform", origPlatform);
    // On win32 with no env vars: null (tty skipped)
    expect(result).toBeNull();
  });
});
