import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { mergeJdtSettings, installClaudeSettings, uninstallClaudeSettings } from "../src/claude-setup.mjs";

describe("mergeJdtSettings", () => {
  it("adds permission and hook to empty object", () => {
    const result = mergeJdtSettings({});
    expect(result.permissions.allow).toContain("Bash(jdt *)");
    expect(result.hooks.PreToolUse).toHaveLength(1);
    expect(result.hooks.PreToolUse[0].matcher).toBe("Bash");
    expect(result.hooks.PreToolUse[0].hooks[0].command).toContain("jdt ");
  });

  it("preserves existing permissions", () => {
    const result = mergeJdtSettings({
      permissions: { allow: ["Bash(git *)"] },
    });
    expect(result.permissions.allow).toContain("Bash(git *)");
    expect(result.permissions.allow).toContain("Bash(jdt *)");
  });

  it("preserves existing hooks", () => {
    const result = mergeJdtSettings({
      hooks: {
        PostToolUse: [{ matcher: "Write", hooks: [] }],
      },
    });
    // Existing Write hook + new jdt refresh hook
    expect(result.hooks.PostToolUse).toHaveLength(2);
    expect(result.hooks.PostToolUse[0].matcher).toBe("Write");
    expect(result.hooks.PreToolUse).toHaveLength(1);
  });

  it("is idempotent", () => {
    const settings = {};
    mergeJdtSettings(settings);
    mergeJdtSettings(settings);
    expect(settings.permissions.allow.filter((r) => r === "Bash(jdt *)")).toHaveLength(1);
    expect(settings.hooks.PreToolUse).toHaveLength(1);
    expect(settings.hooks.PostToolUse).toHaveLength(1);
  });

  it("adds PostToolUse refresh hook for Edit|Write", () => {
    const result = mergeJdtSettings({});
    expect(result.hooks.PostToolUse).toHaveLength(1);
    expect(result.hooks.PostToolUse[0].matcher).toBe("Edit|Write");
    expect(result.hooks.PostToolUse[0].hooks[0].command).toContain("jdt");
    expect(result.hooks.PostToolUse[0].hooks[0].command).toContain("refresh");
  });

  it("does not duplicate if exact hook already exists", () => {
    const settings = {};
    mergeJdtSettings(settings);
    const hookCommand = settings.hooks.PreToolUse[0].hooks[0].command;
    // Add same hook manually — should not duplicate
    mergeJdtSettings(settings);
    expect(settings.hooks.PreToolUse).toHaveLength(1);
    expect(settings.hooks.PreToolUse[0].hooks[0].command).toBe(hookCommand);
  });

  it("adds hook alongside unrelated Bash hook", () => {
    const settings = {
      hooks: {
        PreToolUse: [
          {
            matcher: "Bash",
            hooks: [{ type: "command", command: "echo other check" }],
          },
        ],
      },
    };
    mergeJdtSettings(settings);
    expect(settings.hooks.PreToolUse).toHaveLength(2);
  });
});

describe("installClaudeSettings", () => {
  let tmpDir;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), "jdt-claude-test-"));
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it("creates .claude/settings.json in project root", () => {
    const { file, settings } = installClaudeSettings(tmpDir);
    expect(file).toContain(".claude");
    expect(settings.permissions.allow).toContain("Bash(jdt *)");
    const content = JSON.parse(readFileSync(file, "utf8"));
    expect(content.hooks.PreToolUse).toHaveLength(1);
  });

  it("merges into existing settings", () => {
    const dir = join(tmpDir, ".claude");
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      join(dir, "settings.local.json"),
      JSON.stringify({ permissions: { allow: ["Bash(git *)"] } }),
    );

    const { settings } = installClaudeSettings(tmpDir);
    expect(settings.permissions.allow).toContain("Bash(git *)");
    expect(settings.permissions.allow).toContain("Bash(jdt *)");
  });

  it("is idempotent on disk", () => {
    installClaudeSettings(tmpDir);
    installClaudeSettings(tmpDir);
    const file = join(tmpDir, ".claude", "settings.local.json");
    const content = JSON.parse(readFileSync(file, "utf8"));
    expect(content.permissions.allow.filter((r) => r === "Bash(jdt *)")).toHaveLength(1);
    expect(content.hooks.PreToolUse).toHaveLength(1);
  });

  it("writes to settings.local.json not settings.json", () => {
    installClaudeSettings(tmpDir);
    expect(existsSync(join(tmpDir, ".claude", "settings.local.json"))).toBe(true);
  });

  // ---- Agent installation ----

  it("installs Explore and Plan agents", () => {
    installClaudeSettings(tmpDir);
    const agentsDir = join(tmpDir, ".claude", "agents");
    expect(existsSync(join(agentsDir, "Explore.md"))).toBe(true);
    expect(existsSync(join(agentsDir, "Plan.md"))).toBe(true);
  });

  it("agents contain jdtbridge marker", () => {
    installClaudeSettings(tmpDir);
    const explore = readFileSync(
      join(tmpDir, ".claude", "agents", "Explore.md"), "utf8");
    expect(explore).toContain("jdtbridge-managed");
  });

  it("agents contain jdt commands", () => {
    installClaudeSettings(tmpDir);
    const explore = readFileSync(
      join(tmpDir, ".claude", "agents", "Explore.md"), "utf8");
    expect(explore).toContain("jdt refs");
    expect(explore).toContain("jdt src");
    const plan = readFileSync(
      join(tmpDir, ".claude", "agents", "Plan.md"), "utf8");
    expect(plan).toContain("jdt src");
    expect(plan).toContain("jdt hierarchy");
  });

  it("does not overwrite non-jdt agent", () => {
    const agentsDir = join(tmpDir, ".claude", "agents");
    mkdirSync(agentsDir, { recursive: true });
    writeFileSync(join(agentsDir, "Explore.md"), "custom agent\n");

    installClaudeSettings(tmpDir);
    const content = readFileSync(
      join(agentsDir, "Explore.md"), "utf8");
    expect(content).toBe("custom agent\n");
  });

  it("updates existing jdt agent", () => {
    installClaudeSettings(tmpDir);
    // Simulate older version
    const agentFile = join(tmpDir, ".claude", "agents", "Explore.md");
    writeFileSync(agentFile, "old jdt agent\n<!-- jdtbridge-managed -->\n");

    installClaudeSettings(tmpDir);
    const content = readFileSync(agentFile, "utf8");
    expect(content).toContain("jdt refs");
    expect(content).toContain("jdtbridge-managed");
  });

  // ---- Migration from settings.json ----

  it("migrates hooks from settings.json to local", () => {
    const dir = join(tmpDir, ".claude");
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "settings.json"), JSON.stringify({
      permissions: { allow: ["Bash(jdt *)", "Bash(git *)"] },
      hooks: {
        PreToolUse: [{
          matcher: "Bash",
          hooks: [{ type: "command", command: "jdt check" }],
        }],
      },
    }));

    installClaudeSettings(tmpDir);

    // settings.json should have jdt removed
    const main = JSON.parse(readFileSync(
      join(dir, "settings.json"), "utf8"));
    expect(main.permissions.allow).not.toContain("Bash(jdt *)");
    expect(main.permissions.allow).toContain("Bash(git *)");
    expect(main.hooks?.PreToolUse || []).toHaveLength(0);

    // settings.local.json should have jdt
    const local = JSON.parse(readFileSync(
      join(dir, "settings.local.json"), "utf8"));
    expect(local.permissions.allow).toContain("Bash(jdt *)");
  });
});

// ---- uninstall ----

describe("uninstallClaudeSettings", () => {
  let tmpDir;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), "jdt-claude-test-"));
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it("removes hooks from settings.local.json", () => {
    installClaudeSettings(tmpDir);
    uninstallClaudeSettings(tmpDir);
    const file = join(tmpDir, ".claude", "settings.local.json");
    const content = JSON.parse(readFileSync(file, "utf8"));
    expect(content.hooks).toBeUndefined();
    expect(content.permissions).toBeUndefined();
  });

  it("removes jdt agents", () => {
    installClaudeSettings(tmpDir);
    uninstallClaudeSettings(tmpDir);
    expect(existsSync(
      join(tmpDir, ".claude", "agents", "Explore.md"))).toBe(false);
    expect(existsSync(
      join(tmpDir, ".claude", "agents", "Plan.md"))).toBe(false);
  });

  it("preserves non-jdt agents", () => {
    installClaudeSettings(tmpDir);
    const custom = join(tmpDir, ".claude", "agents", "Custom.md");
    writeFileSync(custom, "my agent\n");

    uninstallClaudeSettings(tmpDir);
    expect(existsSync(custom)).toBe(true);
    expect(readFileSync(custom, "utf8")).toBe("my agent\n");
  });
});
