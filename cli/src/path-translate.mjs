// Path translation for remote instances.
//
// Plugin responses carry absolute paths on the Eclipse host.
// The model driving the CLI lives in a sandbox (Docker container,
// VM, SSH-mounted workstation) where those paths point at nothing
// — the host filesystem is not mounted through. Translation is
// mount-aware:
//
//   1. `jdt setup remote --add-mount-point <local-path>` scans
//      <local-path> for `.project` files and records each project's
//      local path (CLI-side, absolute, CLI-native format).
//   2. During the same scan the CLI queries the plugin for every
//      project's `:rootPath` (Eclipse-host absolute path).
//   3. The cache file stores both roots per project.
//
// At runtime each path in a plugin response is matched against the
// cache's `eclipseRoot` prefixes (longest first — so nested
// projects win over outer mount points). On a match the
// Eclipse-host prefix is swapped for the CLI-side prefix and the
// suffix is normalised to the CLI's separator convention.
//
// Paths that match no project (JRE JARs, `.m2` cache, Eclipse
// install-dir resources) stay as-is. The model sees them as
// "not under any mount" and goes through the API (`@source`,
// `@type`) for content rather than `Read` for bytes.

import { existsSync, readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { remoteProjectPathsDir } from "./home.mjs";
import { currentInstance } from "./client.mjs";

function bridgeSocketHash(bridgeSocket) {
  return createHash("sha256").update(bridgeSocket).digest("hex")
      .slice(0, 12);
}

function cacheFilePath(bridgeSocket) {
  return join(remoteProjectPathsDir(),
      bridgeSocketHash(bridgeSocket) + ".json");
}

/**
 * Load the translation table for a remote bridge-socket — an
 * array of `{eclipseRoot, localRoot}` rows sorted longest
 * eclipseRoot first. Empty array when the cache file is absent,
 * unreadable, or has no usable rows.
 */
export function loadTranslationTable(bridgeSocket) {
  const file = cacheFilePath(bridgeSocket);
  if (!existsSync(file)) return [];
  let cache;
  try {
    cache = JSON.parse(readFileSync(file, "utf8"));
  } catch {
    return [];
  }
  const projects = cache.projects || {};
  const rows = [];
  for (const name of Object.keys(projects)) {
    const entry = projects[name];
    if (entry && typeof entry === "object"
        && typeof entry.eclipseRoot === "string"
        && typeof entry.localRoot === "string") {
      rows.push({
        eclipseRoot: entry.eclipseRoot,
        localRoot: entry.localRoot,
      });
    }
  }
  rows.sort((a, b) => b.eclipseRoot.length - a.eclipseRoot.length);
  return rows;
}

/** Normalise to forward slashes for comparison. */
function canonicalize(p) {
  return p.replace(/\\/g, "/");
}

/**
 * Pick the separator style of a filesystem path — backslash when
 * the path starts with a drive letter (`D:\…`) or uses backslashes,
 * forward slash otherwise. Applied to the suffix after prefix
 * replacement so the result is a clean native-style path.
 */
function separatorOf(path) {
  if (/^[A-Za-z]:[\\/]/.test(path)) return path.includes("\\") ? "\\" : "/";
  return path.includes("\\") && !path.includes("/") ? "\\" : "/";
}

function normaliseSeparators(s, sep) {
  return sep === "\\" ? s.replace(/\//g, "\\") : s.replace(/\\/g, "/");
}

/**
 * Translate one path against a translation table. Returns the
 * input unchanged when no row matches or the input is not a
 * String.
 */
export function translatePath(hostPath, table) {
  if (typeof hostPath !== "string" || table.length === 0) return hostPath;
  const canon = canonicalize(hostPath);
  for (const { eclipseRoot, localRoot } of table) {
    const eclipseCanon = canonicalize(eclipseRoot);
    if (canon === eclipseCanon) return localRoot;
    if (canon.startsWith(eclipseCanon + "/")) {
      const suffix = canon.slice(eclipseCanon.length);
      return localRoot + normaliseSeparators(suffix, separatorOf(localRoot));
    }
  }
  return hostPath;
}

/**
 * High-level translator used by the API-response remapper. Reads
 * the current instance via {@link currentInstance} and picks the
 * right translation table:
 * <ul>
 *   <li>Local instance — returns input verbatim. Plugin and CLI
 *       see the same filesystem; no translation needed.</li>
 *   <li>Remote instance — loads the instance's project-paths
 *       cache and applies {@link translatePath}.</li>
 * </ul>
 */
export function translateHostPath(hostPath) {
  const inst = currentInstance();
  if (!inst || !inst.remote) return hostPath;
  const socket = inst.bridgeSocket || (inst.host + ":" + inst.port);
  return translatePath(hostPath, loadTranslationTable(socket));
}

/**
 * Reverse of {@link translatePath}: local path → host path. Swaps
 * {@code localRoot} for {@code eclipseRoot} on longest-prefix match.
 * Needed when a pipeline carries a path field into an outbound
 * axis (e.g. `@containingFile` piping {@code :location/file} back
 * into `@file`). Without this, the outbound request hands the
 * server a CLI-local path it cannot resolve.
 */
export function translateLocalPath(localPath, table) {
  if (typeof localPath !== "string" || table.length === 0) return localPath;
  const canon = canonicalize(localPath);
  const rows = [...table].sort(
    (a, b) => b.localRoot.length - a.localRoot.length);
  for (const { eclipseRoot, localRoot } of rows) {
    const localCanon = canonicalize(localRoot);
    if (canon === localCanon) return eclipseRoot;
    if (canon.startsWith(localCanon + "/")) {
      const suffix = canon.slice(localCanon.length);
      return eclipseRoot + normaliseSeparators(suffix, separatorOf(eclipseRoot));
    }
  }
  return localPath;
}

/**
 * High-level reverse translator: a path already in CLI-local form
 * (e.g. projected from a previous response's {@code :location/file}
 * or typed by the user after a {@code jdt editors} listing) is
 * converted back to the shape the plugin accepts. No-op on local
 * instances or on paths that match no mount-point prefix.
 */
export function translateHostPathFromLocal(localPath) {
  const inst = typeof currentInstance === "function"
    ? currentInstance() : null;
  if (!inst || !inst.remote) return localPath;
  const socket = inst.bridgeSocket || (inst.host + ":" + inst.port);
  return translateLocalPath(localPath, loadTranslationTable(socket));
}
