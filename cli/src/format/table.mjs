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

/** Format rows as aligned columns with headers. Handles multiline cell values. */
export function formatTable(headers, rows) {
  // Split each cell into lines for width calculation
  const cellLines = rows.map((r) => r.map((c) => (c || "").split("\n")));

  const widths = headers.map((h, i) =>
    Math.max(h.length, ...cellLines.map((r) =>
      Math.max(...(r[i] || [""]).map(visLen)))));

  const fmtLine = (cells) => cells.map((c, i) => pad(c || "", widths[i])).join("  ").trimEnd();
  const out = [fmtLine(headers)];

  for (const cl of cellLines) {
    const maxLines = Math.max(...cl.map((c) => c.length));
    for (let ln = 0; ln < maxLines; ln++) {
      out.push(fmtLine(cl.map((c) => c[ln] || "")));
    }
  }
  return out.join("\n");
}
