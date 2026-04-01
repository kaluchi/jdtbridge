import { get, getStream } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { output } from "../output.mjs";
import { printJson } from "../json-output.mjs";
import { formatTable } from "../format/table.mjs";

export async function launchList(args = []) {
  const data = await get("/launch/list");

  output(args, data, {
    empty: "(no launches)",
    text(data) {
      const rows = data.map((r) => {
        const status = r.terminated
          ? `terminated${r.exitCode !== undefined ? ` (${r.exitCode})` : ""}`
          : "running";
        return [r.name, r.type, r.mode, status, r.pid ? `${r.pid}` : ""];
      });
      console.log(formatTable(["NAME", "TYPE", "MODE", "STATUS", "PID"], rows));
    },
  });
}

export async function launchConfigs(args = []) {
  const data = await get("/launch/configs");

  output(args, data, {
    empty: "(no launch configurations)",
    text(data) {
      const rows = data.map((r) => [
        r.name,
        r.type,
        r.project || "",
        configTarget(r),
      ]);
      console.log(
        formatTable(["NAME", "TYPE", "PROJECT", "TARGET"], rows),
      );
    },
  });
}

function configTarget(r) {
  if (r.class) {
    let t = r.class;
    if (r.method) t += "#" + r.method;
    return t;
  }
  if (r.mainClass) return r.mainClass;
  if (r.goals) return r.goals;
  return "";
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

export async function launchConfig(args) {
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: launch config <name> [--xml] [--json]");
    process.exit(1);
  }
  const xml = args.includes("--xml");
  let url = `/launch/config?name=${encodeURIComponent(name)}`;
  if (xml) url += "&format=xml";
  const data = await get(url);
  if (data.error) {
    console.error(data.error);
    return;
  }
  if (xml) {
    console.log(data.xml);
  } else {
    printJson(data);
  }
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

// ---- Help strings ----

export const launchListHelp = `List all launches (running and terminated).

Usage:  jdt launch list [--json]

Options:
  --json    output as JSON

Examples:
  jdt launch list
  jdt launch list --json`;

export const launchConfigsHelp = `List saved launch configurations.

Usage:  jdt launch configs [--json]

Shows NAME, TYPE, PROJECT, and TARGET (class, method, main class, or goals).

Options:
  --json    output as JSON (includes runner for test configs)

Examples:
  jdt launch configs
  jdt launch configs --json`;

export const launchConfigHelp = `Show details of a launch configuration.

Usage:  jdt launch config <name> [--xml]

By default, outputs JSON with all configuration attributes.
With --xml, outputs the raw .launch XML file content.

Options:
  --xml     output raw .launch XML instead of JSON

Examples:
  jdt launch config my-server
  jdt launch config my-server --xml
  jdt launch config ObjectMapperTest | jq .attributes`;

export const launchRunHelp = `Launch a saved configuration (non-blocking).

Usage:  jdt launch run <config-name> [-f] [-q]

Without -f, launches and prints a guide with available commands.
With -f, launches and streams console output until the process terminates.

Flags:
  -f, --follow   stream output (Ctrl+C to detach, process keeps running)
  -q, --quiet    suppress onboarding guide

Examples:
  jdt launch run my-server                run + show guide
  jdt launch run my-server -q             run silently
  jdt launch run jdtbridge-verify -f      run + stream all output
  jdt launch run my-server -f | tail -20  run + wait + bounded output`;

export const launchDebugHelp = `Launch a configuration in debug mode.

Usage:  jdt launch debug <config-name> [-f] [-q]

Same as 'launch run' but attaches the debugger.

Examples:
  jdt launch debug my-server
  jdt launch debug my-server -f`;

export const launchStopHelp = `Stop a running launch.

Usage:  jdt launch stop <name>

Example:  jdt launch stop my-server`;

export const launchClearHelp = `Remove terminated launches from the list.

Usage:  jdt launch clear [name]

Without name: removes all terminated launches.
With name: removes only that specific terminated launch.`;

export const launchLogsHelp = `Show console output of a launch.

Usage:  jdt launch logs <name> [-f|--follow] [--tail N]
                                [--stdout] [--stderr]

Without -f, returns a snapshot of current output.
With -f, streams output until the process terminates.

Flags:
  -f, --follow     stream live output (blocks until exit)
  --tail <N>       last N lines only
  --stdout         stdout stream only
  --stderr         stderr stream only

Examples:
  jdt launch logs my-server
  jdt launch logs my-server --tail 50
  jdt launch logs my-server -f
  jdt launch logs my-server -f | tail -20`;

export const launchConsoleHelp = launchLogsHelp;
