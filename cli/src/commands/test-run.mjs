import { get, getStreamLines } from "../client.mjs";
import { extractPositional, parseFlags, parseFqmn } from "../args.mjs";
import {
  formatTestRunHeader,
  testRunGuide,
  formatTestEvent,
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

  if (flags.timeout) url += `&timeout=${flags.timeout}`;
  if (args.includes("--no-refresh")) url += "&no-refresh";

  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }

  // Wait briefly for session to register total count
  const session = result.session;
  await sleep(500);
  try {
    const status = await get(
      `/test/status?session=${encodeURIComponent(session)}`,
      5_000,
    );
    if (status && !status.error && status.total > 0) {
      result.total = status.total;
      if (status.label) result.label = status.label;
    }
  } catch {
    // ignore — total just won't be shown
  }

  console.log(formatTestRunHeader(result));

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    console.log();
    const exitCode = await followTestStatus(session, args);
    process.exit(exitCode);
  }

  const quiet = args.includes("-q") || args.includes("--quiet");
  if (!quiet) {
    console.log(testRunGuide(session));
  }
}

/**
 * Stream test status until session finishes.
 */
async function followTestStatus(session, args) {
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
        if (ev.event === "finished" && (ev.failed > 0 || ev.errors > 0)) {
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

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

export const help = `Launch tests non-blocking with real-time progress.

Usage:  jdt test run <FQN>[#method] [-f] [-q]
        jdt test run --project <name> [--package <pkg>] [-f] [-q]

Without -f, launches and prints a guide with available commands.
With -f, launches and streams test progress until completion.

Flags:
  -f, --follow    stream test status (only failures by default)
  -q, --quiet     suppress onboarding guide
  --all           include passed tests in output (with -f)
  --ignored       show only ignored tests (with -f)
  --timeout <s>   test run timeout in seconds (default: 300)

Examples:
  jdt test run com.example.MyTest                 run + show guide
  jdt test run com.example.MyTest -f              run + stream failures
  jdt test run com.example.MyTest -f --all        run + stream all tests
  jdt test run --project my-project -f             run project tests + stream`;
