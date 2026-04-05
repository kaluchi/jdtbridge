# Bridge UI — Design Spec

## Vision

Transform JDT Bridge from a background utility into a visible,
manageable AI-orchestration hub within Eclipse. Zero-config
onboarding, deep visibility into agent activities, unified agent
lifecycle management through `jdt` CLI.

### Design decisions

- **Separate UI plugin** (`io.github.kaluchi.jdtbridge.ui`) — core
  plugin stays headless, UI is a new module.
- **External system terminal, not built-in Eclipse Terminal.** Buggy
  TTY, poor interactive CLI support, non-viable for agent sessions.
- **All agent launches go through `jdt` CLI** — every invocation is
  mediated by `jdt agent run`. Integration point for environment
  propagation, telemetry, lifecycle tracking.
- **No change review / diff UI.** Agent works → opens PR → developer
  reviews with standard tools. Plugin's role: launch, observe, stop.
- **No agent interaction UI.** No chat window, no prompt input.
  Interactive communication happens in the agent's own terminal.
- **No agent-specific configuration.** Model selection, API keys,
  system prompts belong to the agent itself. The plugin configures
  bridge integration (which projects, which instance), not agent
  behavior.

## Out of scope

- Code review / diff viewer — don't reinvent pull requests.
- Agent interaction UI (chat) — happens in the agent's terminal.
- Built-in terminal emulator — unreliable for interactive TTY.
- Agent-specific configuration — model, API keys, prompts are the
  agent's concern.

## UI/UX principles

1. **Never block the UI.** All checks and launches are asynchronous.
2. **Native over Fancy.** Standard Eclipse components (Launch Configs,
   Views, Preference Pages), not custom WebView UIs.
3. **Actionable errors.** Every error has an associated fix-it action.
4. **Context-aware.** "Launch Agent" prioritizes the project of the
   currently open file.
5. **jdt-mediated.** Eclipse UI never spawns agents directly — always
   through `jdt agent run`.

## Module structure

```
eclipse-jdt-search/
  plugin/            io.github.kaluchi.jdtbridge          (core, headless)
  ui/                io.github.kaluchi.jdtbridge.ui
  plugin.tests/      io.github.kaluchi.jdtbridge.tests
  branding/          io.github.kaluchi.jdtbridge.branding
  feature/           feature.xml (includes plugin + ui + branding)
  site/              p2 update site
  cli/               @kaluchi/jdtbridge npm package
```

Core never depends on UI. UI depends on core
(`Require-Bundle: io.github.kaluchi.jdtbridge`).

### ui/ plugin contents

```
ui/
  META-INF/MANIFEST.MF
  plugin.xml                    Commands, menus, preferences, launch UI
  src/io/github/kaluchi/jdtbridge/ui/
    Activator.java              AbstractUIPlugin, preference store
    BridgeConnection.java       Reads instance files for launch delegate
    ProcessUtil.java            Process builder for jdt CLI commands
    WelcomeStartupHandler.java  Startup check, version dialog
    commands/
      HealthCheckHandler.java   Bridge diagnostics command
      NewAgentHandler.java      New agent launch dialog
      OpenTerminalHandler.java  System terminal with bridge env vars
    launch/
      AgentLaunchDelegate.java  ILaunchConfigurationDelegate2
      AgentTab.java             Provider + agent selection tab
      AgentTabGroup.java        Tab group for agent launch configs
    menus/
      AgentConfigsContribution.java  Dynamic menu: saved agent configs
      EditAgentContribution.java     Dynamic menu: edit configurations
    preferences/
      BridgePreferencePage.java      Dual socket preference page
      PreferenceConstants.java       Delegates to ServerPreferences
      PreferenceInitializer.java     Default values
```

## Detailed specs

| Area | Spec |
|---|---|
| Main menu, context menu | [bridge-ui-menu-spec](bridge-ui-menu-spec.md) |
| Preferences (dual socket, tokens) | [bridge-ui-preferences-spec](bridge-ui-preferences-spec.md) |
| Agent launch configuration (tabs, delegate) | [bridge-ui-launch-spec](bridge-ui-launch-spec.md) |
| Agent lifecycle (providers, CLI commands) | [jdt-agent-spec](jdt-agent-spec.md) |
| Session scope (project filtering) | [bridge-session-spec](bridge-session-spec.md) |

## Future work

### Telemetry view

Many `jdt` CLI operations don't hit the bridge HTTP server: `jdt setup`,
discovery, agent spawning, hook installation. A dedicated Eclipse View
showing live stream of bridge HTTP requests + CLI telemetry events.

`POST /telemetry` endpoint, ring buffer on plugin side, Activity
Monitor view. Fire-and-forget, default-on, local-only.

### Onboarding dashboard

Eclipse View with environment health checks: Node.js, npm, CLI
version, Docker availability. Fix-it actions: install CLI globally,
run `jdt setup --claude` in a specific project.
