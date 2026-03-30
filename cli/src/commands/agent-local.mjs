/**
 * Local provider — open system terminal with bridge env vars.
 *
 * Opens an external terminal window with JDT_BRIDGE_* environment
 * variables and runs the agent command inside it.
 *
 * Two bootstrap paths:
 * 1. Eclipse: session config has port/token/workingDir — no discovery
 * 2. CLI: discover bridge from instance files
 */

import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { discoverInstances } from "../discovery.mjs";
import { agentsDir } from "../home.mjs";
import { openTerminal } from "../terminal.mjs";
import { bold, red, dim } from "../color.mjs";

const IS_WINDOWS = process.platform === "win32";

export async function run({ agent, name, agentArgs, session }) {
  // 1. Resolve bridge connection
  const bridgeEnv = session
    ? bridgeFromSession(session)
    : await bridgeFromDiscovery();

  bridgeEnv.JDT_BRIDGE_SESSION = name;

  const workDir = session?.workingDir || null;

  console.log(dim(`Bridge: port ${bridgeEnv.JDT_BRIDGE_PORT}`));
  console.log(dim(`Session: ${name}`));
  if (workDir) console.log(dim(`Working dir: ${workDir}`));

  // 2. Open system terminal
  const agentCmd = [agent, ...agentArgs].join(" ");
  const child = openAgentTerminal(name, agentCmd, bridgeEnv, workDir);

  // 3. Write agent tracking file
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
