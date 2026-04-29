import { get } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { preflightCompileErrors } from "../preflight-compile-errors.mjs";
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

  const target = pos[0];
  if (!target) {
    console.error(
      "Usage: jdt test run <target> [--project <name>]"
      + " [-f] [-q] [--json] [--coverage]"
      + " [--no-refresh] [--ignore-compile-errors]");
    process.exit(1);
  }

  let url = `/test/run?target=${encodeURIComponent(target)}`;
  if (flags.project) {
    url += `&project=${encodeURIComponent(flags.project)}`;
  }
  if (args.includes("--no-refresh")) url += "&no-refresh";
  const coverage = args.includes("--coverage");
  if (coverage) url += "&coverage=true";

  const jsonFlag = args.includes("--json");
  const cleared = await preflightCompileErrors(args, { json: jsonFlag });
  if (!cleared) process.exit(1);

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

  if (!jsonFlag) console.log(formatTestRunHeader(result));

  if (coverage && !jsonFlag) {
    if (result.coverageId) {
      console.log(`CoverageId: \`${result.coverageId}\``);
    }
    if (result.launchMode) {
      console.log(`LaunchMode: ${result.launchMode}`);
    }
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    if (!jsonFlag) console.log();
    const exitCode = await followTestStream(testRunId, args);
    process.exit(exitCode);
  }

  const quiet = args.includes("-q") || args.includes("--quiet");
  if (!quiet) {
    console.log(testRunGuide(testRunId, launchId));
    if (coverage && result.coverageId && !jsonFlag) {
      console.log("");
      console.log("**Coverage status:**");
      console.log(`  \`jdt coverage status ${result.coverageId}\`             snapshot`);
      console.log(`  \`jdt coverage status ${result.coverageId} -f\`          follow`);
    }
    console.log("");
    console.log("Add `-q` to suppress this guide.");
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

export const help = `Launch tests non-blocking with real-time progress.

Usage:  jdt test run <target> [--project <name>] [-f] [-q] [--json]

Without -f, launches and prints a guide with available commands.
With -f, launches and streams test progress until completion.

The target is one positional, polymorphically resolved by the bridge
via JDT model lookup. Accepted shapes:

  com.example.MyTest             test class (run all tests in class)
  com.example.MyTest#testFoo     single test method
  com.example.MyTest#testFoo(String)   method with overload signature
  com.example                    package (run all tests in package)
  my-project                     project name (run all tests in project)
  /abs/path/to/Foo.java          file (run tests in file's primary type)

Flags:
  --project <name>          override classpath/runtime context — use this
                            project's classpath even if the test class
                            physically lives in another project (test
                            reuse across projects, e.g. test class in
                            project A but dependencies from project B)
  -f, --follow              stream test status (only failures by default)
  -q, --quiet               suppress onboarding guide
  --all                     include passed tests in output (with -f)
  --ignored                 show only ignored tests (with -f)
  --json                    output as JSONL when streaming (-f), or JSON snapshot
  --coverage                launch in coverage mode (EclEmma)
  --no-refresh              skip the workspace refresh before launching
  --ignore-compile-errors   launch despite workspace compile errors

Examples:
  jdt test run com.example.MyTest                  run + show guide
  jdt test run com.example.MyTest#testFoo -f       single method, streamed
  jdt test run my-project -f                       project tests + stream
  jdt test run com.example -f                      package tests + stream
  jdt test run com.example.MyTest --project Build -f
                                                   reuse class, classpath = Build
  jdt test run com.example.MyTest -f --json        stream as JSONL

The output shows testRunId (for test commands) and launchId (for launch commands):
  jdt test status <testRunId> -f          test pass/fail details
  jdt launch logs <launchId>              console output (stdout, stderr, stack traces)
  jdt launch logs <launchId> --tail 50    last 50 lines of console`;
