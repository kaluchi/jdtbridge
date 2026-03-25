// Setup command — install/update/remove the Eclipse JDT Bridge plugin.

import { existsSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import { createInterface } from "node:readline";
import { readConfig, writeConfig } from "../home.mjs";
import { discoverInstances } from "../discovery.mjs";
import { green, red, bold, dim } from "../color.mjs";
import { parseFlags } from "../args.mjs";
import { createRequire } from "node:module";

const { version: cliVersion } = createRequire(import.meta.url)("../../package.json");
import {
  eclipseExe,
  isEclipseRunning,
  findEclipsePath,
  getEclipseVersion,
  detectProfile,
  getInstalledVersion,
  stopEclipse,
  startEclipse,
  getEclipseJavaHome,
  generateTargetPlatform,
  isEclipseInstall,
  waitForBridge,
  p2Install,
  p2Uninstall,
} from "../eclipse.mjs";

const BUNDLE_ID = "io.github.kaluchi.jdtbridge";
const FEATURE_IU = `${BUNDLE_ID}.feature.feature.group`;
const __dirname = dirname(fileURLToPath(import.meta.url));

// ---- UI helpers ----

function ask(question, defaultVal = "") {
  if (!process.stdin.isTTY) return Promise.resolve(defaultVal);
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((res) => {
    rl.question(question, (answer) => {
      rl.close();
      res(answer.trim() || defaultVal);
    });
  });
}

function ok(msg) { console.log(`  ${green("\u2713")} ${msg}`); }
function fail(msg) { console.log(`  ${red("\u2717")} ${msg}`); }
function info(msg) { console.log(`  ${dim("\u00b7")} ${msg}`); }

// ---- prerequisite checks ----

function checkNode() {
  const major = parseInt(process.versions.node);
  return { ok: major >= 20, label: `Node.js ${process.versions.node}` };
}

function checkJava() {
  try {
    const out = execSync("java -version 2>&1", { encoding: "utf8" });
    const m = out.match(/version "([^"]+)"/);
    return { ok: true, label: `Java ${m ? m[1] : "?"}` };
  } catch {
    return { ok: false, label: "Java \u2014 not found" };
  }
}

function checkMaven() {
  try {
    const out = execSync("mvn --version", {
      encoding: "utf8",
      stdio: ["pipe", "pipe", "pipe"],
    });
    const m = out.match(/Apache Maven (\S+)/);
    return { ok: true, label: `Maven ${m ? m[1] : "?"}` };
  } catch {
    return { ok: false, label: "Maven \u2014 not found" };
  }
}

function showPrereqs() {
  console.log(bold("Prerequisites"));
  ok(`CLI ${cliVersion}`);
  const checks = [checkNode(), checkJava(), checkMaven()];
  for (const c of checks) (c.ok ? ok : fail)(c.label);
  return checks.every((c) => c.ok);
}

// ---- plugin source ----

function findRepoRoot() {
  // cli/src/commands/setup.mjs -> 3 levels up = repo root
  const candidate = resolve(__dirname, "..", "..", "..");
  if (
    existsSync(join(candidate, "pom.xml")) &&
    existsSync(join(candidate, "plugin"))
  ) {
    return candidate;
  }
  return null;
}

function getBuiltRepoPath(repoRoot) {
  const dir = join(repoRoot, "site", "target", "repository");
  return existsSync(dir) ? dir : null;
}

// ---- ensure Eclipse is stopped, with confirmation ----

async function ensureStopped() {
  if (!isEclipseRunning()) return true;
  const answer = await ask("  Eclipse is running. Stop it? [Y/n] ", "y");
  if (answer.toLowerCase() === "n") {
    console.log("  Eclipse must be stopped. Aborting.");
    return false;
  }
  info("Stopping Eclipse...");
  if (!stopEclipse()) {
    console.error("  Failed to stop Eclipse.");
    process.exit(1);
  }
  info("Eclipse stopped.");
  return true;
}

// ---- modes ----

async function runCheck(config) {
  console.log();
  showPrereqs();

  console.log();
  console.log(bold("Eclipse"));
  const eclipsePath = findEclipsePath(config);
  if (eclipsePath) {
    const ver = getEclipseVersion(eclipsePath);
    ok(
      `${eclipsePath}${ver ? ` (${ver})` : ""}${config.eclipse ? dim(" \u2014 config") : ""}`,
    );
    const profile = detectProfile(eclipsePath);
    (profile ? ok : fail)(`Profile: ${profile || "not found"}`);
    const running = isEclipseRunning();
    (running ? ok : info)(running ? "Running" : "Not running");
  } else {
    fail("Eclipse not found");
  }

  console.log();
  console.log(bold("Bridge"));
  const instances = discoverInstances();
  if (instances.length > 0) {
    for (const inst of instances) {
      ok(`port ${inst.port}, PID ${inst.pid}, workspace ${inst.workspace}`);
      if (inst.version) ok(`Plugin: ${inst.version}${inst.location ? dim(` — ${inst.location}`) : ""}`);
    }
  } else {
    fail("No live instances");
  }

  console.log();
  console.log(bold("Plugin source"));
  const repoRoot = findRepoRoot();
  if (repoRoot) {
    ok(`Repo: ${repoRoot}`);
    const built = getBuiltRepoPath(repoRoot);
    (built ? ok : info)(built ? "p2 site: built" : "Not built yet");
  } else {
    info("Repo not found (CLI not in cloned repo)");
  }
  console.log();
}

async function runInstall(config, flags) {
  console.log();
  if (!showPrereqs()) {
    console.error("\nMissing prerequisites.");
    process.exit(1);
  }

  // Eclipse
  console.log();
  console.log(bold("Eclipse"));
  let eclipsePath = findEclipsePath(config);
  if (!eclipsePath) {
    eclipsePath = await ask("  Eclipse not found. Path: ");
    if (!eclipsePath || !isEclipseInstall(eclipsePath)) {
      console.error("  Not a valid Eclipse installation.");
      process.exit(1);
    }
  }
  const ver = getEclipseVersion(eclipsePath);
  ok(`${eclipsePath}${ver ? ` (${ver})` : ""}`);
  if (config.eclipse !== eclipsePath) {
    writeConfig({ eclipse: eclipsePath });
    info("Saved to config");
  }

  const profile = detectProfile(eclipsePath);
  if (!profile) {
    console.error("  Cannot detect p2 profile.");
    process.exit(1);
  }
  ok(`Profile: ${profile}`);

  const installedVersion = getInstalledVersion(eclipsePath, BUNDLE_ID);
  if (installedVersion) {
    info(`Currently installed: ${installedVersion}`);
  }

  // Build
  console.log();
  console.log(bold("Building plugin..."));
  let repoRoot = findRepoRoot();
  if (!repoRoot) {
    repoRoot = config.pluginSource;
    if (!repoRoot || !existsSync(repoRoot)) {
      repoRoot = await ask("  Plugin source repo path: ");
    }
  }
  if (!repoRoot || !existsSync(join(repoRoot, "pom.xml"))) {
    console.error("  Invalid plugin source path.");
    process.exit(1);
  }
  info(`Source: ${repoRoot}`);

  if (!flags["skip-build"]) {
    generateTargetPlatform(repoRoot, eclipsePath);
    info(`Target platform: ${eclipsePath}`);
    const javaHome = getEclipseJavaHome(eclipsePath);
    const mvnEnv = javaHome
      ? { ...process.env, JAVA_HOME: javaHome }
      : process.env;
    if (javaHome) info(`JAVA_HOME: ${javaHome}`);
    const mvnCmd = flags.clean ? "mvn clean verify" : "mvn verify";
    try {
      execSync(mvnCmd, { cwd: repoRoot, stdio: "inherit", timeout: 300_000, env: mvnEnv });
    } catch {
      console.error("\n  Build failed.");
      process.exit(1);
    }
  } else {
    info("Build skipped (--skip-build)");
  }

  const repoDir = getBuiltRepoPath(repoRoot);
  if (!repoDir) {
    console.error("  p2 site not found in site/target/repository/.");
    process.exit(1);
  }
  ok("Plugin built");

  // Capture workspace BEFORE stopping Eclipse (instances are filtered by
  // isPidAlive, so after stop they disappear from discoverInstances).
  const wasRunning = isEclipseRunning();
  let workspace = null;
  if (wasRunning) {
    const instances = discoverInstances();
    if (instances.length > 0) workspace = instances[0].workspace;
  }

  // Install
  console.log();
  console.log(bold("Installing plugin..."));
  if (!(await ensureStopped())) return;

  try {
    if (installedVersion) {
      info("Removing old version...");
      try {
        p2Uninstall(eclipsePath, profile, FEATURE_IU);
      } catch {
        /* may fail on dirty profile -- proceed anyway */
      }
    }
    info("Installing via p2 director...");
    p2Install(eclipsePath, profile, repoDir, FEATURE_IU);
    ok("Installed");
  } catch (e) {
    console.error(`\n  ${e.message}`);
    process.exit(1);
  }

  const newVersion = getInstalledVersion(eclipsePath, BUNDLE_ID);
  if (newVersion) ok(`Version: ${newVersion}`);

  // Restart
  console.log();
  const launcherPath = join(eclipsePath, eclipseExe("eclipse"));
  if (existsSync(launcherPath)) {
    if (wasRunning) {
      const pid = startEclipse(eclipsePath, workspace);
      info(`Eclipse started (PID ${pid})`);
      info("Waiting for bridge...");
      try {
        const { port, projects } = await waitForBridge(discoverInstances, pid);
        ok(`Bridge ready on port ${port} (${projects.length} projects)`);
      } catch {
        fail("Bridge did not start (Eclipse may still be loading)");
      }
    } else {
      info("Start Eclipse to activate the plugin.");
    }
  } else {
    info("Plugin installed. Run your Eclipse product to complete setup and activate the bridge.");
  }

  console.log();
  ok(bold("Setup complete"));
  console.log();
}

async function runRemove(config) {
  console.log();
  console.log(bold("Removing plugin..."));

  const eclipsePath = findEclipsePath(config);
  if (!eclipsePath) {
    console.error("  Eclipse not found. Run jdt setup --check first.");
    process.exit(1);
  }

  const profile = detectProfile(eclipsePath);
  if (!profile) {
    console.error("  Cannot detect p2 profile.");
    process.exit(1);
  }

  const installed = getInstalledVersion(eclipsePath, BUNDLE_ID);
  if (!installed) {
    info("Plugin is not installed.");
    return;
  }
  info(`Installed: ${installed}`);

  if (!(await ensureStopped())) return;

  try {
    p2Uninstall(eclipsePath, profile, FEATURE_IU);
    ok("Plugin removed");
  } catch (e) {
    console.error(`  ${e.message}`);
    process.exit(1);
  }
  console.log();
}

// ---- entry point ----

export async function setup(args) {
  const flags = parseFlags(args);
  const config = readConfig();
  if (flags.eclipse) config.eclipse = flags.eclipse;

  if (args.includes("--check")) {
    await runCheck(config);
  } else if (args.includes("--remove")) {
    await runRemove(config);
  } else if (args.includes("--claude")) {
    await setupClaude();
  } else {
    await runInstall(config, flags);
  }
}

async function setupClaude() {
  const { installClaudeSettings } = await import("../claude-setup.mjs");
  const { file } = installClaudeSettings();

  console.log(`${green("✓")} Claude Code settings written to ${file}`);
  console.log();
  console.log("  Installed:");
  console.log("  - Bash(jdt *) permission rule");
  console.log("  - PreToolUse hook for # workaround (issue #34061)");
  console.log();
  console.log("  Restart Claude Code to apply.");
}

export const help = `Set up the Eclipse JDT Bridge plugin.

Usage:  jdt setup [options]

Modes:
  (default)       build plugin from source, install into Eclipse
  --check         show status of all components (diagnostic only)
  --remove        uninstall the plugin from Eclipse
  --claude        configure Claude Code for this project (permissions + hooks)

Options:
  --eclipse <path>    Eclipse installation directory
  --skip-build        install last build without rebuilding
  --clean             clean build (mvn clean verify)

Eclipse must be stopped for install/update/remove operations.
If Eclipse is running, you will be prompted to stop it.

Examples:
  jdt setup
  jdt setup --check
  jdt setup --eclipse D:/eclipse
  jdt setup --skip-build
  jdt setup --remove`;
