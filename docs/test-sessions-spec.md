# Test Sessions — Design Spec

## Problem

`jdt test` blocks until all tests finish, returns a flat summary, and provides
zero progress visibility. On large projects (150+ tests, 30+ classes, suites
with deep nesting) this means:

1. **Blocking** — the model/user can't do anything else while tests run
2. **No progress** — no indication which tests passed, which failed, how far along
3. **No structure** — suites, parameterized tests, class grouping all flattened
4. **Lost information** — Eclipse has real-time test progress via `TestRunListener`
   but `ResultCollector` only uses `sessionFinished`, discarding all intermediate events

## Eclipse JUnit View — features to replicate

Based on the Eclipse JUnit View UI (screenshots reviewed):

| Eclipse UI element | Description |
|---|---|
| Progress bar + `Runs: 3/11` | Real-time counter as tests complete |
| Test tree with icons (pass/fail/ignored) | Hierarchical: suite → class → method → parameterized instance |
| Failure Trace panel | Stack trace for selected failing test |
| Java Stack Trace Console | Console output linked to the test run |
| "Show Failures Only" toggle | Filter tree to failures |
| "Show Skipped Tests Only" toggle | Filter tree to ignored/skipped |
| History dropdown | Previous test runs |
| Re-run / Re-run failures | Re-execute tests |
| Timing per test | `(0.005 s)` next to each node |

### Test tree nesting (observed)

```
IntegrationTestSuite [Runner: JUnit 5]                   ← ITestRunSession
  ├── DeviceTokenLoginHandlerTest                        ← ITestSuiteElement (class)
  │     ├── testLogin ✓                                  ← ITestCaseElement
  │     └── testLogout ✓
  ├── EventHandlerTest
  │     ├── testProcess(Arguments)                       ← ITestSuiteElement (parameterized)
  │     │     ├── [1] payload=valid ✓                    ← ITestCaseElement
  │     │     └── [2] payload=null ✗
  │     └── testSimple ✓
  └── ...

UrlValidatorTest [Runner: JUnit 5]
  ├── testInvalidUrls(String)                            ← parameterized group
  │     ├── [1] url=example.com ✓
  │     ├── [9] url=null ✓                               ← @NullAndEmptySource
  │     └── [10] url= ✓
  └── testValidUrls(String)
        ├── [1] url=example.com/video/abc123... ✓
        └── ...
```

Up to 4 levels deep: session → suite → class → parameterized method → instance.

## Eclipse APIs used

### TestRunListener (already imported, underused)

```java
sessionLaunched(ITestRunSession session)      // session created, tree not ready
sessionStarted(ITestRunSession session)       // tree available, tests about to run
testCaseStarted(ITestCaseElement testCase)    // individual test starting
testCaseFinished(ITestCaseElement testCase)   // individual test done (result + trace)
sessionFinished(ITestRunSession session)      // all done
```

Currently only `sessionFinished` is used. All other events are discarded.

### ITestElement data available per test case

```java
tc.getTestClassName()          // "com.example.util.SerializerTest"
tc.getTestMethodName()         // "testValidation" or "[5] url=..."
tc.getTestResult(false)        // OK, FAILURE, ERROR, IGNORED
tc.getElapsedTimeInSeconds()   // 9.214
tc.getFailureTrace()           // FailureTrace with:
  .getTrace()                  //   full stack trace
  .getExpected()               //   expected value (for assertEquals)
  .getActual()                 //   actual value (for assertEquals)
tc.getParentContainer()        // ITestSuiteElement (class or param group)
```

### Link between test session and launch

`TestHandler` (line 93-113) creates a launch that is automatically tracked:

```java
String configName = "jdtbridge-test-" + System.currentTimeMillis();
ILaunch launch = new Launch(wc, ILaunchManager.RUN_MODE, null);
manager.addLaunch(launch);   // LaunchTracker picks this up
```

The config name is shared between:
- `ITestRunSession.getTestRunName()` — test session identity
- `launch.getLaunchConfiguration().getName()` — launch identity (console access)

This means `jdt launch logs <name>` already works for test launches.
Console output survives in `LaunchTracker` (StringBuilder buffers) but Eclipse's
`IStreamMonitor` buffers may be GC'd for old terminated processes.

## New CLI commands

### `jdt test run`

Launch tests non-blocking. Analogous to `jdt launch run`.

```
jdt test run <FQN>                          class
jdt test run <FQN>#method                   method
jdt test run --project <name>               project
jdt test run --project <name> --package <pkg>   package

Flags:
  -f, --follow    stream test status until completion
  -q, --quiet     suppress onboarding guide
  --all           include passed tests in output (with -f)
  --timeout <s>   test run timeout in seconds (default: 300)
```

### `jdt test status`

View test progress (snapshot or stream). Works for both running and completed sessions.

```
jdt test status <launch-name>

Flags:
  -f, --follow    stream events live until completion
  --all           show all tests (default: failures only)
  --ignored       show only ignored/skipped tests
```

### `jdt test sessions`

List active and completed test sessions.

```
jdt test sessions
```

### Reused from launch system (no new code)

```
jdt launch logs <launch-name>              console output (stdout/stderr)
jdt launch logs <launch-name> -f           stream console live
jdt launch stop <launch-name>              abort running tests
jdt launch clear <launch-name>             remove terminated session
```

## CLI output — all scenarios

### `jdt test run SerializerTest`

```
Started test run: SerializerTest (11 tests)
  Launch:   jdtbridge-test-1774552295107
  Project:  my-project
  Runner:   JUnit 5

  Test status (snapshot or live stream):
    jdt test status jdtbridge-test-1774552295107
    jdt test status jdtbridge-test-1774552295107 -f
    jdt test status jdtbridge-test-1774552295107 --all

  Console output:
    jdt launch logs jdtbridge-test-1774552295107
    jdt launch logs jdtbridge-test-1774552295107 -f

  Manage:
    jdt test sessions
    jdt launch stop jdtbridge-test-1774552295107
    jdt launch clear jdtbridge-test-1774552295107

  Navigate to failing test (FQMNs from status output):
    jdt source <FQMN>
    jdt test run <FQMN> -f

  Add -q to suppress this guide.
```

### `jdt test run SerializerTest -q`

```
Started test run: SerializerTest (11 tests)
  Launch:   jdtbridge-test-1774552295107
  Project:  my-project
  Runner:   JUnit 5
```

### `jdt test run SerializerTest -f`

```
Started test run: SerializerTest (11 tests)
  Launch:   jdtbridge-test-1774552295107
  Project:  my-project
  Runner:   JUnit 5

FAIL [M] `com.example.util.SerializerTest#testValidation` (9.214s)
  AssertionError: No setter found for: [com.example.model.OrderAction.getId]
  at com.example.util.SerializerTest.testValidation(SerializerTest.java:254)

11 tests, 10 passed, 1 failed, 9.6s
```

### `jdt test run SerializerTest -f --all`

```
Started test run: SerializerTest (11 tests)
  Launch:   jdtbridge-test-1774552295107
  Project:  my-project
  Runner:   JUnit 5

PASS [M] `com.example.util.SerializerTest#testList` (0.000s)
PASS [M] `com.example.util.SerializerTest#testDate` (0.004s)
PASS [M] `com.example.util.SerializerTest#testCreate` (0.205s)
PASS [M] `com.example.util.SerializerTest#testException` (0.011s)
PASS [M] `com.example.util.SerializerTest#testTypeInfo` (0.057s)
PASS [M] `com.example.util.SerializerTest#testParse` (0.007s)
PASS [M] `com.example.util.SerializerTest#testRaw` (0.007s)
PASS [M] `com.example.util.SerializerTest#testRawExtra` (0.005s)
FAIL [M] `com.example.util.SerializerTest#testValidation` (9.214s)
  AssertionError: No setter found for: [com.example.model.OrderAction.getId]
  at com.example.util.SerializerTest.testValidation(SerializerTest.java:254)
PASS [M] `com.example.util.SerializerTest#testModel` (0.049s)
PASS [M] `com.example.util.SerializerTest#testLocation` (0.040s)

11 tests, 10 passed, 1 failed, 9.6s
```

### `jdt test status <id>` — snapshot while running

```
my-project — 87/150 (running), 85 passed, 2 failed, 23.1s

FAIL [M] `com.example.dao.UserDaoTest#testUpdateRequired` (0.3s)
  Expected 3 but was 2
  at UserDaoTestCase.java:142

FAIL [M] `com.example.web.OrderControllerTest#testUpdateOrder` (0.1s)
  NullPointerException
  at OrderRestControllerTest.java:89
```

### `jdt test status <id>` — snapshot after completion

```
my-project — 150/150 (finished), 145 passed, 3 failed, 1 error, 1 ignored, 45.2s

FAIL [M] `com.example.dao.UserDaoTest#testUpdateRequired` (0.3s)
  Expected 3 but was 2
  at UserDaoTestCase.java:142

FAIL [M] `com.example.web.OrderControllerTest#testUpdateOrder` (0.1s)
  NullPointerException
  at OrderRestControllerTest.java:89

ERROR [M] `com.example.web.AuthControllerTest#testLoginExpired` (0.2s)
  IllegalStateException: Session expired
  at AuthControllerTestCase.java:55
```

### `jdt test status <id>` — stopped mid-run

```
my-project — 87/150 (stopped), 85 passed, 2 failed, 23.1s

FAIL [M] `com.example.dao.UserDaoTest#testUpdateRequired` (0.3s)
  ...
```

### `jdt test status <id> -f` — stream (attach to running or dump completed)

```
FAIL [M] `com.example.web.OrderControllerTest#testUpdateOrder` (0.1s)
  NullPointerException
  at OrderRestControllerTest.java:89

ERROR [M] `com.example.web.AuthControllerTest#testLoginExpired` (0.2s)
  IllegalStateException: Session expired
  at AuthControllerTestCase.java:55

150/150, 145 passed, 3 failed, 1 error, 1 ignored, 45.2s
```

Only events not yet seen are streamed. Summary at end. If session already
completed, dumps all failures and exits immediately.

### `jdt test status <id> --ignored`

```
IGNORED [M] `com.example.websocket.StateMachineListenerTest#testTimeout`
IGNORED [M] `com.example.websocket.StateMachineListenerTest#testCleanup`
IGNORED [M] `com.example.websocket.StateMachineTimeoutsTest#testIdleTimeout`
...

122 tests, 115 passed, 7 ignored, 7.5s
```

### `jdt test status <id> --all` — suite with class grouping

```
DeviceTokenLoginHandlerTest                      2 passed                0.15s
DeviceTokenLogoutHandlerTest                     2 passed                0.08s
FilterPredicateTest                              4 passed, 2 ignored     0.30s
StateMachineListenerIntegrationTest              0 passed, 3 ignored     0.00s
EventHandlerTest                                 8 passed                1.20s
...

122 tests, 115 passed, 7 ignored, 7.5s
```

Class-level summary for passed classes. Classes with failures expand to show
individual failing tests:

```
DeviceTokenLoginHandlerTest                      2 passed                0.15s
EventHandlerTest                                 7 passed, 1 failed      1.20s
  FAIL [M] `...EventHandlerTest#testProcess(Arguments)[3]` (0.1s)
    Expected: REJECTED
    Actual: ACCEPTED
    at EventHandlerTest.java:89
...

122 tests, 113 passed, 2 failed, 7 ignored, 7.8s
```

### `jdt test status <id> --all` — parameterized test with failures

```
UrlValidatorTest                                20 passed, 1 failed      0.08s
  FAIL [M] `...UrlValidatorTest#testInvalidUrls(String)[5]` (0.003s)
    url=example.com/channel/abc456
    Expected: false
    Actual: true
    at UrlValidatorTest.java:52

21 tests, 20 passed, 1 failed, 0.1s
```

### `jdt test sessions`

```
jdtbridge-test-1774552295107  my-project         150 tests  145 passed, 3 failed  45.2s  finished
jdtbridge-test-1774550888398  SerializerTest    11 tests   10 passed, 1 failed   9.6s  finished
jdtbridge-test-1774553127000  IntegrationTestSuite 122 tests  115 passed, 7 ign     7.5s  finished
jdtbridge-test-1774554000000  my-project          150 tests   running (87/150)     23.1s  running
```

## Plugin-side protocol (JSONL over HTTP stream)

### Endpoint: `/test/run`

Request: `GET /test/run?class=<FQN>[&method=<name>][&project=<name>][&package=<pkg>][&timeout=300]`

Response (immediate JSON):
```json
{
  "ok": true,
  "session": "jdtbridge-test-1774552295107",
  "project": "my-project",
  "runner": "JUnit 5",
  "total": 11
}
```

### Endpoint: `/test/status/stream`

Request: `GET /test/status/stream?session=<name>[&filter=failures|ignored|all]`

Response (JSONL, one event per line, streamed):
```jsonl
{"event":"started","session":"jdtbridge-test-1774552295107","total":11}
{"event":"case","fqmn":"com.example.util.SerializerTest#testList","status":"PASS","time":0.000}
{"event":"case","fqmn":"com.example.util.SerializerTest#testValidation","status":"FAIL","time":9.214,"trace":"AssertionError: ...","expected":"...","actual":"...","class":"com.example.util.SerializerTest","suite":"SerializerTest"}
{"event":"finished","total":11,"passed":10,"failed":1,"errors":0,"ignored":0,"time":9.6}
```

Each `case` event includes:
- `fqmn` — `Class#method` format, copy-pasteable to `jdt source` / `jdt test`
- `status` — `PASS`, `FAIL`, `ERROR`, `IGNORED`
- `time` — elapsed seconds
- `trace` — stack trace (only for FAIL/ERROR)
- `expected`, `actual` — comparison values (only for assertEquals failures)
- `class` — test class name (for grouping)
- `suite` — parent suite name (if applicable)

### Endpoint: `/test/status`

Request: `GET /test/status?session=<name>`

Response (JSON snapshot):
```json
{
  "session": "jdtbridge-test-1774552295107",
  "label": "SerializerTest",
  "state": "running|finished|stopped",
  "total": 11,
  "completed": 5,
  "passed": 4,
  "failed": 1,
  "errors": 0,
  "ignored": 0,
  "time": 4.2,
  "failures": [
    {
      "fqmn": "com.example.util.SerializerTest#testValidation",
      "status": "FAIL",
      "time": 9.214,
      "trace": "...",
      "expected": "...",
      "actual": "..."
    }
  ]
}
```

### Endpoint: `/test/sessions`

Request: `GET /test/sessions`

Response (JSON array):
```json
[
  {
    "session": "jdtbridge-test-1774552295107",
    "label": "SerializerTest",
    "state": "finished",
    "total": 11,
    "passed": 10,
    "failed": 1,
    "errors": 0,
    "ignored": 0,
    "time": 9.6
  }
]
```

## Architecture (implemented)

### Plugin classes (SRP: tracker + handler, like LaunchTracker + LaunchHandler)

| Class | Role |
|---|---|
| `TestSessionTracker` | Listener + repository. Extends `TestRunListener`, accumulates events in `TrackedTestSession` |
| `TestSessionHandler` | HTTP handlers. Reads from tracker, serializes JSON for `/test/status` and `/test/sessions` |
| `TestProgressStreamer` | JSONL streamer. Writes accumulated + live events to HTTP socket for `/test/status/stream` |
| `TestHandler` | Launch preparation. `prepareLaunch()` record shared logic, `handleTestRun()` non-blocking |

### CLI modules

| Module | Role |
|---|---|
| `commands/test-run.mjs` | `jdt test run` — launch + onboarding/streaming |
| `commands/test-status.mjs` | `jdt test status` — snapshot/stream |
| `commands/test-sessions.mjs` | `jdt test sessions` — list |
| `format/test-status.mjs` | Formatter — FQMN `#`, `[M]` badges, traces |
| `client.mjs: getStreamLines()` | Shared JSONL streaming (reused by test-run and test-status) |

## FQMN format conventions

Test output follows `jdt source` V2 conventions (see `docs/source-output-brainstorm.md`):

1. **Zero-Modification Navigation** — every FQMN is a valid argument for
   `jdt source` and `jdt test run`
2. **Badge-Link Separation** — `[M]` prefix is visual, not part of FQMN
3. **Full Qualification** — never truncate packages
4. **`#` separator** — `Class#method`

Example:
```
FAIL [M] `com.example.OrderServiceTest#testCalculateTotal` (0.3s)
```
- Copy FQMN → `jdt source com.example.OrderServiceTest#testCalculateTotal`
- Copy FQMN → `jdt test run com.example.OrderServiceTest#testCalculateTotal -f`

## Filtering

Maps Eclipse JUnit View toolbar toggles:

| Eclipse toggle | CLI flag | What's shown |
|---|---|---|
| (default view) | `--all` | All tests, grouped by class |
| Show Failures Only | *(default)* | Only FAIL + ERROR tests |
| Show Skipped Tests Only | `--ignored` | Only IGNORED tests |

Summary line is always shown regardless of filter.
Filters apply to both snapshot and `-f` stream modes — in stream mode,
events not matching the filter are suppressed.

## Status

Implemented and verified. Legacy blocking `jdt test <FQN>` removed.
See commit history on `feature/test-sessions` branch.
