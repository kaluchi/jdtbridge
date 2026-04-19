// JSON output helpers — walk a response tree and rewrite
// host-absolute paths through toSandboxPath so the model inside a
// Docker sandbox sees them in its own filesystem convention.
//
// `:fqn` is NEVER remapped — fqn is an identifier round-tripped
// back to the plugin verbatim. Only `:file`, `:path`, `:rootPath`,
// `:outputLocation` carry filesystem paths.

import { toSandboxPath } from "./paths.mjs";

const PATH_KEY_NAMES = ["file", "path", "rootPath", "outputLocation"];

export function remapJsonPaths(obj) {
  if (Array.isArray(obj)) {
    obj.forEach(remapJsonPaths);
  } else if (obj && typeof obj === "object") {
    for (const key of Object.keys(obj)) {
      if (PATH_KEY_NAMES.includes(key) && typeof obj[key] === "string") {
        obj[key] = toSandboxPath(obj[key]);
      } else {
        remapJsonPaths(obj[key]);
      }
    }
  }
  return obj;
}

export function printJson(data) {
  remapJsonPaths(data);
  console.log(JSON.stringify(data, null, 2));
}
