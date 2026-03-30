// Path utilities for workspace-relative paths.

/**
 * Strip leading slash from workspace-relative path.
 * Eclipse returns paths like /m8-server/src/... — we want m8-server/src/...
 */
export function stripProject(wsPath) {
  return wsPath.startsWith("/") ? wsPath.slice(1) : wsPath;
}

/**
 * Ensure path starts with / for workspace-relative API calls.
 * Accepts: m8-server/src/... or /m8-server/src/...
 */
export function toWsPath(p) {
  return p.startsWith("/") ? p : "/" + p;
}

/**
 * Convert Windows drive path to Linux mount path.
 * D:\foo\bar or D:/foo/bar → /d/foo/bar.
 * Returns null if not a Windows drive path.
 */
export function convertDrivePath(p) {
  const m = /^([A-Za-z]):[/\\]/.exec(p);
  if (!m) return null;
  return "/" + m[1].toLowerCase() + p.slice(2).replace(/\\/g, "/");
}

/**
 * Convert Windows absolute path to Docker sandbox Linux path.
 * Only converts on Linux (inside sandbox). On Windows host — no-op.
 */
export function toSandboxPath(p) {
  if (!p || process.platform !== "linux") return p;
  return convertDrivePath(p) ?? p;
}

/**
 * Convert Windows host path to Docker sandbox Linux path.
 * Always converts (runs on host to build paths for sandbox commands).
 * Non-drive paths get backslash normalization only.
 */
export function hostToSandboxPath(p) {
  return convertDrivePath(p) ?? p.replace(/\\/g, "/");
}
