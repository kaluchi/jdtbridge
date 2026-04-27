// Pre-flight gate for jdt test run / jdt coverage run.
// Refuses launch when @problems carries any error-severity marker.
// Bypassed by --ignore-compile-errors.

import { get } from "./client.mjs";
import { bold, dim, red } from "./color.mjs";

const FLAG = "--ignore-compile-errors";
const SAMPLE_LIMIT = 5;

/**
 * @param {string[]} args - argv (used for flag detection)
 * @param {object} opts
 * @param {boolean} [opts.json] - --json mode (machine output)
 * @returns {Promise<boolean>} true → caller proceeds; false → caller exits 1
 */
export async function preflightCompileErrors(args, { json = false } = {}) {
  const problems = await get("/problems");
  const errors = (Array.isArray(problems) ? problems : [])
    .filter((p) => p?.severity === "error");
  if (errors.length === 0) return true;

  if (args.includes(FLAG)) {
    if (!json) console.error(dim(bypassWarning(errors.length)));
    return true;
  }

  if (json) {
    console.log(JSON.stringify({
      error: "workspace-has-compile-errors",
      count: errors.length,
      markers: errors.slice(0, SAMPLE_LIMIT).map(toMarker),
      hint: `pass ${FLAG} to launch anyway`,
    }));
  } else {
    printRefusal(errors);
  }
  return false;
}

function bypassWarning(count) {
  return `(${FLAG}: workspace has ${count} compile `
    + `${plural(count, "error")}; expect missing/empty results)`;
}

function toMarker(p) {
  return {
    file: p.location?.file,
    line: p.location?.startLine,
    message: p.message,
  };
}

function printRefusal(errors) {
  const cwd = process.cwd();
  console.error(red(bold(
    `#### Refused: workspace has ${errors.length} compile `
      + plural(errors.length, "error"))));
  console.error("");
  for (const p of errors.slice(0, SAMPLE_LIMIT)) {
    const file = relPath(p.location?.file, cwd);
    const line = p.location?.startLine ?? "?";
    console.error(`  ${file}:${line}  ${p.message}`);
  }
  if (errors.length > SAMPLE_LIMIT) {
    console.error("");
    console.error(dim(
      `(showing ${SAMPLE_LIMIT} of ${errors.length} `
        + `— full list: jdt q '@problems | filter(/error)')`));
  }
  console.error("");
  console.error("Tests / coverage on a broken workspace produce meaningless");
  console.error("results (no instrumented classes loaded, JUnit reports 0 tests).");
  console.error(`Fix the errors, or pass ${FLAG} to launch anyway.`);
}

function plural(n, word) { return n === 1 ? word : word + "s"; }

function relPath(absPath, cwd) {
  if (!absPath) return "?";
  const norm = absPath.replace(/\\/g, "/");
  const cwdNorm = cwd.replace(/\\/g, "/");
  if (norm.toLowerCase().startsWith(cwdNorm.toLowerCase() + "/")) {
    return norm.slice(cwdNorm.length + 1);
  }
  return absPath;
}
