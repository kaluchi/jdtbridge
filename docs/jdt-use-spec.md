# jdt use — Design Spec

## Overview

`jdt use` manages connections to multiple running Eclipse instances.
Each Eclipse instance owns a unique workspace (enforced by `.metadata/.lock`).
The CLI needs to know which instance to talk to — when only one is
running it's automatic, but with multiple instances the user must choose.

`jdt use` provides identical UX for humans and agents: same command,
same behavior, same resolution.

## Problem

Discovery (`~/.jdtbridge/instances/*.json`) reads all running instances
and picks the first one. With multiple Eclipse instances this is
unreliable — the user has no control over which instance gets selected.

A child process cannot modify the parent's environment variables.
Writing to disk is the only cross-process communication channel
available without shell-specific tricks (`eval`, `source`,
`Invoke-Expression`). Pin files on disk solve this — all processes
in the same terminal tab independently read them.

The same problem exists in other CLI tools. See
[anthropics/claude-code#21731](https://github.com/anthropics/claude-code/issues/21731)
for prior art on per-terminal session affinity. The approach below
uses the same pattern: terminal emulators expose session identifiers
as environment variables, inherited by all child processes.

## Who needs `jdt use`

**Does NOT need it:**
- Docker-sandboxed agents — one Eclipse per container, discovery works
- Agents launched from Eclipse (AgentLaunchDelegate) — session file
  provides `JDT_BRIDGE_PORT/TOKEN` via env vars
- Single Eclipse instance — discovery auto-selects

**Needs it:**
- Human with multiple Eclipse instances, switching between terminal tabs
- Standalone CLI agent (not launched from Eclipse) with multiple
  instances visible — e.g. main Eclipse + headless integration test
  Eclipse, or after Eclipse restart (port changes)

## Commands

### `jdt use`

List known workspaces. Scans `~/.jdtbridge/instances/` for running
instances and auto-adds new ones to `workspaces.json`.

```
$ jdt use
#  ALIAS  WORKSPACE                        STATUS  PINNED  HOST       PORT   PLUGIN
1  jdt    D:\eclipse-workspace-jdtbridge   online  pinned  127.0.0.1  7777   2.5.0
2  web    D:\eclipse-workspace-webapp      online          127.0.0.1  59100  2.5.0
3         C:\Users\John\Projects\legacy    offline

Pin this terminal:  jdt use <N|alias|path>
```

Columns:

| Column | Description |
|---|---|
| `#` | Registry number (position in `workspaces.json`) |
| `ALIAS` | User-defined short name for quick switching |
| `WORKSPACE` | Workspace path as seen by the CLI host |
| `STATUS` | Connection status (see below) |
| `PINNED` | Shows `pinned` for the active workspace in this terminal |
| `HOST` | Server bind address |
| `PORT` | Server port |
| `PLUGIN` | Plugin version on the Eclipse instance |

Status values:

| Status | Meaning |
|---|---|
| `online` | Instance running, port reachable |
| `offline` | In registry but no running instance |
| `remote` | Remote instance (no probe, no PID check) |
| `*new*` | First time seen, just added to registry |

After display, `*new*` entries become regular (status will show as
`online` or `offline` on next invocation).

### `jdt use <N|alias|path>`

Pin current terminal to workspace. Argument resolves in order:

1. Number — position in `workspaces.json`
2. Alias — user-defined short name
3. Path — exact or substring match of workspace path

```bash
$ jdt use 2
Pinned to: D:\eclipse-workspace-webapp (web, port 59100)

$ jdt use web
Pinned to: D:\eclipse-workspace-webapp (web, port 59100)

$ jdt use "eclipse-workspace-webapp"
Pinned to: D:\eclipse-workspace-webapp (web, port 59100)
```

Fails if workspace is offline (no running instance to connect to).
Remote instances always allow pinning (no liveness check).

Numbers are stable across Eclipse restarts but shift when entries
are deleted via `--delete`. Aliases and workspace paths never shift.

### `jdt use <N|alias> --alias <name>`

Set or change alias for a workspace entry.

```bash
$ jdt use 4 --alias exp
Alias set: exp → D:\eclipse-workspace-experiment

$ jdt use exp
Pinned to: D:\eclipse-workspace-experiment (exp, port 58801)
```

Aliases must be unique, alphanumeric + hyphens, no spaces.
`--alias ""` removes an alias.

### `jdt use --delete <N|alias>`

Remove a workspace from the registry. Shifts subsequent numbers.

```bash
$ jdt use --delete 3
Removed: C:\Users\John\Projects\legacy
```

### `jdt use --pins`

Show active pin files for debugging. Compatible with `--json`.

```
$ jdt use --pins
TYPE      ID                    WORKSPACE                       STATUS  PINNED AT
ppid      42512                 D:\eclipse-workspace-jdtbridge  stale   2026-04-04T02:49:02Z
terminal  0679187d-a3ea-4c9...  D:\eclipse-workspace-jdtbridge  active  2026-04-04T02:49:02Z
```

### Remote instances

`jdt use` must be fast — no network probes. It reads instance
files and checks local PID liveness only.

For remote instances (from `~/.jdtbridge/remote-instances/`):
- STATUS = `remote` (no probe, no PID to check)
- To verify connection: `jdt setup remote --check`
- Switching works the same: `jdt use <N>` or `jdt use <alias>`

See [jdt-setup-remote-spec.md](jdt-setup-remote-spec.md) for
remote setup and `--check`.

Example inside a Linux container connected to a remote Eclipse:

```
$ jdt use
#  ALIAS  WORKSPACE   STATUS  PINNED  HOST                    PORT   PLUGIN
1         /workspace  remote  pinned  host.docker.internal    7777   2.5.0
```

Multiple remote Eclipse instances:

```
$ jdt use
#  ALIAS  WORKSPACE        STATUS   PINNED  HOST                    PORT   PLUGIN
1  dev    /mnt/dev         remote   pinned  host.docker.internal    7777   2.5.0
2  stage  /mnt/staging     remote           192.168.1.100           8888   2.4.0
3  prod   /mnt/production  remote           10.0.0.5                9999
```

Switching works the same as local: `jdt use 2` or `jdt use stage`.

### `jdt use --json`

JSON output with live status for scripting. Includes `pinned` field.

```json
[
  {
    "index": 1,
    "alias": "jdt",
    "workspace": "D:\\eclipse-workspace-jdtbridge",
    "status": "online",
    "pinned": true,
    "port": 58800,
    "version": "2.4.0"
  },
  {
    "index": 2,
    "alias": "web",
    "workspace": "D:\\eclipse-workspace-webapp",
    "status": "online",
    "pinned": false,
    "port": 59100,
    "version": "2.4.0"
  }
]
```

## Terminal session identification

Terminal emulators assign unique session identifiers to each tab/pane
and expose them as environment variables. These are inherited by all
child processes — through shell → agent → sandbox bash → jdt CLI.

Empirically verified: `WT_SESSION` is identical across 10 invocations
in different contexts (PostToolUse hooks, agent Bash calls, user `!`
commands, subcommand spawns) within one Windows Terminal tab. Meanwhile
`process.ppid` was different in all 10 — useless for cross-call identity.

### `resolveTerminalId()`

Returns the first non-null value from the priority chain:

```javascript
function resolveTerminalId() {
  const vars = [
    "WT_SESSION",        // Windows Terminal — per tab
    "ITERM_SESSION_ID",  // iTerm2 — per tab/pane
    "TERM_SESSION_ID",   // macOS Terminal.app, VS Code, JetBrains — per session
    "TMUX_PANE",         // tmux — per pane
    "STY",               // GNU screen — per session
    "ConEmuPID",         // ConEmu — per instance
  ];
  for (const v of vars) {
    if (process.env[v]) return process.env[v];
  }
  // Unix: tty device as fallback (skip on Windows — tty command absent)
  if (process.platform !== "win32") {
    try { return execSync("tty", { stdio: ["pipe","pipe","pipe"], encoding: "utf8" }).trim(); }
    catch { /* no tty */ }
  }
  return null;
}
```

If `null`, pin-based resolution is skipped — falls through to discovery.

### Known edge cases

- **WT_SESSION leaks into VS Code.** If VS Code is launched from
  Windows Terminal, it inherits `WT_SESSION`. See
  [microsoft/terminal#13006](https://github.com/microsoft/terminal/issues/13006).
  Acceptable — same tab lineage, same pin.

- **Terminals without session vars** (alacritty, kitty, foot). Fall
  back to tty device on Unix. On Windows these terminals are rare;
  Windows Terminal dominates.

- **Nested shells.** `bash` inside `pwsh` inherits `WT_SESSION` from
  the original tab. Correct behavior — same tab, same pin.

## Pin mechanism

### What `jdt use N` writes

Two pin files, keyed differently:

```
~/.jdtbridge/use/pins/term-<terminalId>.json    ← per terminal tab
~/.jdtbridge/use/pins/ppid-<ppid>.json          ← per process
```

Pin files store **workspace path only**, not port/token:

```json
{
  "workspace": "D:\\eclipse-workspace-webapp",
  "pinnedAt": "2026-04-04T12:00:00Z"
}
```

Port and token are resolved live from instance files on every read.
This avoids stale connection data when Eclipse restarts (port changes).

### Why two keys

Terminal ID covers human and sequential agent use — stable across
bash calls within the same tab.

ppid covers parallel subagents. `ppid` here means the parent PID
of the `jdt` CLI process — i.e. the bash process that runs the `&&`
chain. Within `jdt use 1 && jdt find Foo`, both `jdt` invocations
are children of the same bash (same ppid). Different `&&` chains
(parallel subagents) have different bash PIDs → different ppid pins
→ no race.

| Scenario | Terminal ID | ppid |
|---|---|---|
| Human: `jdt use 2` then `jdt find` | same ✓ | same ✓ |
| Sequential agent: bash1 `jdt use 2`, bash2 `jdt find` | same ✓ | different (dead) |
| Parallel: `jdt use 1 && jdt find` / `jdt use 2 && jdt refs` | same (race) | different ✓ |

### Race analysis (parallel subagents)

Two parallel subagents in the same terminal tab:

```
Subagent A (bash PID 1234):  jdt use 1 && jdt find Foo
Subagent B (bash PID 5678):  jdt use 2 && jdt refs Bar
```

Both write `term-<WT_SESSION>.json` — last writer wins (race).
Both write `ppid-<their-bash-pid>.json` — separate files, no conflict.

Resolution reads ppid pin first. Within each `&&` chain, ppid matches
→ correct workspace. Terminal pin is overwritten (race) but never
consulted (ppid pin has priority).

If subagents do NOT call `jdt use` (main agent pinned earlier):

```
Main agent bash1:  jdt use 1           → term pin = ws1
Subagent A bash2:  jdt find Foo        → ppid pin not found → term pin = ws1 ✓
Subagent B bash3:  jdt refs Bar        → ppid pin not found → term pin = ws1 ✓
```

All subagents inherit the main agent's choice via terminal pin.

## Connection resolution

Every `jdt` command resolves its target Eclipse instance:

```
1. env vars JDT_BRIDGE_PORT/TOKEN?        → use directly
2. ppid pin exists, ppid alive?
   → resolve workspace from instance files → use it
   → ppid dead? delete pin, continue
3. terminal ID pin exists?
   → resolve workspace from instance files → use it
   → workspace offline? delete pin, continue
4. discovery (scan instance files)
   └─ 1 instance                          → use it
   └─ 2+ instances                        → warn, use first
   └─ 0 instances                         → error
```

At steps 2-3, the pin contains a workspace path. Resolution finds
the matching instance file to get live port/token. If no instance
file matches (Eclipse stopped), the pin is stale — deleted and
resolution continues to the next step.

Step 1 covers AgentLaunchDelegate (Eclipse-launched agents) and
Docker-sandboxed agents — they never reach step 2.

Step 2 covers parallel subagents using `jdt use N && jdt cmd`.

Step 3 covers humans and sequential agents after `jdt use N`.

Step 4 is the existing behavior — unchanged for single-instance users.

### Multi-instance warning

When resolution reaches step 4 and finds 2+ instances:

```
⚠ Multiple Eclipse instances found. Using first.
  Run `jdt use` to see all and pin one.
```

Single line to stderr. Does not block the command.

## Workspaces file

`~/.jdtbridge/use/workspaces.json`:

```json
[
  {
    "workspace": "D:\\eclipse-workspace-jdtbridge",
    "alias": "jdt",
    "addedAt": "2026-04-04T12:00:00Z"
  },
  {
    "workspace": "D:\\eclipse-workspace-webapp",
    "alias": "web",
    "addedAt": "2026-04-04T12:05:00Z"
  },
  {
    "workspace": "C:\\Users\\John\\Projects\\legacy",
    "addedAt": "2026-03-15T09:00:00Z"
  }
]
```

Array order = display order = numbering. `alias` is optional.

- New instances discovered during `jdt use` are appended.
- `--delete` removes by index, shifts subsequent numbers.
  Aliases and workspace paths are stable across deletes.
- No "active" field — pinning is per-terminal, not global.

### Workspace path matching

Used in auto-discovery (matching instance files against registry)
and in `jdt use <path>`:

```javascript
function workspacePathsMatch(a, b) {
  const normalize = p => p.toLowerCase().replace(/\\/g, "/");
  return normalize(a) === normalize(b);
}
```

Case-insensitive, backslash-normalized. No symlink resolution.

## Auto-discovery

On every `jdt use` (list) invocation:

1. Read `workspaces.json` (or create empty)
2. Scan `~/.jdtbridge/instances/*.json` for local instances
   and `~/.jdtbridge/remote-instances/*.json` for remote instances
3. For each instance: match `workspace` against registry
4. Unmatched instances → append to registry, mark `*new*`
5. Registry entries without running instance → `offline`
6. Save updated `workspaces.json`
7. Clean up stale pin files (ppid dead, or workspace offline)

## Stale pin cleanup

**On every pin read (steps 2-3 of resolution):**

- ppid pin: check if ppid is alive (`process.kill(ppid, 0)` on Unix,
  process list check on Windows). Dead → delete pin, fall through.
- Terminal pin: resolve workspace against instance files. No match
  (offline) → delete pin, fall through.

**During `jdt use` (list):** scan `use/pins/` directory, delete any
pin whose ppid is dead or whose workspace has no running instance.

Stale pins outside `jdt use` are harmless — resolution validates
before use and cleans up on the fly.

## Constraints

- **Delete shifts numbers.** Removing entry [2] from [1,2,3] makes
  old [3] become [2]. Acceptable — delete is rare. Aliases and
  workspace paths are stable identifiers that don't shift.

- **Port changes on Eclipse restart.** Pin files store workspace path,
  not port. Port is resolved live from instance files. If Eclipse
  hasn't restarted yet (no instance file), pin is treated as stale
  and deleted. Discovery takes over as fallback.

- **No terminal ID available.** Some terminals (alacritty, kitty on
  Windows, bare SSH) set no session env var and have no tty. Resolution
  falls through to discovery. Single instance works. Multiple instances
  → warning with `jdt use` hint. These terminals can still use
  `jdt use N && jdt cmd` for explicit per-command pinning via ppid.

## Design decisions

- **Terminal session ID as primary key.** Inherited by all child
  processes through arbitrary nesting depth. Proven empirically across
  hooks, agent bash calls, user commands, and subcommand spawns.
  Same pattern proposed in anthropics/claude-code#21731.

- **ppid as parallel isolation.** Terminal ID is shared within a tab —
  parallel subagents race on it. ppid is unique per bash process but
  shared within `&&` chains. Combined: terminal ID for persistence,
  ppid for isolation.

- **Workspace path in pins, not port.** Port is ephemeral (changes
  on Eclipse restart). Workspace path is stable. Resolving port live
  from instance files avoids stale connection data without requiring
  user to re-pin after every Eclipse restart.

- **No global state.** No global "active workspace" file. Pins are
  keyed by terminal session or process — two tabs or two subagents
  can independently target different workspaces.

- **Aliases for stable human-friendly names.** Numbers shift on delete.
  Aliases are user-defined, memorable, and stable. `jdt use web` is
  clearer than `jdt use 2` and survives registry changes.

- **Warning, not error.** Multiple instances without pin produces a
  warning on stderr. Commands still work (using first instance).

## Relationship to other specs

- **[jdt-agent-spec](jdt-agent-spec.md)** — Eclipse-launched agents
  get `JDT_BRIDGE_PORT/TOKEN` from session files (step 1 of resolution).
  `jdt use` solves the standalone terminal case (steps 2-3).

## Files

CLI:
  commands/use.mjs           — jdt use command implementation
  terminal-id.mjs            — resolveTerminalId()
  resolve.mjs                — connection resolution (env → ppid pin → term pin → discovery)
  home.mjs                   — workspaces.json read/write, pins directory management
  bridge-env.mjs             — getPinnedBridge()
  discovery.mjs              — discoverInstances() reads local + remote-instances

Data:
  ~/.jdtbridge/use/workspaces.json         — workspace list with aliases
  ~/.jdtbridge/use/pins/term-<id>.json     — per terminal tab pin
  ~/.jdtbridge/use/pins/ppid-<pid>.json    — per process pin
  ~/.jdtbridge/instances/                  — local instance files
  ~/.jdtbridge/remote-instances/           — remote instance files
