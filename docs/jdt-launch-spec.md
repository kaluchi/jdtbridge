# jdt launch — Design Spec

## Overview

`jdt launch` manages Eclipse launch configurations and running processes.
It exposes the Eclipse Debug/Launch infrastructure through CLI commands,
letting agents and terminal users list, inspect, run, monitor, and stop
any launch configuration in the workspace — JUnit tests, Java applications,
Maven builds, PDE plugin tests, and custom agent types.

## Eclipse concepts mapped to CLI

| Eclipse concept | CLI surface |
|---|---|
| Launch Configuration (saved .launch file) | `jdt launch configs`, `jdt launch config <name>` |
| ILaunch (running/terminated process) | `jdt launch list`, `jdt launch logs`, `jdt launch stop` |
| Launch Manager | Internal — mediates all launch operations |
| Launch History (favorites + recent) | `jdt launch configs` sorts favorites first |
| Console output (IStreamMonitor) | `jdt launch logs` (snapshot), `jdt launch logs -f` (stream) |

## Commands

### `jdt launch list [--json]`

List active and terminated launches (current Eclipse session).

```
NAME                TYPE                MODE  STATUS          PID
my-server           Java Application    run   running         12345
ObjectMapperTest    JUnit               run   terminated (0)
```

JSON fields: `name`, `type`, `mode`, `terminated`, `started`, `exitCode`, `pid`.

Launches are ephemeral — they exist only for the current Eclipse session.
Restarting Eclipse clears the list. Use `jdt launch clear` to remove
terminated entries without restarting.

### `jdt launch configs [--json]`

List all saved launch configurations in the workspace.

```
NAME              TYPE                PROJECT      TARGET
my-server         Java Application    m8-server    app.m8.Main
ObjectMapperTest  JUnit               m8-server    app.m8.ObjectMapperTest
jdtbridge-verify  Maven Build                      clean verify
AllTests          JUnit Plug-in Test  jdtbridge    io.github.kaluchi.jdtbridge.AllTests
```

Columns:
- **NAME** — configuration name (unique identifier for `jdt launch run`)
- **TYPE** — Eclipse launch type (human-readable)
- **PROJECT** — Java project (from `org.eclipse.jdt.launching.PROJECT_ATTR`)
- **TARGET** — type-specific summary:
  - JUnit: `class#method` (FQMN)
  - Java Application: main class
  - Maven Build: goals

JSON includes additional fields per type:
- JUnit / JUnit Plug-in Test: `class`, `method`, `runner` (JUnit 4/5/6)
- Java Application: `mainClass`
- Maven Build: `goals`, `profiles`

Sort order: favorites first, then launch history, then alphabetical.
This matches the Eclipse Run > Run Configurations dialog order.

### `jdt launch config <name> [--xml]`

Show full details of a single launch configuration.

Default output (JSON):
```json
{
  "name": "ObjectMapperTest",
  "type": "JUnit",
  "typeId": "org.eclipse.jdt.junit.launchconfig",
  "file": "/path/to/.metadata/.plugins/org.eclipse.debug.core/.launches/ObjectMapperTest.launch",
  "attributes": {
    "org.eclipse.jdt.launching.MAIN_TYPE_NAME": "app.m8.ObjectMapperTest",
    "org.eclipse.jdt.launching.PROJECT_ATTR": "m8-server",
    "org.eclipse.jdt.junit.TEST_KIND": "org.eclipse.jdt.junit.loader.junit5",
    "org.eclipse.jdt.launching.VM_ARGUMENTS": "-Xmx512m"
  }
}
```

With `--xml`, outputs the raw .launch file content (Eclipse XML format).
Useful for debugging attribute issues or comparing configurations.

Design decisions:
- Default is JSON (not text table) because config details are inherently
  nested key-value data, not tabular. JSON is both human-readable and
  machine-parseable.
- Attribute keys are raw Eclipse IDs (e.g. `org.eclipse.jdt.launching.MAIN_TYPE_NAME`),
  not mapped to friendly names. This preserves the full fidelity and avoids
  lossy mapping. Agents can use known keys; humans use `--xml` for the full picture.
- `getAttributes()` preserves types: strings, booleans, integers, lists, maps.
  This is richer than parsing XML where everything is a string.

### `jdt launch run <name> [-f] [-q]`

Launch a saved configuration in run mode (non-blocking).

Without `-f`: prints launch info + onboarding guide.
With `-f`: launches and streams console output until termination.
With `-q`: suppresses the guide.

```
Launched my-server (run) [Java Application]
  PID:        12345
  Working dir: /d/git/m8
  Command:    java -cp ... app.m8.Main
```

### `jdt launch debug <name> [-f] [-q]`

Same as `run` but attaches the Eclipse debugger.

### `jdt launch logs <name> [-f] [--tail N] [--stdout] [--stderr]`

Show console output of a launch.

Without `-f`: snapshot of current output.
With `-f`: stream live until process exits (Ctrl+C to detach, process keeps running).
`--tail N`: last N lines only.
`--stdout`/`--stderr`: filter to one stream.

### `jdt launch stop <name>`

Terminate a running launch. Returns error if already terminated.

### `jdt launch clear [name]`

Remove terminated launches from the list.
Without name: removes all terminated.
With name: removes only that specific terminated launch.

## HTTP API

### `GET /launch/list`

Returns: `[{name, type, mode, terminated, started, exitCode, pid}]`

### `GET /launch/configs`

Returns: `[{name, type, project?, class?, method?, runner?, mainClass?, goals?, profiles?}]`

Fields vary by launch type. Only non-null fields are included.

### `GET /launch/config?name=<name>[&format=xml]`

Default: `{name, type, typeId, file, attributes: {key: value, ...}}`

`format=xml`: `{name, file, xml: "<raw .launch XML content>"}`

Attribute values preserve Eclipse types:
- `String` -> JSON string
- `boolean` -> JSON boolean
- `int` -> JSON number
- `List<String>` -> JSON array
- `Set<String>` -> JSON array
- `Map<String, String>` -> JSON object

### `GET /launch/run?name=<name>[&debug]`

Returns: `{ok, name, mode, type, pid, cmdline, workingDir}`

### `GET /launch/console?name=<name>[&tail=N][&stream=stdout|stderr]`

Returns: `{name, terminated, output}`

### `GET /launch/console/stream?name=<name>[&tail=N][&stream=stdout|stderr]`

SSE stream (text/plain). Streams console output until process terminates.

### `GET /launch/stop?name=<name>`

Returns: `{ok, name}`

### `GET /launch/clear[?name=<name>]`

Returns: `{removed: N}`

## Architecture

### Plugin classes

| Class | Responsibility |
|---|---|
| `LaunchHandler` | HTTP handlers for all `/launch/*` endpoints. Reads from LaunchManager and LaunchTracker, serializes JSON responses. |
| `LaunchTracker` | `ILaunchesListener2` implementation. Tracks all launches, captures console output via `IStreamMonitor`. Survives `ILaunch` removal from LaunchManager. |
| `ConsoleStreamer` | SSE streamer for `/launch/console/stream`. Sends accumulated output then streams live until termination. |

### CLI modules

| Module | Responsibility |
|---|---|
| `commands/launch.mjs` | All launch subcommands: list, configs, config, run, debug, stop, clear, logs |
| `format/table.mjs` | Shared table formatter (used by list and configs) |
| `output.mjs` | Format dispatcher (--json routing) |
| `json-output.mjs` | JSON printer with path remapping |

### Launch types and their identifiers

| Human name | Type ID | Plugin |
|---|---|---|
| JUnit | `org.eclipse.jdt.junit.launchconfig` | JDT |
| JUnit Plug-in Test | `org.eclipse.pde.ui.JunitLaunchConfig` | PDE |
| Java Application | `org.eclipse.jdt.launching.localJavaApplication` | JDT |
| Maven Build | `org.eclipse.m2e.Maven2LaunchConfigurationType` | m2e |
| Launch Group | `org.eclipse.debug.core.groups.GroupLaunchConfigurationType` | Debug |
| JDT Bridge Agent | `io.github.kaluchi.jdtbridge.ui.agentLaunchType` | jdtbridge.ui |

### Key attribute keys

**Common (all Java types):**
- `org.eclipse.jdt.launching.PROJECT_ATTR` — project name
- `org.eclipse.jdt.launching.MAIN_TYPE_NAME` — main class FQN
- `org.eclipse.jdt.launching.VM_ARGUMENTS` — JVM arguments
- `org.eclipse.debug.core.ATTR_WORKING_DIRECTORY` — working directory

**JUnit-specific:**
- `org.eclipse.jdt.junit.TEST_KIND` — runner (junit4/junit5/junit6 loader ID)
- `org.eclipse.jdt.junit.TESTNAME` — specific test method
- `org.eclipse.jdt.junit.CONTAINER` — test container (for package/project scope)

**PDE JUnit-specific:**
- `run_in_ui_thread` — false for headless tests
- `application` — test application ID
- `default`, `automaticAdd`, `includeOptional` — bundle resolution
- `location` — temp workspace path
- `clearws` — clear workspace before run

**Maven-specific:**
- `M2_GOALS` — Maven goals
- `M2_PROFILES` — Maven profiles

**JDT Bridge Agent-specific:**
- `io.github.kaluchi.jdtbridge.provider` — "local" or "sandbox"
- `io.github.kaluchi.jdtbridge.agent` — agent name
- `io.github.kaluchi.jdtbridge.workingDir` — working directory
- `io.github.kaluchi.jdtbridge.agentArgs` — agent arguments

## Launch configuration storage

`.launch` files are always stored in the workspace metadata directory:
```
<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/<name>.launch
```

Format: XML with `launchConfiguration` root element. Attributes are stored
as typed elements (`stringAttribute`, `booleanAttribute`, `listAttribute`, etc.).

**Eclipse API:**
- `getFile()` — returns `null` for configs in `.metadata` (all standard configs).
  Only non-null if the .launch file is stored inside a workspace project.
- `getLocation()` — deprecated since 3.5 (EFS). Not used.
- `getAttributes()` — deserialized `Map<String, Object>` with proper types.
- `DebugPlugin.getStateLocation()` — returns the debug plugin state directory.
  Appending `.launches/<name>.launch` gives the file path. This is the same
  approach Eclipse uses internally (`LaunchManager.LOCAL_LAUNCH_CONFIGURATION_CONTAINER_PATH`).

Accessible via:
- `ILaunchConfiguration.getAttributes()` — returns deserialized `Map<String, Object>`
  with proper types (String, Boolean, Integer, List, Map). Richer than parsing XML.
- `jdt launch config <name>` — full attributes as JSON (default)
- `jdt launch config <name> --xml` — raw .launch XML content

## Launch lifecycle

```
[saved .launch file] --jdt launch run--> [ILaunch: running] --terminates--> [ILaunch: terminated]
                                               |                                    |
                                         jdt launch list                    jdt launch list
                                         jdt launch logs -f                 jdt launch logs
                                         jdt launch stop                    jdt launch clear
```

Key lifecycle facts:
- **ILaunch is session-scoped.** Restarting Eclipse clears all ILaunch objects.
- **LaunchTracker survives ILaunch removal.** Even after `jdt launch clear`,
  tracker retains console output until plugin restarts.
- **Console output is in-memory.** LaunchTracker accumulates stdout/stderr
  in StringBuilder buffers. No persistence across Eclipse restarts.
- **Launch configs are persistent.** Saved as .launch files on disk.
  Survive Eclipse restarts. Managed via Run > Run Configurations dialog.

## Launch history and ordering

`jdt launch configs` uses Eclipse's internal launch history API
(`DebugUIPlugin.getLaunchConfigurationManager()`) to sort results:

1. **Favorites** (Run favorites, then Debug favorites)
2. **Recent history** (Run history, then Debug history)
3. **Remaining configs** (alphabetical)

This matches the order in Eclipse's Run > Run History menu.
The API is `@SuppressWarnings("restriction")` — internal Eclipse API,
but stable across versions.

## Relationship to `jdt test`

Test launches are a special case of the launch system:

- `jdt test run` creates a JUnit/PDE launch configuration and launches it
- The resulting ILaunch appears in `jdt launch list`
- Console output is accessible via `jdt launch logs`
- Test-specific progress is tracked separately by `TestSessionTracker`

Test launch configs created by `jdt test run` use timestamped names
(e.g. `SearchIntegrationTest-1775075114208`) and are saved to disk.
This is a known issue — configs accumulate. Future improvement: reuse
configs with stable names (see Known Issues).

## Known issues

### Launch config accumulation from `jdt test run`

Each `jdt test run` creates a new ILaunchConfigurationWorkingCopy with a
unique timestamped name. Eclipse's `wc.launch()` persists it as a .launch file.
Over time, workspace accumulates stale test configs.

Planned fix: use stable names (just the class/package/project name without
timestamp) and clean up the previous terminated ILaunch before creating a new one.

### Maven prerequisite in `jdt setup --skip-build`

`jdt setup --skip-build` checks for Maven even though it doesn't invoke it.
This blocks installation in environments without Maven (e.g. Docker sandbox
where Maven runs via Eclipse launch config). The prerequisites check should
be aware of `--skip-build` and skip the Maven requirement.

## Design constraints

1. **Eclipse must be running.** All launch operations go through the running
   Eclipse instance via HTTP. No headless mode.

2. **Single workspace.** Launch configs are workspace-scoped. The bridge
   serves one workspace per Eclipse instance.

3. **No cross-instance launches.** Cannot launch a config from one Eclipse
   instance in another.

4. **Console buffer limits.** LaunchTracker uses unbounded StringBuilders.
   Very long-running processes (hours of output) may consume significant memory.

5. **Path conversion.** In Docker sandbox mode, Windows paths from Eclipse
   are converted to Linux paths via `toSandboxPath()`. This applies to
   `file` fields in JSON output.
