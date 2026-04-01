import { get } from "../client.mjs";
import { output } from "../output.mjs";
import { green, red, yellow } from "../color.mjs";
import { formatTable } from "../format/table.mjs";

/**
 * List active and completed test sessions.
 */
export async function testSessions(args = []) {
  const data = await get("/test/sessions");

  output(args, data, {
    empty: "(no test sessions)",
    text(data) {
      const now = Date.now();
      const headers = ["SESSION", "LABEL", "TESTS", "RESULT", "TIME", "STATUS"];
      const rows = data.map((s) => {
        const time = Number.isFinite(s.time) && s.time > 0
          ? `${s.time.toFixed(1)}s` : "";
        const label = s.label && s.label !== s.session ? s.label : "";
        const startMs = s.startedAt || 0;
        let status;
        if (s.state === "running") {
          status = startMs ? `running, started ${ago(now - startMs)}` : "running";
        } else {
          const endMs = startMs && s.time > 0 ? startMs + s.time * 1000 : 0;
          status = endMs ? `finished ${ago(now - endMs)}` : "finished";
        }
        return [s.session, label, `${s.total}`, formatCounts(s), time, status];
      });
      console.log(formatTable(headers, rows));
    },
  });
}

function formatCounts(s) {
  const parts = [];
  if (s.passed > 0) parts.push(green(`${s.passed} passed`));
  if (s.failed > 0) parts.push(red(`${s.failed} failed`));
  if (s.errors > 0) parts.push(red(`${s.errors} errors`));
  if (s.ignored > 0) parts.push(yellow(`${s.ignored} ign`));
  return parts.join(", ");
}

function ago(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  return `${h}h ago`;
}

export const help = `List active and completed test sessions.

Usage:  jdt test sessions [--json]

Options:
  --json    output as JSON

Output: session ID, label, test counts, status — one session per line.

The session ID is also the launch name. To see console output
(stdout, stderr, stack traces) of a test run:
  jdt launch logs <session-name>
  jdt launch logs <session-name> --tail 50

Examples:
  jdt test sessions
  jdt test sessions --json`;
