/**
 * Agent lifecycle management — run, stop, list, logs, providers.
 *
 * Each provider (local, sandbox) is a separate module that handles
 * the specifics of spawning and managing agent processes.
 */

import { readdirSync, readFileSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { execSync } from "node:child_process";
import treeKill from "tree-kill";
import { agentsDir } from "../home.mjs";
import { bold, dim, red, green } from "../color.mjs";
import { parseFlags } from "../args.mjs";

// Provider registry — lazy imports to avoid loading Docker deps when unused.
const providers = {
  local: () => import("./agent-local.mjs"),
  sandbox: () => import("./agent-sandbox.mjs"),
};

// ---- run ----

export async function agentRun(args) {
  const dashIdx = args.indexOf("--");
  const mainArgs = dashIdx >= 0 ? args.slice(0, dashIdx) : args;
  const agentArgs = dashIdx >= 0 ? args.slice(dashIdx + 1) : [];

  const flags = parseFlags(mainArgs);
  const positional = mainArgs.filter((a) => !a.startsWith("--"));

  const providerName = positional[0];
  const agent = positional[1];

  if (!providerName || !agent) {
    console.error(bold(red(
      "Usage: jdt agent run <provider> <agent> [--name <id>] [-- agent-args...]")));
    console.error(`\nAvailable providers: ${Object.keys(providers).join(", ")}`);
    process.exit(1);
  }

  if (!providers[providerName]) {
    console.error(bold(red(`Unknown provider: ${providerName}`)));
    console.error(`Available providers: ${Object.keys(providers).join(", ")}`);
    process.exit(1);
  }

  const name = flags.name
    || `${providerName}-${agent}-${Date.now()}`;

  const providerModule = await providers[providerName]();
  await providerModule.run({ agent, name, agentArgs });
}

// ---- list ----

export async function agentList() {
  const sessions = readSessions();

  if (sessions.length === 0) {
    console.log("No agent sessions.");
    return;
  }

  console.log(
    bold("NAME".padEnd(35)) +
    "PROVIDER".padEnd(12) +
    "AGENT".padEnd(12) +
    "STATUS".padEnd(12) +
    "STARTED",
  );

  for (const sess of sessions) {
    const alive = isAlive(sess);
    const status = alive ? green("running") : red("stopped");
    const started = new Date(sess.startedAt).toLocaleString();
    console.log(
      sess.name.padEnd(35) +
      (sess.provider || "?").padEnd(12) +
      (sess.agent || "?").padEnd(12) +
      status.padEnd(12 + (status.length - (alive ? 7 : 7))) +
      dim(started),
    );

    // Clean up stale session files
    if (!alive) {
      try { unlinkSync(sess._file); } catch { /* ignore */ }
    }
  }
}

// ---- stop ----

export async function agentStop(args) {
  const hint = args[0] || null;
  const { match: sess, candidates } = findSession(hint);
  if (!sess) {
    printSessionError("stop", hint, candidates);
    process.exit(1);
  }

  if (sess.provider === "sandbox" && sess.container) {
    try {
      execSync(`docker sandbox stop ${sess.container}`,
        { stdio: "ignore" });
    } catch { /* may already be stopped */ }
  } else if (sess.pid) {
    killProcessTree(sess.pid);
  }

  try { unlinkSync(sess._file); } catch { /* ignore */ }
  console.log(`Stopped ${sess.name}`);
}

// ---- logs ----

export async function agentLogs(args) {
  const flags = parseFlags(args);
  const hint = args.find((a) => !a.startsWith("--")) || null;
  const { match: sess, candidates } = findSession(hint);
  if (!sess) {
    printSessionError("logs", hint, candidates);
    process.exit(1);
  }

  if (sess.provider === "sandbox" && sess.container) {
    const { spawn } = await import("node:child_process");
    const logArgs = ["sandbox", "logs", sess.container];
    if (flags.f || flags.follow) logArgs.push("-f");
    const child = spawn("docker", logArgs, { stdio: "inherit" });
    child.on("close", (code) => process.exit(code || 0));
  } else {
    console.log("Logs not available — agent runs in attached terminal.");
  }
}

// ---- providers ----

export async function agentProviders() {
  console.log(bold("Available providers:\n"));
  console.log("  local      Run agent on host with bridge env vars (foreground)");
  console.log("  sandbox    Run agent in Docker sandbox with bridge connectivity");
}

// ---- session helpers ----

function readSessions() {
  const dir = agentsDir();
  let files;
  try {
    files = readdirSync(dir).filter((f) => f.endsWith(".json"));
  } catch {
    return [];
  }

  const sessions = [];
  for (const file of files) {
    const filePath = join(dir, file);
    try {
      const data = JSON.parse(readFileSync(filePath, "utf8"));
      sessions.push({ ...data, _file: filePath });
    } catch { /* corrupt — skip */ }
  }
  return sessions;
}

/**
 * Find a session by exact name, agent name, or partial match.
 * If hint is null and there's exactly one session, return it.
 * Returns { match, candidates } — match is the session or null,
 * candidates lists ambiguous matches when match is null.
 */
function findSession(hint) {
  const sessions = readSessions();
  if (!hint) {
    if (sessions.length === 1) return { match: sessions[0], candidates: [] };
    return { match: null, candidates: sessions };
  }
  // Exact name match
  const exact = sessions.find((s) => s.name === hint);
  if (exact) return { match: exact, candidates: [] };
  // Agent name match
  const byAgent = sessions.filter((s) => s.agent === hint);
  if (byAgent.length === 1) return { match: byAgent[0], candidates: [] };
  if (byAgent.length > 1) return { match: null, candidates: byAgent };
  // Partial name match
  const partial = sessions.filter((s) => s.name.includes(hint));
  if (partial.length === 1) return { match: partial[0], candidates: [] };
  if (partial.length > 1) return { match: null, candidates: partial };
  return { match: null, candidates: [] };
}

/**
 * Print error for ambiguous or missing session match.
 * @param {string} subcmd - "stop" or "logs"
 */
function printSessionError(subcmd, hint, candidates) {
  if (candidates.length > 1) {
    console.error(`Multiple sessions match "${hint || ""}". Specify one:`);
    for (const s of candidates) {
      console.error(`  jdt agent ${subcmd} ${s.name}`);
    }
  } else if (!hint) {
    console.error("No agent sessions.");
  } else {
    console.error(`No agent session found: ${hint}`);
  }
}

function isAlive(sess) {
  if (sess.provider === "sandbox" && sess.container) {
    try {
      const out = execSync("docker sandbox ls", { encoding: "utf8" });
      return out.includes(sess.container);
    } catch {
      return false;
    }
  }
  if (sess.pid) {
    try {
      process.kill(sess.pid, 0);
      return true;
    } catch {
      return false;
    }
  }
  return false;
}

/**
 * Kill a process and all its descendants.
 * Uses tree-kill for cross-platform process tree termination.
 */
export function killProcessTree(pid) {
  try {
    treeKill(pid);
  } catch { /* process may already be dead */ }
}

// ---- help strings ----

export const agentRunHelp = `Launch an agent session via a provider.

Usage:  jdt agent run <provider> <agent> [--name <id>] [-- agent-args...]

Providers:
  local      Spawn agent on host with JDT_BRIDGE_* env vars
  sandbox    Run in Docker sandbox with bridge connectivity

Options:
  --name <id>    Session ID (default: <provider>-<agent>-<timestamp>)

The agent receives bridge connection coordinates via environment
variables (JDT_BRIDGE_PORT, JDT_BRIDGE_TOKEN, etc.) so jdt CLI
commands inside the agent skip discovery and connect directly.

Examples:
  jdt agent run local claude
  jdt agent run sandbox claude --name my-fix
  jdt agent run local claude -- --continue
`;

export const agentStopHelp = `Stop a running agent session.

Usage:  jdt agent stop <name>

Stops the agent process (local) or container (sandbox)
and removes the session file.

Examples:
  jdt agent stop sandbox-claude-1743300000
  jdt agent stop my-fix
`;

export const agentListHelp = `List agent sessions.

Usage:  jdt agent list

Shows all agent sessions with their status. Stale sessions
(where the process has exited) are cleaned up automatically.
`;

export const agentLogsHelp = `Show agent output.

Usage:  jdt agent logs <name> [-f]

For sandbox agents, shows Docker container logs.
For local agents, logs are not captured (agent runs in foreground).

Options:
  -f, --follow    Stream logs (sandbox only)
`;

export const agentProvidersHelp = `List available agent providers.

Usage:  jdt agent providers

Shows all registered providers and their descriptions.
`;
