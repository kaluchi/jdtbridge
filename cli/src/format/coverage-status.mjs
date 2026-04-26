// Renderers for jdt coverage run / status / -f stream.

import { bold, dim, green, red, yellow } from "../color.mjs";
import { composeStatus } from "./coverage-state.mjs";

/** Header printed by `jdt coverage run`. */
export function formatRunHeader(result) {
  const parts = [];
  parts.push(`#### Coverage: ${result.configId}`);
  parts.push(`CoverageId:    \`${result.coverageId}\``);
  if (result.coverageScope?.length) {
    parts.push("CoverageScope:");
    for (const path of result.coverageScope) {
      parts.push(`  ${path}`);
    }
  }
  if (result.launchId) parts.push(`LaunchId:      \`${result.launchId}\``);
  parts.push(`ConfigId:      \`${result.configId}\``);
  if (result.configType) parts.push(`ConfigType:    ${result.configType}`);
  parts.push("LaunchMode:    coverage");
  return parts.join("\n");
}

/** Onboarding guide printed by `jdt coverage run` (unless -q). */
export function runGuide(coverageId, launchId) {
  return `
**Coverage status** (coverageId = ${coverageId}):
  \`jdt coverage status ${coverageId}\`             snapshot
  \`jdt coverage status ${coverageId} -f\`          follow until ready
  \`jdt coverage active\`                            show active session

**Console output** (launchId = ${launchId}):
  \`jdt launch logs ${launchId}\`
  \`jdt launch logs ${launchId} --tail 50\`

**Manage running launch:**
  \`jdt coverage dump ${coverageId}\`               request a dump
  \`jdt coverage dump ${coverageId} --reset\`       dump + reset agent probes
  \`jdt coverage stop ${coverageId}\`               terminate

**Sessions:**
  \`jdt coverage runs\`                              list all sessions
  \`jdt coverage activate ${coverageId}\`           switch IDE display
  \`jdt coverage refresh\`                           re-analyze active
  \`jdt coverage relaunch\`                          re-launch active in coverage mode
  \`jdt coverage merge <coverageId> <coverageId>\`  merge two or more
  \`jdt coverage remove\`                            remove active
  \`jdt coverage remove --all\`                      remove all

Add \`-q\` to suppress this guide.`;
}

/** Format a /coverage/session response as a snapshot block. */
export function formatStatusSnapshot(entry) {
  const status = composeStatus(entry);
  const active = entry.active ? ", active" : "";
  const lines = [];
  lines.push(`#### ${entry.coverageId} (${entry.configId || entry.coverageSessionKind}) — ${status}${active}`);
  lines.push("");
  if (entry.coverageScope?.length) {
    lines.push("CoverageScope:");
    for (const path of entry.coverageScope) {
      lines.push(`  ${path}`);
    }
  }
  if (entry.description) {
    lines.push(`Description:   ${entry.description}`);
  }
  if (entry.configType) {
    lines.push(`ConfigType:    ${entry.configType}`);
  }
  if (entry.launchId) {
    lines.push(`LaunchId:      ${entry.launchId}`);
  }
  if (typeof entry.dumpCount === "number") {
    lines.push(`DumpCount:     ${entry.dumpCount}`);
  }
  if (entry.launchTimestamp) {
    lines.push(`LaunchedAt:    ${formatDate(entry.launchTimestamp)}`);
  }
  if (entry.terminatedAt) {
    lines.push(`TerminatedAt:  ${formatDate(entry.terminatedAt)}`);
  } else if (entry.coverageSessionKind === "live" && entry.terminated === false) {
    lines.push("TerminatedAt:  —");
  }

  if (entry.analysisReady === true && entry.counters) {
    lines.push("");
    for (const [label, key] of [
      ["Instructions", "instruction"],
      ["Branches", "branch"],
      ["Lines", "line"],
      ["Complexity", "complexity"],
      ["Methods", "method"],
      ["Classes", "class"],
    ]) {
      const c = entry.counters[key];
      if (c) lines.push(formatCounterRow(label, c));
    }
  } else if (entry.analysisLoading === true) {
    lines.push("");
    lines.push(dim("(analysis loading)"));
  } else if (entry.dataReceived === false && entry.terminated === true) {
    lines.push("");
    lines.push(dim("(no data received)"));
  }
  return lines.join("\n");
}

function formatCounterRow(label, c) {
  const total = c.totalCount ?? 0;
  if (total === 0) {
    return `${label.padEnd(13)}—  ${colorStatus("EMPTY")}`;
  }
  const ratio = formatRatio(c.coveredRatio);
  return [
    label.padEnd(13),
    `coveredCount=${c.coveredCount}`.padEnd(20),
    `missedCount=${c.missedCount}`.padEnd(19),
    `totalCount=${c.totalCount}`.padEnd(18),
    `coveredRatio=${ratio}`.padEnd(20),
    colorStatus(c.coverageStatus),
  ].join("  ");
}

function formatRatio(r) {
  if (r == null) return "—";
  const pct = (r * 100).toFixed(1);
  return `${pct}%`;
}

function colorStatus(status) {
  switch (status) {
    case "FULLY_COVERED": return green(status);
    case "PARTLY_COVERED": return yellow(status);
    case "NOT_COVERED": return red(status);
    case "EMPTY": return dim(status);
    default: return status;
  }
}

function formatDate(millis) {
  const d = new Date(millis);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/**
 * Format one JSONL event from /coverage/session/stream as a single
 * human-readable line. Returns true if anything was written.
 */
export function formatStreamEvent(jsonLine) {
  let ev;
  try { ev = JSON.parse(jsonLine); } catch { return false; }
  const ts = formatTime(Date.now());

  switch (ev.event) {
    case "snapshot": {
      const status = composeStatus({
        coverageSessionKind: ev.coverageSessionKind || "live",
        terminated: ev.terminated,
        dataReceived: ev.dataReceived,
        analysisLoading: ev.analysisLoading,
        analysisReady: ev.analysisReady,
        dumpCount: ev.dumpCount || 0,
      });
      console.log(`[${ts}] snapshot: ${status || "—"}, ${ev.dumpCount || 0} dumps`);
      return true;
    }
    case "dumped":
      console.log(`[${ts}] dumped #${ev.dumpIndex} at ${formatTime(ev.dumpTimestamp)}`);
      return true;
    case "analysisLoading":
      console.log(`[${ts}] analyzing #${ev.dumpIndex}`);
      return true;
    case "analysisReady":
      console.log(`[${ts}] ready #${ev.dumpIndex}`);
      return true;
    case "terminated": {
      const at = ev.terminatedAt ? formatTime(ev.terminatedAt) : ts;
      const tail = ev.dataReceived === false ? " (no data)" : "";
      console.log(`[${ts}] terminated at ${at}${tail}`);
      return true;
    }
    case "failed":
      console.log(`[${ts}] failed: ${ev.reason || "unknown"}`);
      return true;
    default:
      return false;
  }
}

function formatTime(millis) {
  const d = new Date(millis);
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/**
 * Stream /coverage/session/stream events.
 * Returns 0 on clean exit, 1 on error.
 */
export async function followCoverageStream(coverageId, args) {
  const { followJsonlStream } = await import("./stream.mjs");
  const jsonFlag = args.includes("--json");
  const url = `/coverage/session/stream?coverageId=${encodeURIComponent(coverageId)}`;
  return followJsonlStream(url, (line) => {
    if (jsonFlag) console.log(line);
    else formatStreamEvent(line);
  });
}
