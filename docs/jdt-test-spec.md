# jdt test — Design Spec

## Overview

`jdt test` runs and monitors JUnit tests through Eclipse's built-in
test infrastructure. It wraps Eclipse's `JUnitModel` and launch system
into CLI commands that let agents and terminal users run tests, stream
progress, and inspect results.

## Commands

### `jdt test run <FQN>[#method] [--project <name>] [-f] [-q]`
### `jdt test run --project <name> [--package <pkg>] [-f] [-q]`

Launch tests by FQN — class, method, package, or project scope.
Non-blocking by default. With `-f`, streams test progress until done.

Server response includes `reused: true/false` to indicate whether
an existing launch configuration was reused or a new one was created.

### `jdt test runs [--json]`

List test runs (youngest first). Replaces `test sessions`.

### `jdt test status <testRunId> [-f] [--all] [--ignored]`

Show test run progress/results. Snapshot or live stream.

## Config reuse

### Problem

Previous implementation created a new launch configuration on every
`jdt test run` with a timestamped name (`FooTest-1775081500555`).
This polluted `jdt launch configs` with hundreds of stale entries.

### Eclipse's approach

Eclipse GUI "Run As > JUnit Test" reuses configs via
`JUnitLaunchShortcut#performLaunch`:

1. Create a temporary WorkingCopy with desired attributes
2. Search all existing configs of the same launch type
3. Compare 4 key attributes: `PROJECT_NAME`, `TEST_CONTAINER`,
   `MAIN_TYPE_NAME`, `TEST_NAME`
4. If match found — launch existing config
5. If no match — save WorkingCopy as new config, then launch

Source: `org.eclipse.jdt.junit.launcher.JUnitLaunchShortcut`
(public API since 3.3, `org.eclipse.jdt.junit` bundle).

### Our implementation

Port the 4-attribute search algorithm. No dependency on
`org.eclipse.jdt.junit` UI bundle — avoids OSGi classloader issues
with subclassing across bundles.

```java
private ILaunchConfiguration findExistingConfig(
        ILaunchConfigurationWorkingCopy wc) throws CoreException {
    String[] keys = {
        ATTR_PROJECT_NAME, ATTR_TEST_CONTAINER,
        ATTR_MAIN_TYPE_NAME, ATTR_TEST_NAME,
    };
    for (ILaunchConfiguration config
            : launchManager().getLaunchConfigurations(wc.getType())) {
        if (hasSameAttributes(config, wc, keys))
            return config;
    }
    return null;
}
```

Flow:
1. Create WorkingCopy with stable name (no timestamp)
2. `findExistingConfig(wc)` — search by 4 attributes
3. Found → launch existing config (`reused: true`)
4. Not found → `wc.doSave()`, launch new (`reused: false`)

### Config naming

Stable names without timestamp, matching Eclipse GUI convention:

| Scope | Config name |
|---|---|
| Class | `FooTest` (simple name) |
| Class#method | `FooTest.methodName` |
| Package | `com.example.service` |
| Project | `my-server` |

## Data source: JUnitModel

### Problem with custom tracker

`TestSessionTracker` used a `ConcurrentHashMap` keyed by config name.
Multiple runs of the same config overwrote each other — 3 runs of
`my-project` showed as 1 entry. Eclipse JUnit view showed all 3.

### Eclipse's data source

`JUnitCorePlugin.getModel().getTestRunSessions()` — `LinkedList`,
youngest first. Each run is a separate entry. Same data as the
Eclipse JUnit view dropdown.

Key `TestRunSession` API:
- `getTestRunName()` — config name (= configId)
- `getLaunch()` → `ILaunch` → PID, ATTR_LAUNCH_TIMESTAMP
- `getLaunch().getAttribute(ATTR_LAUNCH_TIMESTAMP)` — source of testRunId
- `isRunning()`, `isStarting()`
- `getTotalCount()`, `getStartedCount()`
- `getErrorCount()`, `getFailureCount()`, `getIgnoredCount()`
- `getElapsedTimeInSeconds()`
- `getTestResult()` — OK, ERROR, FAILURE
- `getChildren()` — full test tree (suites → cases)
- `addTestSessionListener(ITestSessionListener)` — live events

Capacity controlled by `MAX_TEST_RUNS` preference (default 10).

### testRunId composition

`testRunId = configId + ":" + ATTR_LAUNCH_TIMESTAMP`

`ATTR_LAUNCH_TIMESTAMP` is set by Eclipse on `ILaunch` at the moment
`config.launch()` is called — available immediately, no waiting.
This is different from `TestRunSession.getStartTime()` which is set
later when the JUnit remote runner connects.

`TestSessionHandler.testRunId(session)` — single method that extracts
testRunId from any `TestRunSession` via its `ILaunch`.

### Implementation

All test handlers read from `JUnitModel` directly:
- `test runs` → `getTestRunSessions()` — list with counts, state, time
- `test status` → `findSession(testRunId)` → snapshot from session tree
- `test run -f` → `ITestSessionListener` on `TestRunSession` for live events
- `test run` response waits for nothing — testRunId from launch timestamp

No custom tracker. All data from Eclipse `JUnitModel` and
`ITestSessionListener`.

## Design decisions

- **FQN in, IDs out.** `jdt test run` accepts what the agent knows
  (class name) and returns what the system needs (testRunId, launchId).
  The agent never manages configs directly.

- **Config reuse by attributes, not name.** Two projects can have a
  class with the same name. Eclipse compares project + class + method +
  container — we do the same.

- **`reused` flag in response.** Tells the agent if a new config was
  created. Guide can show `launch config <configId>` only on first run.

- **Read from JUnitModel, not custom tracker.** Single source of truth.
  Same data as Eclipse JUnit view. No deduplication bugs.

- **`test runs` not `test sessions`.** "Run" is what the user did.
  "Session" is an Eclipse internal term with no meaning to agents.

- **configId as primary UX.** `test status` and `test runs` accept
  plain configId — resolves to the most recent run, silently. Exact
  testRunId is for advanced disambiguation. No warnings on ambiguity,
  no `--latest` flag. Humans type configId, machines use exact IDs.

## Constraints

- **JUnitModel is internal API.** `@SuppressWarnings("restriction")`.
  Stable across Eclipse versions.

- **Run limit.** Eclipse caps at MAX_TEST_RUNS (default 10). Old runs
  evicted automatically. `jdt test runs` shows only what Eclipse keeps.

- **PDE JUnit Plug-in Tests.** Additional config attributes
  (run_in_ui_thread, application, clearws, location). Config reuse
  preserves them. `clearws=true` ensures clean workspace per run.

- **Streaming.** Uses `ITestSessionListener` on `TestRunSession` for
  live `testEnded` events. Replay from `getChildren()` for already
  completed tests. Session found via `findSession(testRunId)` which
  matches by `ATTR_LAUNCH_TIMESTAMP` on `ILaunch`.

## Relationship to other specs

- **[jdt-launch-spec.md](jdt-launch-spec.md)** — test launches appear
  in `launch list`, console via `launch logs`.
- **[jdt-status-spec.md](jdt-status-spec.md)** — `test runs` feeds
  the tests section in `jdt status`.
