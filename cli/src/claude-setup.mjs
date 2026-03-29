// Configure Claude Code for JDT Bridge projects.

import { existsSync, readFileSync, writeFileSync, mkdirSync, readdirSync, unlinkSync } from "node:fs";
import { join, dirname } from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const JDT_HOOK_COMMAND = `node -e "d=JSON.parse(require('fs').readFileSync(0,'utf8'));d.tool_input?.command?.startsWith('jdt ')&&process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:'PreToolUse',permissionDecision:'allow'}}))"`;

const JDT_REFRESH_HOOK_COMMAND = `node -e "const d=JSON.parse(require('fs').readFileSync(0,'utf8'));const f=d.tool_input?.file_path;if(f){try{require('child_process').execSync('jdt refresh '+JSON.stringify(f),{timeout:5000,stdio:'ignore',shell:true})}catch{}}"`;

const JDT_SUBAGENT_HOOK_COMMAND = `node -e "process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:'SubagentStart',additionalContext:'You are a subagent. Do NOT read AGENTS.md or run environment checks. Go straight to the task.'}}))"`;

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

  // PostToolUse: refresh Eclipse on Edit/Write of .java files
  if (!settings.hooks.PostToolUse) settings.hooks.PostToolUse = [];
  // Remove any old jdt refresh hooks (command may have changed)
  settings.hooks.PostToolUse = settings.hooks.PostToolUse.filter(
    (h) =>
      !(h.matcher === "Edit|Write" &&
        h.hooks?.some((hk) => hk.command?.includes("jdt") && hk.command?.includes("refresh"))),
  );
  settings.hooks.PostToolUse.push({
    matcher: "Edit|Write",
    hooks: [{ type: "command", command: JDT_REFRESH_HOOK_COMMAND }],
  });

  // SubagentStart: tell subagents to skip AGENTS.md
  if (!settings.hooks.SubagentStart) settings.hooks.SubagentStart = [];
  settings.hooks.SubagentStart = settings.hooks.SubagentStart.filter(
    (h) => !h.hooks?.some(
      (hk) => hk.command?.includes("SubagentStart")));
  settings.hooks.SubagentStart.push({
    hooks: [{ type: "command", command: JDT_SUBAGENT_HOOK_COMMAND }],
  });

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
  const file = join(dir, "settings.local.json");

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

  // Migrate: remove jdt hooks from settings.json if present
  const mainFile = join(dir, "settings.json");
  if (existsSync(mainFile)) {
    try {
      const main = JSON.parse(readFileSync(mainFile, "utf8"));
      let changed = false;
      if (main.hooks?.PreToolUse) {
        const before = main.hooks.PreToolUse.length;
        main.hooks.PreToolUse = main.hooks.PreToolUse.filter(
          (h) => !h.hooks?.some(
            (hk) => hk.command?.includes("jdt ")));
        if (main.hooks.PreToolUse.length !== before) changed = true;
        if (main.hooks.PreToolUse.length === 0) delete main.hooks.PreToolUse;
      }
      if (main.hooks?.PostToolUse) {
        const before = main.hooks.PostToolUse.length;
        main.hooks.PostToolUse = main.hooks.PostToolUse.filter(
          (h) => !(h.hooks?.some(
            (hk) => hk.command?.includes("jdt") && hk.command?.includes("refresh"))));
        if (main.hooks.PostToolUse.length !== before) changed = true;
        if (main.hooks.PostToolUse.length === 0) delete main.hooks.PostToolUse;
      }
      if (main.permissions?.allow) {
        const before = main.permissions.allow.length;
        main.permissions.allow = main.permissions.allow.filter(
          (r) => r !== "Bash(jdt *)");
        if (main.permissions.allow.length !== before) changed = true;
        if (main.permissions.allow.length === 0) delete main.permissions.allow;
      }
      if (main.hooks && Object.keys(main.hooks).length === 0) delete main.hooks;
      if (main.permissions && Object.keys(main.permissions).length === 0) delete main.permissions;
      if (changed) {
        writeFileSync(mainFile, JSON.stringify(main, null, 2) + "\n");
      }
    } catch { /* skip migration */ }
  }

  // Copy agent definitions
  installAgents(dir);

  return { file, settings };
}

const JDT_AGENT_MARKER = "<!-- jdtbridge-managed -->";

/**
 * Copy agent definitions from cli/agents/ to .claude/agents/.
 * Only overwrites files with jdtbridge marker.
 */
function installAgents(claudeDir) {
  const agentsDir = join(claudeDir, "agents");
  mkdirSync(agentsDir, { recursive: true });

  const cliRoot = dirname(fileURLToPath(import.meta.url));
  const srcDir = join(cliRoot, "..", "agents");
  if (!existsSync(srcDir)) return [];

  const installed = [];
  for (const name of readdirSync(srcDir)) {
    if (!name.endsWith(".md")) continue;
    const dest = join(agentsDir, name);

    // Skip if file exists and is NOT ours
    if (existsSync(dest)) {
      const content = readFileSync(dest, "utf8");
      if (!content.includes(JDT_AGENT_MARKER)) continue;
    }

    let content = readFileSync(join(srcDir, name), "utf8");
    if (!content.includes(JDT_AGENT_MARKER)) {
      content = content.trimEnd() + "\n" + JDT_AGENT_MARKER + "\n";
    }
    writeFileSync(dest, content);
    installed.push(name);
  }
  return installed;
}

/**
 * Remove jdtbridge-managed agent files.
 */
function removeAgents(claudeDir) {
  const agentsDir = join(claudeDir, "agents");
  if (!existsSync(agentsDir)) return;
  for (const name of readdirSync(agentsDir)) {
    if (!name.endsWith(".md")) continue;
    const file = join(agentsDir, name);
    const content = readFileSync(file, "utf8");
    if (content.includes(JDT_AGENT_MARKER)) {
      unlinkSync(file);
    }
  }
}

/**
 * Remove JDT Bridge settings from Claude Code.
 * Removes permission rules and hooks, preserves unrelated settings.
 * @param {string} [root] project root (default: git root or cwd)
 * @returns {{ file: string, removed: boolean }}
 */
export function uninstallClaudeSettings(root) {
  if (!root) root = findProjectRoot();

  const dir = join(root, ".claude");

  // Remove agents
  removeAgents(dir);

  // Clean settings.local.json
  const file = join(dir, "settings.local.json");
  if (!existsSync(file)) return { file, removed: false };

  let settings;
  try {
    settings = JSON.parse(readFileSync(file, "utf8"));
  } catch {
    return { file, removed: false };
  }

  // Remove jdt permission
  if (settings.permissions?.allow) {
    settings.permissions.allow = settings.permissions.allow
      .filter((r) => r !== "Bash(jdt *)");
  }

  // Remove PreToolUse jdt hooks
  if (settings.hooks?.PreToolUse) {
    settings.hooks.PreToolUse = settings.hooks.PreToolUse
      .filter((h) => !h.hooks?.some(
        (hk) => hk.command?.includes("jdt ")));
  }

  // Remove PostToolUse jdt refresh hooks
  if (settings.hooks?.PostToolUse) {
    settings.hooks.PostToolUse = settings.hooks.PostToolUse
      .filter((h) => !h.hooks?.some(
        (hk) => hk.command?.includes("jdt") && hk.command?.includes("refresh")));
  }

  // Remove SubagentStart jdt hooks
  if (settings.hooks?.SubagentStart) {
    settings.hooks.SubagentStart = settings.hooks.SubagentStart
      .filter((h) => !h.hooks?.some(
        (hk) => hk.command?.includes("SubagentStart")));
  }

  // Clean empty arrays
  if (settings.hooks?.PreToolUse?.length === 0) delete settings.hooks.PreToolUse;
  if (settings.hooks?.PostToolUse?.length === 0) delete settings.hooks.PostToolUse;
  if (settings.hooks?.SubagentStart?.length === 0) delete settings.hooks.SubagentStart;
  if (settings.hooks && Object.keys(settings.hooks).length === 0) delete settings.hooks;
  if (settings.permissions?.allow?.length === 0) delete settings.permissions.allow;
  if (settings.permissions && Object.keys(settings.permissions).length === 0) delete settings.permissions;

  writeFileSync(file, JSON.stringify(settings, null, 2) + "\n");
  return { file, removed: true };
}
