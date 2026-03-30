// JDTBRIDGE_HOME management — config dir, instances, plugin artifacts.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
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

/** Reset cached home path (for testing). */
export function resetHome() {
  _home = undefined;
}

function ensureDir(dir) {
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
}
