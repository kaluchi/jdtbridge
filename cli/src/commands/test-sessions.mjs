import { get } from "../client.mjs";
import { output } from "../output.mjs";
import { green, red, yellow } from "../color.mjs";
import { formatTable } from "../format/table.mjs";
import { ago } from "../format/relative-time.mjs";

/**
 * List test runs (active and completed).
 */
export async function testSessions(args = []) {
  const data = await get("/test/sessions");

  output(args, data, {
    empty: "(no test runs)",
    text(data) {
      const now = Date.now();
      const headers = ["TESTRUNID", "CONFIGID", "TESTS", "RESULT", "TIME", "STATUS"];
      const rows = data.map((s) => {
        const time = Number.isFinite(s.time) && s.time > 0
          ? `${s.time.toFixed(1)}s` : "";
        const configId = s.configId;
        const testRunId = s.testRunId;
        const startMs = s.startedAt || 0;
        let status;
        if (s.state === "running") {
          status = startMs ? `running, started ${ago(now - startMs)}` : "running";
        } else {
          const endMs = startMs && s.time > 0 ? startMs + s.time * 1000 : 0;
          status = endMs ? `finished ${ago(now - endMs)}` : "finished";
        }
        return [testRunId, configId, `${s.total}`, formatCounts(s), time, status];
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

export const help = `List test runs (active and completed).

Usage:  jdt test runs [--json]

Options:
  --json    output as JSON

Shows TESTRUNID, CONFIGID, test counts, result, time, and status.
Use TESTRUNID with jdt test status. Use CONFIGID with jdt launch config.

To see console output (stdout, stderr, stack traces):
  jdt launch logs <launchId>
  jdt launch logs <launchId> --tail 50

Examples:
  jdt test runs
  jdt test runs --json`;
