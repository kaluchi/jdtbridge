// Configure Claude Code for JDT Bridge projects.

import { existsSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { execSync } from "node:child_process";

const JDT_HOOK_COMMAND = `node -e "d=JSON.parse(require('fs').readFileSync(0,'utf8'));d.tool_input?.command?.startsWith('jdt ')&&process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:'PreToolUse',permissionDecision:'allow'}}))"`;

/**
 * Find the project root (git root or cwd).
 * @returns {string}
 */
export function findProjectRoot() {
  try {
    return execSync("git rev-parse --show-toplevel", {
      encoding: "utf8",
      stdio: ["pipe", "pipe", "pipe"],
    }).trim();
  } catch {
    return process.cwd();
  }
}

/**
 * Merge JDT Bridge settings into a Claude Code settings object.
 * Idempotent — safe to call multiple times.
 * @param {object} settings existing settings (mutated in place)
 * @returns {object} the mutated settings
 */
export function mergeJdtSettings(settings) {
  // Permissions
  if (!settings.permissions) settings.permissions = {};
  const allow = settings.permissions.allow || [];
  if (!allow.includes("Bash(jdt *)")) allow.push("Bash(jdt *)");
  settings.permissions.allow = allow;

  // Hook
  if (!settings.hooks) settings.hooks = {};
  if (!settings.hooks.PreToolUse) settings.hooks.PreToolUse = [];
  const existing = settings.hooks.PreToolUse.find(
    (h) =>
      h.matcher === "Bash" &&
      h.hooks?.some((hk) => hk.command === JDT_HOOK_COMMAND),
  );
  if (!existing) {
    settings.hooks.PreToolUse.push({
      matcher: "Bash",
      hooks: [{ type: "command", command: JDT_HOOK_COMMAND }],
    });
  }

  return settings;
}

/**
 * Write Claude Code settings for JDT Bridge to a project.
 * Creates .claude/settings.json if it doesn't exist,
 * merges into existing if it does.
 * @param {string} [root] project root (default: git root or cwd)
 * @returns {{ file: string, settings: object }}
 */
export function installClaudeSettings(root) {
  if (!root) root = findProjectRoot();

  const dir = join(root, ".claude");
  const file = join(dir, "settings.json");

  let settings = {};
  if (existsSync(file)) {
    try {
      settings = JSON.parse(readFileSync(file, "utf8"));
    } catch {
      // corrupt — overwrite
    }
  }

  mergeJdtSettings(settings);

  mkdirSync(dir, { recursive: true });
  writeFileSync(file, JSON.stringify(settings, null, 2) + "\n");

  return { file, settings };
}
