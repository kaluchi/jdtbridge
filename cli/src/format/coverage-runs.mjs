// Renderer for `jdt coverage runs` — table output.

import { formatTable } from "./table.mjs";
import { composeStatus } from "./coverage-state.mjs";

/**
 * Render the array returned by GET /coverage/runs as a table.
 * @param {object[]} runs
 */
export function renderRunsTable(runs) {
  const headers = ["COVERAGEID", "CONFIGID", "ACTIVE", "DUMPS", "STATUS"];
  const now = Date.now();
  const rows = runs.map((run) => [
    run.coverageId,
    run.configId || "—",
    run.active ? "*" : "",
    String(run.dumpCount ?? ""),
    composeStatus(run, now),
  ]);
  console.log(formatTable(headers, rows));
}
