/**
 * Local provider — spawn agent on host with bridge env vars.
 *
 * The agent runs in the foreground (attached to the current terminal).
 * Bridge connection coordinates are injected as environment variables
 * so jdt CLI inside the agent skips discovery.
 */

import { spawn } from "node:child_process";
import { writeFileSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { discoverInstances } from "../discovery.mjs";
import { agentsDir } from "../home.mjs";
import { killProcessTree } from "./agent.mjs";
import { bold, red, dim } from "../color.mjs";

export async function run({ agent, name, agentArgs }) {
  // 1. Find bridge
  const instances = await discoverInstances();
  if (instances.length === 0) {
    console.error(bold(red("No live bridge found.")) +
      "\nStart Eclipse with the jdtbridge plugin first.");
    process.exit(1);
  }
  const inst = instances[0];

  // 2. Build env vars
  const env = {
    ...process.env,
    JDT_BRIDGE_PORT: String(inst.port),
    JDT_BRIDGE_TOKEN: inst.token,
    JDT_BRIDGE_HOST: "127.0.0.1",
    JDT_BRIDGE_WORKSPACE: inst.workspace,
    JDT_BRIDGE_SESSION: name,
  };

  console.log(dim(`Bridge: port ${inst.port}`));
  console.log(dim(`Session: ${name}`));

  // 3. Write session file
  const sessionFile = join(agentsDir(), `${name}.json`);
  const session = {
    name,
    provider: "local",
    agent,
    pid: null, // set after spawn
    startedAt: Date.now(),
    bridgePort: inst.port,
    workspace: inst.workspace,
  };

  // 4. Spawn agent
  const child = spawn(agent, agentArgs, {
    stdio: "inherit",
    env,
    shell: true,
  });

  session.pid = child.pid;
  writeFileSync(sessionFile, JSON.stringify(session, null, 2) + "\n");

  // 5. Cleanup on exit
  function cleanup() {
    try { unlinkSync(sessionFile); } catch { /* ignore */ }
  }

  child.on("close", (code) => {
    cleanup();
    process.exit(code || 0);
  });

  process.on("SIGINT", () => {
    killProcessTree(child.pid);
  });
  process.on("SIGTERM", () => {
    killProcessTree(child.pid);
    cleanup();
  });
}
