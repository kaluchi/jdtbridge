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
 * Convert Windows absolute path to Docker sandbox Linux path.
 * Bridge returns Windows paths (D:/src/Foo.java) that don't exist in the
 * Linux sandbox. Docker sandbox mounts workspace with drive letter lowercased:
 * D:\git\project → /d/git/project.
 *
 * Only converts on Linux (inside sandbox). On Windows host — no-op.
 */
export function toSandboxPath(p) {
  if (!p) return p;
  if (process.platform === "linux" && /^[A-Z]:[/\\]/.test(p)) {
    return "/" + p[0].toLowerCase() + p.slice(2).replace(/\\/g, "/");
  }
  return p;
}
