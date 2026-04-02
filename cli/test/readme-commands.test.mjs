import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const readme = readFileSync(resolve(__dirname, "../README.md"), "utf8");
const cliSrc = readFileSync(resolve(__dirname, "../src/cli.mjs"), "utf8");

/**
 * Parse the commands map and subcommand maps from cli.mjs source.
 * Returns a Set of command strings like "jdt find", "jdt launch config", etc.
 *
 * Maps use the pattern:  name: { fn: ..., help: ... }
 * We extract only keys that have a { fn: value.
 */
function parseCommands(src) {
  const cmds = new Set();

  // Match entries like:  "project-info": { fn:  or  find: { fn:
  const entryRe = /(?:"([^"]+)"|(\w+)):\s*\{\s*fn:/g;

  // Top-level commands
  const commandsMatch = src.match(/^const commands = \{([\s\S]*?)^\};/m);
  if (commandsMatch) {
    for (const m of commandsMatch[1].matchAll(entryRe)) {
      cmds.add(`jdt ${m[1] || m[2]}`);
    }
  }

  // Subcommand maps: launch, test, agent
  const subMaps = [
    { pattern: /^const launchSubcommands = \{([\s\S]*?)^\};/m, prefix: "jdt launch" },
    { pattern: /^const testSubcommands = \{([\s\S]*?)^\};/m, prefix: "jdt test" },
    { pattern: /^const agentSubcommands = \{([\s\S]*?)^\};/m, prefix: "jdt agent" },
  ];
  for (const { pattern, prefix } of subMaps) {
    const match = src.match(pattern);
    if (match) {
      for (const m of match[1].matchAll(entryRe)) {
        cmds.add(`${prefix} ${m[1] || m[2]}`);
      }
    }
  }

  return cmds;
}

/**
 * Extract "jdt <command>" names from README code blocks.
 * Strips arguments/flags — keeps only the command name.
 * "jdt find <Name|*Pattern*|pkg> [--source-only]" → "jdt find"
 * "jdt launch config <name> [--xml]" → "jdt launch config"
 */
function parseReadmeCommands(content) {
  const cmds = new Set();
  const lines = content.split("\n");
  let inBlock = false;

  // Subcommand parents — next word after these is part of the command name
  const parents = new Set(["launch", "test", "agent", "maven"]);

  for (const line of lines) {
    if (line.startsWith("```bash")) { inBlock = true; continue; }
    if (line.startsWith("```") && inBlock) { inBlock = false; continue; }
    if (!inBlock) continue;

    const match = line.match(/^\s*jdt\s+(\S+)(?:\s+(\S+))?/);
    if (!match) continue;

    const first = match[1];
    // Skip lines starting with flags (e.g. "jdt setup --check")
    if (first.startsWith("-")) continue;

    if (parents.has(first) && match[2] && !match[2].startsWith("-") && !match[2].startsWith("<") && !match[2].startsWith("[")) {
      cmds.add(`jdt ${first} ${match[2]}`);
    } else {
      cmds.add(`jdt ${first}`);
    }
  }
  return cmds;
}

// Commands that are internal/hidden — not expected in README
const hidden = new Set([
  "jdt launch console", // internal alias for launch logs streaming
]);

describe("CLI README completeness", () => {
  const srcCommands = parseCommands(cliSrc);
  const readmeCommands = parseReadmeCommands(readme);

  it("source has commands", () => {
    expect(srcCommands.size).toBeGreaterThan(20);
  });

  it("README has commands", () => {
    expect(readmeCommands.size).toBeGreaterThan(20);
  });

  it("every source command appears in README", () => {
    const missing = [];
    for (const cmd of srcCommands) {
      if (hidden.has(cmd)) continue;
      // For parent commands (launch, test, agent, maven) — subcommands cover them
      if (["jdt launch", "jdt test", "jdt agent", "jdt maven"].includes(cmd)) continue;
      if (!readmeCommands.has(cmd)) {
        missing.push(cmd);
      }
    }
    expect(missing, `Commands in source but not in README:\n  ${missing.join("\n  ")}`).toEqual([]);
  });
});
