# Bridge UI Launch Configuration — Design Spec

Agent launch configuration in Eclipse Run Configurations dialog.
Custom `ILaunchConfigurationType` with three tabs for configuring
how an AI agent connects to the bridge.

## Tab group

Three tabs in `AgentTabGroup`:

| Tab | Class | Purpose |
|---|---|---|
| Agent | `AgentTab` | What to launch and where |
| Environment | `EnvironmentTab` (Eclipse built-in) | Custom env vars for the agent process |
| Common | `CommonTab` (Eclipse built-in) | Console encoding, favorites |

## Agent tab

Five fields:

| Field | Widget | Default | Purpose |
|---|---|---|---|
| Provider | Combo (read-only): `local`, `sandbox` | `local` | How the agent runs: system terminal or Docker container |
| Agent | Text | `claude` | Agent command name (e.g. `claude`, `gemini`) |
| Working directory | Text + Workspace.../File System.../Variables... | empty | Agent's cwd. Supports `${workspace_loc:project}` variables. Also defines project scope boundary |
| Limit project scope | Checkbox | on | When on, bridge responses filtered to projects under working directory (see [bridge-session-spec](bridge-session-spec.md)) |
| Agent arguments | Multiline text + Variables... | empty | Passed after `--` to agent command. E.g. `--continue`, `--model opus` |

Validation: provider must be selected, agent name must be non-empty.
Working directory is optional (provider uses default cwd).

### Working directory buttons

Same pattern as Maven Build launch configuration:
- **Workspace...** — `ContainerSelectionDialog`, inserts `${workspace_loc:/project}`
- **File System...** — `DirectoryDialog`, inserts absolute path
- **Variables...** — `StringVariableSelectionDialog`, inserts any Eclipse variable

### Environment tab

Standard Eclipse `EnvironmentTab`. Key/value pairs stored in
`ATTR_ENVIRONMENT_VARIABLES`. Passed through to session file as
`env` object, then merged into the agent's terminal environment.

Use case: `ANTHROPIC_API_KEY`, `CLAUDE_CODE_USE_BEDROCK`, custom
model endpoints — anything the agent command reads from env.

## Launch delegate

`AgentLaunchDelegate` implements `ILaunchConfigurationDelegate2`.

### Current flow (MVP)

```
Eclipse Run button
    │
    ▼
AgentLaunchDelegate.launch()
    │
    ├── buildForLaunch() → false (no workspace build)
    ├── Read config attributes from tabs
    ├── BridgeConnection.find() → port, token from instance files
    ├── bridgeSessionId = configName + "-" + timestamp
    ├── Write ~/.jdtbridge/sessions/<id>.json
    │     { provider, agent, workingDir, bridgePort, bridgeToken,
    │       bridgeHost, projectScope, agentArgs, env }
    ├── Spawn: jdt agent run --session <id>
    ├── DebugPlugin.newProcess(launch, process, bridgeSessionId)
    └── registerCleanup: on TERMINATE → delete session file
```

CLI side (`runFromSession`):
1. Read session file
2. Merge args (session + CLI `--` args)
3. Dispatch to provider module with `session` object
4. Provider calls `resolveBridge(session)` — reads port/token from session

### What Eclipse owns that it shouldn't

The delegate does bridge discovery and session file management —
responsibilities that belong to the CLI engine:

| Responsibility | Current owner | Target owner |
|---|---|---|
| Bridge discovery (port/token) | Eclipse (`BridgeConnection.find()`) | CLI (`resolveBridge()`) |
| Session file creation | Eclipse (`writeSessionFile()`) | CLI (`jdt agent run`) |
| Session file cleanup | Eclipse (debug event listener) | CLI (process exit handler) |
| bridgeSessionId generation | Eclipse | CLI |
| Env var merge (Environment tab) | Eclipse (writes to session JSON) | CLI (reads from launch config or flags) |

Eclipse's `BridgeConnection.find()` duplicates CLI's `resolveBridge()`.
Session file is a transport format between Eclipse and CLI — Eclipse
writes JSON, CLI reads it. The debug event listener for cleanup is
fragile: if Eclipse crashes, session files are orphaned.

### Target architecture

Eclipse should be a thin launcher — read tab fields, pass as CLI
flags, monitor process. All engine logic in CLI.

```
Eclipse Run button
    │
    ▼
AgentLaunchDelegate.launch()
    │
    ├── buildForLaunch() → false
    ├── Read config attributes from tabs
    ├── Spawn: jdt agent run <provider> <agent>
    │     --workdir <path>
    │     --project-scope
    │     --env KEY=VALUE --env KEY2=VALUE2
    │     -- <agentArgs>
    ├── DebugPlugin.newProcess(launch, process, name)
    └── (no cleanup — CLI owns session lifecycle)
```

CLI side:
1. Parse flags
2. Discover bridge (`resolveBridge()`)
3. Generate bridgeSessionId
4. Write session file
5. Dispatch to provider
6. On exit: delete session file

Benefits:
- Single code path for Eclipse launch and `jdt agent run` from terminal
- No orphaned session files on Eclipse crash (CLI cleanup on exit)
- No duplicate bridge discovery logic
- Environment tab values passed as `--env` flags, not JSON transport

Migration: incremental. Add `--workdir`, `--project-scope`, `--env`
flags to `jdt agent run`. Update delegate to use flags instead of
session file. Session file becomes CLI-internal (not written by Eclipse).

## Configuration attributes

| Key | Type | Default | Description |
|---|---|---|---|
| `io.github.kaluchi.jdtbridge.ui.provider` | String | `local` | Provider name |
| `io.github.kaluchi.jdtbridge.ui.agent` | String | `claude` | Agent command |
| `io.github.kaluchi.jdtbridge.ui.workingDir` | String | `""` | Working directory (supports variables) |
| `io.github.kaluchi.jdtbridge.ui.projectScope` | boolean | `true` | Limit bridge to projects in workingDir |
| `io.github.kaluchi.jdtbridge.ui.agentArgs` | String | `""` | Arguments after `--` |
| `ATTR_ENVIRONMENT_VARIABLES` | Map | null | Custom env vars (Eclipse standard) |

## Relationship to other specs

- **[jdt-agent-spec](jdt-agent-spec.md)** — CLI-side agent lifecycle,
  providers, bootstrap flow. The delegate spawns `jdt agent run`.
- **[bridge-session-spec](bridge-session-spec.md)** — session file
  format, project scope resolution, `X-Bridge-Session` header.
- **[bridge-ui-menu-spec](bridge-ui-menu-spec.md)** — menu items
  that launch agent configs.

## Files

UI:
  launch/AgentLaunchDelegate.java — session file, spawn, cleanup
  launch/AgentTab.java            — 5 fields: provider, agent, workDir, scope, args
  launch/AgentTabGroup.java       — Agent + Environment + Common
  BridgeConnection.java           — reads instance files (to be removed)

CLI:
  commands/agent.mjs              — runFromSession() reads session file
  commands/agent-local.mjs        — local provider, terminal with env
  commands/agent-sandbox.mjs      — sandbox provider, Docker bootstrap
