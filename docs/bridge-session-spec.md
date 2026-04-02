# Bridge Session Scope

Server-side project filtering for agent sessions.
When an agent is launched through Eclipse with a working directory,
all bridge responses are scoped to projects under that directory.
Without a session, full workspace is visible.

## Protocol

Header: `X-Bridge-Session: <bridgeSessionId>`

CLI sends this header with every HTTP request when launched via Eclipse.
The bridgeSessionId is unique per launch: configName + timestamp
(e.g. `local-claude-1775129067241`, `docker sandbox run claude-1775130000000`).

Agent-local provider sets `JDT_BRIDGE_SESSION` env var in the terminal.
`client.mjs` reads it and attaches the header to every request.

When CLI is used directly (no Eclipse launch), the header is absent
and no filtering is applied.

## Session file

Written by `AgentLaunchDelegate` (ui bundle) at launch time:

    ~/.jdtbridge/sessions/<bridgeSessionId>.json

```json
{
  "provider": "local",
  "agent": "claude",
  "workingDir": "D:\\git\\eclipse-jdt-search",
  "bridgePort": 56480,
  "bridgeToken": "abc123...",
  "bridgeHost": "127.0.0.1",
  "agentArgs": "--continue"
}
```

Key field: `workingDir` -- the agent's working directory, set in
AgentTab when creating the launch configuration. Typically the root
of a git repository or Maven multi-module project.

Deleted automatically when the agent process terminates
(`AgentLaunchDelegate.registerCleanup`). Whoever creates
the file is responsible for cleaning it up.

## Scope resolution

```
X-Bridge-Session: "local-claude-1775129067241"
    |
    v
SessionScope.resolve(bridgeSessionId)
    |
    +-- null/empty --> ProjectScope.ALL (no filtering)
    |
    +-- read ~/.jdtbridge/sessions/<bridgeSessionId>.json
    |   workingDir = "D:\git\eclipse-jdt-search"
    |
    +-- file missing --> ProjectScope.ALL (agent terminated)
    |
    +-- resolve:
            ProjectScope.allOpenProjects()
                .filter(project.getLocation().startsWith(workDir))
                --> Set { "io.github.kaluchi.jdtbridge",
                          "io.github.kaluchi.jdtbridge.tests",
                          "io.github.kaluchi.jdtbridge.ui", ... }
            |
            v
       ProjectScope.of(projectNames)
```

No caching. Scope is resolved fresh on every request.
Session file is small (~200 bytes), project iteration is
~39 path comparisons. Total cost: ~2ms per request.
This ensures new/removed projects are visible immediately.

## ProjectScope

Null Object pattern -- never null.

ProjectScope.ALL: default, no filtering, full workspace.
ProjectScope.of(Set): filtered scope for specific projects.

Domain methods:

    containsProject(name)     -- is project name in scope?
    containsConfig(config)    -- is launch config in scope?
    containsLaunch(launch)    -- is launch in scope?
    openProjects()            -- stream of open projects in scope
    allOpenProjects()         -- static, all open projects (unfiltered)
    searchScope()             -- IJavaSearchScope for JDT search engine

containsConfig checks in order:
  1. PROJECT_ATTR -- direct project reference (JUnit, Java App)
  2. Launch Group -- recursive check of child configurations
  3. WORKING_DIRECTORY -- path overlap (Maven builds, agents)

For ProjectScope.ALL, all methods return true / full workspace.
For filtered scope, each method checks against the project set.

## Launch handling

Agent launch uses `DebugUITools.launch()` (Job-based, non-blocking)
instead of `config.launch()` (synchronous on UI thread).

AgentLaunchDelegate implements ILaunchConfigurationDelegate2:
  - buildForLaunch() returns false (agent is a CLI wrapper)
  - No workspace build before launch

bridgeSessionId is unique per launch (configName + timestamp),
so multiple launches of the same configuration don't conflict.

## Filtered endpoints

Endpoint            Filtering method
/projects           scope.openProjects()
/find               scope.searchScope()
/references         scope.searchScope()
/subtypes           scope.searchScope()
/hierarchy          scope.searchScope()
/implementors       scope.searchScope()
/source             scope.searchScope() (incoming refs)
/errors             scope.containsProject(marker project)
/editors            scope.containsProject(file project)
/launch/list        scope.containsLaunch(launch)
/launch/configs     scope.containsConfig(config)
/test/sessions      scope.containsLaunch(session launch)

## Unfiltered endpoints (explicit params)

/build, /refresh, /maven/update, /type-info, /project-info,
/test/run, /test/status, /organize-imports, /format, /rename,
/move, /open, /launch/config, /launch/run, /launch/stop

These accept explicit project/file/class parameters.
The agent specifies the target directly.

## Design principles

- Null Object over null: ProjectScope.ALL is the default, never null.
  Handlers call scope methods without null checks.

- Domain methods over generic: containsProject(), containsConfig(),
  containsLaunch(), searchScope() -- not a generic contains(String).
  Each method knows the domain semantics of its entity.

- No caching: scope resolved fresh every request. Session file is
  tiny, path comparison is cheap. New projects visible immediately.

- Zero CLI changes: the X-Bridge-Session header was already sent
  for telemetry. Session file was already written by AgentLaunchDelegate.
  Scope piggybacks on existing infrastructure.

- Explicit params bypass scope: endpoints that accept explicit
  project/file/class don't need scope. The agent already knows
  what it's targeting. Scope only filters "list all" operations.

- Whoever creates cleans up: AgentLaunchDelegate writes session
  file and registers cleanup on process termination.

- Non-blocking launch: DebugUITools.launch() runs in a Job,
  buildForLaunch() returns false. No UI thread blocking.

## Constraints and limitations

- Path matching uses Path.startsWith(workDir). Projects must be
  physically located under the working directory. Linked projects
  or projects with custom locations outside workDir are excluded.

- Windows path normalization: uses Path.toRealPath() for
  case-insensitive comparison. If the path doesn't exist,
  falls back to toAbsolutePath().normalize().

- Launch Group filtering resolves child configs by name from
  ILaunchManager.getLaunchConfigurations(). If a child config
  is deleted, it is skipped (not an error).

- WORKING_DIRECTORY filtering resolves ${workspace_loc:...}
  variables via VariablesPlugin.performStringSubstitution().
  This requires org.eclipse.core.variables bundle.

## Files

Plugin:
  SessionScope.java      -- session-to-scope resolver (no cache)
  ProjectScope.java      -- scope object with domain filter methods
  HttpServer.java         -- resolves scope per request, passes to dispatch

UI:
  AgentLaunchDelegate.java -- writes session file, unique bridgeSessionId,
                              ILaunchConfigurationDelegate2 (no build)
  AgentConfigsContribution.java -- DebugUITools.launch() (Job-based)
  AgentTab.java            -- working directory field in launch config UI

CLI:
  client.mjs              -- sends X-Bridge-Session header
  agent-local.mjs         -- sets JDT_BRIDGE_SESSION env var
  agent-sandbox.mjs       -- writes session field to instance file
