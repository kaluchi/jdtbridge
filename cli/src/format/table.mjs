// eslint-disable-next-line no-control-regex
const ANSI_RE = /\x1b\[[0-9;]*m/g;

/** Visible length excluding ANSI escape codes. */
function visLen(s) {
  return s.replace(ANSI_RE, "").length;
}

/** Pad string to width, accounting for ANSI codes. */
function pad(s, w) {
  const diff = w - visLen(s);
  return diff > 0 ? s + " ".repeat(diff) : s;
}

/** Format rows as aligned columns with headers. */
export function formatTable(headers, rows) {
  const widths = headers.map((h, i) =>
    Math.max(h.length, ...rows.map((r) => visLen(r[i] || ""))));
  const line = (cells) => cells.map((c, i) => pad(c || "", widths[i])).join("  ").trimEnd();
  return [line(headers), ...rows.map(line)].join("\n");
}
