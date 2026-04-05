# Bridge Test Protocol — Design Spec

Server-side test infrastructure: HTTP endpoints, JSONL streaming,
Eclipse JUnit integration. CLI spec: [jdt-test-spec](jdt-test-spec.md).

## Data source: JUnitModel

All test data comes from Eclipse's built-in `JUnitCorePlugin.getModel()` —
single source of truth, same data as the Eclipse JUnit view.

Key `TestRunSession` API:
- `getTestRunName()` — config name (= configId)
- `getLaunch()` → `ILaunch` → PID, `ATTR_LAUNCH_TIMESTAMP`
- `isRunning()`, `isStarting()`
- `getTotalCount()`, `getStartedCount()`
- `getErrorCount()`, `getFailureCount()`, `getIgnoredCount()`
- `getElapsedTimeInSeconds()`
- `getChildren()` — full test tree (suites → cases)
- `addTestSessionListener(ITestSessionListener)` — live events

Capacity controlled by `MAX_TEST_RUNS` preference (default 10).
Old runs evicted automatically.

## testRunId composition

```
testRunId = configId + ":" + ATTR_LAUNCH_TIMESTAMP
```

`ATTR_LAUNCH_TIMESTAMP` is set by Eclipse on `ILaunch` at the moment
`config.launch()` is called — available immediately, no waiting.
Different from `TestRunSession.getStartTime()` which is set later
when the JUnit remote runner connects.

`TestSessionHandler.testRunId(session)` — single method that extracts
testRunId from any `TestRunSession` via its `ILaunch`.

## HTTP endpoints

### `GET /test/run`

Launch tests non-blocking.

Query params:
- `class` — FQN of test class
- `method` — test method name (optional)
- `project` — project name (optional, for classpath resolution)
- `package` — package name (with `project`, no `class`)
- `no-refresh` — skip workspace refresh before launch

Response:
```json
{
  "ok": true,
  "configId": "HttpServerBindTest",
  "launchId": "HttpServerBindTest:6408",
  "testRunId": "HttpServerBindTest:1775393835096",
  "reused": true,
  "project": "io.github.kaluchi.jdtbridge.tests",
  "runner": "JUnit 6",
  "pid": "6408"
}
```

### `GET /test/status`

Snapshot of test run state.

Query params:
- `testRunId` — required (exact testRunId or plain configId)
- `filter` — `failures` (default), `all`, `ignored`

Response:
```json
{
  "configId": "HttpServerBindTest",
  "testRunId": "HttpServerBindTest:1775393835096",
  "project": "io.github.kaluchi.jdtbridge.tests",
  "state": "finished",
  "total": 13,
  "completed": 13,
  "passed": 13,
  "failed": 0,
  "errors": 0,
  "ignored": 0,
  "time": 0.0,
  "entries": [
    {
      "fqmn": "pkg.MyTest#testMethod",
      "status": "FAIL",
      "time": 0.123,
      "trace": "java.lang.AssertionError: ...",
      "expected": "3",
      "actual": "2"
    }
  ]
}
```

`entries` filtered by `filter` param. `state`: `running`, `starting`,
or `finished`. `testRunId` accepts plain `configId` — resolves to
most recent run.

### `GET /test/status/stream`

JSONL stream of test events. Blocks until session finishes.

Query params:
- `testRunId` — required
- `filter` — `failures` (default), `all`, `ignored`

Waits up to 10 seconds for session to appear in JUnitModel before
returning 404. Replays already-completed results first, then streams
live events via `ITestSessionListener`.

Events (one JSON object per line):
```jsonl
{"event":"case","fqmn":"pkg.MyTest#testMethod","status":"PASS","time":0.001,"suite":"MyTestSuite"}
{"event":"case","fqmn":"pkg.MyTest#testFailing","status":"FAIL","time":0.123,"trace":"...","expected":"3","actual":"2"}
{"event":"case","fqmn":"pkg.MyTest#testError","status":"ERROR","time":0.050,"trace":"NullPointerException..."}
{"event":"case","fqmn":"pkg.MyTest#testSkipped","status":"IGNORED"}
```

`finished` event is NOT sent by the streamer — the stream simply
closes when the session ends. The CLI synthesizes the summary from
accumulated counts.

Case event fields:
- `fqmn` — `Class#method`, copy-pasteable to `jdt source` / `jdt test run`
- `status` — `PASS`, `FAIL`, `ERROR`, `IGNORED`
- `time` — elapsed seconds
- `trace` — stack trace (FAIL/ERROR only)
- `expected`, `actual` — comparison values (assertEquals failures only)
- `suite` — parent suite name (if applicable)

### `GET /test/sessions`

List all test runs in JUnitModel.

Query params: none. Filtered by `ProjectScope`.

Response:
```json
[
  {
    "configId": "HttpServerBindTest",
    "testRunId": "HttpServerBindTest:1775393835096",
    "launchId": "HttpServerBindTest:6408",
    "state": "finished",
    "total": 13,
    "completed": 13,
    "passed": 13,
    "failed": 0,
    "errors": 0,
    "ignored": 0,
    "time": 0.0,
    "startedAt": 1775393835096
  }
]
```

### `GET /test/clear`

Remove terminated test sessions from JUnitModel.

Query params:
- `testRunId` — optional (specific session, or all terminated)

Response: `{"removed": 3}`

## Config reuse

Repeated `jdt test run` for the same class must reuse the existing
launch configuration, not create duplicates. Config names are stable
(no timestamps) — one config per unique test target.

### Eclipse's approach

Eclipse GUI "Run As > JUnit Test" reuses configs via
`JUnitLaunchShortcut#performLaunch`:

1. Create a temporary WorkingCopy with desired attributes
2. Search all existing configs of the same launch type
3. Compare 4 key attributes: `PROJECT_NAME`, `TEST_CONTAINER`,
   `MAIN_TYPE_NAME`, `TEST_NAME`
4. Match found → launch existing config
5. No match → save WorkingCopy as new config, then launch

### Our implementation

Same 4-attribute search. No dependency on `org.eclipse.jdt.junit` UI
bundle (avoids OSGi classloader issues).

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

Config naming (stable, no timestamp):

| Scope | Config name |
|---|---|
| Class | `FooTest` (simple name) |
| Class#method | `FooTest.methodName` |
| Package | `com.example.service` |
| Project | `my-server` |

## Launch type detection

Two dimensions: launch type (JUnit vs PDE JUnit) and runner version
(JUnit 4/5/6).

### Launch type: JUnit vs PDE JUnit

If the target project has `PluginNature` and the PDE JUnit launch type
is installed → PDE JUnit. Otherwise → plain JUnit.

PDE JUnit config adds OSGi-specific attributes:
- `run_in_ui_thread=false` — headless, no workbench
- `application` = `org.eclipse.pde.junit.runtime.coretestapplication`
- `automaticAdd=false` — target platform bundles only (avoids
  validation errors from non-OSGi workspace projects)
- `clearws=true` — fresh temp workspace per run

### Runner version: JUnit 4 / 5 / 6

Eclipse uses `TEST_KIND` attribute to select the correct test runner
loader. Wrong kind = tests not discovered.

Detection algorithm (`detectTestKind`):

1. Find JUnit Platform marker type on project classpath:
   `org.junit.platform.commons.annotation.Testable` or
   `org.junit.platform.suite.api.Suite`
2. Resolve JAR version of `junit-platform-commons` or
   `junit-platform-suite-api`:
   - Parse version from JAR filename (`junit-platform-commons-6.0.jar`)
   - Fallback: read `Specification-Version` from JAR manifest
3. Map major version:
   - Major 6 → `org.eclipse.jdt.junit.loader.junit6`
   - Major 1 → `org.eclipse.jdt.junit.loader.junit5`
     (JUnit 5 platform uses version 1.x)
4. Additional version disambiguation: exclude JUnit container paths
   belonging to other versions (e.g. if JUnit 6 JAR found, verify
   it's not on the JUnit 5 container path)
5. Fallback: if marker type not found but `org.junit.jupiter.api.Test`
   exists on classpath → JUnit 5
6. Default: JUnit 4

## Plugin classes

| Class | Responsibility |
|---|---|
| `TestHandler` | Launch preparation: `prepareLaunch()` with config reuse, `handleTestRun()` non-blocking launch |
| `TestSessionHandler` | Query API: reads from JUnitModel, handles `/test/status`, `/test/sessions`, `/test/clear` |
| `TestProgressStreamer` | JSONL streamer: attaches `ITestSessionListener` to `TestRunSession`, replays + streams live events |

## Constraints

- **JUnitModel is internal API.** `@SuppressWarnings("restriction")`.
  Stable across Eclipse versions.

- **Run limit.** Eclipse caps at `MAX_TEST_RUNS` (default 10). Old
  runs evicted automatically.

- **PDE test workspace.** `clearws=true` ensures clean workspace per
  run. Tests run in a separate Eclipse runtime with workspace bundles.

- **Streaming replay.** `TestProgressStreamer` replays completed
  results from `getChildren()` tree, then attaches listener for live
  events. Skips `UNDEFINED` results (not yet finished).

## Relationship to other specs

- **[jdt-test-spec](jdt-test-spec.md)** — CLI commands, UX, output
  format, design decisions.
- **[jdt-launch-spec](jdt-launch-spec.md)** — test launches appear
  in `launch list`, console via `launch logs`.
