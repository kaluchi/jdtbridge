# jdt agent — Design Spec

## Overview

`jdt agent` manages AI agent sessions — launching agents with bridge
connectivity, monitoring them, and cleaning up when they stop. The key
insight: before an agent can use the bridge, its environment needs
significant bootstrapping (networking, CLI installation, connection
files, project hooks). Setting these from inside a running agent is
fragile and incomplete. A dedicated launcher owns the full bootstrap
sequence and ensures the agent starts in a ready state.

Two providers handle the specifics:

| Provider | What it does |
|---|---|
| `local` | Opens a system terminal with `JDT_BRIDGE_*` env vars set |
| `sandbox` | Creates a Docker sandbox, configures networking, installs CLI, runs agent |

Two bootstrap paths:

| Path | Trigger | Config source |
|---|---|---|
| Eclipse | Run > Run Configurations > JDT Bridge Agent | Session file (`~/.jdtbridge/sessions/<id>.json`) |
| CLI | `jdt agent run <provider> <agent>` | Instance discovery (`~/.jdtbridge/instances/*.json`) |

## Commands

### `jdt agent run <provider> <agent> [--name <id>] [-- agent-args...]`

Launch an agent session via a provider. Non-blocking from Eclipse
(terminal opens separately), blocking from CLI (process foreground).

`--name` sets the session ID (default: `<provider>-<agent>-<timestamp>`).

`-- agent-args...` passes everything after `--` to the agent command.

### `jdt agent run --session <id>`

Internal — called by Eclipse's `AgentLaunchDelegate`. Reads all config
from the session file written by Eclipse. Not for direct use.

### `jdt agent list [--json]`

List agent sessions with status. Stale sessions (process exited) are
cleaned up automatically.

### `jdt agent stop <name>`

Stop an agent by session name. Kills the process (local) or stops the
container (sandbox) and removes the session file.

### `jdt agent logs <name> [-f]`

Show agent output. Sandbox: Docker container logs. Local: not available
(agent runs in attached terminal).

### `jdt agent providers`

List available providers with descriptions.

## Bootstrap flow

### Eclipse path

```
User clicks Run in Eclipse
    |
    v
AgentLaunchDelegate.launch()
    |
    +-- Read provider, agent, workingDir, agentArgs, projectScope from config
    +-- Generate bridgeSessionId = configName + "-" + System.currentTimeMillis()
    +-- BridgeConnection.find() — read port/token from ~/.jdtbridge/instances/
    +-- Write session file: ~/.jdtbridge/sessions/<bridgeSessionId>.json
    |   {
    |     provider, agent, workingDir, bridgePort, bridgeToken,
    |     bridgeHost: "127.0.0.1", projectScope, agentArgs, env
    |   }
    +-- Spawn: jdt agent run --session <bridgeSessionId>
    +-- DebugPlugin.newProcess(launch, process) — appears in Eclipse Console
    +-- Register cleanup: delete session file on process termination
         |
         v
    agent.mjs runFromSession(bridgeSessionId)
         |
         +-- Read session file
         +-- Determine provider from session config
         +-- Merge CLI args (-- ...) with session args
         +-- Dispatch to provider module
```

### CLI path

```
jdt agent run sandbox claude --name my-fix
    |
    v
agent.mjs agentRun(args)
    |
    +-- No --session flag → CLI path
    +-- Parse: provider=sandbox, agent=claude, name=my-fix
    +-- Dispatch to provider module (no session object)
         |
         v
    Provider calls resolveBridge(null)
         |
         +-- No session → discoverInstances()
         +-- Read ~/.jdtbridge/instances/*.json
         +-- Return first instance {port, token, host}
```

## Providers

### Local provider (`agent-local.mjs`)

Opens a system terminal with bridge env vars injected.

Bootstrap:
1. `resolveBridge()` — get port/token/host from session or discovery
2. Build env vars: `JDT_BRIDGE_PORT`, `JDT_BRIDGE_TOKEN`, `JDT_BRIDGE_HOST`, `JDT_BRIDGE_SESSION`
3. Merge custom env vars from Eclipse Environment tab (`session.env`)
4. Write temp script (`.cmd` on Windows, `.sh` on Unix) with export + cd + agent command
5. `openTerminal()` — platform-specific terminal launcher
6. Write session tracking file to `~/.jdtbridge/agents/<name>.json`
7. If Eclipse path: stream telemetry to Eclipse Console until terminal closes

The terminal stays open after the agent command exits (`exec bash` / `/K`).
The user can restart the agent or use jdt commands interactively.

### Sandbox provider (`agent-sandbox.mjs`)

Runs agent in a Docker sandbox with full bridge connectivity.

Bootstrap:
1. `resolveBridge()` — get port/token from session or discovery
2. `docker sandbox create <agent> <workspace>` — create sandbox if not exists, workspace mounted via virtiofs
3. `docker sandbox network proxy <container> --allow-host localhost` — tunnel container→host traffic through loopback
4. Install jdt CLI inside sandbox:
   - If dev repo visible via virtiofs: `npm link` (live sync)
   - If dev repo exists but outside mount: `npm pack` → copy tarball → `npm install -g`
   - Fallback: `npm install -g @kaluchi/jdtbridge` from registry
5. Write bridge instance file inside container:
   ```json
   {
     "port": 56480,
     "token": "abc123...",
     "host": "host.docker.internal",
     "session": "sandbox-claude-1743300000"
   }
   ```
   at `~/.jdtbridge/instances/bridge.json`
6. Write session tracking file on host: `~/.jdtbridge/agents/<name>.json`
7. Launch agent:
   - Eclipse path: `openTerminal()` + telemetry streaming
   - CLI path: `docker sandbox run <container> -- <agent-args>` in foreground

### Sandbox networking

The bridge server binds to `InetAddress.getLoopbackAddress()` (127.0.0.1).
The sandbox provider makes this work from inside Docker:

```
Agent (container)
    |
    connects to host.docker.internal:<port>
    |
    v
Docker sandbox network proxy (--allow-host localhost)
    |
    tunnels to localhost:<port> on host
    |
    v
Bridge server (127.0.0.1:<port>)
    |
    accepts — connection appears from loopback
```

No server-side changes needed. The proxy is configured once at sandbox
creation and persists for the container's lifetime.

### CLI installation strategy

The sandbox provider must keep the jdt CLI inside the container in sync
with the plugin version. Three strategies, tried in order:

| Strategy | When | Tradeoff |
|---|---|---|
| `npm link` (live sync) | Dev repo visible via virtiofs mount | Live edits, but fragile — `--no-save --ignore-scripts` to avoid rewriting host lockfile |
| `npm pack` + install | Dev repo exists but outside sandbox mount | Snapshot — no live sync, but correct version |
| `npm install -g` from registry | No local repo | Always works, but may lag behind plugin |

The version check compares the CLI version inside the sandbox with the
bridge's reported version. On mismatch, the CLI is reinstalled.

## Eclipse integration

### Launch configuration type

Type ID: `io.github.kaluchi.jdtbridge.ui.agentLaunchType`

Attributes:

| Key | Default | Description |
|---|---|---|
| `io.github.kaluchi.jdtbridge.provider` | `local` | Provider name |
| `io.github.kaluchi.jdtbridge.agent` | `claude` | Agent command name |
| `io.github.kaluchi.jdtbridge.workingDir` | `""` | Working directory (supports `${workspace_loc:...}`) |
| `io.github.kaluchi.jdtbridge.agentArgs` | `""` | Arguments passed after `--` to the agent |
| `io.github.kaluchi.jdtbridge.projectScope` | `true` | Limit bridge responses to projects under workingDir |

Plus standard Eclipse attributes:
- `org.eclipse.debug.core.ATTR_ENVIRONMENT_VARIABLES` — custom env vars from Environment tab

### Tab group

`AgentTabGroup` provides three tabs:

| Tab | Purpose |
|---|---|
| Agent (`AgentTab`) | Provider combo, agent name, working dir (Workspace/File System/Variables), project scope checkbox, agent arguments |
| Environment (`EnvironmentTab`) | Custom env vars — passed through to session file as `env` object |
| Common (`CommonTab`) | Console encoding, favorites |

### Launch delegate

`AgentLaunchDelegate` implements `ILaunchConfigurationDelegate2`:
- `buildForLaunch()` returns `false` — agent is a CLI wrapper, no workspace build
- `launch()` writes session file, spawns `jdt agent run --session <id>`
- `registerCleanup()` listens for `TERMINATE` debug event, deletes session file

### Menu contribution

`AgentConfigsContribution` — dynamic menu listing saved agent configs.
Numbered for keyboard access (Alt+J, 1/2/3). Launches via
`DebugUITools.launch(config, RUN_MODE)` — Job-based, non-blocking.

## Session and scope

Agent sessions integrate with the bridge session scope system
(see [bridge-session-spec](bridge-session-spec.md)):

- `bridgeSessionId` is unique per launch (configName + timestamp)
- Session file carries `projectScope` flag and `workingDir`
- CLI sends `X-Bridge-Session` header with every HTTP request
- Server resolves scope per request: projects under workingDir
- When `projectScope=false`, full workspace is visible

The local provider sets `JDT_BRIDGE_SESSION` env var in the terminal.
The sandbox provider writes `session` field to the instance file inside
the container. Both paths result in the header being sent.

## Telemetry

When launched from Eclipse, the bootstrap process (CLI output) appears
in Eclipse Console via the spawned process stdout. But once the agent
opens in a separate terminal, its output no longer flows to Eclipse.

Telemetry bridges this gap:

- Agent-side CLI intercepts `console.log`/`console.error`, POSTs text
  to `/telemetry` on the bridge with `X-Bridge-Session` header
- Eclipse-side `telemetryUntilExit()` polls `GET /telemetry?session=<id>`
  every 2 seconds, writes response to stdout (Eclipse Console)

This gives Eclipse Console visibility into bridge request activity
without requiring the agent's own output.

## Terminal launcher

`openTerminal()` opens a platform-specific terminal window:

| Platform | Strategy |
|---|---|
| Windows | `cmd.exe /c start /wait cmd.exe /K call <tmp.cmd>` |
| macOS | `open -a Terminal <tmp.sh>` |
| Linux | `x-terminal-emulator -e <tmp.sh>` |

Commands are written to temp script files to avoid shell escaping issues.
The parent process stays alive while the terminal window is open
(`start /wait` on Windows), enabling the `exit` event for cleanup.

## File system layout

```
~/.jdtbridge/
├── instances/          # Running Eclipse bridges (written by plugin)
│   └── *.json          # {port, token, pid, workspace, version}
├── sessions/           # Agent session configs (written by Eclipse delegate)
│   └── <id>.json       # {provider, agent, workingDir, bridgePort, ...}
├── agents/             # Active agent tracking (written by providers)
│   └── <name>.json     # {name, provider, agent, pid|container, startedAt, ...}
└── config.json         # CLI config (eclipse path, etc.)
```

Lifecycle:
- `instances/*.json` — created by plugin on Eclipse start, deleted on Eclipse stop
- `sessions/<id>.json` — created by `AgentLaunchDelegate`, deleted on process termination
- `agents/<name>.json` — created by provider, deleted on agent stop or stale cleanup

## Design decisions

- **Launcher over configuration.** Setting env vars or connection
  properties from inside a running agent is a dead end. The environment
  needs CLI installation, networking, connection files, hooks — all
  before the agent command runs. A launcher owns this sequence.

- **Provider model.** Local and sandbox have fundamentally different
  bootstraps (env vars vs Docker commands). A provider interface with
  `run({ agent, name, agentArgs, session })` keeps them independent.

- **Session file as config transport.** Eclipse writes structured JSON,
  CLI reads it. No command-line argument encoding issues, no length
  limits, supports nested objects (env vars). Cleanup via debug event
  listener.

- **bridgeSessionId = configName + timestamp.** Multiple launches of
  the same config don't conflict. The configName prefix keeps sessions
  human-readable in logs and file listings.

- **Telemetry polling, not streaming.** The agent runs in a separate
  terminal — there's no pipe between the agent process and Eclipse.
  Polling `/telemetry` every 2 seconds is simple and works across
  providers. The bridge buffers text between polls.

- **npm link with --no-save --ignore-scripts.** Inside sandbox, virtiofs
  mounts the host filesystem. Without these flags, `npm link` rewrites
  `package-lock.json` with Linux-resolved deps, corrupting the host's
  lockfile. This is fragile but necessary for live development.

- **Three CLI installation strategies.** Dev experience (live sync) and
  production (registry install) have different needs. The provider tries
  each in order, falling back gracefully.

## Constraints

- **Docker Desktop required for sandbox.** `docker sandbox` is a Docker
  Desktop feature. Not available on Linux servers without Docker Desktop,
  Docker Engine-only setups, or Podman.

- **Single bridge per sandbox.** The instance file inside the container
  is always `bridge.json` — one connection. Multiple Eclipse instances
  would require multiple sandboxes.

- **Terminal launcher is platform-specific.** `x-terminal-emulator` may
  not exist on all Linux distributions. macOS `open -a Terminal` only
  opens Terminal.app, not the user's preferred terminal.

- **Telemetry is best-effort.** POST/GET to `/telemetry` silently fails
  on network errors. No retry, no persistence. If the bridge is
  unreachable, telemetry is lost.

- **Loopback-only server.** The bridge binds to 127.0.0.1. Remote
  access requires a proxy (sandbox provider handles this) or future
  configurable bind address.

## Relationship to other specs

- **[bridge-session-spec](bridge-session-spec.md)** — session scope
  filtering. Agent launches create sessions; the session spec describes
  how sessions translate to project scope.
- **[jdt-launch-spec](jdt-launch-spec.md)** — agent launch configurations
  appear in `jdt launch configs`. Agent type is
  `io.github.kaluchi.jdtbridge.ui.agentLaunchType`.

## Files

Plugin (server-side):
  HttpServer.java         — loopback bind, /telemetry endpoint

UI (Eclipse-side):
  AgentLaunchDelegate.java — writes session file, spawns CLI, cleanup
  AgentTab.java            — provider/agent/workingDir/scope/args UI
  AgentTabGroup.java       — tab group: Agent + Environment + Common
  AgentConfigsContribution.java — dynamic menu for saved agent configs
  BridgeConnection.java    — reads instance files for port/token

CLI:
  commands/agent.mjs       — run/list/stop/logs/providers dispatch, resolveBridge(), help strings
  commands/agent-local.mjs — local provider: terminal with env vars
  commands/agent-sandbox.mjs — sandbox provider: Docker bootstrap
  telemetry.mjs            — console intercept + HTTP POST/GET telemetry
  terminal.mjs             — cross-platform terminal launcher
  home.mjs                 — ~/.jdtbridge directory management
  discovery.mjs            — instance file discovery
  paths.mjs                — Windows↔Linux path conversion for sandbox
