/**
 * Sandbox provider — run agent in Docker sandbox with bridge connectivity.
 *
 * Wraps `docker sandbox run` — creates sandbox if needed,
 * configures network policy, installs CLI, injects bridge
 * instance file, then runs the agent.
 */

import { spawn, execSync, spawnSync } from "node:child_process";
import { writeFileSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { discoverInstances } from "../discovery.mjs";
import { agentsDir } from "../home.mjs";
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
  console.log(dim(`Bridge: port ${inst.port}`));

  // 2. Ensure sandbox exists
  const container = detectExisting(agent)
    || createSandbox(agent, ".");
  console.log(dim(`Sandbox: ${container}`));
  console.log(dim(`Session: ${name}`));

  // 3. Allow bridge through sandbox network proxy
  spawnSync("docker", [
    "sandbox", "network", "proxy", container,
    "--allow-host", "localhost",
  ], { stdio: "ignore" });

  // 4. Install or update CLI to match plugin version
  const wantVersion = (inst.version || "").replace(/\.\d{12,}$/, "");
  const have = spawnSync("docker", [
    "sandbox", "exec", container,
    "bash", "-c", "jdt --version 2>/dev/null || echo none",
  ], { encoding: "utf8" });
  const sandboxVersion = (have.stdout || "").trim();
  if (sandboxVersion !== wantVersion) {
    const pkg = wantVersion
      ? `@kaluchi/jdtbridge@${wantVersion}`
      : "@kaluchi/jdtbridge";
    console.log(dim(sandboxVersion === "none"
      ? `Installing jdt CLI ${wantVersion}...`
      : `Updating jdt CLI ${sandboxVersion} → ${wantVersion}...`));
    spawnSync("docker", [
      "sandbox", "exec", container,
      "npm", "install", "-g", pkg,
    ], { stdio: "inherit" });
  }

  // 5. Write bridge instance file inside sandbox
  const instJson = JSON.stringify({
    port: inst.port,
    token: inst.token,
    pid: 1,
    workspace: inst.workspace,
    version: inst.version,
    host: "host.docker.internal",
  });
  spawnSync("docker", [
    "sandbox", "exec", "-i", container,
    "bash", "-c",
    "mkdir -p ~/.jdtbridge/instances"
      + " && cat > ~/.jdtbridge/instances/bridge.json",
  ], { input: instJson });

  console.log(dim(
    `Ready. Bridge at host.docker.internal:${inst.port}`));

  // 6. Write session file
  const sessionFile = join(agentsDir(), `${name}.json`);
  const session = {
    name,
    provider: "sandbox",
    agent,
    container,
    startedAt: Date.now(),
    bridgePort: inst.port,
    workspace: inst.workspace,
  };
  writeFileSync(sessionFile, JSON.stringify(session, null, 2) + "\n");

  // 7. Run
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
  process.on("SIGINT", () => child.kill("SIGINT"));
  process.on("SIGTERM", () => {
    child.kill("SIGTERM");
    cleanup();
  });
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
  console.log(dim("Creating sandbox..."));
  spawnSync("docker", [
    "sandbox", "create", agent, workspace,
  ], { stdio: "inherit" });
  return detectExisting(agent);
}
