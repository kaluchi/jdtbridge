# jdt coverage — Design Spec

CLI surface for Eclipse coverage workflow.
Server-side contract: [bridge-coverage-spec](bridge-coverage-spec.md).
This spec references HTTP endpoints, bridge wire fields, and
user-facing Eclipse GUI labels.

## Eclipse GUI → CLI map

| Eclipse user-facing action | HTTP endpoint | CLI command |
|---|---|---|
| Right-click → Coverage As (on a config or class) | `GET /coverage/run` or `GET /test/run?coverage=true` | `jdt coverage run <configId>` / `jdt test run <fqn> --coverage` |
| Coverage View title bar — active session description | `GET /coverage/active` | `jdt coverage active` |
| Coverage View toolbar dropdown — list of all sessions to pick active | `GET /coverage/runs` + `POST /coverage/activate` | `jdt coverage runs` + `jdt coverage activate <coverageId>` |
| Coverage View popup → Refresh (F5) | `POST /coverage/refresh` | `jdt coverage refresh` |
| Coverage View toolbar — Relaunch Session | `POST /coverage/relaunch` | `jdt coverage relaunch` |
| Coverage View toolbar — Remove Active Session | `POST /coverage/remove` (empty body) | `jdt coverage remove` |
| Coverage View toolbar — Remove All Sessions | `POST /coverage/remove` (`all:true`) | `jdt coverage remove --all` |
| Coverage View toolbar — Merge Sessions… | `POST /coverage/merge` | `jdt coverage merge <coverageId> <coverageId> [...]` |
| Coverage View toolbar dropdown — Dump Execution Data per running launch | `POST /coverage/dump` (with `reset` body field) | `jdt coverage dump <coverageId> [--reset]` |
| Console / Debug View — Stop on running coverage launch | `GET /launch/stop` | `jdt coverage stop <coverageId>` (delegates to `jdt launch stop`) |

Eclipse's Import Session, Export Session, and Open Session Execution
Data commands have no CLI counterpart. Sessions imported through the
Eclipse Coverage View popup appear in `jdt coverage runs` with
`coverageSessionKind: "imported"` (read-only).

## Identity vocabulary

Three high-entropy domain keys, never aliased and never shortened:

| Key | Format | Identifies |
|---|---|---|
| `coverageId` | live: `<configId>:<launchTimestamp>` (with `:<dumpIndex>` suffix to address a specific dump); merged: `merged:<millis>`; imported: `imported:<millis>` (full composition rules — including intra-millisecond collision suffix — in [bridge-coverage-spec § Identity](bridge-coverage-spec.md#identity)) | one coverage session |
| `launchId` | `<configId>:<processPid>` | a running/terminated process |
| `configId` | the launch config name | a saved launch configuration |

These are never called "id", "name", "key", or "ref" anywhere in the
CLI surface — neither in flags, nor in JSON output, nor in error
messages. Composition rules and the relationship `coverageId ==
testRunId` (when both refer to the same launch) live in
[bridge-coverage-spec § Identity](bridge-coverage-spec.md#identity).

For a long-running launch with multiple `coverage dump` calls, each
dump produces its own session under the same launch — addressable
as `<coverageId>:<dumpIndex>` (1-based). Without a numeric suffix,
`<coverageId>` resolves to the latest dump.

## Commands

### `jdt coverage run <configId> [-f] [-q] [-- args...]`

Launch a coverage run. Non-blocking.

Flags:
- `-f, --follow` — stream state events until analysis reaches a
  terminal state
- `-q, --quiet` — suppress the onboarding guide
- `-- args...` — extra arguments appended to the launch config (same
  semantics as `jdt launch run -- args`)
- `--json` — JSON snapshot output

Whether the new session becomes active is a server-side
preference (see [bridge-coverage-spec](bridge-coverage-spec.md)).

```bash
jdt coverage run MyTest
jdt coverage run MyTest -f
jdt coverage run my-server -- --port 8080
```

### `jdt coverage runs [--json]`

Calls `GET /coverage/runs` and renders the array as a table.
Mirrors Eclipse's session-selection dropdown in the Coverage View
toolbar.

```
COVERAGEID                        CONFIGID            ACTIVE  DUMPS  STATUS
MyTest:1777078913423              MyTest              *       1      running, analysis ready, started 30s ago
HttpServerBindTest:1777078815046  HttpServerBindTest          1      finished 5m ago, analysis ready
LongServer:1777078500000          my-server                   3      finished 12m ago
merged:1777079000000              —                           1      merged 2 sessions
imported:1777080500000            —                           1      imported
```

Columns (each backed by a JSON field from the endpoint response):
- `COVERAGEID` — `coverageId`
- `CONFIGID` — `configId` (`—` when JSON returns `null`)
- `ACTIVE` — `*` when `active == true`
- `DUMPS` — `dumpCount`
- `STATUS` — composed from `terminated` / `dataReceived` /
  `analysisLoading` / `analysisReady` / `coverageSessionKind` /
  `dumpCount` / `terminatedAt` (see § STATUS composition)

### `jdt coverage status <coverageId> [-f] [--json]`

Snapshot of one session.

Flags:
- `-f, --follow` — stream state events to `/coverage/session/stream`
  until terminal state reached
- `--json` — JSONL when streaming, JSON snapshot otherwise

`<coverageId>` accepts:
- full form `MyTest:1777078913423` — latest dump
- with dump suffix `MyTest:1777078913423:2` — specific dump
- bare `configId` (e.g. `MyTest`) — most recent run for that config

Snapshot output (text):
```
#### MyTest:1777078913423 (MyTest) — running, ready, active

CoverageScope:
  /MyProject/src/main/java
  /MyProject/src/test/java
Description:   MyTest (Apr 25, 2026 03:14:25 AM)
ConfigType:    JUnit Plug-in Test
LaunchId:      MyTest:6408
DumpCount:     1
LaunchedAt:    2026-04-25 03:14:25
TerminatedAt:  —

Instructions   coveredCount=12345  missedCount=678  totalCount=13023  coveredRatio=94.8%  PARTLY_COVERED
Branches       coveredCount=234    missedCount=56   totalCount=290    coveredRatio=80.7%  PARTLY_COVERED
Lines          coveredCount=1900   missedCount=100  totalCount=2000   coveredRatio=95.0%  PARTLY_COVERED
Complexity     coveredCount=75     missedCount=25   totalCount=100    coveredRatio=75.0%  PARTLY_COVERED
Methods        coveredCount=50     missedCount=0    totalCount=50     coveredRatio=100%   FULLY_COVERED
Classes        coveredCount=10     missedCount=0    totalCount=10     coveredRatio=100%   FULLY_COVERED
```

Counter rows shown only when `analysisReady == true`. For
`analysisLoading == true` the top header shows `analysis loading`
and the counter section is omitted; for `dataReceived == false` after
termination, top shows `no data received`.

Counter row formatting rules (each row consumes one entry from
the JSON `counters` map — see [bridge-coverage-spec § Counter shape](bridge-coverage-spec.md#counter-shape)):
- Row labels: `Instructions`, `Branches`, `Lines`, `Complexity`,
  `Methods`, `Classes` — one per `counters.<key>`
- Numeric columns are wire fields verbatim: `coveredCount`,
  `missedCount`, `totalCount`, `coveredRatio`
- `coverageStatus` value rendered as the literal string from the
  wire (`EMPTY`, `NOT_COVERED`, `FULLY_COVERED`, `PARTLY_COVERED`)
- Color: `FULLY_COVERED` green, `PARTLY_COVERED` yellow,
  `NOT_COVERED` red, `EMPTY` dim
- For `totalCount == 0`: `—  EMPTY` instead of the full breakdown

Streaming output (`-f`) — one line per state event:
```
[03:14:25] snapshot: running, pending, 0 dumps
[03:14:30] dumped #1 at 03:14:30
[03:14:30] analyzing #1
[03:14:32] ready #1 — instructions 94.8%, branches 80.7%, lines 95.0%, complexity 75%, methods 100%, classes 100%
[03:14:45] dumped #2 at 03:14:45
[03:14:45] analyzing #2
[03:14:47] ready #2 — instructions 95.1%, branches 81.0%, lines 95.2%, complexity 76%, methods 100%, classes 100%
[03:15:01] terminated at 03:15:01
```

### `jdt coverage dump <coverageId> [--reset] [--json]`

Eclipse: Coverage View toolbar → Dump Execution Data button.
Calls `POST /coverage/dump` with body `{coverageId, reset}`.

`--reset` sets the body's `reset` field to `true` — agent flushes
current data and zeroes its probes; next dump only contains data
since the reset. Without `--reset` (`reset:false`) dumps are
cumulative. Behavior detail: see
[bridge-coverage-spec § `POST /coverage/dump`](bridge-coverage-spec.md#post-coveragedump).

```bash
jdt coverage dump MyTest:1777078913423
jdt coverage dump MyTest:1777078913423 --reset
```

### `jdt coverage refresh [--json]`

Eclipse: Coverage View popup → Refresh (F5).
Calls `POST /coverage/refresh` (empty body). Re-runs analysis for
the active session.

```bash
jdt coverage refresh
```

### `jdt coverage relaunch [--json]`

Eclipse: Coverage View toolbar → Relaunch Session.
Calls `POST /coverage/relaunch` (empty body). Re-launches the active
session's source config in coverage mode. Returns the same shape as
`jdt coverage run`.

```bash
jdt coverage relaunch
```

### `jdt coverage activate <coverageId> [--json]`

Eclipse: Coverage View toolbar dropdown radio menu / Select Active
Session dialog. Calls `POST /coverage/activate` with body
`{coverageId}`.

```
$ jdt coverage activate HttpServerBindTest:1777078815046
Activated HttpServerBindTest:1777078815046
Previous: MyTest:1777078913423
```

### `jdt coverage active [--json]`

Eclipse: title bar of Coverage View (description of currently
selected session). Calls `GET /coverage/active`, prints
`activeCoverageId` from the response.

```
$ jdt coverage active
MyTest:1777078913423
```

`none` when JSON returns `{"activeCoverageId": null}`.

### `jdt coverage merge <coverageId> <coverageId> [<coverageId>...] [--name <description>] [--json]`

Eclipse: Coverage View toolbar → Merge Sessions… (opens dialog with
selectable input list and editable description).
Calls `POST /coverage/merge` with body `{coverageIds, description}`.
Response carries `mergedCoverageId` plus `consumedCoverageIds`
(inputs become unresolvable after merge).

Flags:
- `--name <description>` — passed as the `description` body field.
  When omitted the bridge supplies a default mirroring Eclipse's
  Merge Sessions dialog default.
- `--json`

```bash
jdt coverage merge MyTest:1777078913423 OtherTest:1777078815046
jdt coverage merge unit-tests integration-tests --name "Combined run"
```

Output:
```
#### Merged 2 sessions → merged:1777079000000

Consumed:
  MyTest:1777078913423           removed
  OtherTest:1777078815046         removed

Active session: merged:1777079000000
  jdt coverage status merged:1777079000000 -f
```

### `jdt coverage remove [--all] [--json]`

Eclipse: Coverage View toolbar → Remove Active Session / Remove All
Sessions. Calls `POST /coverage/remove` with empty body or
`{all: true}`.

```bash
jdt coverage remove          # remove active
jdt coverage remove --all    # remove all
```

Output:
```
Removed 1 coverage session
```

### `jdt coverage stop <coverageId>`

Eclipse: Console / Debug View → Stop button on the running coverage
launch. Resolves `coverageId` to `launchId` and delegates to
`jdt launch stop` (HTTP `GET /launch/stop`).

```bash
jdt coverage stop MyTest:1777078913423
```

Errors:
- `coverage-launch-terminated` — already terminated
- `coverage-launch-not-live` — session has no live launch (merged or imported)

## Test integration: `--coverage` flag

`jdt test run` accepts `--coverage` to launch tests in coverage mode.

```bash
jdt test run com.example.MyTest --coverage
jdt test run com.example.MyTest#testFoo --coverage -f
jdt test run --project my-tests --coverage
```

When `--coverage` is set:
- CLI calls `GET /test/run?…&coverage=true` (server-side handling
  in [bridge-coverage-spec § Test integration](bridge-coverage-spec.md#test-integration-coverage-flag-on-testrun))
- Response carries `coverageId` and `launchMode: "coverage"`
  alongside the existing `testRunId`, `launchId`, `configId`.
  `coverageId` and `testRunId` are byte-identical strings.
- Header is the standard test header (see
  [jdt-test-spec § Header](jdt-test-spec.md)) with these deltas:
  - `CoverageId:` line inserted right after `TestRunId:`
  - `CoverageScope:` block inserted right after `CoverageId:`
    (rendered as in § Header format below)
  - `LaunchMode: coverage` appended at the end (omitted when
    `--coverage` is not used)
  - `Config:` label renamed to `LaunchConfig:` for disambiguation
    (this rename also propagates to the no-coverage `jdt test run`
    output for consistency)
- Onboarding guide includes both `jdt test status …` and
  `jdt coverage status …` paths

## Header format — `jdt coverage run`

Mirrors `jdt test run` header (see [jdt-test-spec](jdt-test-spec.md)):
markdown-friendly heading, backtick-wrapped values, fixed-width labels.

```
#### Coverage: MyTest
CoverageId:    `MyTest:1777078913423`
CoverageScope:
  /MyProject/src/main/java
  /MyProject/src/test/java
LaunchId:      `MyTest:6408`
ConfigId:      `MyTest`
ConfigType:    JUnit Plug-in Test
LaunchMode:    coverage
```

## Onboarding guide — `jdt coverage run`

Printed after non-blocking launch unless `-q`. Four sections plus
trailer, mirrors `jdt test run` guide.

```
**Coverage status** (coverageId = MyTest:1777078913423):
  `jdt coverage status MyTest:1777078913423`             snapshot
  `jdt coverage status MyTest:1777078913423 -f`          follow until ready
  `jdt coverage active`                                  show active session

**Console output** (launchId = MyTest:6408):
  `jdt launch logs MyTest:6408`
  `jdt launch logs MyTest:6408 --tail 50`

**Manage running launch:**
  `jdt coverage dump MyTest:1777078913423`               request a dump
  `jdt coverage dump MyTest:1777078913423 --reset`       dump + reset agent probes
  `jdt coverage stop MyTest:1777078913423`               terminate

**Sessions:**
  `jdt coverage runs`                                    list all sessions
  `jdt coverage activate MyTest:1777078913423`           switch IDE display
  `jdt coverage refresh`                                 re-analyze active
  `jdt coverage relaunch`                                re-launch active in coverage mode
  `jdt coverage merge <coverageId> <coverageId>`         merge two or more
  `jdt coverage remove`                                  remove active
  `jdt coverage remove --all`                            remove all

Add `-q` to suppress this guide.
```

## STATUS composition

CLI composes the STATUS string from the four boolean signals plus
`dumpCount` and `coverageSessionKind` returned by the bridge.

Two token groups, joined by `, `:

**Group A — launch/origin** (exactly one token):
| Condition | Token |
|---|---|
| `coverageSessionKind == "merged"` | `merged N sessions` (`N` = `consumedCoverageIds.size()`) |
| `coverageSessionKind == "imported"` | `imported` |
| `coverageSessionKind == "live" && terminated == false` | `running` |
| `coverageSessionKind == "live" && terminated == true` | `finished Xm ago` |

**Group B — data/analysis** (zero or more tokens, in order):
| Condition | Token |
|---|---|
| `dataReceived == false && terminated == true` | `no data received` |
| `dumpCount > 1` | `<N> dumps` |
| `analysisLoading == true` | `analysis loading` |
| `analysisReady == true` | `analysis ready` |
| `analysisLoading == false && analysisReady == false && terminated == true && dataReceived == true` | `analysis pending` |

**Group C — relative time** (at most one token, appended last):
| Condition | Token |
|---|---|
| `coverageSessionKind == "live" && terminated == false` | `started <relative> ago` (from `launchTimestamp`) |

Examples:
- `running, analysis ready, started 30s ago`
- `running, 3 dumps, analysis loading`
- `finished 5m ago, analysis ready`
- `finished 12m ago, no data received`
- `merged 2 sessions, analysis ready`
- `imported, analysis pending`

## Files

  commands/coverage-run.mjs        — `jdt coverage run`
  commands/coverage-runs.mjs       — `jdt coverage runs`
  commands/coverage-status.mjs     — `jdt coverage status` (snapshot + stream)
  commands/coverage-dump.mjs       — `jdt coverage dump`
  commands/coverage-refresh.mjs    — `jdt coverage refresh`
  commands/coverage-relaunch.mjs   — `jdt coverage relaunch`
  commands/coverage-active.mjs     — `jdt coverage active` / `activate`
  commands/coverage-merge.mjs      — `jdt coverage merge`
  commands/coverage-remove.mjs     — `jdt coverage remove`
  commands/coverage-stop.mjs       — `jdt coverage stop`
  format/coverage-status.mjs       — header, state events, guide, follow
  format/coverage-runs.mjs         — runs table
  format/coverage-state.mjs        — STATUS line composition
  commands/test-run.mjs            — extended with `--coverage` flag

## Cross-references

- **[bridge-coverage-spec](bridge-coverage-spec.md)** — server HTTP
  contract, lifecycle, error categories, plugin classes, identity
  composition rules.
- **[jdt-launch-spec](jdt-launch-spec.md)** — coverage launches
  appear in `jdt launch list`; `coverage stop` delegates to
  `launch stop`; console output via `launch logs <launchId>`.
- **[jdt-test-spec](jdt-test-spec.md)** — `jdt test run --coverage`
  reuses test launch infrastructure; the `coverageId` field in
  the response equals the `testRunId` field byte-for-byte (same
  underlying launch).
