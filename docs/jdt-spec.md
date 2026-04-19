# jdt CLI — Design Spec

## Overview

`jdt` is a CLI bridge to a running Eclipse IDE. Commands cover Java
search, compilation, testing, refactoring, and agent lifecycle.
Same IDE, different interface.

## Stakeholders

Four distinct consumers:

| # | Stakeholder | Commands used | Examples |
|---|-------------|--------------|---------|
| 1 | **Agent process** | Working commands | `jdt q`, `jdt build`, `jdt test run`, `jdt launch` |
| 2 | **User in terminal** | Same as agent | `jdt q`, `jdt status`, `! jdt q '@problems'` |
| 3 | **Eclipse plugin** | Service/admin | `jdt setup --check`, `jdt agent run/stop/list` |
| 4 | **User launching agents** | Agent lifecycle | `jdt agent run`, `jdt agent stop` |

Stakeholders 1+2 share the working command interface.
Stakeholders 3+4 share the agent lifecycle interface.

### Context detection

`jdt` adjusts visible commands based on caller context:

| Signal | Stakeholder | Behavior |
|--------|-------------|----------|
| `isTTY=true`, no `JDT_BRIDGE_SESSION` | User in terminal (2, 4) | Full help, all commands |
| `isTTY=false`, `JDT_BRIDGE_SESSION` set | Agent process (1) | Working commands only |
| Called by plugin with admin token | Eclipse plugin (3) | Admin/service commands |

UX differentiation only, not a security boundary.

## Two layers

```
┌───────────────────────────────────────────────────────────┐
│  Plugin + User (stakeholders 3+4)                         │
│                                                           │
│  Eclipse UI calls:  jdt agent run sandbox claude          │
│  User calls:        jdt agent run local claude            │
│                                                           │
│  CLI does ALL setup: bridge discovery, env injection,     │
│  Docker/terminal setup, PID registration                  │
└───────────────────────────────────────────────────────────┘
         ▲ spawn + manage agents
   ══════╪══════════════════════════════════════════════════
         ▼ working commands for agents
┌───────────────────────────────────────────────────────────┐
│  Agent + User in terminal (stakeholders 1+2)              │
│                                                           │
│  jdt q '"com.example.Service" | @callers'  semantic search│
│  jdt test run com.example.MyTest           run tests      │
│  jdt build --project my-server             compilation    │
│  jdt launch run my-maven-build             Java launches  │
└───────────────────────────────────────────────────────────┘
```

## Environment variable propagation

When `jdt agent run` spawns an agent, each provider injects bridge
connection env vars:

```bash
JDT_BRIDGE_PORT=63741
JDT_BRIDGE_TOKEN=abc123...
JDT_BRIDGE_HOST=127.0.0.1
JDT_BRIDGE_WORKSPACE=/path
JDT_BRIDGE_SESSION=sess-001
```

When `JDT_BRIDGE_PORT` + `JDT_BRIDGE_TOKEN` are set, `jdt` CLI skips
discovery and connects directly. Eliminates multi-instance collision.

These env vars must survive the agent's tool chain. Claude Code passes
env to child processes (Bash tool), so `jdt` commands invoked by the
agent inherit the pinned instance.

## Connection resolution

Every `jdt` command resolves its target Eclipse instance
(see [jdt-use-spec](jdt-use-spec.md) for full algorithm):

1. Env vars `JDT_BRIDGE_PORT/TOKEN` → use directly
2. ppid pin → resolve workspace from instance files
3. Terminal ID pin → resolve workspace from instance files
4. Discovery (scan `~/.jdtbridge/instances/`) → use first

Step 1 covers Eclipse-launched agents and Docker sandboxes.
Steps 2-3 cover `jdt use` pinning for multi-instance setups.
Step 4 is the default single-instance behavior.

## Output formats

`jdt q` emits qlang-literal — the native round-trippable form of
the semantic graph. Every other read-only command emits either
plain JSON (server-passthrough table commands such as `jdt git
--json`, `jdt launch configs --json`, `jdt editors --json`) or a
table rendering (the same commands without `--json`). JSONL
streaming is used by `jdt test run -f --json` and `jdt test
status -f --json` for per-event real-time feeds.

### Principles

1. **qlang stays qlang.** `jdt q` has no `--json` flag; the
   qlang-literal output round-trips through `parse + evalQuery`
   and composes as `jdt X | jdt q '...'`.
2. **Plain-JSON passthroughs keep `--json`.** Server-JSON
   commands (git / editors / launch list|configs|config / test
   runs / agent list / use / setup remote) accept `--json` and
   emit server shapes unchanged.
3. **Stable contract.** Plain-JSON field names are the API. Don't
   rename or remove without a major version bump.
4. **Paths go through the remote-instance cache.** Every
   path-keyed response field is rewritten via
   `path-translate.mjs` before reaching output, using the project
   root map from `jdt setup remote`. See
   [jdt-setup-remote-spec](jdt-setup-remote-spec.md).

### Commands with `--json`

| Command | JSON shape |
|---------|------------|
| `editors` | `[{fqn, project, path, active}]` |
| `git` | structured repo objects |
| `launch list` | `[{launchId, configId, configType, mode, terminated, pid}]` |
| `launch configs` | `[{configId, type, project?, class?, goals?}]` |
| `launch config` | `{configId, type, file, attributes: {}}` |
| `test run` | JSONL when streaming (`-f`), JSON snapshot otherwise |
| `test status` | JSON snapshot or JSONL stream |
| `test runs` | `[{configId, testRunId, state, total, passed, failed}]` |
| `setup remote` | instance config + cached projects |
| `use` | `[{index, alias, workspace, status, pinned, port}]` |
| `agent list` | agent sessions with status |

### Commands emitting qlang-literal

| Command | Shape |
|---------|-------|
| `q <pipeline>` | Any qlang value (Vec / Map / Set / scalar / `!{}` error) |

### Dashboard

`jdt status` emits a markdown dashboard only — machine-readable
access to any single section goes through the underlying command
(`jdt git --json`, `jdt launch configs --json`, `jdt q
'@problems'`, `jdt q '@projects'`).

Action commands (`build`, `refresh`, `setup`, `rename`, `move`,
`organize-imports`, `format`, `open`, `agent run/stop`) print
status messages; no structured output.

## Specs index

| Area | Spec |
|---|---|
| Dashboard (`jdt status`) | [jdt-status-spec](jdt-status-spec.md) |
| Graph query (`jdt q`) | [jdt-query-spec](jdt-query-spec.md) |
| Test commands (`jdt test`) | [jdt-test-spec](jdt-test-spec.md) |
| Test server protocol | [bridge-test-spec](bridge-test-spec.md) |
| Launch system (`jdt launch`) | [jdt-launch-spec](jdt-launch-spec.md) |
| Launch config operations | [jdt-launch-config-spec](jdt-launch-config-spec.md) |
| Agent lifecycle (`jdt agent`) | [jdt-agent-spec](jdt-agent-spec.md) |
| Instance switching (`jdt use`) | [jdt-use-spec](jdt-use-spec.md) |
| Setup (`jdt setup`) | [jdt-setup-spec](jdt-setup-spec.md) |
| Remote setup (`jdt setup remote`) | [jdt-setup-remote-spec](jdt-setup-remote-spec.md) |
| Session scope (project filtering) | [bridge-session-spec](bridge-session-spec.md) |
| Eclipse UI | [bridge-ui-spec](bridge-ui-spec.md) |
