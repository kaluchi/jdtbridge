/**
 * Sandbox provider — run agent in Docker sandbox with bridge connectivity.
 *
 * Wraps `docker sandbox run` — creates sandbox if needed,
 * configures network policy, installs CLI, injects bridge
 * instance file, sets env vars, then runs the agent.
 *
 * Every step is logged to stdout so Eclipse Console shows
 * the full bootstrap process.
 */

import { spawn, execSync, spawnSync } from "node:child_process";
import { writeFileSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { agentsDir } from "../home.mjs";
import { openTerminal } from "../terminal.mjs";
import { telemetryUntilExit } from "../telemetry.mjs";
import { resolveBridge, killProcessTree } from "./agent.mjs";
import { dim } from "../color.mjs";

export async function run({ agent, name, agentArgs, session }) {
  // 1. Resolve bridge
  const bridgeEnv = await resolveBridge(session);
  const inst = {
    port: Number(bridgeEnv.JDT_BRIDGE_PORT),
    token: bridgeEnv.JDT_BRIDGE_TOKEN,
    version: "",
    workspace: session?.workingDir || "",
  };

  console.log(`Bridge: port ${inst.port}`);

  // 2. Ensure sandbox exists
  console.log(`Detecting sandbox for ${agent}...`);
  let container = detectExisting(agent);
  if (container) {
    console.log(`Sandbox: ${container} (existing)`);
  } else {
    console.log(`Creating sandbox for ${agent}...`);
    container = createSandbox(agent, ".");
    console.log(`Sandbox: ${container} (created)`);
  }
  console.log(`Session: ${name}`);

  // 3. Allow bridge through sandbox network proxy
  console.log(`Configuring network proxy: allow localhost...`);
  const proxyCmd = ["sandbox", "network", "proxy", container,
    "--allow-host", "localhost"];
  console.log(dim(`  $ docker ${proxyCmd.join(" ")}`));
  spawnSync("docker", proxyCmd, { stdio: "ignore" });

  // 4. Install or update CLI to match plugin version
  const wantVersion = (inst.version || "").replace(/\.\d{12,}$/, "");
  console.log(`Checking jdt CLI version inside sandbox...`);
  const versionCmd = ["sandbox", "exec", container,
    "bash", "-c", "jdt --version 2>/dev/null || echo none"];
  console.log(dim(`  $ docker ${versionCmd.join(" ")}`));
  const have = spawnSync("docker", versionCmd, { encoding: "utf8" });
  const sandboxVersion = (have.stdout || "").trim();
  console.log(`  Sandbox CLI: ${sandboxVersion || "not installed"}`);

  if (sandboxVersion !== wantVersion) {
    const pkg = wantVersion
      ? `@kaluchi/jdtbridge@${wantVersion}`
      : "@kaluchi/jdtbridge";
    console.log(`Installing ${pkg}...`);
    const installCmd = ["sandbox", "exec", container,
      "npm", "install", "-g", pkg];
    console.log(dim(`  $ docker ${installCmd.join(" ")}`));
    spawnSync("docker", installCmd, { stdio: "inherit" });
  } else {
    console.log(`  CLI up to date`);
  }

  // 5. Write bridge instance file inside sandbox
  console.log(`Writing bridge instance file...`);
  const instJson = JSON.stringify({
    port: inst.port,
    token: inst.token,
    pid: 1,
    workspace: inst.workspace,
    version: inst.version,
    host: "host.docker.internal",
    session: name,
  }, null, 2);
  console.log(dim(`  ~/.jdtbridge/instances/bridge.json`));
  spawnSync("docker", [
    "sandbox", "exec", "-i", container,
    "bash", "-c",
    "mkdir -p ~/.jdtbridge/instances"
      + " && cat > ~/.jdtbridge/instances/bridge.json",
  ], { input: instJson });

  console.log(`Ready. Bridge at host.docker.internal:${inst.port}`);

  // 7. Write session tracking file
  const sessionFile = join(agentsDir(), `${name}.json`);
  writeFileSync(sessionFile, JSON.stringify({
    name,
    provider: "sandbox",
    agent,
    container,
    startedAt: Date.now(),
    bridgePort: inst.port,
    workspace: inst.workspace,
  }, null, 2) + "\n");

  // 8. Run — external terminal (Eclipse) or inline (CLI)
  if (session) {
    const dockerCmd = ["docker", "sandbox", "run", container,
      ...agentArgs].join(" ");
    console.log(`Launching agent...`);
    console.log(dim(`  $ ${dockerCmd}`));
    const child = openTerminal(name, dockerCmd);
    console.log(`Terminal opened for ${agent} (sandbox)`);
    console.log(dim("Streaming request telemetry...\n"));
    const bridgeEnv = {
      JDT_BRIDGE_PORT: String(inst.port),
      JDT_BRIDGE_TOKEN: inst.token,
      JDT_BRIDGE_HOST: "127.0.0.1",
    };
    await telemetryUntilExit(bridgeEnv, name, child);
    console.log(dim("\nAgent session ended."));
  } else {
    const child = spawn("docker", [
      "sandbox", "run", container, ...agentArgs,
    ], { stdio: "inherit" });

    function cleanup() {
      try { unlinkSync(sessionFile); } catch { /* ignore */ }
    }

    child.on("close", (code) => {
      cleanup();
      process.exit(code || 0);
    });
    process.on("SIGINT", () => killProcessTree(child.pid));
    process.on("SIGTERM", () => {
      killProcessTree(child.pid);
      cleanup();
    });
  }
}

/** Find existing sandbox for this agent. */
function detectExisting(agent) {
  try {
    const out = execSync("docker sandbox ls",
      { encoding: "utf8" });
    for (const line of out.trim().split("\n").slice(1)) {
      const parts = line.split(/\s{2,}/);
      if (parts.length >= 2 && parts[1] === agent)
        return parts[0];
    }
  } catch {}
  return null;
}

/** Create sandbox and return its name. */
function createSandbox(agent, workspace) {
  const cmd = ["sandbox", "create", agent, workspace];
  console.log(dim(`  $ docker ${cmd.join(" ")}`));
  spawnSync("docker", cmd, { stdio: "inherit" });
  return detectExisting(agent);
}
