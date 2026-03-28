import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { mergeJdtSettings, installClaudeSettings } from "../src/claude-setup.mjs";

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
      join(dir, "settings.json"),
      JSON.stringify({ permissions: { allow: ["Bash(git *)"] } }),
    );

    const { settings } = installClaudeSettings(tmpDir);
    expect(settings.permissions.allow).toContain("Bash(git *)");
    expect(settings.permissions.allow).toContain("Bash(jdt *)");
  });

  it("is idempotent on disk", () => {
    installClaudeSettings(tmpDir);
    installClaudeSettings(tmpDir);
    const file = join(tmpDir, ".claude", "settings.json");
    const content = JSON.parse(readFileSync(file, "utf8"));
    expect(content.permissions.allow.filter((r) => r === "Bash(jdt *)")).toHaveLength(1);
    expect(content.hooks.PreToolUse).toHaveLength(1);
  });
});
