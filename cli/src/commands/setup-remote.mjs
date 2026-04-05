// jdt setup remote — configure CLI to connect to a remote Eclipse.
// See docs/jdt-setup-remote-spec.md for full design.

import { existsSync, readFileSync, writeFileSync, readdirSync, unlinkSync, renameSync } from "node:fs";
import { join } from "node:path";
import { createHash, randomBytes } from "node:crypto";
import { remoteInstancesDir, remoteProjectPathsDir, maskToken } from "../home.mjs";
import { parseFlags } from "../args.mjs";
import { normalizePath } from "../paths.mjs";
import { directGet } from "../client.mjs";
import { printJson } from "../json-output.mjs";
import { formatTable } from "../format/table.mjs";
import { green, red } from "../color.mjs";

/**
 * Hash a bridge-socket string to a 12-char hex filename.
 * Same algorithm as Activator.workspaceHash().
 */
function bridgeSocketHash(bridgeSocket) {
  const digest = createHash("sha256").update(bridgeSocket).digest();
  return digest.subarray(0, 6).toString("hex");
}

function instanceFilePath(bridgeSocket) {
  const hash = bridgeSocketHash(bridgeSocket);
  return join(remoteInstancesDir(), hash + ".json");
}

function cacheFilePath(bridgeSocket) {
  const hash = bridgeSocketHash(bridgeSocket);
  return join(remoteProjectPathsDir(), hash + ".json");
}

function readInstanceFile(filePath) {
  if (!existsSync(filePath)) return null;
  try {
    return JSON.parse(readFileSync(filePath, "utf8"));
  } catch { return null; }
}

function readAllRemoteInstances() {
  const dir = remoteInstancesDir();
  const remoteInstances = [];
  try {
    for (const fileName of readdirSync(dir)) {
      if (fileName.endsWith(".json")) {
        const filePath = join(dir, fileName);
        const instanceData = readInstanceFile(filePath);
        if (instanceData && instanceData["bridge-socket"]) {
          remoteInstances.push({ ...instanceData, file: filePath });
        }
      }
    }
  } catch { /* dir not found */ }
  return remoteInstances;
}


/**
 * Scan a directory for .project files, extract <name>.
 * Returns array of { projectName, localPath }.
 */
function scanMountPoint(mountPointPath, maxDepth = 4) {
  const foundProjects = [];

  function scanDir(dirPath, currentDepth) {
    if (currentDepth > maxDepth) return;
    try {
      const entries = readdirSync(dirPath, { withFileTypes: true });
      for (const dirEntry of entries) {
        if (dirEntry.name === ".project" && dirEntry.isFile()) {
          const projectName = parseProjectName(
            join(dirPath, ".project"));
          if (projectName) {
            foundProjects.push({
              projectName,
              localPath: dirPath,
              mountPoint: mountPointPath,
            });
          }
        } else if (dirEntry.isDirectory()
          && !dirEntry.name.startsWith(".")
          && dirEntry.name !== "node_modules"
          && dirEntry.name !== "target"
          && dirEntry.name !== "bin") {
          scanDir(join(dirPath, dirEntry.name), currentDepth + 1);
        }
      }
    } catch { /* permission error, etc */ }
  }

  scanDir(mountPointPath, 0);
  return foundProjects;
}

function parseProjectName(projectFilePath) {
  try {
    const projectXml = readFileSync(projectFilePath, "utf8");
    const nameMatch = projectXml.match(/<name>([^<]+)<\/name>/);
    return nameMatch ? nameMatch[1] : null;
  } catch { return null; }
}

function scanAllMountPoints(mountPoints) {
  const allProjects = [];
  for (const mountPoint of mountPoints) {
    allProjects.push(...scanMountPoint(mountPoint));
  }
  return allProjects;
}

function writeCacheFile(bridgeSocket, mountPoints, scannedProjects) {
  const cacheData = {
    "bridge-socket": bridgeSocket,
    scannedAt: Date.now(),
    "mount-points": mountPoints,
    projects: {},
  };
  for (const scannedProject of scannedProjects) {
    cacheData.projects[scannedProject.projectName] =
      scannedProject.localPath;
  }
  const filePath = cacheFilePath(bridgeSocket);
  const tmpPath = filePath + ".tmp";
  writeFileSync(tmpPath, JSON.stringify(cacheData, null, 2) + "\n");
  renameSync(tmpPath, filePath);
  return cacheData;
}

/** Collect all values for a repeated flag (parseFlags keeps only last). */
function collectFlag(args, flagName) {
  const collected = [];
  const prefix = "--" + flagName;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === prefix && i + 1 < args.length
        && !args[i + 1].startsWith("--")) {
      collected.push(args[++i]);
    }
  }
  return collected;
}

/** Collect mount-point operations in argument order. */
function collectMountPointOps(args) {
  const mountPointOps = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--add-mount-point" && i + 1 < args.length
        && !args[i + 1].startsWith("--")) {
      mountPointOps.push({ op: "add", path: args[++i] });
    } else if (args[i] === "--remove-mount-point"
        && i + 1 < args.length
        && !args[i + 1].startsWith("--")) {
      mountPointOps.push({ op: "remove", path: args[++i] });
    }
  }
  return mountPointOps;
}

export async function setupRemote(args) {
  const flags = parseFlags(args);
  const bridgeSocket = flags["bridge-socket"];
  const addMountPoints = collectFlag(args, "add-mount-point");
  const removeMountPoints = collectFlag(args, "remove-mount-point");
  const mountPointOps = collectMountPointOps(args);
  const jsonOutput = args.includes("--json");
  const checkMode = args.includes("--check");
  const deleteMode = args.includes("--delete");

  if (!bridgeSocket) {
    if (checkMode) return handleCheckAll(jsonOutput);
    if (deleteMode || flags.token || addMountPoints.length > 0
        || removeMountPoints.length > 0) {
      console.error("Missing --bridge-socket <host>:<port>");
      process.exit(1);
    }
    return handleNoArgs(jsonOutput);
  }

  // --delete
  if (deleteMode) {
    return handleDelete(bridgeSocket);
  }

  // --json query for specific remote (no mutations)
  if (jsonOutput && !flags.token && addMountPoints.length === 0
      && removeMountPoints.length === 0 && !checkMode) {
    const filePath = instanceFilePath(bridgeSocket);
    const instanceData = readInstanceFile(filePath);
    if (!instanceData) {
      console.error(`No remote instance for ${bridgeSocket}`);
      process.exit(1);
    }
    printJson(buildInstanceJson({ ...instanceData, file: filePath }));
    return;
  }

  // Configure / update
  return handleConfigure(bridgeSocket, flags, addMountPoints,
      removeMountPoints, mountPointOps, checkMode, jsonOutput);
}

async function handleNoArgs(jsonOutput) {
  const remoteInstances = readAllRemoteInstances();

  if (remoteInstances.length === 0) {
    if (jsonOutput) { printJson([]); return; }
    console.log("No remote instances configured.");
    console.log();
    console.log("To connect to a remote Eclipse:");
    console.log();
    console.log("  1. On the Eclipse host:");
    console.log("     Window > Preferences > JDT Bridge");
    console.log("     - Enable Remote socket");
    console.log("     - Set a fixed port");
    console.log("     - Copy the remote token");
    console.log();
    console.log("  2. Here, configure the connection and mount points where your");
    console.log("     project sources are accessible:");
    console.log();
    console.log("     jdt setup remote \\");
    console.log("       --bridge-socket <eclipse-host>:<eclipse-port> \\");
    console.log("       --token <paste-token-from-step-1> \\");
    console.log("       --add-mount-point <mounted-directory> \\");
    console.log("       --add-mount-point <another-mounted-directory>");
    console.log();
    console.log("     <eclipse-host> — hostname or IP where Eclipse is running.");
    console.log("       For Docker on the same machine: host.docker.internal");
    console.log("       For SSH tunnel: localhost");
    console.log("       For remote machine: IP or hostname");
    console.log();
    console.log("     <eclipse-port> — the fixed port you set in Eclipse preferences.");
    console.log();
    console.log("     <mounted-directory> — each directory on this machine that");
    console.log("       contains Eclipse project sources (with .project files).");
    console.log("       Add as many --add-mount-point flags as you have mount points.");
    console.log();
    console.log("     Examples:");
    console.log();
    console.log("     Docker container, Eclipse on same machine:");
    console.log("       jdt setup remote \\");
    console.log("         --bridge-socket host.docker.internal:7777 \\");
    console.log("         --token <token> \\");
    console.log("         --add-mount-point /workspace");
    console.log();
    console.log("     Remote machine via SSH tunnel:");
    console.log("       jdt setup remote \\");
    console.log("         --bridge-socket localhost:7777 \\");
    console.log("         --token <token> \\");
    console.log("         --add-mount-point /home/user/projects");
    console.log();
    console.log("     After configuring, verify:");
    console.log("       jdt setup remote --check");
    return;
  }

  if (jsonOutput) {
    const jsonData = remoteInstances.map(remoteInstance =>
      buildInstanceJson(remoteInstance));
    printJson(jsonData);
    return;
  }

  // Full status per remote
  for (const remoteInstance of remoteInstances) {
    printRemoteStatus(remoteInstance);
  }

  console.log("─".repeat(60));
  console.log();
  console.log("Verify connection and project mapping against Eclipse:");
  console.log(`  jdt setup remote --check`);
  console.log();
  console.log("Switch between remote instances:");
  console.log(`  jdt use`);
  console.log();
  console.log("Update token:");
  console.log(`  jdt setup remote --bridge-socket <host>:<port> --token <new-token>`);
  console.log();
  console.log("Add projects from another directory:");
  console.log(`  jdt setup remote --bridge-socket <host>:<port> --add-mount-point <path>`);
  console.log();
  console.log("Remove a remote instance:");
  console.log(`  jdt setup remote --delete --bridge-socket <host>:<port>`);
}

async function handleCheckAll(jsonOutput) {
  const remoteInstances = readAllRemoteInstances();
  if (remoteInstances.length === 0) {
    console.log("No remote instances configured.");
    return;
  }
  const results = [];
  for (const remoteInstance of remoteInstances) {
    results.push(await checkRemote(remoteInstance));
  }
  if (jsonOutput) {
    printJson(results);
    return;
  }
  for (const result of results) {
    printCheckResult(result);
  }
}

async function checkRemote(remoteInstance) {
  const bridgeSocket = remoteInstance["bridge-socket"];
  const colonIdx = bridgeSocket.lastIndexOf(":");
  const host = bridgeSocket.substring(0, colonIdx);
  const port = parseInt(bridgeSocket.substring(colonIdx + 1), 10);
  const inst = { host, port, token: remoteInstance.token };

  const result = {
    "bridge-socket": bridgeSocket,
    tcp: false,
    token: false,
    plugin: null,
    tcpError: null,
    projects: null,
    unmapped: [],
  };

  // TCP + auth probe via /projects
  try {
    const projects = await directGet(inst, "/projects", 5000);
    result.tcp = true;
    result.token = true;
    result.projects = projects;
    if (Array.isArray(projects)) {
      // Compare against cached projects
      const cacheData = readInstanceFile(cacheFilePath(bridgeSocket));
      const cachedNames = cacheData?.projects
        ? new Set(Object.keys(cacheData.projects)) : new Set();
      result.unmapped = projects
        .map(p => p.name || p)
        .filter(name => !cachedNames.has(name));
    }
  } catch (checkError) {
    const msg = checkError.message || String(checkError);
    if (msg.includes("401") || msg.includes("403")) {
      result.tcp = true;
      result.token = false;
    } else {
      result.tcpError = msg;
    }
  }

  // Plugin version via /status
  if (result.tcp && result.token) {
    try {
      const status = await directGet(inst, "/status", 5000);
      result.plugin = status?.version || null;
    } catch { /* version unavailable */ }
  }

  return result;
}

function printCheckResult(result) {
  const bridgeSocket = result["bridge-socket"];
  console.log(`── ${bridgeSocket} ${"─".repeat(
    Math.max(0, 55 - bridgeSocket.length))}`);
  console.log();
  const ok = (msg) => console.log(`  ${green("\u2713")} ${msg}`);
  const fail = (msg) => console.log(`  ${red("\u2717")} ${msg}`);

  (result.tcp ? ok : fail)(
    `TCP ${result.tcp ? "connected" : result.tcpError || "failed"}`);
  if (result.tcp) {
    (result.token ? ok : fail)(
      `Token ${result.token ? "accepted" : "rejected"}`);
  }
  if (result.plugin) {
    ok(`Plugin ${result.plugin}`);
  }
  if (result.projects) {
    ok(`${result.projects.length} projects`);
    if (result.unmapped.length > 0) {
      console.log();
      console.log(`  ${result.unmapped.length} unmapped (no local path):`);
      for (const name of result.unmapped) {
        console.log(`    ${name}`);
      }
      console.log();
      console.log("  Add mount points to map them:");
      console.log(`    jdt setup remote --bridge-socket ${bridgeSocket} --add-mount-point <path>`);
    }
  }
  console.log();
}

function handleDelete(bridgeSocket) {
  const filePath = instanceFilePath(bridgeSocket);
  const cachePath = cacheFilePath(bridgeSocket);
  if (!existsSync(filePath)) {
    console.error(`No remote instance for ${bridgeSocket}`);
    process.exit(1);
  }
  try { unlinkSync(filePath); } catch { /* ok */ }
  try { unlinkSync(cachePath); } catch { /* ok */ }
  console.log(`Removed: ${bridgeSocket}`);
}

async function handleConfigure(bridgeSocket, flags, addMountPoints,
    removeMountPoints, mountPointOps, checkMode, jsonOutput) {
  const filePath = instanceFilePath(bridgeSocket);
  const existingInstance = readInstanceFile(filePath);
  const isUpdate = existingInstance !== null;

  // Resolve token
  let resolvedToken = flags.token;
  let tokenSource = null;
  if (!resolvedToken && existingInstance) {
    resolvedToken = existingInstance.token;
  }
  if (!resolvedToken) {
    resolvedToken = generateToken();
    tokenSource = "no --token, auto-generated, shown once";
  }

  // Build instance data
  const instanceData = existingInstance
    ? { ...existingInstance }
    : { "bridge-socket": bridgeSocket };
  instanceData.token = resolvedToken;

  // Mount points
  const existingMountPoints = instanceData["mount-points"] || [];
  let mountPointsChanged = false;

  // Apply mount-point operations in argument order
  for (const mountPointOp of mountPointOps) {
    if (mountPointOp.op === "add") {
      if (!existingMountPoints.includes(mountPointOp.path)) {
        existingMountPoints.push(mountPointOp.path);
        mountPointsChanged = true;
      }
    } else if (mountPointOp.op === "remove") {
      const removeIndex = existingMountPoints.indexOf(mountPointOp.path);
      if (removeIndex >= 0) {
        existingMountPoints.splice(removeIndex, 1);
        mountPointsChanged = true;
      }
    }
  }
  instanceData["mount-points"] = existingMountPoints;

  // Write instance file (atomic: temp + rename)
  const tmpInstancePath = filePath + ".tmp";
  writeFileSync(tmpInstancePath, JSON.stringify(instanceData, null, 2) + "\n");
  renameSync(tmpInstancePath, filePath);

  // Report what was written
  if (!jsonOutput) {
    const tokenChanged = isUpdate && flags.token
        && existingInstance.token !== resolvedToken;
    const hasChanges = !isUpdate || tokenChanged
        || addMountPoints.length > 0 || removeMountPoints.length > 0;

    if (hasChanges) {
      console.log(`${isUpdate ? "Updated" : "Wrote"} ${filePath}:`);
      if (!isUpdate) {
        console.log(`  bridge-socket: ${bridgeSocket}`);
      }
      if (tokenChanged) {
        console.log(`  token:         ${maskToken(resolvedToken)} (was: ${maskToken(existingInstance.token)})`);
      } else if (!isUpdate) {
        const tokenDisplay = tokenSource
          ? `${resolvedToken} (${tokenSource})`
          : maskToken(resolvedToken);
        console.log(`  token:         ${tokenDisplay}`);
      }
      if (addMountPoints.length > 0) {
        console.log(`  mount-points:  added ${addMountPoints.join(", ")}`);
      }
      if (removeMountPoints.length > 0) {
        console.log(`  mount-points:  removed ${removeMountPoints.join(", ")}`);
      }
      console.log();
    }
  }

  // Scan mount points (on new instance or any mount-point change)
  const mountPoints = instanceData["mount-points"] || [];
  if (mountPoints.length > 0 && (mountPointsChanged || !isUpdate)) {
    // Read old cache to compute diff for remove output
    const oldCacheData = readInstanceFile(cacheFilePath(bridgeSocket));
    const oldProjects = oldCacheData?.projects || {};

    const scannedProjects = scanAllMountPoints(mountPoints);
    writeCacheFile(bridgeSocket, mountPoints, scannedProjects);

    if (!jsonOutput) {
      // Show removed projects (were in old cache, not in new scan)
      if (removeMountPoints.length > 0) {
        const removedProjects = Object.entries(oldProjects)
          .filter(([name]) => !scannedProjects.some(
            sp => sp.projectName === name));
        if (removedProjects.length > 0) {
          console.log("Removed from cache:");
          console.log();
          const removedRows = removedProjects.map(
            ([projectName, localPath]) => {
              const mp = removeMountPoints.find(
                rmp => normalizePath(localPath).startsWith(
                  normalizePath(rmp)));
              return [projectName, localPath, mp || ""];
            });
          console.log(formatTable(
            ["PROJECT", "LOCAL_PATH", "MOUNT_POINT"], removedRows));
          console.log();
        }
      }

      // Show added/scanned projects
      if (addMountPoints.length > 0 && isUpdate) {
        // On update, scan only newly added mount points for display
        const addedProjects = scannedProjects.filter(
          sp => addMountPoints.some(amp =>
            normalizePath(sp.localPath).startsWith(
              normalizePath(amp))));
        if (addedProjects.length > 0) {
          const scanPaths = addMountPoints.join(", ");
          console.log(`Scanning ${scanPaths} for .project files...`);
          console.log();
          const projectRows = addedProjects.map(sp => [
            sp.projectName, sp.localPath, sp.mountPoint,
          ]);
          console.log(formatTable(
            ["PROJECT", "LOCAL_PATH", "MOUNT_POINT"], projectRows));
          console.log();
        }
      } else {
        // New instance — show all scanned
        const scanPaths = mountPoints.join(", ");
        console.log(`Scanning ${scanPaths} for .project files...`);
        console.log();
        if (scannedProjects.length > 0) {
          const projectRows = scannedProjects.map(sp => [
            sp.projectName, sp.localPath, sp.mountPoint,
          ]);
          console.log(formatTable(
            ["PROJECT", "LOCAL_PATH", "MOUNT_POINT"], projectRows));
          console.log();
        }
      }
      console.log(`${scannedProjects.length} projects cached.`);
    }
  } else if (removeMountPoints.length > 0 && !mountPointsChanged) {
    // Remove-only with no net change (path wasn't in list) — rescan
    const remainingProjects = scanAllMountPoints(mountPoints);
    writeCacheFile(bridgeSocket, mountPoints, remainingProjects);
    if (!jsonOutput) {
      console.log(`${remainingProjects.length} projects cached.`);
    }
  }

  if (checkMode) {
    const filePath = instanceFilePath(bridgeSocket);
    const instanceData = readInstanceFile(filePath);
    if (instanceData) {
      const result = await checkRemote(
        { ...instanceData, file: filePath });
      if (jsonOutput) {
        printJson(result);
      } else {
        printCheckResult(result);
      }
    }
  }
}

function printRemoteStatus(remoteInstance) {
  const bridgeSocket = remoteInstance["bridge-socket"];
  const mountPoints = remoteInstance["mount-points"] || [];

  console.log(`── ${bridgeSocket} ${"─".repeat(
    Math.max(0, 55 - bridgeSocket.length))}`)
  console.log();
  console.log("  SETTING        VALUE");
  console.log(`  token          ${maskToken(remoteInstance.token)}`);
  if (mountPoints.length > 0) {
    console.log(`  mount-points   ${mountPoints.join(", ")}`);
  }
  console.log();

  // Read cache for project table
  const cachePath = cacheFilePath(bridgeSocket);
  const cacheData = readInstanceFile(cachePath);
  if (cacheData && cacheData.projects
      && Object.keys(cacheData.projects).length > 0) {
    const projectRows = Object.entries(cacheData.projects).map(
      ([projectName, localPath]) => {
        const matchingMountPoint = mountPoints.find(
          mountPoint => normalizePath(localPath).startsWith(normalizePath(mountPoint)));
        return [projectName, localPath, matchingMountPoint || ""];
      });
    console.log(formatTable(
      ["PROJECT", "LOCAL_PATH", "MOUNT_POINT"],
      projectRows));
    console.log();
  }

  console.log(`  File: ${remoteInstance.file}`);
  console.log();
}

function buildInstanceJson(remoteInstance) {
  const cachePath = cacheFilePath(remoteInstance["bridge-socket"]);
  const cacheData = readInstanceFile(cachePath);
  const mountPoints = remoteInstance["mount-points"] || [];
  return {
    "bridge-socket": remoteInstance["bridge-socket"],
    file: remoteInstance.file,
    token: maskToken(remoteInstance.token),
    "mount-points": mountPoints,
    projects: cacheData?.projects
      ? Object.entries(cacheData.projects).map(
          ([projectName, localPath]) => {
            const mountPoint = mountPoints.find(
              mp => normalizePath(localPath).startsWith(normalizePath(mp)));
            return { project: projectName, localPath,
              mountPoint: mountPoint || null };
          })
      : [],
  };
}

export const setupRemoteHelp = `Configure CLI to connect to a remote Eclipse instance.

Usage:
  jdt setup remote                                            status / onboarding
  jdt setup remote --bridge-socket <host>:<port>              configure (auto-token)
  jdt setup remote --bridge-socket <host>:<port> --token <t>  configure with token
  jdt setup remote --bridge-socket <host>:<port> --check      verify connection
  jdt setup remote --bridge-socket <host>:<port> --json       output as JSON
  jdt setup remote --delete --bridge-socket <host>:<port>     remove remote

Mount points (directories scanned for .project files):
  --add-mount-point <path>       add directory to scan
  --remove-mount-point <path>    remove directory from scan
  Multiple allowed, applied in argument order.

Examples:
  jdt setup remote --bridge-socket host.docker.internal:7777 --token abc123
  jdt setup remote --bridge-socket host.docker.internal:7777 --add-mount-point /workspace
  jdt setup remote --check
  jdt setup remote --json
  jdt setup remote --delete --bridge-socket host.docker.internal:7777`;

function generateToken() {
  return randomBytes(16).toString("hex");
}
