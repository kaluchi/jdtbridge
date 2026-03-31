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
import { writeFileSync, readFileSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { agentsDir } from "../home.mjs";
import { openTerminal } from "../terminal.mjs";
import { telemetryUntilExit } from "../telemetry.mjs";
import { resolveBridge, killProcessTree, printBootstrapChecks } from "./agent.mjs";
import { findRepoRoot } from "./setup.mjs";
import { hostToSandboxPath } from "../paths.mjs";
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
  console.log(`Session: ${name}`);
  console.log(`Agent: ${agent}`);
  if (agentArgs.length > 0) console.log(`Arguments: ${agentArgs.join(" ")}`);
  if (inst.workspace) console.log(`Working dir: ${inst.workspace}`);

  if (session && inst.workspace) {
    printBootstrapChecks(inst.workspace);
  }

  // 2. Ensure sandbox exists
  console.log(`Detecting sandbox for ${agent}...`);
  let container = detectExisting(agent);
  if (container) {
    console.log(`Sandbox: ${container} (existing)`);
  } else {
    console.log(`Creating sandbox for ${agent}...`);
    container = createSandbox(agent, inst.workspace || ".");
    console.log(`Sandbox: ${container} (created)`);
  }

  // 3. Allow bridge through sandbox network proxy
  console.log(`Configuring network proxy: allow localhost...`);
  const proxyCmd = ["sandbox", "network", "proxy", container,
    "--allow-host", "localhost"];
  console.log(dim(`  $ docker ${proxyCmd.join(" ")}`));
  spawnSync("docker", proxyCmd, { stdio: "ignore" });

  // 4. Install or update CLI in sandbox
  const repoRoot = findRepoRoot();
  let cliInstalled = false;

  if (repoRoot) {
    // Host has npm link — try live sync, fall back to tarball
    const sandboxCliPath = hostToSandboxPath(join(repoRoot, "cli"));
    const checkCmd = ["sandbox", "exec", container,
      "bash", "-c", `test -f "${sandboxCliPath}/package.json" && echo visible || echo hidden`];
    const visibility = spawnSync("docker", checkCmd, { encoding: "utf8" });
    const isVisible = (visibility.stdout || "").trim() === "visible";

    if (isVisible) {
      // npm link with --no-save --ignore-scripts to avoid rewriting
      // package-lock.json on the host via virtiofs. Without these flags,
      // npm resolves deps for Linux and overwrites the Windows lockfile.
      // This is fragile — npm link is only for jdt CLI developers.
      console.log(`Linking CLI from ${repoRoot} (live sync)...`);
      const linkCmd = ["sandbox", "exec", container,
        "bash", "-c", `cd "${sandboxCliPath}" && npm link --no-save --ignore-scripts`];
      console.log(dim(`  $ docker ${linkCmd.join(" ")}`));
      spawnSync("docker", linkCmd, { stdio: "inherit" });
      cliInstalled = true;
    } else {
      console.log(`Packing CLI from ${repoRoot}...`);
      console.log(dim(`  repo outside sandbox workspace — snapshot install (no live sync)`));
      const cliDir = join(repoRoot, "cli");
      console.log(dim(`  $ npm pack --json  (in ${cliDir})`));
      const packResult = spawnSync("npm", ["pack", "--json"], {
        cwd: cliDir, encoding: "utf8", shell: true,
      });
      try {
        if (packResult.status !== 0 || packResult.error)
          throw new Error((packResult.stderr || "").trim() || String(packResult.error || "unknown error"));
        const filename = JSON.parse(packResult.stdout)[0].filename;
        const tarball = join(cliDir, filename);
        console.log(dim(`  ${filename} → sandbox:/tmp/jdt.tgz`));
        spawnSync("docker", [
          "sandbox", "exec", "-i", container,
          "bash", "-c", "cat > /tmp/jdt.tgz",
        ], { input: readFileSync(tarball) });
        console.log(dim(`  $ docker sandbox exec ${container} npm install -g /tmp/jdt.tgz`));
        spawnSync("docker", [
          "sandbox", "exec", container,
          "npm", "install", "-g", "/tmp/jdt.tgz",
        ], { stdio: "inherit" });
        unlinkSync(tarball);
        spawnSync("docker", [
          "sandbox", "exec", container,
          "bash", "-c", "rm -f /tmp/jdt.tgz",
        ], { stdio: "ignore" });
        cliInstalled = true;
      } catch (e) {
        console.error(`  npm pack failed: ${e.message}`);
        console.log(`  Falling back to npm registry...`);
      }
    }
  }

  if (!cliInstalled) {
    // Fallback: install from npm registry
    console.log(`Checking jdt CLI version inside sandbox...`);
    const versionCmd = ["sandbox", "exec", container,
      "bash", "-c", "jdt --version 2>/dev/null || echo none"];
    console.log(dim(`  $ docker ${versionCmd.join(" ")}`));
    const have = spawnSync("docker", versionCmd, { encoding: "utf8" });
    const sandboxVersion = (have.stdout || "").trim();
    console.log(`  Sandbox CLI: ${sandboxVersion || "not installed"}`);
    const wantVersion = (inst.version || "").replace(/\.\d{12,}$/, "");
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
