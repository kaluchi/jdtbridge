import { get } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";

export async function launchList() {
  const results = await get("/launch/list");
  if (results.error) {
    console.error(results.error);
    process.exit(1);
  }
  if (results.length === 0) {
    console.log("(no launches)");
    return;
  }
  for (const r of results) {
    const status = r.terminated
      ? `terminated${r.exitCode !== undefined ? ` (${r.exitCode})` : ""}`
      : "running";
    const parts = [r.name, r.type, r.mode, status];
    if (r.pid) parts.push(`pid:${r.pid}`);
    console.log(parts.join("  "));
  }
}

export async function launchConfigs() {
  const results = await get("/launch/configs");
  if (results.error) {
    console.error(results.error);
    process.exit(1);
  }
  if (results.length === 0) {
    console.log("(no launch configurations)");
    return;
  }
  for (const r of results) {
    console.log(`${r.name}  ${r.type}`);
  }
}

export async function launchClear(args) {
  const pos = extractPositional(args);
  let url = "/launch/clear";
  if (pos[0]) url += `?name=${encodeURIComponent(pos[0])}`;
  const result = await get(url);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  console.log(`Removed ${result.removed} terminated launch${result.removed !== 1 ? "es" : ""}`);
}

export async function launchConsole(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: launch console <name> [--tail N] [--stderr|--stdout]");
    process.exit(1);
  }
  let url = `/launch/console?name=${encodeURIComponent(name)}`;
  if (flags.tail !== undefined && flags.tail !== true)
    url += `&tail=${flags.tail}`;
  if (args.includes("--stderr")) url += "&stream=stderr";
  else if (args.includes("--stdout")) url += "&stream=stdout";
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  if (result.output) {
    const text = result.output.endsWith("\n")
      ? result.output.slice(0, -1)
      : result.output;
    console.log(text);
  }
}

export const launchConfigsHelp = `List saved launch configurations (Run → Run Configurations).

Usage:  jdt launch configs

Output: configuration name and type, one per line.`;

export const launchClearHelp = `Remove terminated launches and their console output.

Usage:  jdt launch clear [name]

Without name, removes all terminated launches. With name, removes only that one.`;

export const launchListHelp = `List all launches (running and terminated).

Usage:  jdt launch list

Output: name, type, mode, status — one launch per line.`;

export const launchConsoleHelp = `Show console output (stdout/stderr) of a launch.

Usage:  jdt launch console <name> [--tail N] [--stderr] [--stdout]

Flags:
  --tail <N>    last N lines only
  --stderr      stderr only
  --stdout      stdout only

Examples:
  jdt launch console m8-server
  jdt launch console m8-server --tail 50
  jdt launch console ObjectMapperTest --stderr`;
