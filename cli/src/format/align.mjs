// Align command/description rows so descriptions start at the same column.
// Used by `*RunGuide` blocks that mix variable-length commands (with
// embedded launchId / coverageId) and short descriptions.

/**
 * @param {Array<[string, string]>} rows - [command, description] pairs
 * @param {object} [opts]
 * @param {string} [opts.indent="  "] - line prefix
 * @param {number} [opts.gap=2] - spaces between command and description
 * @returns {string} joined lines, no trailing newline
 */
export function alignCmds(rows, { indent = "  ", gap = 2 } = {}) {
  const widest = Math.max(...rows.map(([cmd]) => cmd.length));
  return rows.map(([cmd, desc]) => {
    const pad = " ".repeat(widest - cmd.length + gap);
    return `${indent}\`${cmd}\`${pad}${desc}`;
  }).join("\n");
}
