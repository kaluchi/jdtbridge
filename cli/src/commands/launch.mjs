import { get, getStream } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { formatTable } from "../format/table.mjs";

export async function launchList() {
  const results = await get("/launch/list");
  if (results.error) {
    console.error(results.error);
    return;
  }
  if (results.length === 0) {
    console.log("(no launches)");
    return;
  }
  const rows = results.map((r) => {
    const status = r.terminated
      ? `terminated${r.exitCode !== undefined ? ` (${r.exitCode})` : ""}`
      : "running";
    return [r.name, r.type, r.mode, status, r.pid ? `${r.pid}` : ""];
  });
  const headers = ["NAME", "TYPE", "MODE", "STATUS", "PID"];
  console.log(formatTable(headers, rows));
}

export async function launchConfigs() {
  const results = await get("/launch/configs");
  if (results.error) {
    console.error(results.error);
    return;
  }
  if (results.length === 0) {
    console.log("(no launch configurations)");
    return;
  }
  const rows = results.map((r) => [r.name, r.type]);
  const headers = ["NAME", "TYPE"];
  console.log(formatTable(headers, rows));
}

export async function launchClear(args) {
  const pos = extractPositional(args);
  let url = "/launch/clear";
  if (pos[0]) url += `?name=${encodeURIComponent(pos[0])}`;
  const result = await get(url);
  if (result.error) {
    console.error(result.error);
    return;
  }
  console.log(`Removed ${result.removed} terminated launch${result.removed !== 1 ? "es" : ""}`);
}

/**
 * Launch a configuration. Without -f, prints onboarding guide.
 * With -f, streams console output until termination.
 */
export async function launchRun(args) {
  return launchWithMode(args, "run");
}

/** Launch in debug mode. Same interface as run. */
export async function launchDebug(args) {
  return launchWithMode(args, "debug");
}

async function launchWithMode(args, mode) {
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error(`Usage: launch ${mode} <config-name> [-f] [-q]`);
    process.exit(1);
  }

  let url = `/launch/run?name=${encodeURIComponent(name)}`;
  if (mode === "debug") url += "&debug";
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    return;
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    console.error(formatLaunched(result));
    const exitCode = await followLogs(result.name, args);
    process.exit(exitCode);
  }

  const quiet = args.includes("-q") || args.includes("--quiet");
  const n = result.name;
  console.log(formatLaunched(result));

  if (!quiet) {
    console.log(launchGuide(n));
  }
}

function formatLaunched(result) {
  const parts = [`Launched ${result.name} (${result.mode})`];
  if (result.type) parts[0] += ` [${result.type}]`;
  if (result.pid) parts.push(`  PID:        ${result.pid}`);
  if (result.workingDir) parts.push(`  Working dir: ${result.workingDir}`);
  if (result.cmdline) {
    parts.push(`  Command:    ${result.cmdline}`);
  }
  return parts.join("\n");
}

function launchGuide(name) {
  return `
  Console output is captured by Eclipse and remains available
  after the process terminates. You can read it at any time,
  filter with grep, or pipe through tail/head.

  View logs:
    jdt launch logs ${name}
    jdt launch logs ${name} --tail 30

  Wait for completion (blocks until process exits):
    jdt launch logs ${name} -f | tail -20
    jdt launch logs ${name} -f

  Manage:
    jdt launch list
    jdt launch stop ${name}
    jdt launch clear

  Run modes:
    jdt launch run <config>           launch (this output)
    jdt launch run <config> -f        launch + stream output
    jdt launch run <config> -f | tail launch + wait + bounded
    jdt launch debug <config>         launch with debugger

  Add -q to suppress this guide.`;
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
    return;
  }
  console.log(`Stopped ${result.name}`);
}

/**
 * Show console output. "logs" is the primary name,
 * "console" is kept as an alias.
 */
export async function launchLogs(args) {
  const pos = extractPositional(args);
  const flags = parseFlags(args);
  const name = pos[0];
  if (!name) {
    console.error(
      "Usage: launch logs <name> [-f|--follow] [--tail N]",
    );
    process.exit(1);
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    const exitCode = await followLogs(name, args);
    process.exit(exitCode);
  }

  // Snapshot mode
  let url = `/launch/console?name=${encodeURIComponent(name)}`;
  if (flags.tail !== undefined && flags.tail !== true)
    url += `&tail=${flags.tail}`;
  if (args.includes("--stderr")) url += "&stream=stderr";
  else if (args.includes("--stdout")) url += "&stream=stdout";
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    return;
  }
  if (result.output) {
    const text = result.output.endsWith("\n")
      ? result.output.slice(0, -1)
      : result.output;
    console.log(text);
  }
}

/** Alias: console → logs */
export const launchConsole = launchLogs;

/**
 * Stream console output until process terminates or Ctrl+C.
 * Returns the process exit code (0 on detach).
 */
async function followLogs(name, args) {
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

  // Stream ended — fetch exit code
  try {
    const list = await get("/launch/list");
    const entry = Array.isArray(list)
      ? list.find((l) => l.name === name && l.terminated)
      : null;
    return entry?.exitCode ?? 0;
  } catch {
    return 0;
  }
}

export const launchRunHelp = `Launch a saved configuration.

Usage:  jdt launch run <config-name> [-f] [-q]

Without -f, launches and prints a guide with available commands.
With -f, launches and streams console output until the process terminates.

Flags:
  -f, --follow   stream output (Ctrl+C to detach, process keeps running)
  -q, --quiet    suppress onboarding guide

Examples:
  jdt launch run m8-server                run + show guide
  jdt launch run m8-server -q             run silently
  jdt launch run jdtbridge-verify -f      run + stream all output
  jdt launch run m8-server -f | tail -20  run + wait + bounded output`;

export const launchDebugHelp = `Launch a configuration in debug mode.

Usage:  jdt launch debug <config-name> [-f] [-q]

Same as "launch run" but attaches the Eclipse debugger.

Examples:
  jdt launch debug m8-server
  jdt launch debug m8-server -f`;

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

export const launchLogsHelp = `Show console output of a launch.

Console output is captured by Eclipse and persists after the process
terminates. Without -f, returns a snapshot. With -f, streams in real-time.

Usage:  jdt launch logs <name> [-f|--follow] [--tail N]

Flags:
  -f, --follow   stream until process terminates (Ctrl+C to detach)
  --tail <N>     last N lines only (snapshot), or start N lines back (follow)

Examples:
  jdt launch logs m8-server                full output snapshot
  jdt launch logs m8-server --tail 30      last 30 lines
  jdt launch logs m8-server -f             stream live
  jdt launch logs m8-server -f | tail -20  wait + bounded output`;

// Keep old name for backward compatibility
export const launchConsoleHelp = launchLogsHelp;
