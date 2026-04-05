// jdt setup remote — configure CLI to connect to a remote Eclipse.
// See docs/jdt-setup-remote-spec.md for full design.

import { existsSync, readFileSync, writeFileSync, readdirSync, unlinkSync, renameSync } from "node:fs";
import { join } from "node:path";
import { createHash, randomBytes } from "node:crypto";
import { remoteInstancesDir, remoteProjectPathsDir } from "../home.mjs";
import { parseFlags } from "../args.mjs";
import { dim } from "../color.mjs";
import { printJson } from "../json-output.mjs";
import { formatTable } from "../format/table.mjs";

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

function maskToken(fullToken) {
  if (!fullToken || fullToken.length < 5) return "******";
  return "******" + fullToken.substring(fullToken.length - 5);
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
    console.log("  2. Here, configure the connection and mount points:");
    console.log();
    console.log("     jdt setup remote \\");
    console.log("       --bridge-socket <eclipse-host>:<eclipse-port> \\");
    console.log("       --token <paste-token-from-step-1> \\");
    console.log("       --add-mount-point <mounted-directory>");
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
  // TODO: implement full --check with TCP probe + /projects
  console.log("--check: not yet implemented");
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
    tokenSource = "no --token, auto-generated and written";
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

  // Write instance file
  writeFileSync(filePath, JSON.stringify(instanceData, null, 2) + "\n");

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

  // Scan mount points
  const mountPoints = instanceData["mount-points"] || [];
  if (mountPoints.length > 0 && (mountPointsChanged || !isUpdate)) {
    const scannedProjects = scanAllMountPoints(mountPoints);
    writeCacheFile(bridgeSocket, mountPoints, scannedProjects);

    if (!jsonOutput) {
      console.log("Scanning mount points for .project files...");
      console.log();
      if (scannedProjects.length > 0) {
        // Print table
        const projectRows = scannedProjects.map(scannedProject => [
          scannedProject.projectName,
          scannedProject.localPath,
          scannedProject.mountPoint,
        ]);
        console.log(formatTable(
          ["PROJECT", "LOCAL_PATH", "MOUNT_POINT"],
          projectRows));
        console.log();
      }
      console.log(`${scannedProjects.length} projects cached.`);
    }
  }

  // Remove mount point — rescan remaining
  if (removeMountPoints.length > 0) {
    const remainingProjects = scanAllMountPoints(
      instanceData["mount-points"] || []);
    writeCacheFile(bridgeSocket, instanceData["mount-points"] || [],
      remainingProjects);
    if (!jsonOutput) {
      console.log(`${remainingProjects.length} projects in cache.`);
    }
  }

  if (checkMode) {
    // TODO: implement --check (TCP probe + /projects comparison)
    console.log("--check: not yet implemented");
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
        const norm = p => p.replace(/\\/g, "/");
        const matchingMountPoint = mountPoints.find(
          mountPoint => norm(localPath).startsWith(norm(mountPoint)));
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
  const norm = p => p.replace(/\\/g, "/");
  return {
    "bridge-socket": remoteInstance["bridge-socket"],
    file: remoteInstance.file,
    token: maskToken(remoteInstance.token),
    "mount-points": mountPoints,
    projects: cacheData?.projects
      ? Object.entries(cacheData.projects).map(
          ([projectName, localPath]) => {
            const mountPoint = mountPoints.find(
              mp => norm(localPath).startsWith(norm(mp)));
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
