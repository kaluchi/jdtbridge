// Test results formatter.
// Shows summary line + failure details with truncated stack traces.

import { red, green, yellow, bold } from "../color.mjs";

export function formatTestResults(result) {
  // Summary line
  const parts = [`${result.total} tests`];
  if (result.passed > 0) parts.push(green(`${result.passed} passed`));
  if (result.failed > 0) parts.push(red(`${result.failed} failed`));
  if (result.errors > 0) parts.push(red(`${result.errors} errors`));
  if (result.ignored > 0) parts.push(yellow(`${result.ignored} ignored`));
  const time = Number.isFinite(result.time) ? result.time : 0;
  parts.push(`${time.toFixed(1)}s`);
  console.log(parts.join(", "));

  // Failure details
  if (result.failures && result.failures.length > 0) {
    console.log();
    for (const f of result.failures) {
      const status =
        f.status === "FAILURE" ? red(f.status) : bold(red(f.status));
      console.log(`${status} [M] \`${f.class}#${f.method}\``);
      if (f.trace) {
        const traceLines = f.trace.split("\n").slice(0, 10);
        for (const line of traceLines) console.log(`  ${line}`);
        if (f.trace.split("\n").length > 10) console.log("  ...");
      }
    }
  }
}
