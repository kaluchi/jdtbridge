// JDTBRIDGE_HOME management — config dir, instances, plugin artifacts.

import { existsSync, mkdirSync, readFileSync, writeFileSync, unlinkSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

const DEFAULT_HOME = join(homedir(), ".jdtbridge");

let _home;

/** Returns JDTBRIDGE_HOME path, creating it if needed. */
export function getHome() {
  if (_home) return _home;
  _home = process.env.JDTBRIDGE_HOME || DEFAULT_HOME;
  ensureDir(_home);
  return _home;
}

/** Directory where running Eclipse instances write their bridge files. */
export function instancesDir() {
  const dir = join(getHome(), "instances");
  ensureDir(dir);
  return dir;
}

/** Directory where agent session files are stored. */
export function agentsDir() {
  const dir = join(getHome(), "agents");
  ensureDir(dir);
  return dir;
}

/** Directory where Eclipse writes launch session configs. */
export function sessionsDir() {
  const dir = join(getHome(), "sessions");
  ensureDir(dir);
  return dir;
}

/** Read config.json from JDTBRIDGE_HOME. Returns {} if missing. */
export function readConfig() {
  const configPath = join(getHome(), "config.json");
  if (!existsSync(configPath)) return {};
  try {
    return JSON.parse(readFileSync(configPath, "utf8"));
  } catch {
    return {};
  }
}

/** Write config.json to JDTBRIDGE_HOME. Merges with existing. */
export function writeConfig(updates) {
  const configPath = join(getHome(), "config.json");
  const current = readConfig();
  const merged = { ...current, ...updates };
  writeFileSync(configPath, JSON.stringify(merged, null, 2) + "\n");
  return merged;
}

/** Directory for jdt use data (workspace registry + pins). */
export function useDir() {
  const dir = join(getHome(), "use");
  ensureDir(dir);
  return dir;
}

/** Directory for per-terminal / per-process pin files. */
export function pinsDir() {
  const dir = join(useDir(), "pins");
  ensureDir(dir);
  return dir;
}

/** Read workspaces.json — known workspace registry. Returns [] if missing. */
export function readWorkspaces() {
  const file = join(useDir(), "workspaces.json");
  if (!existsSync(file)) return [];
  try {
    const data = JSON.parse(readFileSync(file, "utf8"));
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
}

/** Write workspaces.json — full replacement, not merge. */
export function writeWorkspaces(entries) {
  writeFileSync(
    join(useDir(), "workspaces.json"),
    JSON.stringify(entries, null, 2) + "\n",
  );
}

/** Read a single pin file from pins/. Returns parsed JSON or null. */
export function readPin(filename) {
  const file = join(pinsDir(), filename);
  if (!existsSync(file)) return null;
  try {
    return JSON.parse(readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

/** Write a pin file to pins/. */
export function writePin(filename, data) {
  writeFileSync(
    join(pinsDir(), filename),
    JSON.stringify(data, null, 2) + "\n",
  );
}

/** Delete a pin file. Silent if missing. */
export function deletePin(filename) {
  const file = join(pinsDir(), filename);
  try { unlinkSync(file); } catch { /* missing — ok */ }
}

/** List all pin filenames in pins/. */
export function listPins() {
  try {
    return readdirSync(pinsDir()).filter(f => f.endsWith(".json"));
  } catch {
    return [];
  }
}

/** Reset cached home path (for testing). */
export function resetHome() {
  _home = undefined;
}

function ensureDir(dir) {
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
}
