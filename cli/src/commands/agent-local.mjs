/**
 * Local provider — open system terminal with bridge env vars.
 *
 * Two bootstrap paths:
 * 1. Eclipse (session): opens terminal, outputs bootstrap checks,
 *    then streams request telemetry to Eclipse Console
 * 2. CLI: opens terminal and exits
 */

import { request } from "node:http";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { discoverInstances } from "../discovery.mjs";
import { agentsDir } from "../home.mjs";
import { openTerminal } from "../terminal.mjs";
import { bold, red, dim, green } from "../color.mjs";

const IS_WINDOWS = process.platform === "win32";

export async function run({ agent, name, agentArgs, session }) {
  const bridgeEnv = session
    ? bridgeFromSession(session)
    : await bridgeFromDiscovery();

  bridgeEnv.JDT_BRIDGE_SESSION = name;

  const workDir = session?.workingDir || null;

  console.log(dim(`Bridge: port ${bridgeEnv.JDT_BRIDGE_PORT}`));
  console.log(dim(`Session: ${name}`));
  if (workDir) console.log(dim(`Working dir: ${workDir}`));

  // Bootstrap checks (shown in Eclipse Console)
  if (session && workDir) {
    printBootstrapChecks(workDir);
  }

  // Open system terminal
  const agentCmd = [agent, ...agentArgs].join(" ");
  const child = openAgentTerminal(name, agentCmd, bridgeEnv, workDir);

  // Write agent tracking file
  const agentFile = join(agentsDir(), `${name}.json`);
  writeFileSync(agentFile, JSON.stringify({
    name,
    provider: "local",
    agent,
    pid: child.pid,
    startedAt: Date.now(),
    bridgePort: Number(bridgeEnv.JDT_BRIDGE_PORT),
    workingDir: workDir || "",
  }, null, 2) + "\n");

  console.log(dim(`Terminal opened for ${agent}`));

  // Eclipse path: poll telemetry, exit when terminal closes
  if (session) {
    console.log(dim("Streaming request telemetry...\n"));
    await telemetryUntilExit(bridgeEnv, name, child);
    console.log(dim("\nAgent session ended."));
  }
}

function printBootstrapChecks(workDir) {
  console.log();
  const ok = (msg) => console.log(green("  ✓ ") + msg);
  const no = (msg) => console.log(dim("  · ") + msg);

  // CLAUDE.md
  existsSync(join(workDir, "CLAUDE.md"))
    ? ok("CLAUDE.md") : no("CLAUDE.md not found");

  // .claude directory
  const claudeDir = join(workDir, ".claude");
  if (!existsSync(claudeDir)) {
    no(".claude/ not found — run: jdt setup --claude");
    console.log();
    return;
  }

  // Hooks
  const localPath = join(claudeDir, "settings.local.json");
  if (existsSync(localPath)) {
    try {
      const settings = JSON.parse(readFileSync(localPath, "utf8"));
      const hasPre = settings.hooks?.PreToolUse?.some(
        (h) => h.hooks?.some((hk) => hk.command?.includes("jdt ")));
      const hasPost = settings.hooks?.PostToolUse?.some(
        (h) => h.hooks?.some(
          (hk) => hk.command?.includes("jdt")
            && hk.command?.includes("refresh")));
      hasPre ? ok("PreToolUse hook") : no("PreToolUse hook missing");
      hasPost ? ok("PostToolUse hook") : no("PostToolUse hook missing");
    } catch { /* ignore */ }
  }

  // Agents
  const agentsPath = join(claudeDir, "agents");
  existsSync(join(agentsPath, "Explore.md"))
    ? ok("Explore agent") : no("Explore agent missing");
  existsSync(join(agentsPath, "Plan.md"))
    ? ok("Plan agent") : no("Plan agent missing");

  console.log();
}

/**
 * Poll telemetry queue while terminal is open.
 * Terminal child 'exit' event is the lifecycle signal —
 * no PID polling, no markers, just OS process lifecycle.
 */
function telemetryUntilExit(bridgeEnv, session, child) {
  const port = Number(bridgeEnv.JDT_BRIDGE_PORT);
  const token = bridgeEnv.JDT_BRIDGE_TOKEN;

  return new Promise((resolve) => {
    let done = false;

    const interval = setInterval(() => {
      drainTelemetry(port, token, session, (text) => {
        if (text) process.stdout.write(text);
      });
    }, 2000);

    child.on("exit", () => {
      if (done) return;
      done = true;
      // Final drain
      drainTelemetry(port, token, session, (text) => {
        if (text) process.stdout.write(text);
        clearInterval(interval);
        resolve();
      });
    });
  });
}

function drainTelemetry(port, token, session, done) {
  const path = `/telemetry?session=${encodeURIComponent(session)}`;
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;

  const req = request(
    { hostname: "127.0.0.1", port, path, headers, timeout: 5000 },
    (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => {
        if (data) process.stdout.write(data);
        done();
      });
    },
  );
  req.on("error", () => done());
  req.on("timeout", () => { req.destroy(); done(); });
  req.end();
}

function bridgeFromSession(session) {
  return {
    JDT_BRIDGE_PORT: String(session.bridgePort),
    JDT_BRIDGE_TOKEN: session.bridgeToken,
    JDT_BRIDGE_HOST: session.bridgeHost || "127.0.0.1",
  };
}

async function bridgeFromDiscovery() {
  const instances = await discoverInstances();
  if (instances.length === 0) {
    console.error(bold(red("No live bridge found.")) +
      "\nStart Eclipse with the jdtbridge plugin first.");
    process.exit(1);
  }
  const inst = instances[0];
  return {
    JDT_BRIDGE_PORT: String(inst.port),
    JDT_BRIDGE_TOKEN: inst.token,
    JDT_BRIDGE_HOST: inst.host || "127.0.0.1",
  };
}

function openAgentTerminal(title, agentCmd, bridgeEnv, workDir) {
  const envParts = Object.entries(bridgeEnv).map(([k, v]) =>
    IS_WINDOWS ? `set "${k}=${v}"` : `export ${k}="${v}"`);
  const sep = IS_WINDOWS ? " && " : "; ";
  const cdCmd = workDir
    ? (IS_WINDOWS ? `cd /d "${workDir}"` : `cd "${workDir}"`)
    : null;

  const parts = [...envParts];
  if (cdCmd) parts.push(cdCmd);
  parts.push(agentCmd);

  return openTerminal(title, parts.join(sep));
}
