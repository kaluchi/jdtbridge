import { get } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { formatTestResults } from "../format/test-results.mjs";

export async function test(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  let url = "/test?";
  const fqn = pos[0];
  if (fqn) {
    url += `class=${encodeURIComponent(fqn)}`;
    const method = pos[1];
    if (method) url += `&method=${encodeURIComponent(method)}`;
  } else if (flags.project) {
    url += `project=${encodeURIComponent(flags.project)}`;
    if (flags.package)
      url += `&package=${encodeURIComponent(flags.package)}`;
  } else {
    console.error(
      "Usage: test <FQN> [method] | test --project <name> [--package <pkg>]",
    );
    process.exit(1);
  }
  if (flags.timeout) url += `&timeout=${flags.timeout}`;
  if (args.includes("--no-refresh")) url += "&no-refresh";
  const result = await get(url, 300_000);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  formatTestResults(result);
}

export const help = `Run JUnit tests via Eclipse's built-in test runner.

Usage:  jdt test <FQN> [method]
        jdt test --project <name> [--package <pkg>]

Flags:
  --project <name>   run all tests in a project
  --package <pkg>    narrow to a specific package (with --project)
  --timeout <sec>    test run timeout in seconds (default: 120)

Examples:
  jdt test app.m8ws.utils.ObjectMapperTest
  jdt test app.m8ws.utils.ObjectMapperTest testSerialize
  jdt test --project m8-server`;
