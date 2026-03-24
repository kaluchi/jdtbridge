import { get, getStream } from "../client.mjs";
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

export async function launchRun(args) {
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: launch run <config-name> [--debug] [-f|--follow]");
    process.exit(1);
  }
  let url = `/launch/run?name=${encodeURIComponent(name)}`;
  if (args.includes("--debug")) url += "&debug";
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (!follow) {
    console.log(`Launched ${result.name} (${result.mode})`);
    return;
  }

  // Launch + follow: stream console output, exit with process code
  console.error(`Launched ${result.name} (${result.mode})`);
  const exitCode = await followConsole(result.name, args);
  process.exit(exitCode);
}

export async function launchStop(args) {
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: launch stop <name>");
    process.exit(1);
  }
  const url = `/launch/stop?name=${encodeURIComponent(name)}`;
  const result = await get(url);
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  console.log(`Stopped ${result.name}`);
}

export async function launchConsole(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const name = pos[0];
  if (!name) {
    console.error(
      "Usage: launch console <name> [-f|--follow] [--tail N] [--stderr|--stdout]",
    );
    process.exit(1);
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    const exitCode = await followConsole(name, args);
    process.exit(exitCode);
  }

  // Snapshot mode (existing behavior)
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

/**
 * Stream console output until process terminates or Ctrl+C.
 * Returns the process exit code (0 on detach).
 */
async function followConsole(name, args) {
  const flags = parseFlags(args);
  let url = `/launch/console/stream?name=${encodeURIComponent(name)}`;
  if (flags.tail !== undefined && flags.tail !== true)
    url += `&tail=${flags.tail}`;
  if (args.includes("--stderr")) url += "&stream=stderr";
  else if (args.includes("--stdout")) url += "&stream=stdout";

  // Ctrl+C = detach, not kill
  let detached = false;
  const onSigint = () => {
    detached = true;
    process.stdout.write("\n");
    process.exit(0);
  };
  process.on("SIGINT", onSigint);

  try {
    await getStream(url, process.stdout);
  } catch (e) {
    if (!detached) {
      console.error(e.message);
      return 1;
    }
    return 0;
  } finally {
    process.removeListener("SIGINT", onSigint);
  }

  // Stream ended (process terminated) — fetch exit code
  try {
    const info = await get(
      `/launch/console?name=${encodeURIComponent(name)}`,
    );
    if (info && info.terminated) {
      const list = await get("/launch/list");
      const entry = Array.isArray(list)
        ? list.find((l) => l.name === name && l.terminated)
        : null;
      return entry?.exitCode ?? 0;
    }
  } catch {
    // best effort
  }
  return 0;
}

export const launchRunHelp = `Launch a saved configuration.

Usage:  jdt launch run <config-name> [--debug] [-f|--follow]

Flags:
  --debug        launch in debug mode (default: run)
  -f, --follow   stream console output until process terminates

Examples:
  jdt launch run m8-server
  jdt launch run jdtbridge-verify --follow
  jdt launch run m8-server --debug -f | grep ERROR`;

export const launchStopHelp = `Stop a running launch.

Usage:  jdt launch stop <name>

Example:  jdt launch stop m8-server`;

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

Without --follow, returns the full output as a snapshot.
With --follow, streams output in real-time until the process terminates.

Usage:  jdt launch console <name> [-f|--follow] [--tail N] [--stderr] [--stdout]

Flags:
  -f, --follow   stream output until process terminates (Ctrl+C to detach)
  --tail <N>     last N lines only (snapshot), or start N lines back (follow)
  --stderr       stderr only
  --stdout       stdout only

Examples:
  jdt launch console m8-server
  jdt launch console m8-server --follow
  jdt launch console m8-server -f --tail 20
  jdt launch console m8-server -f --stdout | grep ERROR`;
