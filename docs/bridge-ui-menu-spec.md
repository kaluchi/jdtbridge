# Bridge UI Menu — Design Spec

Eclipse main menu and context menu for JDT Bridge.

## Main menu

`JDT Bridge` menu in the main menu bar (after Additions):

```
JDT Bridge
  ├─ New Agent...                    (command: newAgent)
  ├─ ─────────── configs ──────────
  ├─ <dynamic: saved agent configs>  (AgentConfigsContribution)
  ├─ <dynamic: edit agent>           (EditAgentContribution)
  ├─ ─────────── tools ───────────
  ├─ Health Check                    (command: healthCheck)
  └─ Open Terminal                   (command: openTerminal)
```

### Static items

| Item | Command ID | Icon | Action |
|---|---|---|---|
| New Agent... | `newAgent` | new-agent.svg | Opens new agent launch dialog |
| Health Check | `healthCheck` | health-check.svg | Runs `jdt setup --check` equivalent |
| Open Terminal | `openTerminal` | terminal.svg | Opens system terminal with bridge env vars |

### Dynamic contributions

**AgentConfigsContribution** — lists saved JDT Bridge Agent launch
configurations. Each item launches the config directly (one-click
agent start). Updated on menu open.

**EditAgentContribution** — shows "Edit Configurations..." when
agent configs exist. Opens Run Configurations dialog filtered to
JDT Bridge Agent type.

### Separators

Two named separators divide the menu into sections:
- `configs` — between New Agent and saved configs
- `tools` — between configs and utility commands

## Context menu

Right-click on project in Package Explorer / Project Explorer:

```
JDT Bridge
  └─ Open Terminal Here              (command: openTerminal)
```

Visible only when selection contains `IProject` (adapter check).
Opens terminal in the selected project's directory.

## Commands and handlers

Registered via `org.eclipse.ui.commands` and `org.eclipse.ui.handlers`:

| Command | Handler class | Description |
|---|---|---|
| `newAgent` | `NewAgentHandler` | Agent launch wizard |
| `healthCheck` | `HealthCheckHandler` | Bridge diagnostics |
| `openTerminal` | `OpenTerminalHandler` | System terminal with env |

Terminal command configurable in preferences (`PreferenceConstants.TERMINAL_COMMAND`).

## Relationship to launch system

Menu items that launch agents create or reuse
`ILaunchConfiguration` of type `agentLaunchType`.
See [jdt-launch-spec.md](jdt-launch-spec.md) and
[bridge-ui-spec.md](bridge-ui-spec.md) section 10.
