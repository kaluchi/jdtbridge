// JSON output helpers for --json flag.
// Separate module so tests can mock toSandboxPath in paths.mjs
// and the mock propagates through the import chain.

import { toSandboxPath } from "./paths.mjs";

/**
 * Recursively apply toSandboxPath to all "file" and "location" values
 * in a JSON-serializable object. Ensures consistent path format in
 * Docker sandbox environments.
 */
export function remapJsonPaths(obj) {
  if (Array.isArray(obj)) {
    for (const item of obj) remapJsonPaths(item);
  } else if (obj && typeof obj === "object") {
    for (const key of Object.keys(obj)) {
      if ((key === "file" || key === "location") && typeof obj[key] === "string") {
        obj[key] = toSandboxPath(obj[key]);
      } else {
        remapJsonPaths(obj[key]);
      }
    }
  }
}

/**
 * Print data as JSON with sandbox path remapping.
 * Standard --json output: remap file/location paths, pretty-print, log.
 */
export function printJson(data) {
  remapJsonPaths(data);
  console.log(JSON.stringify(data, null, 2));
}
