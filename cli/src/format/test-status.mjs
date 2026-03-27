// Test status formatter.
// Formats test session snapshots and streaming JSONL events.
// Uses FQMN with # separator and [M] badges for navigation.

import { red, green, yellow, bold, dim } from "../color.mjs";

/**
 * Format a test status snapshot (JSON from /test/status).
 * Shows summary line + failure/ignored details based on filter.
 */
export function formatTestStatus(result) {
  const state = result.state === "running"
    ? " (running)"
    : result.state === "stopped"
      ? " (stopped)"
      : "";

  const label = result.label || result.session;
  const progress = result.state === "running"
    ? `${result.completed}/${result.total}`
    : `${result.total}/${result.total}`;

  console.log(
    `#### ${label} — ${progress}${state}, ${formatCounts(result)}`,
  );

  const entries = result.entries || result.failures || [];
  if (entries.length > 0) {
    console.log();
    for (const f of entries) {
      formatEntry(f);
    }
  }
}

/**
 * Format a single JSONL event line from the stream.
 * Returns true if something was printed.
 */
export function formatTestEvent(jsonLine) {
  let parsed;
  try {
    parsed = JSON.parse(jsonLine);
  } catch {
    return false;
  }

  if (parsed.event === "started") {
    // Header already printed by test-run, skip in stream
    return false;
  }

  if (parsed.event === "finished") {
    console.log();
    console.log(formatCounts(parsed));
    return true;
  }

  if (parsed.event === "case") {
    const status = parsed.status;
    const fqmn = parsed.fqmn;
    const time = formatTime(parsed.time);

    if (status === "PASS") {
      console.log(`${green("PASS")} [M] \`${fqmn}\` ${dim(time)}`);
    } else if (status === "FAIL") {
      console.log(`${red("FAIL")} [M] \`${fqmn}\` ${dim(time)}`);
      printTrace(parsed);
    } else if (status === "ERROR") {
      console.log(
        `${bold(red("ERROR"))} [M] \`${fqmn}\` ${dim(time)}`,
      );
      printTrace(parsed);
    } else if (status === "IGNORED") {
      console.log(
        `${yellow("IGNORED")} [M] \`${fqmn}\``,
      );
    }
    return true;
  }

  return false;
}

/**
 * Format a single entry from status snapshot (PASS, FAIL, ERROR, IGNORED).
 */
function formatEntry(f) {
  const status = f.status;
  const fqmn = f.fqmn;
  const time = formatTime(f.time);

  if (status === "PASS") {
    console.log(`${green("PASS")} [M] \`${fqmn}\` ${dim(time)}`);
  } else if (status === "FAIL") {
    console.log(`${red("FAIL")} [M] \`${fqmn}\` ${dim(time)}`);
    printTrace(f);
  } else if (status === "ERROR") {
    console.log(
      `${bold(red("ERROR"))} [M] \`${fqmn}\` ${dim(time)}`,
    );
    printTrace(f);
  } else if (status === "IGNORED") {
    console.log(`${yellow("IGNORED")} [M] \`${fqmn}\``);
  }
}

function printTrace(parsed) {
  if (parsed.expected != null && parsed.actual != null) {
    console.log(`  Expected: ${parsed.expected}`);
    console.log(`  Actual: ${parsed.actual}`);
  }
  if (parsed.trace) {
    const lines = parsed.trace.split("\n").slice(0, 10);
    for (const line of lines) console.log(`  ${line}`);
    if (parsed.trace.split("\n").length > 10) console.log("  ...");
  }
}

function formatCounts(r) {
  const parts = [`${r.total} tests`];
  if (r.passed > 0) parts.push(green(`${r.passed} passed`));
  if (r.failed > 0) parts.push(red(`${r.failed} failed`));
  if (r.errors > 0) parts.push(red(`${r.errors} errors`));
  if (r.ignored > 0) parts.push(yellow(`${r.ignored} ignored`));
  const time = Number.isFinite(r.time) ? r.time : 0;
  parts.push(`${time.toFixed(1)}s`);
  return parts.join(", ");
}

function formatTime(t) {
  if (t == null || !Number.isFinite(t)) return "";
  return `(${t.toFixed(3)}s)`;
}

/**
 * Format the header printed at test run start.
 * Markdown-friendly: heading + backtick-wrapped values.
 */
export function formatTestRunHeader(result) {
  const label = result.label || result.session;
  const total = result.total ? ` (${result.total} tests)` : "";
  const parts = [`#### Test: ${label}${total}`];
  parts.push(`Launch: \`${result.session}\``);
  if (result.project) parts.push(`Project: \`${result.project}\``);
  if (result.runner) parts.push(`Runner: ${result.runner}`);
  return parts.join("\n");
}

/**
 * Onboarding guide printed after non-blocking launch.
 * Markdown-friendly: bold sections, backtick commands.
 */
export function testRunGuide(session) {
  return `
**Status** — snapshot or live stream:
  \`jdt test status ${session}\`        failures only (default)
  \`jdt test status ${session} -f\`     stream live until done
  \`jdt test status ${session} --all\`  all tests including passed
  \`jdt test status ${session} --ignored\`  only skipped/disabled tests

**Console** — raw stdout/stderr of the test JVM:
  \`jdt launch logs ${session}\`

**Manage:**
  \`jdt test sessions\`                 list active/completed sessions
  \`jdt launch stop ${session}\`   abort
  \`jdt launch clear ${session}\`  remove

**Navigate** — FQMNs from status output are copy-pasteable:
  \`jdt source <FQMN>\`                 view test source
  \`jdt test run <FQMN> -f\`            re-run single test

Add \`-q\` to suppress this guide.`;
}

/**
 * Stream test status via JSONL and format output.
 * Shared by test-run -f and test-status -f.
 * Returns exit code: 0 if all pass, 1 if any failures.
 */
export async function followTestStream(session, args) {
  const { getStreamLines } = await import("../client.mjs");

  let filter = "failures";
  if (args.includes("--all")) filter = "all";
  else if (args.includes("--ignored")) filter = "ignored";

  const url = `/test/status/stream?session=${encodeURIComponent(session)}&filter=${filter}`;

  let detached = false;
  const onSigint = () => {
    detached = true;
    process.stdout.write("\n");
    process.exit(0);
  };
  process.on("SIGINT", onSigint);

  let hasFailed = false;
  try {
    await getStreamLines(url, (line) => {
      formatTestEvent(line);
      try {
        const ev = JSON.parse(line);
        if (ev.event === "finished"
            && (ev.failed > 0 || ev.errors > 0)) {
          hasFailed = true;
        }
      } catch { /* ignore parse errors */ }
    });
  } catch (e) {
    if (!detached) {
      console.error(e.message);
      return 1;
    }
    return 0;
  } finally {
    process.removeListener("SIGINT", onSigint);
  }
  return hasFailed ? 1 : 0;
}
