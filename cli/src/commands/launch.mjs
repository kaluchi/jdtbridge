import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { get, post, getStream } from "../client.mjs";
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
        const status = r.terminated ? "terminated" : "running";
        const exitCode = r.exitCode !== undefined ? String(r.exitCode) : "";
        return [r.launchId, r.configId, r.configType, r.mode, r.pid || "", status, exitCode];
      });
      console.log(formatTable(["LAUNCHID", "CONFIGID", "CONFIGTYPE", "MODE", "PID", "STATUS", "EXITCODE"], rows));
    },
  });
}

export async function launchConfigs(args = []) {
  const data = await get("/launch/configs");

  output(args, data, {
    empty: "(no launch configurations)",
    text(data) {
      const rows = data.map((r) => [
        r.configId,
        r.configType,
        r.project || "",
        configTarget(r),
      ]);
      console.log(
        formatTable(["CONFIGID", "CONFIGTYPE", "PROJECT", "TARGET"], rows),
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
  if (r.package) return r.package;
  if (r.mainClass) return r.mainClass;
  if (r.goals) return r.goals;
  if (r.provider) {
    let t = `jdt agent run ${r.provider}`;
    if (r.agent) t += ` ${r.agent}`;
    if (r.agentArgs) t += ` -- ${r.agentArgs}`;
    return t;
  }
  return "";
}

export async function launchImport(args) {
  const flags = parseFlags(args);
  const positional = extractPositional(args);
  const launchFilePath = positional[0];
  if (!launchFilePath) {
    console.error("Usage: jdt launch config --import <path> [--configid <name>]");
    process.exit(1);
  }

  let launchFileContent;
  try {
    launchFileContent = readFileSync(launchFilePath, "utf8");
  } catch (readError) {
    console.error(`Cannot read file: ${launchFilePath}`);
    process.exit(1);
  }

  const configId = flags.configid
    || basename(launchFilePath, ".launch");

  const importResult = await post(
    `/launch/import?configId=${encodeURIComponent(configId)}`,
    launchFileContent,
  );
  if (importResult.error) {
    console.error(importResult.error);
    process.exit(1);
  }
  console.log(`Imported: ${importResult.configId}`);
}

export async function launchConfigDelete(args) {
  const pos = extractPositional(args);
  const configId = pos[0];
  if (!configId) {
    console.error("Usage: launch config delete <configId>");
    process.exit(1);
  }
  const data = await get(
    `/launch/config/delete?configId=${encodeURIComponent(configId)}`);
  if (data.error) {
    console.error(data.error);
    return;
  }
  console.log(`Deleted ${data.configId}`);
}

export async function launchClear(args) {
  const pos = extractPositional(args);
  let url = "/launch/clear";
  if (pos[0]) url += `?launchId=${encodeURIComponent(pos[0])}`;
  const result = await get(url);
  if (result.error) {
    console.error(result.error);
    return;
  }
  console.log(`Removed ${result.removed} terminated launch${result.removed !== 1 ? "es" : ""}`);
}

export async function launchConfig(args) {
  if (args.includes("--delete")) {
    return launchConfigDelete(
      args.filter((a) => a !== "--delete"));
  }
  if (args.includes("--import")) {
    return launchImport(
      args.filter((a) => a !== "--import"));
  }
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: launch config <configId> [--xml] [--json]");
    process.exit(1);
  }
  const xml = args.includes("--xml");
  const json = args.includes("--json");
  let url = `/launch/config?configId=${encodeURIComponent(name)}`;
  if (xml) url += "&format=xml";
  const data = await get(url);
  if (data.error) {
    console.error(data.error);
    return;
  }
  if (xml) {
    console.log(data.xml);
  } else if (json) {
    printJson(data);
  } else {
    printConfigDetail(data);
  }
}

function configTargetFromAttrs(attrs) {
  const mainType = attrs["org.eclipse.jdt.launching.MAIN_TYPE"] || "";
  const method = attrs["org.eclipse.jdt.junit.TESTNAME"] || "";
  const goals = attrs["M2_GOALS"] || "";
  if (mainType) return method ? `${mainType}#${method}` : mainType;
  const container = attrs["org.eclipse.jdt.junit.CONTAINER"] || "";
  const lt = container.lastIndexOf("<");
  if (lt >= 0) return container.substring(lt + 1);
  if (goals) return goals;
  const provider = attrs["io.github.kaluchi.jdtbridge.ui.provider"] || "";
  const agent = attrs["io.github.kaluchi.jdtbridge.ui.agent"] || "";
  if (provider) {
    let t = `jdt agent run ${provider}`;
    if (agent) t += ` ${agent}`;
    const agentArgs = attrs["io.github.kaluchi.jdtbridge.ui.agentArgs"] || "";
    if (agentArgs) t += ` -- ${agentArgs}`;
    return t;
  }
  return "";
}

function printConfigDetail(data) {
  const a = data.attributes || {};
  const target = configTargetFromAttrs(a);
  const project = a["org.eclipse.jdt.launching.PROJECT_ATTR"] || "";

  // Header
  const rows = [
    ["ConfigId", data.configId],
    ["ConfigType", data.configType],
  ];
  if (project) rows.push(["Project", project]);
  if (target) rows.push(["Target", target]);
  if (data.file) rows.push(["File", data.file]);

  // Attributes (skip already-shown and noisy keys)
  const skip = new Set([
    "org.eclipse.jdt.launching.MAIN_TYPE",
    "org.eclipse.jdt.launching.PROJECT_ATTR",
    "org.eclipse.jdt.junit.TESTNAME",
    "M2_GOALS",
    "org.eclipse.debug.core.MAPPED_RESOURCE_PATHS",
    "org.eclipse.debug.core.MAPPED_RESOURCE_TYPES",
  ]);
  const attrRows = [];
  for (const [key, val] of Object.entries(a)) {
    if (skip.has(key)) continue;
    const display =
      Array.isArray(val) ? val.join(", ") :
      typeof val === "object" ? JSON.stringify(val) :
      String(val);
    attrRows.push([key, display]);
  }

  console.log(formatTable(["KEY", "VALUE"], [...rows, ...attrRows]));
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

  let url = `/launch/run?configId=${encodeURIComponent(name)}`;
  if (mode === "debug") url += "&debug";
  const result = await get(url, 30_000);
  if (result.error) {
    console.error(result.error);
    return;
  }

  const follow = args.includes("-f") || args.includes("--follow");
  const launchId = result.launchId;

  if (follow) {
    console.error(formatLaunched(result));
    const exitCode = await followLogs(launchId, args);
    process.exit(exitCode);
  }

  const quiet = args.includes("-q") || args.includes("--quiet");
  console.log(formatLaunched(result));

  if (!quiet) {
    console.log(launchGuide(result));
  }
}

function formatLaunched(result) {
  const configId = result.configId;
  const launchId = result.launchId;
  const parts = [`Launched ${configId} (${result.mode})`];
  if (result.configType) parts[0] += ` [${result.configType}]`;
  if (result.pid) parts.push(`  PID:        ${result.pid}`);
  if (launchId !== configId) parts.push(`  LaunchId:   ${launchId}`);
  if (result.workingDir) parts.push(`  Working dir: ${result.workingDir}`);
  if (result.cmdline) {
    parts.push(`  Command:    ${result.cmdline}`);
  }
  return parts.join("\n");
}

function launchGuide(result) {
  const launchId = result.launchId;
  const configId = result.configId;
  return `
  Console output is captured by Eclipse and remains available
  after the process terminates. You can read it at any time,
  filter with grep, or pipe through tail/head.

  This launch (launchId = ${launchId}):
    jdt launch logs ${launchId}
    jdt launch logs ${launchId} --tail 30
    jdt launch logs ${launchId} -f | tail -20
    jdt launch stop ${launchId}

  This config (configId = ${configId}):
    jdt launch config ${configId}
    jdt launch run ${configId}
    jdt launch run ${configId} -f

  Dashboard:
    jdt launch list
    jdt launch configs
    jdt launch clear

  Add -q to suppress this guide.`;
}

export async function launchStop(args) {
  const pos = extractPositional(args);
  const name = pos[0];
  if (!name) {
    console.error("Usage: launch stop <launchId>");
    process.exit(1);
  }
  const url = `/launch/stop?launchId=${encodeURIComponent(name)}`;
  const result = await get(url);
  if (result.error) {
    console.error(result.error);
    return;
  }
  console.log(`Stopped ${result.configId}`);
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
      "Usage: launch logs <launchId> [-f|--follow] [--tail N]",
    );
    process.exit(1);
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    const exitCode = await followLogs(name, args);
    process.exit(exitCode);
  }

  // Snapshot mode
  let url = `/launch/console?launchId=${encodeURIComponent(name)}`;
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
  let url = `/launch/console/stream?launchId=${encodeURIComponent(name)}`;
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
      ? list.find((l) => l.launchId === name && l.terminated)
      : null;
    return entry?.exitCode ?? 0;
  } catch {
    return 0;
  }
}

// ---- Help strings ----

export const launchListHelp = `List all launches (running and terminated).

Usage:  jdt launch list [--json]

Shows LAUNCHID, CONFIGID, CONFIGTYPE, MODE, PID, STATUS, EXITCODE.
Use LAUNCHID with launch logs/stop. Use CONFIGID with launch run/config.

Options:
  --json    output as JSON

Examples:
  jdt launch list
  jdt launch list --json`;

export const launchConfigsHelp = `List saved launch configurations.

Usage:  jdt launch configs [--json]

Shows CONFIGID, CONFIGTYPE, PROJECT, and TARGET (class, method, main class, or goals).
Use CONFIGID with launch run and launch config.

Options:
  --json    output as JSON (includes runner for test configs)

Examples:
  jdt launch configs
  jdt launch configs --json`;

export const launchConfigHelp = `Show, import, or delete a launch configuration.

Usage:
  jdt launch config <configId> [--xml] [--json]       show details
  jdt launch config --import <path> [--configid <n>]  import .launch file
  jdt launch config --delete <configId>               delete configuration

Show options:
  --xml     output raw .launch XML instead of table
  --json    output raw JSON with typed attribute values

Import options:
  --configid <name>  override config name (default: filename)

Fails if --import target already exists. Use --delete first or
--configid to import with a different name.

Examples:
  jdt launch config my-server
  jdt launch config my-server --xml
  jdt launch config --import /path/to/my-build.launch
  jdt launch config --import my.launch --configid custom-name
  jdt launch config --delete old-config`;

export const launchRunHelp = `Launch a saved configuration (non-blocking).

Usage:  jdt launch run <configId> [-f] [-q]

Without -f, launches and prints a guide with the launchId for logs/stop.
With -f, launches and streams console output until the process terminates.

Flags:
  -f, --follow   stream output (Ctrl+C to detach, process keeps running)
  -q, --quiet    suppress onboarding guide

Examples:
  jdt launch run my-server                run + show guide with launchId
  jdt launch run my-server -q             run silently
  jdt launch run jdtbridge-verify -f      run + stream all output
  jdt launch run my-server -f | tail -20  run + wait + bounded output`;

export const launchDebugHelp = `Launch a configuration in debug mode.

Usage:  jdt launch debug <configId> [-f] [-q]

Same as 'launch run' but attaches the debugger.

Examples:
  jdt launch debug my-server
  jdt launch debug my-server -f`;

export const launchStopHelp = `Stop a running launch.

Usage:  jdt launch stop <launchId>

LaunchId is configId:pid (e.g. my-server:12345).
Plain configId also works when there is no ambiguity.

Examples:
  jdt launch stop my-server:12345
  jdt launch stop my-server`;

export const launchClearHelp = `Remove terminated launches from the list.

Usage:  jdt launch clear [launchId]

Without argument: removes all terminated launches.
With launchId: removes only that specific terminated launch.`;

export const launchLogsHelp = `Show console output of a launch.

Usage:  jdt launch logs <launchId> [-f|--follow] [--tail N]
                                   [--stdout] [--stderr]

LaunchId is configId:pid (e.g. my-server:12345).
Plain configId also works when there is no ambiguity.

Without -f, returns a snapshot of current output.
With -f, streams output until the process terminates.

Flags:
  -f, --follow     stream live output (blocks until exit)
  --tail <N>       last N lines only
  --stdout         stdout stream only
  --stderr         stderr stream only

Examples:
  jdt launch logs my-server:12345
  jdt launch logs my-server --tail 50
  jdt launch logs my-server -f
  jdt launch logs my-server -f | tail -20`;

export const launchConsoleHelp = launchLogsHelp;
