// Compose the STATUS string for jdt coverage runs / status.
// Source: jdt-coverage-spec § STATUS composition.
//
// Three token groups, joined by ", ":
//   A — exactly one launch/origin token
//   B — zero or more data/analysis tokens, in order
//   C — at most one relative-time token (live runs only)

/**
 * @param {object} run — entry from /coverage/runs (one element)
 *   coverageSessionKind, terminated, dataReceived,
 *   analysisLoading, analysisReady, dumpCount,
 *   consumedCoverageIds (merged), launchTimestamp (live),
 *   terminatedAt
 * @param {number} now — Date.now() at render time
 * @returns {string}
 */
export function composeStatus(run, now = Date.now()) {
  const tokens = [];

  // Group A — origin / launch state
  const kind = run.coverageSessionKind;
  if (kind === "merged") {
    const n = (run.consumedCoverageIds || []).length;
    tokens.push(`merged ${n} sessions`);
  } else if (kind === "imported") {
    tokens.push("imported");
  } else if (kind === "live") {
    if (run.terminated) {
      const endMs = run.terminatedAt || 0;
      tokens.push(endMs ? `finished ${ago(now - endMs)}` : "finished");
    } else {
      tokens.push("running");
    }
  }

  // Group B — data / analysis
  if (run.dataReceived === false && run.terminated === true) {
    tokens.push("no data received");
  }
  if (run.dumpCount > 1) {
    tokens.push(`${run.dumpCount} dumps`);
  }
  if (run.analysisLoading === true) {
    tokens.push("analysis loading");
  }
  if (run.analysisReady === true) {
    tokens.push("analysis ready");
  }
  if (
    run.analysisLoading === false &&
    run.analysisReady === false &&
    run.terminated === true &&
    run.dataReceived === true
  ) {
    tokens.push("analysis pending");
  }

  // Group C — relative time (live, not terminated)
  if (kind === "live" && run.terminated === false && run.launchTimestamp) {
    tokens.push(`started ${ago(now - run.launchTimestamp)}`);
  }

  return tokens.join(", ");
}

function ago(ms) {
  if (ms < 0) ms = 0;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}
