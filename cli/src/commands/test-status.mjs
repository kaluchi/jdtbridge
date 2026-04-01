import { get } from "../client.mjs";
import { extractPositional } from "../args.mjs";
import { output } from "../output.mjs";
import {
  formatTestStatus,
  followTestStream,
} from "../format/test-status.mjs";

/**
 * Show test session status (snapshot or stream).
 * Analogous to `jdt launch logs`.
 */
export async function testStatus(args) {
  const pos = extractPositional(args);
  const session = pos[0];

  if (!session) {
    console.error("Usage: test status <session> [-f] [--all] [--ignored] [--json]");
    process.exit(1);
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    const exitCode = await followTestStream(session, args);
    process.exit(exitCode);
  }

  // Snapshot mode
  let filter = "failures";
  if (args.includes("--all")) filter = "all";
  else if (args.includes("--ignored")) filter = "ignored";

  let url = `/test/status?session=${encodeURIComponent(session)}`;
  if (filter) url += `&filter=${filter}`;

  const data = await get(url, 30_000);

  output(args, data, {
    text: formatTestStatus,
  });
}

export const help = `Show test session status (snapshot or live stream).

Usage:  jdt test status <session> [-f] [--all] [--ignored] [--json]

Without -f, returns a snapshot of the current state.
With -f, streams test events until the session completes.

Flags:
  -f, --follow    stream events live until completion
  --all           show all tests (default: failures only)
  --ignored       show only ignored/skipped tests
  --json          JSON snapshot, or JSONL when streaming (-f)

Examples:
  jdt test status jdtbridge-test-1234567890
  jdt test status jdtbridge-test-1234567890 -f
  jdt test status jdtbridge-test-1234567890 --ignored
  jdt test status jdtbridge-test-1234567890 --all --json
  jdt test status jdtbridge-test-1234567890 -f --json

Console output (stdout, stderr, stack traces):
  jdt launch logs <session-name>
  jdt launch logs <session-name> --tail 50`;
