/**
 * Docker sandbox launcher with JDT Bridge connectivity.
 *
 * Wraps `docker sandbox run` — creates sandbox if needed,
 * configures network policy, installs CLI, injects bridge
 * instance file, then runs the agent.
 *
 * The sandbox HTTP proxy routes traffic to the host.
 * Node.js http.request doesn't support HTTP_PROXY natively,
 * so client.mjs handles proxy routing via requestOptions().
 */

import { spawn, execSync, spawnSync } from "node:child_process";
import { discoverInstances } from "../discovery.mjs";
import { bold, red, dim } from "../color.mjs";

/**
 * jdt sandbox run <agent> [workspace] [-- agent-args...]
 *
 * Steps:
 * 1. Find live bridge on host
 * 2. Ensure sandbox exists (create if needed)
 * 3. Allow localhost through sandbox network proxy
 * 4. Install or update jdt CLI to match plugin version
 * 5. Write bridge instance file via stdin
 * 6. Run sandbox with agent args
 */
export async function sandboxRun(args) {
  const dashIdx = args.indexOf("--");
  const mainArgs = dashIdx >= 0 ? args.slice(0, dashIdx) : args;
  const agentArgs = dashIdx >= 0 ? args.slice(dashIdx) : [];
  const agent = mainArgs[0];

  if (!agent) {
    console.error(bold(red(
      "Usage: jdt sandbox run <agent> [workspace] [-- agent-args...]")));
    process.exit(1);
  }

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
  const name = detectExisting(agent)
    || createSandbox(agent, mainArgs[1] || ".");
  console.log(dim(`Sandbox: ${name}`));

  // 3. Allow bridge through sandbox network proxy.
  //    Sandbox proxy sees destination as "localhost" even
  //    when request goes via host.docker.internal.
  spawnSync("docker", [
    "sandbox", "network", "proxy", name,
    "--allow-host", "localhost",
  ], { stdio: "ignore" });

  // 4. Install or update CLI to match plugin version
  const wantVersion = (inst.version || "").replace(/\.\d{12,}$/, "");
  const have = spawnSync("docker", [
    "sandbox", "exec", name,
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
      "sandbox", "exec", name,
      "npm", "install", "-g", pkg,
    ], { stdio: "inherit" });
  }

  // 5. Write instance file (stdin avoids shell escaping)
  const instJson = JSON.stringify({
    port: inst.port,
    token: inst.token,
    pid: 1,
    workspace: inst.workspace,
    version: inst.version,
    host: "host.docker.internal",
  });
  spawnSync("docker", [
    "sandbox", "exec", "-i", name,
    "bash", "-c",
    "mkdir -p ~/.jdtbridge/instances"
      + " && cat > ~/.jdtbridge/instances/bridge.json",
  ], { input: instJson });

  console.log(dim(
    `Ready. Bridge at host.docker.internal:${inst.port}`));

  // 6. Run
  const child = spawn("docker", [
    "sandbox", "run", name, ...agentArgs,
  ], { stdio: "inherit" });

  child.on("close", (code) => process.exit(code || 0));
  process.on("SIGINT", () => child.kill("SIGINT"));
  process.on("SIGTERM", () => child.kill("SIGTERM"));
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

export const help = `Run an agent in a Docker sandbox with JDT Bridge connectivity.

Usage:  jdt sandbox run <agent> [workspace] [-- agent-args...]

Creates sandbox if needed, configures network access, installs
jdt CLI, and injects bridge instance file. All traffic to the
bridge goes through the sandbox HTTP proxy.

Steps performed automatically:
  1. Find running Eclipse bridge on host
  2. Create Docker sandbox (if not exists)
  3. Allow localhost in sandbox network policy
  4. Install or update @kaluchi/jdtbridge to match plugin version
  5. Write bridge instance file (~/.jdtbridge/instances/)
  6. Run the agent

Examples:
  jdt sandbox run claude
  jdt sandbox run claude ./myproject
  jdt sandbox run claude -- --continue`;
