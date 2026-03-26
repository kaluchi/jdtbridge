import { get } from "../client.mjs";
import { green, red, yellow, dim } from "../color.mjs";

/**
 * List active and completed test sessions.
 */
export async function testSessions() {
  const results = await get("/test/sessions");
  if (results.error) {
    console.error(results.error);
    process.exit(1);
  }
  if (results.length === 0) {
    console.log("(no test sessions)");
    return;
  }
  for (const s of results) {
    const label = s.label || s.session;
    const counts = formatCounts(s);
    const time =
      Number.isFinite(s.time) && s.time > 0
        ? `${s.time.toFixed(1)}s`
        : "";
    const state = s.state === "running"
      ? `running (${s.completed}/${s.total})`
      : s.state;
    console.log(
      `${s.session}  ${label}  ${s.total} tests  ${counts}  ${time}  ${state}`,
    );
  }
}

function formatCounts(s) {
  const parts = [];
  if (s.passed > 0) parts.push(green(`${s.passed} passed`));
  if (s.failed > 0) parts.push(red(`${s.failed} failed`));
  if (s.errors > 0) parts.push(red(`${s.errors} errors`));
  if (s.ignored > 0) parts.push(yellow(`${s.ignored} ign`));
  return parts.join(", ");
}

export const help = `List active and completed test sessions.

Usage:  jdt test sessions

Output: session ID, label, test counts, state — one session per line.`;
