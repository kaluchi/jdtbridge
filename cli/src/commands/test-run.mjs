import { get } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";
import {
  formatTestRunHeader,
  testRunGuide,
  followTestStream,
} from "../format/test-status.mjs";

/**
 * Launch tests non-blocking. Analogous to `jdt launch run`.
 * Without -f: prints header + onboarding guide.
 * With -f: prints header + streams test progress until done.
 */
export async function testRun(args) {
  // Filter out single-char flags (-f, -q) before extracting positionals
  const filtered = args.filter((a) => a !== "-f" && a !== "-q");
  const pos = extractPositional(filtered);
  const flags = parseFlags(args);

  let url = "/test/run?";
  const parsed = parseFqmn(pos[0]);
  const fqn = parsed.className;

  if (fqn) {
    url += `class=${encodeURIComponent(fqn)}`;
    const method = parsed.method;
    if (method) url += `&method=${encodeURIComponent(method)}`;
    if (flags.project)
      url += `&project=${encodeURIComponent(flags.project)}`;
  } else if (flags.project) {
    url += `project=${encodeURIComponent(flags.project)}`;
    if (flags.package)
      url += `&package=${encodeURIComponent(flags.package)}`;
  } else {
    console.error(
      "Usage: test run <FQN>[#method] | test run --project <name> [--package <pkg>]",
    );
    process.exit(1);
  }

  if (args.includes("--no-refresh")) url += "&no-refresh";

  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    return;
  }

  const configId = result.configId;
  const launchId = result.launchId;
  const testRunId = result.testRunId;

  // Wait briefly for test run to register total count
  await sleep(500);
  try {
    const status = await get(
      `/test/status?testRunId=${encodeURIComponent(testRunId)}`,
      5_000,
    );
    if (status && !status.error && status.total > 0) {
      result.total = status.total;
      if (status.label) result.label = status.label;
    }
  } catch {
    // ignore — total just won't be shown
  }

  result.launchId = launchId;
  result.testRunId = testRunId;

  const jsonFlag = args.includes("--json");
  if (!jsonFlag) console.log(formatTestRunHeader(result));

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    if (!jsonFlag) console.log();
    const exitCode = await followTestStream(testRunId, args);
    process.exit(exitCode);
  }

  const quiet = args.includes("-q") || args.includes("--quiet");
  if (!quiet) {
    console.log(testRunGuide(testRunId, launchId));
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

export const help = `Launch tests non-blocking with real-time progress.

Usage:  jdt test run <FQN>[#method] [--project <name>] [-f] [-q] [--json]
        jdt test run --project <name> [--package <pkg>] [-f] [-q] [--json]

Without -f, launches and prints a guide with available commands.
With -f, launches and streams test progress until completion.

When --project is used with <FQN>, the test class is resolved by name
but launched using the specified project's classpath. This is useful when
test sources live in one project but need dependencies from another.

Flags:
  --project <name>  override the project for classpath resolution
  -f, --follow      stream test status (only failures by default)
  -q, --quiet       suppress onboarding guide
  --all             include passed tests in output (with -f)
  --ignored         show only ignored tests (with -f)
  --json            output as JSONL when streaming (-f), or JSON snapshot

Examples:
  jdt test run com.example.MyTest                       run + show guide
  jdt test run com.example.MyTest --project Build -f    run with Build classpath
  jdt test run com.example.MyTest -f --all              run + stream all tests
  jdt test run --project my-project -f                  run project tests + stream
  jdt test run com.example.MyTest -f --json             stream as JSONL

The output shows testRunId (for test commands) and launchId (for launch commands):
  jdt test status <testRunId> -f          test pass/fail details
  jdt launch logs <launchId>              console output (stdout, stderr, stack traces)
  jdt launch logs <launchId> --tail 50    last 50 lines of console`;
