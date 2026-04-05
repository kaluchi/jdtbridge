# UI Integration & UX Specification: JDT Bridge

**Version:** 2.0.0
**Date:** 2026-03-30

---

## 1. Vision

Transform JDT Bridge from a background utility into a visible, manageable,
and trustworthy AI-orchestration hub within Eclipse. Focus on zero-config
onboarding, deep visibility into AI agent activities, and unified agent
lifecycle management through `jdt` CLI.

### Design decisions

- **Separate UI plugin** (`io.github.kaluchi.jdtbridge.ui`) — the core
  plugin stays headless, UI is a new module.
- **Built-in Eclipse Terminal — rejected.** Buggy TTY, poor interactive CLI
  support, non-viable for real agent sessions. Always external system terminal.
- **All agent launches go through `jdt` CLI** — every agent invocation is
  mediated by `jdt agent run`. This is the integration point for environment
  propagation, telemetry, instance pinning, and lifecycle tracking.
- **No change review / diff UI.** The plugin does not review or visualize
  agent-produced code changes. Workflow: agent works, agent opens PR,
  developer reviews PR with standard tools (GitHub, GitLab, IDE git).
  The plugin's role ends at launch, observe, stop.
- **No agent interaction UI.** No chat window, no prompt input, no
  conversation history. Interactive communication happens in the agent's
  own terminal.
- **No agent-specific configuration.** Model selection, API keys, system
  prompts belong to the agent itself. The plugin configures bridge
  integration (which projects, which instance), not agent behavior.
- **`jdt agent` — new CLI namespace** for agent lifecycle (run, stop, list,
  logs, providers). Provider-based: each provider (sandbox, local, ccs) is
  a separate launch flow with its own setup. Eclipse plugin calls
  `jdt agent run` to spawn agents.
- **No `jdt admin` for now.** `jdt setup` covers current admin needs
  (health check, hook installation, plugin management).

---

## 2. User Stories

* **Onboarding:** "As a new user, I want the IDE to tell me if I'm missing
  Node.js or the CLI, and offer a one-click fix."
* **Execution:** "As a developer, I want to launch an AI session for a
  specific subset of projects without manually typing complex commands."
* **Trust & Transparency:** "As a security-conscious dev, I want to see
  exactly what JDT data the agent is requesting in real-time."
* **Control:** "As a multi-tasker, I want to see all running AI sessions
  and stop them instantly."
* **Multi-profile:** "As a power user, I want to run multiple independent
  agent sessions (different profiles, different project scopes) in parallel."

---

## 3. Functional Areas

### A. Environment Health (Onboarding)

* Automatic detection of: Node.js, npm, `@kaluchi/jdtbridge` CLI,
  Docker (for sandbox).
* "Fix-it" actions: install CLI globally, run `jdt setup --claude` in a
  specific project.
* Status Bar indicator (bridge status, CLI version).

### B. Launch Configurations

* Each agent setup is a **distinct named launch configuration**, not a
  parameterized variant of a generic type.
* Examples: "claude-local", "claude-sandbox", "ccs-max" — each with its
  own command, env, hooks.
* Launch configs encode: provider, agent, working directory, environment
  variables (including bridge pinning), project scope.
* Plugin calls `jdt agent run <provider> <agent> --name <id>` to spawn.
* Lifecycle management via `jdt agent stop/list/logs`.

### C. Agent Activity Monitor (Telemetry)

* A dedicated Eclipse View showing a live stream of JDT Bridge HTTP requests.
* Visual distinction between Search, Read Source, Refactor, and Test Run.
* Double-click a log entry to open the corresponding Java element.
* CLI-side telemetry: track `jdt` operations that don't hit the bridge
  socket (setup, discovery, env checks) via a dedicated telemetry endpoint.

---

## 4. Out of Scope

- **Code review / diff viewer.** We don't reinvent pull requests.
- **Agent interaction UI (chat).** Happens in the agent's own terminal.
- **Built-in terminal emulator.** Unreliable for interactive TTY sessions.
- **Agent-specific configuration.** Model, API keys, prompts — agent's concern.

---

## 5. CLI Stakeholders

`jdt` CLI has four distinct consumers:

| # | Stakeholder | Commands used | Examples |
|---|-------------|--------------|---------|
| 1 | **Agent process** | Working commands | `jdt refs`, `jdt find`, `jdt build`, `jdt test run` |
| 2 | **User in terminal** | Same as agent | `jdt refs`, `jdt find`, `! jdt problems` |
| 3 | **Eclipse plugin** | Service/admin | `jdt setup --check`, `jdt agent run/stop/list` |
| 4 | **User launching agents** | Agent lifecycle | `jdt agent run`, `jdt agent stop` |

Stakeholders 1+2 share the working command interface.
Stakeholders 3+4 share the agent lifecycle interface (`jdt agent`).

### Context detection

`jdt` adjusts visible commands based on caller context:

| Signal | Stakeholder | Behavior |
|--------|-------------|----------|
| `isTTY=true`, no `JDT_BRIDGE_SESSION` | User in terminal (2, 4) | Full help, all commands |
| `isTTY=false`, `JDT_BRIDGE_SESSION` set | Agent process (1) | Working commands only |
| Called by plugin with admin token | Eclipse plugin (3) | Admin/service commands |

UX differentiation only, not a security boundary.

---

## 6. `jdt agent` — Agent Lifecycle

### 6.1. Commands

```
jdt agent run <provider> <agent> [options]    Launch agent session
  --name <id>                                 Session ID (launcher-defined)
                                              Default: <provider>-<agent>-<timestamp>

jdt agent run sandbox claude --name my-fix    Launch with specific ID
jdt agent list                                Running sessions
jdt agent stop <name>                         Stop by session ID
jdt agent logs <name> [-f]                    Stream output
jdt agent providers                           Available providers
```

The entity that calls `jdt agent run` (Eclipse UI or user) defines the
Session ID via `--name`. This ID is used for telemetry (`X-Bridge-Session`
header), lifecycle management, and UI grouping.

### 6.2. Providers

Each provider is a completely separate launch flow. No shared logic —
`sandbox` knows about Docker, `ccs` knows about profiles, `local` knows
about terminal emulators.

**Why providers, not flags:** Local Claude and sandbox Claude have different
accounts, histories, hosts, environments. `ccs max` vs direct `claude` have
different hooks, onboarding files, dependencies. Too diverse for a
parameterized command.

**Provider contract:**
1. Deliver `JDT_BRIDGE_*` connection coordinates to the agent
2. Return a handle for lifecycle management (PID, container name, etc.)
3. Implement `stop(handle)` and `logs(handle)` for that handle type

Everything else is the provider's internal concern: terminal selection,
network setup, authentication.

| Provider | Agent runs in | Handle type | Stop mechanism |
|----------|--------------|-------------|----------------|
| `local` | System terminal on host | PID | Process tree kill |
| `sandbox` | Docker container | Container name | `docker sandbox stop` |
| `ccs` | System terminal (via ccs) | PID | Process tree kill |

### 6.3. Provider setup examples

The `sandbox` provider (reference implementation — `jdt sandbox run`):

```
jdt agent run sandbox claude
  1. Discover bridge on host (port, token)
  2. Create Docker sandbox (if not exists)
  3. Configure network proxy (allow localhost)
  4. Install jdt CLI inside sandbox
  5. Write instance file inside sandbox (host.docker.internal)
  6. Spawn agent process
```

The `local` provider:

```
jdt agent run local claude
  1. Discover bridge on host (port, token)
  2. Inject JDT_BRIDGE_* env vars
  3. Open system terminal with agent command
  4. Register PID for lifecycle tracking
```

The `ccs` provider:

```
jdt agent run ccs max
  1. Discover bridge on host (port, token)
  2. Inject JDT_BRIDGE_* env vars
  3. Call `ccs max` with enriched environment
  4. Register PID for lifecycle tracking
```

Each provider is a separate module in `cli/src/commands/agent/`.

### 6.4. `jdt sandbox run` migration

`jdt sandbox run` stays as-is during transition. Once `jdt agent run sandbox`
is stable and tested, the old command becomes an alias, then is removed.

---

## 7. Two Layers of Launch

```
┌───────────────────────────────────────────────────────────┐
│  Plugin + User (stakeholders 3+4)                         │
│                                                           │
│  Eclipse UI calls:  jdt agent run sandbox claude          │
│  User calls:        jdt agent run local claude            │
│                                                           │
│  CLI does ALL setup: bridge discovery, env injection,     │
│  Docker/terminal setup, PID registration                  │
│                                                           │
│  Plugin manages via: jdt agent list / stop / logs         │
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
│                                                           │
│  Existing commands. Unchanged.                            │
└───────────────────────────────────────────────────────────┘
```

---

## 8. Environment Variable Propagation

When `jdt agent run` spawns an agent, each provider injects bridge
connection env vars:

```bash
JDT_BRIDGE_PORT=63741          # pinned port
JDT_BRIDGE_TOKEN=abc123...     # auth token
JDT_BRIDGE_HOST=127.0.0.1     # or host.docker.internal for sandbox
JDT_BRIDGE_WORKSPACE=/path    # workspace path
JDT_BRIDGE_SESSION=sess-001   # session ID for telemetry
```

When `JDT_BRIDGE_PORT` + `JDT_BRIDGE_TOKEN` are set, `jdt` CLI skips
discovery entirely and connects directly. This eliminates the
multi-instance collision bug where a test Eclipse overwrites the instance
file and the dev agent connects to the wrong instance.

**Transitivity:** These env vars must survive the agent's tool chain.
Claude Code passes env to child processes (Bash tool), so `jdt` commands
invoked by the agent inherit the pinned instance. Needs verification for
each agent type.

---

## 9. Third-Party Profile Managers

Tools like [ccs](https://github.com/kaitranntt/ccs),
[claude-code-switch](https://github.com/foreveryh/claude-code-switch),
[claude-code-router](https://github.com/musistudio/claude-code-router)
manage multiple Claude Code profiles (API keys, settings, models).

These become `jdt agent` **providers** — `jdt agent run ccs max` calls
`ccs max` under the hood with bridge env vars injected. Same pattern as
`sandbox` provider wrapping `docker sandbox run`.

---

## 10. Eclipse Launch Integration

### 10.1. Custom ILaunchConfigurationType

| Aspect | Details |
|--------|---------|
| Extension points | `o.e.debug.core.launchConfigurationTypes`, `o.e.debug.ui.launchConfigurationTabGroups` |
| Classes | `AgentLaunchDelegate`, tab group classes |
| What you get | Run Configurations dialog, Debug view, Console view, Run toolbar, history |
| Persistence | `.launch` files (shareable via VCS) |

The launch delegate calls `jdt agent run <provider> <agent> --name <id>`.
CLI does all setup. Plugin identifies the session by the `--name` ID it
assigned, and manages lifecycle via `jdt agent stop/list/logs`.

```java
// AgentLaunchDelegate.launch()
String provider = config.getAttribute(ATTR_PROVIDER, "local");
String agent = config.getAttribute(ATTR_AGENT, "claude");
String name = config.getName(); // Eclipse launch config name = session ID

List<String> cmd = List.of("jdt", "agent", "run", provider, agent,
    "--name", name);

Process proc = new ProcessBuilder(cmd)
    .directory(new File(workDir))
    .start();
```

### 10.2. External Tools (alternative)

External Tools works as a power-user escape hatch for custom/unsupported
agents. Not suitable for onboarding — user must manually construct
commands and add env vars.

### 10.3. Menu Actions (convenience shortcuts)

```
Right-click project > JDT Bridge > Launch Claude Code
  → creates transient launch config
  → calls jdt agent run local claude --name <generated>
  → visible in Debug view
```

---

## 11. Telemetry

### Problem

Many `jdt` CLI operations don't hit the bridge HTTP server: `jdt setup`,
discovery, agent spawning, hook installation. These are invisible to the
plugin's Activity Monitor.

### Proposed: `POST /telemetry`

```
POST /telemetry
Authorization: Bearer <token>

{
  "session": "sess-001",
  "event": "setup.check",
  "ts": 1743300000000,
  "data": { "node": true, "cli": "1.8.0", "docker": true }
}
```

**Event categories:**
- `discovery.*` — instance probing, connection, fallback
- `setup.*` — check, install hooks
- `agent.*` — spawn, stop
- `cli.*` — commands without their own endpoint

Plugin-side `TelemetryHandler` stores events in a ring buffer. Activity
Monitor view reads from this buffer + live HTTP request log.

Fire-and-forget: CLI sends telemetry asynchronously, does not wait for ACK.
Default-on (local-only, no data leaves the machine). Suppressible via
`--quiet` or `JDT_BRIDGE_QUIET=true`.

---

## 12. Project Scoping

### MVP: Working directory + instructions injection

- **Working directory:** Launch agent in project dir — agent defaults to
  exploring its cwd tree.
- **Instructions injection:** Generate CLAUDE.md / config with explicit
  project list. Works with all agents that respect instructions.

### Future: Server-side filtering

Each session gets a `JDT_BRIDGE_SESSION` ID. Agent's `jdt` calls include
`X-Bridge-Session` header. Bridge maintains a session registry:

```
sess-001 → { allowedProjects: ["my-server", "my-lib"] }
```

All handlers filter results by session. Only airtight solution, but
requires session-aware request routing. Deferred.

---

## 13. Feature Evaluation Matrix

**Scale:** 1 (Poor/Hard) to 5 (Excellent/Easy).

| Feature | Variant | UX | Complexity | Gemini | Claude | Notes |
|:---|:---|:---|:---:|:---:|:---:|:---|
| **Onboarding** | A: External Browser (current) | Low | 1 | 2 | 2 | Too detached |
| | B: Eclipse Intro | High | 3 | 4 | 3 | Good first-run, one-shot |
| | C: Dashboard View | High | 3 | 5 | 5 | Persistent, actionable |
| **Terminal** | A: Built-in Eclipse | Low | 2 | 1 | 1 | Rejected |
| | B: External System | High | 1 | 5 | 5 | Full TTY |
| **Launch** | A: Simple Menu Button | Mid | 1 | 3 | 3 | Fine as shortcut |
| | B: External Tools | Mid | 0 | 3 | 2 | Power-user only |
| | C: Custom Launch Type | High | 4 | 5 | 5 | Native feel |
| **Scoping** | A: Manual flags | Low | 1 | 2 | 2 | Error-prone |
| | B: Launch Config picker | High | 3 | 5 | 5 | Intuitive |
| **Telemetry** | A: Console log | Low | 1 | 2 | 2 | Messy |
| | B: Custom Table View | High | 3 | 5 | 5 | Searchable, clickable |

---

## 14. UI/UX Principles

1. **Never block the UI.** All checks and launches are asynchronous.
2. **Native over Fancy.** Standard Eclipse components (Launch Configs,
   Views, Preference Pages), not custom WebView UIs.
3. **Actionable errors.** Every error has an associated fix-it action.
4. **Context-aware.** "Launch Agent" prioritizes the project of the
   currently open file.
5. **jdt-mediated.** Eclipse UI never spawns agents directly — always
   through `jdt agent run`.

---

## 15. Module Structure

```
eclipse-jdt-search/
  plugin/            io.github.kaluchi.jdtbridge          (core, headless)
  ui/                io.github.kaluchi.jdtbridge.ui       (NEW)
  plugin.tests/      io.github.kaluchi.jdtbridge.tests
  branding/          io.github.kaluchi.jdtbridge.branding
  feature/           feature.xml (includes plugin + ui + branding)
  site/              p2 update site
  cli/               @kaluchi/jdtbridge npm package
```

Core never depends on UI. UI depends on core.

### ui/ plugin contents

```
ui/
  META-INF/MANIFEST.MF
  plugin.xml                    Commands, menus, views, preferences, launch UI
  src/io/github/kaluchi/jdtbridge/ui/
    commands/                   Menu action handlers
      LaunchAgentHandler.java
      HealthCheckHandler.java
      SetupHooksHandler.java
    launch/                     Launch configuration UI
      AgentLaunchTabGroup.java
      AgentTab.java             Provider + agent selection
      ProjectScopeTab.java      Project filtering
    views/
      DashboardView.java        Health status + quick actions
      ActivityMonitorView.java  Telemetry / request log
    preferences/
      BridgePreferencePage.java
    onboarding/
      OnboardingWizard.java     First-run wizard (replaces browser welcome)
```

---

## 16. HTTP Server Preferences

### Problem

The bridge HTTP server binds to `127.0.0.1:0` (loopback, random port).
Docker containers using `--add-host=host.docker.internal:host-gateway`
cannot reach loopback — traffic arrives on the host's gateway interface.
Custom agent setups (non-sandbox provider) need configurable bind address
and predictable ports for firewall/networking rules.

See [kaluchi/jdtbridge#93](https://github.com/kaluchi/jdtbridge/issues/93).

### Preferences

Stored per-workspace via Eclipse `InstanceScope` preferences.
Each Eclipse instance reads its own workspace preferences at startup
and on preference change (hot rebind).

| Preference | Key | Default | Values |
|---|---|---|---|
| Bind address | `httpBindAddress` | `loopback` | `loopback`, `all` |
| Fixed port | `httpFixedPort` | `0` | 0 (auto) or 1024–65535 |

Preference node: `io.github.kaluchi.jdtbridge.ui` (UI bundle ID).
Plugin reads via `InstanceScope.INSTANCE.getNode(uiBundleId)` — no
UI dependency, headless-safe.

### Preference page UI

Window > Preferences > JDT Bridge:

```
JDT Bridge settings for AI agent integration.

Terminal command:     [wt.exe                    ]

── HTTP Server ─────────────────────────────────
Bind address:  (•) Loopback only (127.0.0.1)
               ( ) All interfaces (0.0.0.0)

⚠ All interfaces exposes the bridge to your
  network. Use only with trusted networks.

Port:  [0         ]  [Check]
0 = auto-assigned by OS on each restart.
Fixed port enables stable Docker/firewall rules.
```

- **Bind address** — radio buttons. Security warning shown dynamically
  when "All interfaces" is selected.
- **Port** — integer field with Check button. Validates range
  (0 or 1024–65535). Check probes `new ServerSocket(port).close()` to
  test availability.
- **Port conflict** — if configured port is in use at startup, server
  falls back to auto-assigned port with a log warning. Instance file
  always contains the actual port, not the configured one.

### Hot rebind (no Eclipse restart)

When preferences change:

1. `PreferenceChangeListener` in plugin fires
2. Plugin calls `HttpServer.rebind(newAddress, newPort)`
3. `rebind()` opens new `ServerSocket`, then closes old one
4. Plugin rewrites instance file with new port
5. CLI discovers new port via instance file on next command

In-flight requests on the old socket complete normally (executor
thread pool stays alive). New connections go to the new socket.

### Running agents warning

Before applying changes, preference page checks for running agent
launches via `DebugPlugin.getDefault().getLaunchManager().getLaunches()`.
If any non-terminated JDT Bridge Agent launches exist, shows warning:

```
Running agents detected.
Running agents will keep using the old connection.
Restart them to use the new bind address/port.
```

Agents launched from Eclipse have `JDT_BRIDGE_PORT/TOKEN` baked into
their environment at launch time. They cannot pick up the new port
without restart.

### Architecture

```
UI bundle (preferences page)
  │ writes to InstanceScope
  ▼
workspace/.metadata/.plugins/.../io.github.kaluchi.jdtbridge.ui.prefs
  │ PreferenceChangeListener
  ▼
Plugin bundle (HttpServer)
  │ rebind(address, port)
  ▼
~/.jdtbridge/instances/<hash>.json  (rewritten with new port)
  │ discovery
  ▼
CLI (jdt commands resolve new port)
```

No circular dependency. Plugin reads preferences from UI bundle's
node via `InstanceScope` (string-based node ID, no class import).

### Files

Plugin:
  HttpServer.java              — start(address, port), rebind(), getBindAddress()
  Activator.java               — reads preferences, registers listener, triggers rebind
  Log.java                     — existing logging (warn on port conflict)

UI:
  preferences/PreferenceConstants.java  — HTTP_BIND_ADDRESS, HTTP_FIXED_PORT
  preferences/PreferenceInitializer.java — defaults (loopback, 0)
  preferences/BridgePreferencePage.java  — custom layout with radio, Check button, warnings

---

## 17. Phased Delivery

### Phase 1 — Foundation
- Create `ui/` plugin module with build integration
- Main menu + context menu (health check, open terminal)
- Preference page (basic settings, terminal preference)
- Migrate welcome from browser to native Eclipse wizard

### Phase 2 — Agent Launch (`jdt agent`)
- `jdt agent run/stop/list/logs/providers` CLI commands
- Provider implementations: `local`, `sandbox` (migrated from `jdt sandbox run`)
- Custom `ILaunchConfigurationType` — plugin calls `jdt agent run`
- Shared config in `~/.jdtbridge/config.json` — Preference Page writes, CLI reads
- `jdt sandbox run` kept as alias during transition

### Phase 3 — Observability
- `POST /telemetry` endpoint
- Activity Monitor view
- Dashboard view with health checks

### Phase 4 — Advanced
- Server-side project filtering (session-aware)
- Shareable `.launch` configs via VCS
