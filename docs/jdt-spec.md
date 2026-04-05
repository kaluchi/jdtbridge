# jdt CLI — Design Spec

## Overview

`jdt` is a CLI bridge to a running Eclipse IDE. Commands cover Java
search, compilation, testing, refactoring, and agent lifecycle.
Same IDE, different interface.

## Stakeholders

Four distinct consumers:

| # | Stakeholder | Commands used | Examples |
|---|-------------|--------------|---------|
| 1 | **Agent process** | Working commands | `jdt refs`, `jdt find`, `jdt build`, `jdt test run` |
| 2 | **User in terminal** | Same as agent | `jdt refs`, `jdt find`, `! jdt problems` |
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
│  jdt refs com.example.Service      semantic search        │
│  jdt test run com.example.MyTest   run tests              │
│  jdt build --project my-server     compilation            │
│  jdt launch run my-maven-build     Java app launches      │
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

## `--json` output

All query commands support `--json` for structured output.
Default stays human-readable; agents opt in per invocation.

### Principles

1. **Flag, not mode.** `--json` is per-invocation, not a config setting.
2. **Server data, not rendered data.** Raw data structure, no ANSI
   codes, no padding, no badges.
3. **Early exit pattern.** Check `--json` before rendering, return
   raw data, skip all formatting.
4. **Stable contract.** JSON field names are the API. Don't rename
   or remove without a major version bump.
5. **Paths use `toSandboxPath()`.** File paths go through path
   remapping for Docker sandbox compatibility.
6. **Single object or array.** One result = object. Multiple = array.

### Commands with `--json`

| Command | JSON shape |
|---------|------------|
| `find` | `[{kind, fqn, binary, file, origin}]` |
| `references` | `[{file, line, in, content}]` |
| `implementors` | `[{fqn, fqmn?, file, project, startLine, endLine}]` |
| `hierarchy` | `{type, supertypes[], interfaces[], subtypes[]}` |
| `type-info` | `{fqn, kind, fields[], methods[], supertypes[]}` |
| `source` | raw server JSON (source + refs) |
| `problems` | `[{file, line, col, severity, message}]` |
| `projects` | `[{name, location, repo}]` |
| `editors` | `[{fqn, project, path, active}]` |
| `project-info` | raw server response |
| `git` | structured repo objects |
| `launch list` | `[{launchId, configId, configType, mode, terminated, pid}]` |
| `launch configs` | `[{configId, type, project?, class?, goals?}]` |
| `launch config` | `{configId, type, file, attributes: {}}` |
| `test run` | JSONL when streaming (`-f`), JSON snapshot otherwise |
| `test status` | JSON snapshot or JSONL stream |
| `test runs` | `[{configId, testRunId, state, total, passed, failed}]` |
| `status` | composite JSON of all data sections |
| `setup remote` | instance config + cached projects |
| `use` | `[{index, alias, workspace, status, pinned, port}]` |
| `agent list` | agent sessions with status |

### Implementation pattern

```js
const data = await get(url);
if (flags.json) { console.log(JSON.stringify(data, null, 2)); return; }
// ... render for humans
```

Commands that transform server data (group, enrich, aggregate)
return the **transformed** data in `--json`, not raw server response —
the transformation is the command's value-add.

Action commands (`build`, `refresh`, `setup`, `rename`, `move`,
`organize-imports`, `format`, `open`, `agent run/stop`) do not
support `--json` — they print status messages, not data.

## Specs index

| Area | Spec |
|---|---|
| Dashboard (`jdt status`) | [jdt-status-spec](jdt-status-spec.md) |
| Source navigation (`jdt source`) | [jdt-source-spec](jdt-source-spec.md) |
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
