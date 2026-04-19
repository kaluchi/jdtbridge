// Path utilities — small helpers used across the CLI.
//
// API-response paths (file / path / rootPath / outputLocation)
// are translated via path-translate.mjs, which uses the per-
// remote-instance mount cache to rewrite Eclipse-host paths into
// the CLI's filesystem. This file keeps only general-purpose
// helpers that do not require any per-response context.

/**
 * Ensure path starts with / for workspace-relative API calls.
 * Used by the legacy /organize-imports and /format endpoints in
 * refactoring.mjs.
 */
export function toWsPath(p) {
  return p.startsWith("/") ? p : "/" + p;
}

/**
 * Format a line range for table display.
 * Returns ":startLine-endLine" or a "(source not attached)"
 * hint when the start line is negative.
 */
export function formatLineRange(startLine, endLine) {
  if (startLine < 0) return " (source not attached)";
  return `:${startLine}-${endLine}`;
}

/**
 * Construct a Docker-sandbox Linux path from a Windows host path
 * for sandbox CLI invocations. The CLI itself runs on the host;
 * it builds paths the container will see. Windows drive paths
 * become `/<drive-letter>/…` (Docker Desktop WSL2 convention);
 * non-drive paths get backslash normalisation.
 *
 * This is NOT used for translating API response paths — those
 * go through translateHostPath / the per-instance mount cache.
 */
export function hostToSandboxPath(p) {
  const m = /^([A-Za-z]):[/\\]/.exec(p);
  if (m) return "/" + m[1].toLowerCase() + normalizePath(p.slice(2));
  return normalizePath(p);
}

/** Normalise backslashes to forward slashes for path comparison. */
export function normalizePath(p) {
  return p.replace(/\\/g, "/");
}
