// JSON output helpers — walk a response tree and rewrite
// host-absolute paths to the CLI's filesystem convention via the
// remote-instance project-paths cache. `:fqn` is never remapped;
// it is an identifier round-tripped back to the plugin verbatim.

import { translateHostPath } from "./path-translate.mjs";

const PATH_KEY_NAMES = ["file", "path", "rootPath", "outputLocation"];

export function remapJsonPaths(obj) {
  if (Array.isArray(obj)) {
    obj.forEach(remapJsonPaths);
  } else if (obj && typeof obj === "object") {
    for (const key of Object.keys(obj)) {
      if (PATH_KEY_NAMES.includes(key) && typeof obj[key] === "string") {
        obj[key] = translateHostPath(obj[key]);
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
