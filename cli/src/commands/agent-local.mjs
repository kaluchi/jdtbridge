/**
 * Local provider — open system terminal with bridge env vars.
 *
 * Two bootstrap paths:
 * 1. Eclipse (session): opens terminal, outputs bootstrap checks,
 *    then polls request telemetry to Eclipse Console
 * 2. CLI: opens terminal and exits
 */

import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { agentsDir } from "../home.mjs";
import { openTerminal } from "../terminal.mjs";
import { telemetryUntilExit } from "../telemetry.mjs";
import { resolveBridge, printBootstrapChecks } from "./agent.mjs";
import { dim } from "../color.mjs";

const IS_WINDOWS = process.platform === "win32";

export async function run({ agent, name, agentArgs, session }) {
  const bridgeEnv = await resolveBridge(session);
  bridgeEnv.JDT_BRIDGE_SESSION = name;

  const workDir = session?.workingDir || null;

  console.log(`Bridge: port ${bridgeEnv.JDT_BRIDGE_PORT}`);
  console.log(`Session: ${name}`);
  if (workDir) console.log(`Working dir: ${workDir}`);

  if (session && workDir) {
    printBootstrapChecks(workDir);
  }

  const agentCmd = [agent, ...agentArgs].join(" ");
  const child = openAgentTerminal(name, agentCmd, bridgeEnv, workDir);

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

  if (session) {
    console.log(dim("Streaming request telemetry...\n"));
    await telemetryUntilExit(bridgeEnv, name, child);
    console.log(dim("\nAgent session ended."));
  }
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
