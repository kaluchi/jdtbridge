// `jdt coverage *` subcommand dispatcher and implementations.
// Server contract: bridge-coverage-spec.md.

import { get, post } from "../client.mjs";
import { extractPositional, parseFlags } from "../args.mjs";
import { output } from "../output.mjs";
import { preflightCompileErrors } from "../preflight-compile-errors.mjs";
import { renderRunsTable } from "../format/coverage-runs.mjs";
import {
  analyzeNextStepsTail,
  formatRunHeader,
  formatStatusSnapshot,
  followCoverageStream,
  runGuide,
} from "../format/coverage-status.mjs";

// -- run --------------------------------------------------------------

export async function coverageRun(args) {
  const filtered = args.filter((a) => a !== "-f" && a !== "-q");
  const pos = extractPositional(filtered);
  const flags = parseFlags(args);

  const configId = pos[0];
  if (!configId) {
    console.error("Usage: jdt coverage run <configId> [-f] [-q] [--json]");
    process.exit(1);
  }

  let url = `/coverage/run?configId=${encodeURIComponent(configId)}`;
  if (flags.args) url += `&args=${encodeURIComponent(flags.args)}`;

  const jsonFlag = args.includes("--json");
  const cleared = await preflightCompileErrors(args, { json: jsonFlag });
  if (!cleared) process.exit(1);

  const result = await get(url, 30_000);
  if (result.error) {
    if (jsonFlag) console.log(JSON.stringify(result));
    else console.error(JSON.stringify(result));
    process.exit(1);
  }

  if (jsonFlag) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log(formatRunHeader(result));
  }

  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    if (!jsonFlag) console.log();
    const exit = await followCoverageStream(result.coverageId, args);
    process.exit(exit);
  }

  const quiet = args.includes("-q") || args.includes("--quiet");
  if (!quiet && !jsonFlag) {
    console.log(runGuide(result.coverageId, result.launchId || result.configId));
  }
}

export const coverageRunHelp = `Launch a coverage run (non-blocking).

Usage:  jdt coverage run <configId> [-f] [-q] [--json]

Flags:
  -f, --follow              stream state events until analysis terminates
  -q, --quiet               suppress onboarding guide
  --args <text>             extra arguments appended to launch config
  --json                    JSON snapshot output
  --ignore-compile-errors   launch despite workspace compile errors

Examples:
  jdt coverage run MyTest
  jdt coverage run MyTest -f
  jdt coverage run my-server --args "--port 8080"`;

// -- runs -------------------------------------------------------------

export async function coverageRuns(args) {
  const data = await get("/coverage/runs");
  output(args, data, {
    empty: "(no coverage sessions)",
    text: renderRunsTable,
  });
}

export const coverageRunsHelp = `List all coverage sessions.

Usage:  jdt coverage runs [--json]

Mirrors Eclipse's session-selection dropdown in the Coverage View.
Active session marked with *.`;

// -- status -----------------------------------------------------------

export async function coverageStatus(args) {
  const pos = extractPositional(args.filter((a) => a !== "-f"));
  const coverageId = pos[0];
  if (!coverageId) {
    console.error("Usage: jdt coverage status <coverageId> [-f] [--json]");
    process.exit(1);
  }
  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    const exit = await followCoverageStream(coverageId, args);
    process.exit(exit);
  }
  const url = `/coverage/session?coverageId=${encodeURIComponent(coverageId)}`;
  const data = await get(url, 30_000);
  const jsonFlag = args.includes("--json");
  const quiet = args.includes("-q") || args.includes("--quiet");
  output(args, data, {
    text(d) {
      console.log(formatStatusSnapshot(d));
      // M5: same 3-line analyze-next-steps tail as the -f stream
      // path, surfaced once analysis is ready. Skipped under
      // --json (machine output) and -q.
      if (!jsonFlag && !quiet && d?.analysisReady === true) {
        console.log(analyzeNextStepsTail());
      }
    },
  });
}

export const coverageStatusHelp = `Show snapshot or stream of one coverage session.

Usage:  jdt coverage status <coverageId> [-f] [--json]

Without -f: snapshot of current state including counters.
With -f: streams transition events until terminal state.

Counters shown only when analysisReady == true. coverageId may carry
:N dump suffix to address a specific dump within a live run.`;

// -- dump -------------------------------------------------------------

export async function coverageDump(args) {
  const pos = extractPositional(args);
  const coverageId = pos[0];
  if (!coverageId) {
    console.error("Usage: jdt coverage dump <coverageId> [--reset] [--json]");
    process.exit(1);
  }
  const reset = args.includes("--reset");
  const data = await post(
    "/coverage/dump",
    JSON.stringify({ coverageId, reset }),
    "application/json",
  );
  output(args, data, {
    mutating: true,
    text() {
      console.log(`Dumped ${coverageId}${reset ? " (reset)" : ""}`);
    },
  });
}

export const coverageDumpHelp = `Request a dump from the running JaCoCo agent.

Usage:  jdt coverage dump <coverageId> [--reset] [--json]

Mirrors Coverage View toolbar → Dump Execution Data button.

Flags:
  --reset    clear agent probes after this dump (next dump cumulates
             only what runs after the reset)`;

// -- refresh ----------------------------------------------------------

export async function coverageRefresh(args) {
  const data = await post("/coverage/refresh", "{}", "application/json");
  output(args, data, {
    mutating: true,
    text(d) {
      console.log(`Refreshed ${d.activeCoverageId}`);
    },
  });
}

export const coverageRefreshHelp = `Re-analyze the active coverage session.

Usage:  jdt coverage refresh [--json]

Mirrors Coverage View popup → Refresh (F5).`;

// -- relaunch ---------------------------------------------------------

export async function coverageRelaunch(args) {
  const data = await post("/coverage/relaunch", "{}", "application/json");
  const jsonFlag = args.includes("--json");
  if (data.error) {
    if (jsonFlag) console.log(JSON.stringify(data));
    else console.error(JSON.stringify(data));
    process.exit(1);
  }
  if (jsonFlag) {
    console.log(JSON.stringify(data, null, 2));
    return;
  }
  console.log(formatRunHeader(data));
  const follow = args.includes("-f") || args.includes("--follow");
  if (follow) {
    console.log();
    const exit = await followCoverageStream(data.coverageId, args);
    process.exit(exit);
  }
}

export const coverageRelaunchHelp = `Re-launch the active session's source config.

Usage:  jdt coverage relaunch [-f] [-q] [--json]

Mirrors Coverage View toolbar → Relaunch Session.`;

// -- active / activate ------------------------------------------------

export async function coverageActive(args) {
  const data = await get("/coverage/active");
  // Read-only command — must exit 0 even on error
  // (feedback_jdt_read_only_exit_zero.md). output() routes errors
  // to stderr/stdout for us; no process.exit on the text path.
  output(args, data, {
    text(d) {
      console.log(d.activeCoverageId == null
          ? "none" : d.activeCoverageId);
    },
  });
}

export const coverageActiveHelp = `Show the active coverage session ID.

Usage:  jdt coverage active [--json]

Returns "none" when no session is active.`;

export async function coverageActivate(args) {
  const pos = extractPositional(args);
  const coverageId = pos[0];
  if (!coverageId) {
    console.error("Usage: jdt coverage activate <coverageId> [--json]");
    process.exit(1);
  }
  const data = await post(
    "/coverage/activate",
    JSON.stringify({ coverageId }),
    "application/json",
  );
  output(args, data, {
    mutating: true,
    text(d) {
      console.log(`Activated ${d.activeCoverageId}`);
      if (d.previousActiveCoverageId) {
        console.log(`Previous: ${d.previousActiveCoverageId}`);
      }
    },
  });
}

export const coverageActivateHelp = `Make a session the active one.

Usage:  jdt coverage activate <coverageId> [--json]`;

// -- merge ------------------------------------------------------------

export async function coverageMerge(args) {
  const flags = parseFlags(args);
  const pos = extractPositional(args);
  if (pos.length < 2) {
    console.error("Usage: jdt coverage merge <coverageId> <coverageId> [...]"
      + " [--name <description>] [--json]");
    process.exit(1);
  }
  const body = { coverageIds: pos };
  if (flags.name) body.description = flags.name;
  const data = await post(
    "/coverage/merge",
    JSON.stringify(body),
    "application/json",
  );
  output(args, data, {
    mutating: true,
    text(d) {
      const consumed = d.consumedCoverageIds || [];
      console.log(`#### Merged ${consumed.length} sessions → ${d.mergedCoverageId}`);
      if (consumed.length) {
        console.log("\nConsumed:");
        for (const id of consumed) console.log(`  ${id}  removed`);
      }
      if (d.active) {
        console.log(`\nActive session: ${d.mergedCoverageId}`);
        console.log(`  jdt coverage status ${d.mergedCoverageId} -f`);
      }
    },
  });
}

export const coverageMergeHelp = `Merge two or more coverage sessions.

Usage:  jdt coverage merge <coverageId> <coverageId> [...] [--name <text>] [--json]

Mirrors Coverage View toolbar → Merge Sessions… When --name is omitted
the bridge supplies Eclipse's default ('Merged ({date} {time})').
Inputs become unresolvable after merge.`;

// -- remove -----------------------------------------------------------

export async function coverageRemove(args) {
  const all = args.includes("--all");
  const data = await post(
    "/coverage/remove",
    JSON.stringify(all ? { all: true } : {}),
    "application/json",
  );
  output(args, data, {
    mutating: true,
    text(d) {
      const removed = d.removedCoverageIds || [];
      console.log(`Removed ${removed.length} coverage session${removed.length === 1 ? "" : "s"}`);
    },
  });
}

export const coverageRemoveHelp = `Remove coverage sessions.

Usage:  jdt coverage remove [--all] [--json]

Without --all: removes the active session.
With --all: removes every tracked session.`;

// -- stop -------------------------------------------------------------

export async function coverageStop(args) {
  const pos = extractPositional(args);
  const coverageId = pos[0];
  if (!coverageId) {
    console.error("Usage: jdt coverage stop <coverageId>");
    process.exit(1);
  }
  // Resolve coverageId → launchId via /coverage/runs, then delegate.
  const runs = await get("/coverage/runs");
  if (runs.error) {
    console.error(JSON.stringify(runs));
    process.exit(1);
  }
  const run = runs.find((r) => r.coverageId === coverageId);
  if (!run) {
    console.error(`coverage-not-found: ${coverageId}`);
    process.exit(1);
  }
  if (run.coverageSessionKind !== "live") {
    console.error(`coverage-launch-not-live: ${coverageId}`);
    process.exit(1);
  }
  if (run.terminated) {
    console.error(`coverage-launch-terminated: ${coverageId}`);
    process.exit(1);
  }
  if (!run.launchId) {
    console.error(`coverage-launch-not-live: no launchId for ${coverageId}`);
    process.exit(1);
  }
  const data = await get(`/launch/stop?launchId=${encodeURIComponent(run.launchId)}`);
  if (data.error) {
    console.error(`coverage-launch-failed: ${data.error}`);
    process.exit(1);
  }
  console.log(`Stopped ${coverageId}`);
}

export const coverageStopHelp = `Terminate a running coverage launch.

Usage:  jdt coverage stop <coverageId>

Resolves coverageId to launchId (via /coverage/runs) and delegates to
jdt launch stop. Errors with coverage-launch-not-live for merged or
imported sessions.`;

