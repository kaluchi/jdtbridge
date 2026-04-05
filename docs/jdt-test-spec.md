# jdt test — Design Spec

## Overview

`jdt test` runs and monitors JUnit tests through Eclipse's built-in
test infrastructure. Non-blocking by default — launches return
immediately with IDs for status polling and streaming. Server-side
protocol: [bridge-test-spec](bridge-test-spec.md).

## Commands

### `jdt test run <FQN>[#method] [--project <name>] [-f] [-q] [--json]`
### `jdt test run --project <name> [--package <pkg>] [-f] [-q] [--json]`

Launch tests by FQN — class, method, package, or project scope.
Non-blocking by default. With `-f`, streams test progress until done.

Flags:
- `--project <name>` — override project for classpath resolution
- `-f, --follow` — stream test status (only failures by default)
- `-q, --quiet` — suppress onboarding guide
- `--all` — include passed tests in output (with `-f`)
- `--ignored` — show only ignored tests (with `-f`)
- `--json` — JSONL when streaming, JSON snapshot otherwise

Server response includes `reused: true/false` to indicate whether
an existing launch configuration was reused or a new one was created.

### `jdt test status <testRunId> [-f] [--all] [--ignored] [--json]`

Show test run progress/results. Snapshot or live stream.
Accepts exact `testRunId` or plain `configId` (resolves to most recent).

### `jdt test runs [--json]`

List test runs (youngest first).

## CLI output — all scenarios

### `jdt test run FQN -q` — launch, quiet

```
#### Test: HttpServerBindTest
TestRunId: `HttpServerBindTest:1775393835096`
LaunchId:  `HttpServerBindTest:6408`
ConfigId:  `HttpServerBindTest`
Config:    reused
Project:   `io.github.kaluchi.jdtbridge.tests`
Runner:    JUnit 6
```

### `jdt test run FQN` — launch with guide

```
#### Test: HttpServerBindTest
TestRunId: `HttpServerBindTest:1775393835096`
LaunchId:  `HttpServerBindTest:6408`
ConfigId:  `HttpServerBindTest`
Config:    reused
Project:   `io.github.kaluchi.jdtbridge.tests`
Runner:    JUnit 6

**Test status** (testRunId = HttpServerBindTest:1775393835096):
  `jdt test status HttpServerBindTest:1775393835096`            failures only (default)
  `jdt test status HttpServerBindTest:1775393835096 -f`         stream live until done
  `jdt test status HttpServerBindTest:1775393835096 --all`      all tests including passed
  `jdt test status HttpServerBindTest:1775393835096 --ignored`  only skipped/disabled tests

**Console output** (launchId = HttpServerBindTest:6408):
  `jdt launch logs HttpServerBindTest:6408`
  `jdt launch logs HttpServerBindTest:6408 --tail 50`

**Manage:**
  `jdt test runs`                          list test runs
  `jdt launch stop HttpServerBindTest:6408`            abort
  `jdt launch clear HttpServerBindTest:6408`           remove

**Navigate** — FQMNs from status output are copy-pasteable:
  `jdt source <FQMN>`                     view test source
  `jdt test run <FQMN> -f`                re-run single test

Add `-q` to suppress this guide.
```

### `jdt test run FQN -f` — stream, failures only (default)

```
#### Test: SerializerTest (11 tests)
TestRunId: `SerializerTest:1774552295107`
LaunchId:  `SerializerTest:29164`
ConfigId:  `SerializerTest`
Config:    reused
Project:   `my-project`
Runner:    JUnit 5

FAIL [M] `com.example.util.SerializerTest#testValidation` (9.214s)
  AssertionError: No setter found for: [com.example.model.OrderAction.getId]
  at com.example.util.SerializerTest.testValidation(SerializerTest.java:254)

11 tests, 10 passed, 1 failed, 9.6s
```

When all tests pass, only the summary line is shown (no individual
test lines in failures-only mode).

### `jdt test run FQN -f --all` — stream, all tests

```
#### Test: HttpServerBindTest (13 tests)
TestRunId: `HttpServerBindTest:1775393835096`
LaunchId:  `HttpServerBindTest:6408`
ConfigId:  `HttpServerBindTest`
Config:    reused
Project:   `io.github.kaluchi.jdtbridge.tests`
Runner:    JUnit 6

PASS [M] `io.github.kaluchi.jdtbridge.HttpServerBindTest.BindAddressAccessor#returnsAddressAfterStart` (0.000s)
PASS [M] `io.github.kaluchi.jdtbridge.HttpServerBindTest.BindAddressAccessor#returnsNullBeforeStart` (0.000s)
PASS [M] `io.github.kaluchi.jdtbridge.HttpServerBindTest.StartWithFixedPort#autoPortWhenZero` (0.000s)
...

13 tests, 13 passed, 0.0s
```

### `jdt test status <testRunId>` — snapshot, failures only

```
#### HttpServerBindTest — 13/13, 13 tests, 13 passed, 0.0s
```

With failures:

```
#### my-project — 87/150 (running), 150 tests, 85 passed, 2 failed, 23.1s

FAIL [M] `com.example.dao.UserDaoTest#testUpdateRequired` (0.3s)
  Expected: 3
  Actual: 2
  at UserDaoTestCase.java:142

FAIL [M] `com.example.web.OrderControllerTest#testUpdateOrder` (0.1s)
  NullPointerException
  at OrderRestControllerTest.java:89
```

### `jdt test status <testRunId> --all` — snapshot, all tests

```
#### my-project — 150/150, 150 tests, 145 passed, 3 failed, 1 error, 1 ignored, 45.2s

PASS [M] `com.example.dao.UserDaoTest#testCreate` (0.005s)
PASS [M] `com.example.dao.UserDaoTest#testRead` (0.004s)
FAIL [M] `com.example.dao.UserDaoTest#testUpdateRequired` (0.3s)
  Expected: 3
  Actual: 2
  at UserDaoTestCase.java:142
...
```

### `jdt test status <testRunId> --ignored` — only skipped

```
#### my-project — 122/122, 122 tests, 115 passed, 7 ignored, 7.5s

IGNORED [M] `com.example.websocket.StateMachineListenerTest#testTimeout`
IGNORED [M] `com.example.websocket.StateMachineListenerTest#testCleanup`
IGNORED [M] `com.example.websocket.StateMachineTimeoutsTest#testIdleTimeout`
```

### `jdt test runs` — list

```
TESTRUNID                         CONFIGID            TESTS  RESULT                  TIME  STATUS
HttpServerBindTest:1775393835096  HttpServerBindTest  13     13 passed                     finished 2m ago
SerializerTest:1774552295107      SerializerTest      11     10 passed, 1 failed     9.6s  finished 5m ago
my-project:1774554000000          my-project          150    running (87/150)        23.1s  running, started 30s ago
```

## Eclipse JUnit View — what we replicate

| Eclipse UI element | CLI equivalent |
|---|---|
| Progress bar + `Runs: 3/11` | Summary line: `87/150 (running)` |
| Test tree with pass/fail icons | Flat list: `PASS/FAIL [M]` + FQMN |
| Failure Trace panel | Inline trace after FAIL/ERROR entry |
| Console output | `jdt launch logs <launchId>` |
| "Show Failures Only" toggle | Default mode (no flag) |
| "Show Skipped Tests Only" toggle | `--ignored` flag |
| History dropdown | `jdt test runs` |
| Re-run / Re-run failures | `jdt test run <FQMN> -f` |
| Timing per test | `(0.005s)` after each entry |

## Filtering

Maps Eclipse JUnit View toolbar toggles:

| Eclipse toggle | CLI flag | What's shown |
|---|---|---|
| (default view) | `--all` | All tests, flat list |
| Show Failures Only | *(default)* | Only FAIL + ERROR tests |
| Show Skipped Tests Only | `--ignored` | Only IGNORED tests |

Summary line always shown regardless of filter.
Filters apply to both snapshot and `-f` stream modes.

## FQMN format conventions

Test output follows `jdt source` conventions
(see [jdt-source-spec](jdt-source-spec.md)):

1. **Zero-Modification Navigation** — every FQMN is a valid argument
   for `jdt source` and `jdt test run`
2. **Badge-Link Separation** — `[M]` prefix is visual, not part of FQMN
3. **Full Qualification** — never truncate packages
4. **`#` separator** — `Class#method`

Example:
```
FAIL [M] `com.example.OrderServiceTest#testCalculateTotal` (0.3s)
```
- Copy FQMN → `jdt source com.example.OrderServiceTest#testCalculateTotal`
- Copy FQMN → `jdt test run com.example.OrderServiceTest#testCalculateTotal -f`

Nested test classes use `.` for class nesting, `#` for method:
```
PASS [M] `pkg.OuterTest.InnerSuite#testMethod` (0.001s)
```

## Design decisions

- **FQN in, IDs out.** `jdt test run` accepts what the agent knows
  (class name) and returns what the system needs (testRunId, launchId).
  The agent never manages configs directly.

- **`test runs` not `test sessions`.** "Run" is what the user did.
  "Session" is an Eclipse internal term with no meaning to agents.

- **configId as primary UX.** `test status` and `test runs` accept
  plain configId — resolves to the most recent run, silently. Exact
  testRunId is for advanced disambiguation.

- **Flat output, not tree.** Eclipse JUnit view shows a tree
  (session → suite → class → method → parameterized instance, up to
  4 levels). CLI flattens to `FQMN` per test case. The tree hierarchy
  is encoded in the FQMN itself (`OuterClass.InnerClass#method`).

- **Failures by default.** In test output, what matters is what broke.
  `--all` is opt-in for verification. Same philosophy as Eclipse's
  "Show Failures Only" being one of the most-used toolbar toggles.

- **Trace truncation.** Stack traces capped at 10 lines with `...`.
  Full traces available via `jdt launch logs <launchId>` (console
  output includes complete stack traces from the test process).

## Relationship to other specs

- **[bridge-test-spec](bridge-test-spec.md)** — server-side HTTP
  endpoints, JSONL streaming protocol, Eclipse APIs, plugin classes.
- **[jdt-launch-spec](jdt-launch-spec.md)** — test launches appear
  in `launch list`, console via `launch logs`.
- **[jdt-status-spec](jdt-status-spec.md)** — `test runs` feeds
  the tests section in `jdt status`.

## Files

CLI:
  commands/test-run.mjs         — `jdt test run`: launch + header + guide/streaming
  commands/test-status.mjs      — `jdt test status`: snapshot or stream
  commands/test-sessions.mjs    — `jdt test runs`: list table
  format/test-status.mjs        — formatters: header, events, guide, followTestStream
  format/test-results.mjs       — summary + failure details
  client.mjs: getStreamLines()  — shared JSONL streaming (line-by-line callback)
