// Output strategy dispatcher.
// Commands produce data; this module picks the renderer by format flag.
//
// Built-in formats:
//   json  — universal, works for any data shape
//
// Per-command formats (provided via renderers argument):
//   text  — human-readable (tables, colors, etc.)
//   md    — (future) markdown
//   mdp   — (future) markdown preview
//
// Adding a new format:
//   1. Add flag detection in resolveFormat()
//   2. Add built-in strategy in BUILT_IN (if universal) or
//      pass per-command renderer in the renderers object

import { printJson } from "./json-output.mjs";

/** Built-in format strategies that work for any data shape. */
const BUILT_IN = {
  json: {
    error(msg) { console.log(JSON.stringify({ error: msg })); },
    empty() { console.log("[]"); },
    data(d) { printJson(d); },
  },
};

/**
 * Resolve output format from CLI args.
 */
function resolveFormat(args) {
  if (args.includes("--json")) return "json";
  return "text";
}

/**
 * Output command data using the appropriate format strategy.
 *
 * Handles three cases uniformly across all formats:
 *   1. Server error  ({ error: "..." })  → format-specific error output
 *   2. Empty array   ([])                → format-specific empty message
 *   3. Normal data                       → format-specific renderer
 *
 * @param {string[]} args - CLI args (format is resolved from these)
 * @param {*} data - command result: object, array, or { error: string }
 * @param {Object} renderers - per-command renderers keyed by format name
 * @param {Function} renderers.text - text renderer: (data) => void
 * @param {string} [renderers.empty] - empty message for text format (default: "(no results)")
 */
export function output(args, data, renderers) {
  const format = resolveFormat(args);
  const strategy = BUILT_IN[format];

  // --- Server error ---
  if (data?.error && !Array.isArray(data)) {
    if (strategy) { strategy.error(data.error); return; }
    console.error(data.error);
    return;
  }

  // --- Empty array ---
  if (Array.isArray(data) && data.length === 0) {
    if (strategy) { strategy.empty(); return; }
    console.log(renderers.empty || "(no results)");
    return;
  }

  // --- Normal data ---
  if (strategy) { strategy.data(data); return; }

  // Per-command renderer
  const renderer = renderers[format];
  if (!renderer) {
    console.error(`Format '${format}' not supported by this command`);
    process.exit(1);
  }
  renderer(data);
}
