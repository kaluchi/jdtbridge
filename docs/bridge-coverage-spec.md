# Bridge Coverage Protocol — Design Spec

HTTP surface over `org.eclipse.eclemma.core.CoverageTools` API.
CLI counterpart: [jdt-coverage-spec](jdt-coverage-spec.md).

## Eclipse → HTTP map

| Eclipse action | Eclipse command id | HTTP endpoint |
|---|---|---|
| Right-click → Coverage As (on a config or class) | EclEmma launch shortcut | `GET /coverage/run` |
| Coverage View — tree of active session (data display) | `org.eclipse.eclemma.ui.CoverageView` (out of scope) | — |
| Coverage View title bar | `setContentDescription` of active `description` | `GET /coverage/active` |
| Toolbar dropdown — list of all sessions for selecting active | `selectActiveSession` + `SelectActiveSessionsItems` | `GET /coverage/runs` + `POST /coverage/activate` |
| Popup → Refresh (F5) | `org.eclipse.ui.file.refresh` → `RefreshSessionHandler` | `POST /coverage/refresh` |
| Toolbar — Relaunch Session | `relaunchSession` | `POST /coverage/relaunch` |
| Toolbar — Remove Active Session | `removeActiveSession` | `POST /coverage/remove` (no body) |
| Toolbar — Remove All Sessions | `removeAllSessions` | `POST /coverage/remove` with `all:true` |
| Toolbar — Merge Sessions… | `mergeSessions` | `POST /coverage/merge` |
| Toolbar dropdown — Dump Execution Data per running launch | `dumpExecutionData` + `DumpExecutionDataItems` (reads `PREF_RESET_ON_DUMP` toggle in submenu) | `POST /coverage/dump` (`reset` body field — passed per-call to `RuntimeData.collect(..., reset)`) |
| Console / Debug View — Stop on running coverage launch | standard launch terminate | `GET /launch/stop` (resolved from `coverageId`) |

Eclipse Import Session (`importSession`), Export Session
(`exportSession`), and Open Session Execution Data
(`openSessionExecutionData`) commands have no HTTP endpoint
counterpart. Sessions added through Eclipse-side `ImportSessionHandler`
still appear in `GET /coverage/runs` with
`coverageSessionKind: "imported"`.

## Identity

```
coverageId(live)             = "<configId>:<launchTimestamp>"            # latest dump
coverageId(live, dump N)     = "<configId>:<launchTimestamp>:<dumpIndex>"
coverageId(merged)           = "merged:<addedAtMillis>"                   # "<…>#<seq>" on intra-millisecond collision
coverageId(imported)         = "imported:<addedAtMillis>"                 # "<…>#<seq>" on intra-millisecond collision
```

`launchTimestamp` is `ILaunch.getAttribute(DebugPlugin.ATTR_LAUNCH_TIMESTAMP)`,
set on the `ILaunch` instance synchronously inside
`LaunchConfiguration.launch(mode, monitor, build)` after
`getDelegate2().getLaunch(...)` returns and before
`delegate.launch(...)` runs (so the attribute is visible before any
JVM spawn). Stored as `Long.toString(System.currentTimeMillis())` in
the source — `ILaunch.getAttribute` returns `String`; the bridge
parses with `Long.parseLong(...)` to emit a JSON number. Mirrors
`testRunId` in
[bridge-test-spec](bridge-test-spec.md): for one launch in coverage
mode the `coverageId` and `testRunId` are byte-identical strings —
they identify the same `ILaunch` from coverage and test perspectives
respectively.

`dumpIndex` (1-based) addresses a specific `ICoverageSession` within
a single launch. EclEmma's `AgentServer.run()` loop creates a new
`ICoverageSession` on every dump (process termination, manual
`requestDump`). The `coverageId` without suffix always resolves to
the latest dump in the run; `coverageId:N` selects dump N.

`addedAtMillis` is `System.currentTimeMillis()` captured at the
`ISessionListener.sessionAdded(session)` callback. No `configId`
prefix on merged or imported — both are detached from any single
launch. EclEmma adopts the merged session's `launchConfiguration`
only when every input with a non-null `getLaunchConfiguration()`
shares the same value (`SessionManager.java:175-187`); inputs with
`null` config are skipped during set construction. The bridge does
not surface that adoption in `coverageId`.

## Data sources (SSOT)

All output fields trace back to one of these source structures.
Bridge-side fields are derived state in `CoverageTracker`, not stored
upstream.

### Source structures

| SSOT type | Package | Role | Where bridge reads from |
|---|---|---|---|
| `ILaunch` | `org.eclipse.debug.core` | Eclipse debug API for any process launch | `LaunchManager.getLaunches()`; `ILaunchesListener2` callbacks |
| `ILaunchConfiguration` | `org.eclipse.debug.core` | persistent saved launch config | `ILaunch.getLaunchConfiguration()` |
| `ILaunchConfigurationType` | `org.eclipse.debug.core` | type metadata of a config | `ILaunchConfiguration.getType()` |
| `IProcess` | `org.eclipse.debug.core.model` | OS process under an `ILaunch` | `ILaunch.getProcesses()[i]` |
| `ICoverageLaunch extends ILaunch` | `org.eclipse.eclemma.core.launching` | EclEmma's public `Launch` subclass — exposes `getScope()` + `requestDump(boolean)` only | `LaunchManager.getLaunches()` cast-checked; `CoverageTools.getRunningCoverageLaunches()` |
| `CoverageLaunch implements ICoverageLaunch` | `org.eclipse.eclemma.internal.core.launching` | concrete subclass; adds public `getAgentServer()` not declared on the interface | bridge casts `(CoverageLaunch) launch` to reach `getAgentServer()` |
| `AgentServer` | `org.eclipse.eclemma.internal.core.launching` | TCP listener for JaCoCo agent connections; `hasDataReceived()` exposes the per-run flag | `((CoverageLaunch) launch).getAgentServer()` — requires bridge `MANIFEST.MF` to access `org.eclipse.eclemma.internal.core.launching` (e.g. via `Eclipse-BuddyPolicy` or matching `x-friends` allowance) |
| `ICoverageSession` | `org.eclipse.eclemma.core` | one coverage measurement (immutable) | `SessionManager.getSessions()` |
| `ISessionManager` | `org.eclipse.eclemma.core` | session registry | `CoverageTools.getSessionManager()` |
| `JavaCoverageLoader` | `org.eclipse.eclemma.internal.core` | active session analysis cache | `EclEmmaCorePlugin.getInstance().getJavaCoverageLoader()` |
| `IJavaModelCoverage` | `org.eclipse.eclemma.core.analysis` | per-active-session analyzed coverage tree | `CoverageTools.getJavaModelCoverage()` (also `IJavaModelCoverage.LOADING` sentinel constant) |
| `SessionAnalyzer` | `org.eclipse.eclemma.internal.core.analysis` | runs JaCoCo `Analyzer` over scope (`SessionAnalyzer.java:60-87`); `processSession(...)` returns `IJavaModelCoverage`. The same call also accumulates `ExecutionDataStore` + `SessionInfoStore` as instance state, exposed afterwards via `getSessionInfos(): List<SessionInfo>` (`SessionAnalyzer.java:89-91`) and `getExecutionData(): Collection<ExecutionData>` (`:93-95`). Bridge keeps the analyzer instance alive long enough to read both getters. | `SessionAnalyzer a = new SessionAnalyzer(); IJavaModelCoverage cov = a.processSession(session, monitor); a.getSessionInfos(); a.getExecutionData();` |
| `IPackageFragmentRoot` | `org.eclipse.jdt.core` | JDT source/binary root | `ICoverageSession.getScope()` member; `IPackageFragmentRoot.getHandleIdentifier()` for wire serialization |
| `SessionInfo` | `org.jacoco.core.data` | JaCoCo per-dump metadata (id/start/dump timestamps) | `SessionAnalyzer.getSessionInfos()` after `processSession` |
| `ExecutionData` | `org.jacoco.core.data` | per-class probe array for one JVM session | `SessionAnalyzer.getExecutionData()` |
| `ICounter` | `org.jacoco.core.analysis` | counts for one entity (covered/missed/total/ratios/status) | `ICoverageNode.getInstructionCounter()` etc. |
| `ICoverageNode` | `org.jacoco.core.analysis` | analyzed node with 6 counters | `IJavaModelCoverage` (root); `getCoverageFor(IJavaElement)` |
| `IPreferenceStore` | `org.eclipse.jface.preference` | preference store of `EclEmmaUIPlugin` | `EclEmmaUIPlugin.getInstance().getPreferenceStore()` |
| `CoverageTracker.CoverageRun` | bridge-internal | ID-keyed view over `ICoverageSession` lifecycle | bridge `Map<coverageId, CoverageRun>` |

### Sessions in `SessionManager.getSessions()` — three origins

| Kind | `addSession(...)` call site | Distinguishing observable at `sessionAdded(session)` |
|---|---|---|
| `live` | `AgentServer.java:123-124` — `sessionManager.addSession(session, preferences.getActivateNewSessions(), launch)` | `session.getLaunchConfiguration()` equals `getLaunchConfiguration()` of a live `ICoverageLaunch` in `LaunchManager.getLaunches()` |
| `merged` | `SessionManager.java:193` — `addSession(merged, true, null)` (inside the `synchronized (lock)` publication block at `SessionManager.java:192-197`) | followed synchronously by ≥2 `sessionRemoved` calls in the same publication block (`SessionManager.java:194-196`) |
| `imported` | `SessionImporter.java:71` — `addSession(session, true, null)` | no synchronous `sessionRemoved` follows |

### Per-field SSOT mapping

| Field | `live` SSOT | `merged` SSOT | `imported` SSOT |
|---|---|---|---|
| `coverageId` | bridge: `ILaunch.getLaunchConfiguration().getName() + ":" + ILaunch.getAttribute(DebugPlugin.ATTR_LAUNCH_TIMESTAMP)` | bridge: `"merged:" + System.currentTimeMillis()` at `sessionAdded(merged)`, `+ "#<seq>"` if that string was already issued (intra-millisecond collision) | bridge: `"imported:" + System.currentTimeMillis()` at `sessionAdded(imported)`, with the same `#<seq>` collision rule |
| `coverageSessionKind` | bridge `CoverageRun.coverageSessionKind`; classification at `sessionAdded` | same | same |
| `configId` | `ILaunch.getLaunchConfiguration().getName()` | `ICoverageSession.getLaunchConfiguration().getName()` if non-null. `mergeSessions` builds `Set<ILaunchConfiguration>` from inputs whose `getLaunchConfiguration() != null` (`SessionManager.java:175-177`), then adopts the unique element only when `launches.size() == 1` (`SessionManager.java:184-187`); inputs with `null` config are skipped during set construction. Three inputs with config A + one with `null` → adopts A; two inputs with A + one with B → `null`. | `null` (constant — `CoverageSession` constructor receives `null` literal at `SessionImporter.java:70`) |
| `launchId` | bridge: `configId + ":" + ILaunch.getProcesses()[0].getAttribute(IProcess.ATTR_PROCESS_ID)` | `null` (no `ILaunch` — `addSession(merged, true, null)`) | `null` (no `ILaunch` — `addSession(session, true, null)`) |
| `configType` | `ILaunch.getLaunchConfiguration().getType().getName()` | same on `ICoverageSession.getLaunchConfiguration()` if non-null; else `null` | `null` |
| `configTypeId` | `ILaunch.getLaunchConfiguration().getType().getIdentifier()` | same on `ICoverageSession.getLaunchConfiguration()` if non-null; else `null` | `null` |
| `description` | `ICoverageSession.getDescription()` of latest dump (set by `AgentServer.createDescription()` = `MessageFormat(CoreMessages.LaunchSessionDescription_value, configName, new Date())`) | `ICoverageSession.getDescription()` (set from `description` arg to `SessionManager.mergeSessions(sessions, description, monitor)`) | `ICoverageSession.getDescription()` (set via `SessionImporter.setDescription(...)` before `importSession`) |
| `coverageScope` | `ICoverageSession.getScope()` → `IPackageFragmentRoot.getHandleIdentifier()` per element. For live: scope was set in `CoverageLauncher.getLaunch():106` to `ScopeUtils.getConfiguredScope(config)` | same shape; scope set in `SessionManager.mergeSessions:170,174` as union of inputs' `getScope()` | same shape; scope set via `SessionImporter.setScope(...)` |
| `terminated` | `ILaunch.isTerminated()` | constant `true` (no `ILaunch`) | constant `true` (no `ILaunch`) |
| `dataReceived` | `((CoverageLaunch) launch).getAgentServer().hasDataReceived()` (`AgentServer.java:98-100`) | constant `true` — merge precondition is `getSessions().size() > 1` (`MergeSessionsHandler.isEnabled():48-50`), every input had data | constant `true` — the `IExecutionDataSource` set via `SessionImporter.setExecutionDataSource` is the imported data |
| `analysisLoading` | `sessionManager.getActiveSession() ∈ run.sessions` AND `JavaCoverageLoader.getJavaModelCoverage() == IJavaModelCoverage.LOADING` | same | same |
| `analysisReady` | `sessionManager.getActiveSession() ∈ run.sessions` AND `coverage != null && coverage != IJavaModelCoverage.LOADING` | same | same |
| `dumpCount` | `CoverageRun.sessions.size()` — bridge increments per `sessionAdded` matching this run's `ILaunch.getLaunchConfiguration()` | constant `1` — `mergeSessions` produces exactly one `CoverageSession` | constant `1` — `importSession` produces exactly one `CoverageSession` |
| `launchTimestamp` | `Long.parseLong(ILaunch.getAttribute(DebugPlugin.ATTR_LAUNCH_TIMESTAMP))` — Eclipse stores it as `Long.toString(System.currentTimeMillis())` (`LaunchConfiguration.java:725`); bridge parses to JSON number | `null` | `null` |
| `terminatedAt` | bridge: `System.currentTimeMillis()` at `ILaunchesListener2.launchesTerminated` callback for this `ILaunch` | bridge: `System.currentTimeMillis()` at `sessionAdded(merged)` (no live launch ever existed) | bridge: `System.currentTimeMillis()` at `sessionAdded(imported)` |
| `consumedCoverageIds` | absent | bridge-collected `List<String>` of tracker `coverageId`s from the burst of `sessionRemoved` immediately after `sessionAdded(merged)` | absent |

`analysisLoading` / `analysisReady` apply only to the **active**
session: EclEmma's `JavaCoverageLoader` holds a single
`IJavaModelCoverage coverage` field (`JavaCoverageLoader.java:42`),
populated by `LoadSessionJob` for whichever session was last
activated. For non-active sessions both are `false` on
`/coverage/runs`; counters reach the wire via
`CoverageAnalyzer.ensureAnalyzed(session)` invoked by
`/coverage/session`.

### Lifetime

| What | When created | When destroyed |
|---|---|---|
| `CoverageRun` (live) | `ILaunchesListener2.launchesAdded` for an `ICoverageLaunch` | `ISessionListener.sessionRemoved` of last session in run + bridge-side cleanup; also gone after plugin restart (in-memory) |
| `CoverageRun` (merged) | `ISessionListener.sessionAdded(merged)` | `ISessionListener.sessionRemoved(merged)` (merged runs hold exactly one session); also gone after plugin restart |
| `ICoverageSession` object | `new CoverageSession(...)` — in `AgentServer.run()` (live) or `mergeSessions(...)` (merged) | explicit `removeSession`/`removeAllSessions`; or plugin restart (in-memory `SessionManager.sessions` list) |
| `.exec` file | `ExecutionDataFiles.newFile(source)` (`ExecutionDataFiles.java:62-76`) writes to `<plugin-state>/.execdata/session<digits>.exec` — name produced by `File.createTempFile("session", ".exec", folder)` at `:65`, no dash separator | `EclEmmaCorePlugin.start()` and `EclEmmaCorePlugin.stop()` both call `executionDataFiles.deleteTemporaryFiles()` (`EclEmmaCorePlugin.java:99,111`) — files are wiped on every plugin start AND stop |
| `JavaCoverageLoader.coverage` | `LoadSessionJob.run()` finishing for the active session | `sessionActivated(other)` triggers `cancel(LOADJOB)` and replaces with the new active's analysis; or `sessionActivated(null)` clears it |
| Bridge `CoverageAnalyzer` cache entry | first call to `ensureAnalyzed(session)` for a non-active session | `ISessionListener.sessionRemoved(session)` |

No persistence across Eclipse restart. Restarting Eclipse clears
`SessionManager.sessions` (in-memory `ArrayList`) and wipes
`<plugin-state>/.execdata/` (twice — at stop of old instance and
start of new). The bridge's `CoverageTracker` rebuilds empty.

## ICoverageSession ↔ coverageId tracker

`ICoverageSession` has identity-only equality (`CoverageSession extends
PlatformObject`, `equals` not overridden). The bridge maintains an
explicit indexed model in `CoverageTracker`:

```
Map<coverageId, CoverageRun>

CoverageRun {
  coverageId          : String                  // primary key, no suffix
  coverageSessionKind : "live" | "merged" | "imported"
  configId            : String?                 // live: launch.config.name
                                                // merged: mergedSession.launchConfig.name iff non-null
                                                // imported: null
  launch              : ILaunch?                // live: ILaunch instance
                                                // merged/imported: null
  launchTimestamp     : long?                   // live: ATTR_LAUNCH_TIMESTAMP
                                                // merged/imported: null
  description         : String                  // live: AgentServer.createDescription()
                                                // merged: arg to mergeSessions
                                                // imported: SessionImporter.setDescription value
  coverageScope       : Set<IPackageFragmentRoot>
  sessions            : List<ICoverageSession>  // live: one per AgentServer dump
                                                // merged/imported: exactly 1
  dumpedAt            : List<long>              // millis per session added
  consumedCoverageIds : List<String>?           // merged only
}
```

`CoverageTracker` listens to:

- `ILaunchesListener2.launchesAdded` — for each `CoverageLaunch`
  (cast-checked), pre-create a `CoverageRun` with
  `coverageSessionKind="live"` indexed by
  `configId + ":" + Long.parseLong(ATTR_LAUNCH_TIMESTAMP)`.
  `terminated=false`, `dumpCount=0`, `dataReceived=false`,
  `launchId=null` — the `IProcess` for the spawned JVM registers
  later via `DebugEvent.CREATE`, after which the bridge populates
  `launchId = configId + ":" + ILaunch.getProcesses()[0].getAttribute(IProcess.ATTR_PROCESS_ID)`.
  `coverageId` is stable from `launchesAdded` onward; `launchId`
  becomes available when the process attaches.
- `ISessionListener.sessionAdded(session)` — classify in two phases:
  - **Phase 1, at callback time** — record the new session in a
    pending slot keyed by `System.identityHashCode(session)`,
    capturing `addedAtMillis = System.currentTimeMillis()`. If
    `session.getLaunchConfiguration() != null` and a live
    `ICoverageLaunch` in `LaunchManager.getLaunches()` has the same
    `getLaunchConfiguration()`, immediately classify as **live**
    dump for that run; append to its `sessions`/`dumpedAt`.
    `dumpIndex = run.sessions.size()` after append. No phase 2.
  - **Phase 2, deferred** — sessions that did not classify as live
    in phase 1 are resolved when control returns from the
    listener-firing thread (specifically, the bridge captures all
    `sessionRemoved(input)` events that arrive on the same thread
    before any further `sessionAdded` does, then runs the
    classifier):
    - If ≥2 `sessionRemoved` events for tracked sessions arrived
      synchronously after this `sessionAdded` (the merge
      publication block emits exactly N removals, N ≥ 2, after one
      addition) → **merged**;
      `coverageId = "merged:" + addedAtMillis`,
      `consumedCoverageIds = ` tracker `coverageId`s of the removed
      inputs.
    - Otherwise → **imported**;
      `coverageId = "imported:" + addedAtMillis`.

  Two merges within the same millisecond would collide on
  `addedAtMillis`. The bridge resolves collisions by appending
  `#N` (1-based) to the second and later occurrences of an
  already-issued `merged:<millis>` / `imported:<millis>` value
  before publishing it, e.g. `merged:1777079000000#2`.
- `ISessionListener.sessionRemoved(session)` — remove from owning
  run's `sessions`. If `sessions.isEmpty()` after removal, drop the
  `CoverageRun`. (Single removed session in a multi-dump run leaves
  the run intact — its remaining dumps are still valid.)
- `ISessionListener.sessionActivated(session)` — update
  `activeCoverageId` field. `null` argument means "no active
  session" (last session removed).
- `IJavaCoverageListener.coverageChanged()` — fired by
  `JavaCoverageLoader` on `LOADING` / `null` / completed-analysis
  transitions of the active session. Bridge updates active run's
  `analysisLoading` / `analysisReady` and emits stream events.

Merge classification: `SessionManager.mergeSessions` accumulates
input data outside the lock (lines 165-189: `session.accept(memory,
memory)` per input, `executiondatafiles.newFile(memory)`), then takes
`SessionManager.lock` for the publication block (lines 192-197) where
it calls `addSession(merged, true, null)` followed by
`removeSession(input)` per input. Because `lock` is reentrant and the
inner `addSession`/`removeSession` re-enter it, all listener callbacks
within those calls (`sessionAdded(merged)`, `sessionActivated(merged)`,
`sessionRemoved(input)` × N) fire on the caller thread while the
outer lock acquisition is still held — no other `addSession` /
`removeSession` can interleave. `SessionImporter.importSession` fires
`sessionAdded(imported)` + `sessionActivated(imported)` only — no
`sessionRemoved` burst follows. The bridge classifies on whether the
publication block produced a `sessionRemoved` burst before the outer
lock released.

## CoverageAnalyzer (bridge-side analysis cache)

JaCoCo `IJavaModelCoverage` is computed by `SessionAnalyzer.processSession`
which walks every `IPackageFragmentRoot` in the session's scope and
parses class files. On large workspaces this is multi-second.
EclEmma's `JavaCoverageLoader` caches result for the **active**
session only.

The bridge owns `CoverageAnalyzer` to extend caching to all sessions:

```
Map<ICoverageSession, CachedAnalysis>

CachedAnalysis {
  modelCoverage      : IJavaModelCoverage
  jacocoSessionInfos : List<SessionInfo>
  jacocoExecData     : Collection<ExecutionData>
  computedAt         : long
}
```

`CoverageAnalyzer.ensureAnalyzed(session)` is the single entry point:

- For the active session: bridge runs its own `SessionAnalyzer.processSession`
  on first call to capture `getSessionInfos()` and `getExecutionData()`
  alongside `CoverageTools.getJavaModelCoverage()` — EclEmma's
  `JavaCoverageLoader` exposes only `IJavaModelCoverage`, not the
  raw `SessionInfo` / `ExecutionData` lists. Subsequent calls return
  the bridge cache.
- For non-active session: synchronous `SessionAnalyzer.processSession`
  with `NullProgressMonitor`, populate cache, return.

Invalidation: only `ISessionListener.sessionRemoved` removes a cache
entry. Sessions are immutable (`CoverageSession` final fields),
analysis is deterministic given the same `executionDataSource` and
scope.

## HTTP endpoints

### `GET /coverage/run`

Start a coverage launch.

Params:
- `configId` (required)
- `args` (optional, URL-encoded extra arguments — same semantics as
  `GET /launch/run`)

Activation of the resulting session is governed by
`ICorePreferences.getActivateNewSessions()` read by EclEmma's
`AgentServer` at the `addSession(...)` call.

Validation order:
1. `LaunchManager.getLaunchConfigurations()` contains a config named
   `configId` → else `coverage-config-not-found`
2. Config's type has a delegate registered for mode `"coverage"`:
   `ILaunchDelegate[] delegates = config.getType().getDelegates(Set.of("coverage"))`;
   `delegates.length >= 1` AND `delegates[0].getDelegate() instanceof ICoverageLauncher`
   (path mirrors EclEmma's own `CoverageLauncher.getLaunchDelegate(launchtype)`
   at `CoverageLauncher.java:72-73`). Else `coverage-mode-not-supported`
   with `:supportedTypeIds` listing every type ID for which the same
   resolution succeeds.

Action:
- `ILaunch launch = config.launch("coverage", null, true)`
- `coverageId = configId + ":" + launch.getAttribute(ATTR_LAUNCH_TIMESTAMP)`
- `launchId = configId + ":" + processPid` (mirrors `/launch/run`)

Response on success:
```json
{
  "ok": true,
  "configId": "MyTest",
  "coverageId": "MyTest:1777078913423",
  "launchId": "MyTest:6408",
  "configType": "JUnit Plug-in Test",
  "configTypeId": "org.eclipse.pde.ui.JunitLaunchConfig",
  "coverageScope": [
    "=MyProject/src\\/main\\/java",
    "=MyProject/src\\/test\\/java"
  ],
  "launchTimestamp": 1777078913423,
  "processPid": "6408",
  "cmdline": "java -javaagent:.../jacocoagent.jar=output=tcpclient,port=51234,includes=...,excludes=... ..."
}
```

`coverageScope` entries are JDT handle identifiers
(`IPackageFragmentRoot.getHandleIdentifier()` — same form EclEmma
stores in `ATTR_SCOPE_IDS`). They survive workspace restart and
disambiguate roots that share a path. Conversion to human paths
happens in CLI rendering, not in the wire format.

### `POST /coverage/dump`

Eclipse: `dumpExecutionData` (`DumpExecutionDataHandler`).

Body:
```json
{ "coverageId": "MyTest:1777078913423", "reset": false }
```

`reset` defaults to `false` when omitted.

Resolves `coverageId` to `CoverageRun.launch`, casts to
`ICoverageLaunch`, calls `requestDump(reset)`. The dump path crosses
the Eclipse↔target-JVM TCP boundary opened by `AgentServer`:

- **Eclipse-side** (`org.eclipse.eclemma.internal.core.launching`):
  `AgentServer.requestDump(reset)` → `RemoteControlWriter.visitDumpCommand(true, reset)`
  writes a `BLOCK_CMDDUMP` frame on the socket.
- **Target-JVM-side** (JaCoCo agent's `org.jacoco.agent.rt.internal.output.TcpClientOutput`):
  the agent's `RemoteControlReader.readDumpCommand` decodes the frame
  and invokes the agent's `IAgent.dump(reset)`, which calls
  `RuntimeData.collect(execVisitor, sessionInfoVisitor, reset)`. When
  `reset == true`, after `store.accept(...)`, `RuntimeData.reset()`
  calls `store.reset()` (iterates `entries.values()`,
  `Arrays.fill(probes, false)` on every `ExecutionData`).
- **Eclipse-side** receives the resulting execution data over the
  same socket via `MemoryExecutionDataSource.readFrom(reader)` in
  `AgentServer.run()` (`AgentServer.java:113-125`); each non-empty
  receipt creates a new `CoverageSession` and registers it.

Response: `{"ok": true}`.

Errors:
- `coverage-not-found`
- `coverage-launch-terminated` (`CoverageRun.terminated == true`)
- `coverage-launch-not-live` (merged session has no launch)

### `POST /coverage/refresh`

Eclipse: `org.eclipse.ui.file.refresh` (F5) → `RefreshSessionHandler`
in Coverage View popup menu.

Empty body. Calls `sessionManager.refreshActiveSession()` which
re-fires `sessionActivated` for the current active. `JavaCoverageLoader`
cancels its in-flight `LoadSessionJob` and schedules a new one.

Enabled only when `sessionManager.getActiveSession() != null` — else
`coverage-no-active-session`.

Response: `{"ok": true, "activeCoverageId": "MyTest:1777078913423"}`.

### `POST /coverage/relaunch`

Eclipse: `relaunchSession` (`RelaunchSessionHandler`) toolbar button.

Empty body. Reads `activeSession = sessionManager.getActiveSession()`,
checks `activeSession.getLaunchConfiguration() != null`. Eclipse's
handler uses `DebugUITools.launch(config, CoverageTools.LAUNCH_MODE)`
(`RelaunchSessionHandler.java:41`) which integrates with launch
history and shows the launch dialog on validation errors. The bridge
runs headless and instead calls
`config.launch(CoverageTools.LAUNCH_MODE, null, true)` directly —
behavior diverges from Eclipse only on the validation-error path,
which the bridge surfaces as `CoreException` returned to the caller.

Enabled only when there is an active session whose
`launchConfiguration != null` — else `coverage-no-active-session` or
`coverage-launch-not-live`.

Response — same shape as `GET /coverage/run` (header for the new
launch).

### `GET /coverage/runs`

Eclipse: list shown in `selectActiveSession` ListDialog and
`SelectActiveSessionsItems` toolbar dropdown — `sessionManager.getSessions()`.

Params: filtered by `ProjectScope` (bridge session header).

Returns metadata only — no counters. Counters arrive via
`GET /coverage/session` per `coverageId`.

Response:
```json
[
  {
    "coverageId": "MyTest:1777078913423",
    "coverageSessionKind": "live",
    "configId": "MyTest",
    "launchId": "MyTest:6408",
    "configType": "JUnit Plug-in Test",
    "configTypeId": "org.eclipse.pde.ui.JunitLaunchConfig",
    "description": "MyTest (Apr 25, 2026 03:14:25 AM)",
    "coverageScope": ["=MyProject/src\\/main\\/java", "=MyProject/src\\/test\\/java"],
    "active": true,
    "terminated": false,
    "dataReceived": true,
    "analysisLoading": false,
    "analysisReady": true,
    "dumpCount": 1,
    "launchTimestamp": 1777078913423,
    "terminatedAt": null
  },
  {
    "coverageId": "merged:1777079000000",
    "coverageSessionKind": "merged",
    "configId": null,
    "launchId": null,
    "configType": null,
    "configTypeId": null,
    "description": "Merged (Apr 25, 2026 03:14:25 AM)",
    "coverageScope": ["=ProjectA/src", "=ProjectB/src"],
    "active": false,
    "terminated": true,
    "dataReceived": true,
    "analysisLoading": false,
    "analysisReady": false,
    "dumpCount": 1,
    "launchTimestamp": null,
    "terminatedAt": 1777079000000,
    "consumedCoverageIds": ["MyTest:1777078913423", "OtherTest:1777078815046"]
  },
  {
    "coverageId": "imported:1777080500000",
    "coverageSessionKind": "imported",
    "configId": null,
    "launchId": null,
    "configType": null,
    "configTypeId": null,
    "description": "<value passed to SessionImporter.setDescription via SessionImportWizard>",
    "coverageScope": ["=ProjectA/src/main/java"],
    "active": false,
    "terminated": true,
    "dataReceived": true,
    "analysisLoading": false,
    "analysisReady": false,
    "dumpCount": 1,
    "launchTimestamp": null,
    "terminatedAt": 1777080500000
  }
]
```

`analysisLoading` / `analysisReady` reflect EclEmma's
`JavaCoverageLoader` for the active session only (`true`/`true` is
impossible — they're mutually exclusive). For non-active sessions
both are `false` — analysis happens on `GET /coverage/session`
through `CoverageAnalyzer`.

### `GET /coverage/session`

Bridge-only — Eclipse activates a session before viewing its tree;
this endpoint reads any session's analysis without changing
activation, via `CoverageAnalyzer.ensureAnalyzed(session)`.

Params:
- `coverageId` (required, with optional `:N` dump suffix)

Triggers `CoverageAnalyzer.ensureAnalyzed(session)` on first call —
returns immediately if cached, else runs synchronous analysis. The
endpoint blocks during analysis. Use `GET /coverage/session/stream`
to follow without blocking.

Response: same shape as one entry from `/coverage/runs` plus:

```json
{
  ...all fields from /coverage/runs entry...,
  "counters": {
    "instruction": { ...counter shape... },
    "branch":      { ...counter shape... },
    "line":        { ...counter shape... },
    "complexity":  { ...counter shape... },
    "method":      { ...counter shape... },
    "class":       { ...counter shape... }
  },
  "jacocoSessionInfos": [
    {
      "jacocoSessionId": "<JaCoCo-generated>",
      "agentStartTimestamp": 1777078913100,
      "dumpTimestamp": 1777078920000
    }
  ]
}
```

SSOT for the additional fields:

| Field | SSOT type | API path |
|---|---|---|
| `counters.instruction` | `ICounter` (JaCoCo) | `IJavaModelCoverage.getInstructionCounter()` (where `IJavaModelCoverage` comes from `CoverageAnalyzer.ensureAnalyzed(session)`) |
| `counters.branch` | `ICounter` | `…getBranchCounter()` |
| `counters.line` | `ICounter` | `…getLineCounter()` |
| `counters.complexity` | `ICounter` | `…getComplexityCounter()` |
| `counters.method` | `ICounter` | `…getMethodCounter()` |
| `counters.class` | `ICounter` | `…getClassCounter()` |
| `jacocoSessionInfos[i].jacocoSessionId` | `SessionInfo` (JaCoCo) | `SessionInfo.getId()` — generated by JaCoCo agent at session start (EclEmma does not set `AgentOptions.SESSIONID`, so it's a JaCoCo-internal random string) |
| `jacocoSessionInfos[i].agentStartTimestamp` | `SessionInfo` | `SessionInfo.getStartTimeStamp()` |
| `jacocoSessionInfos[i].dumpTimestamp` | `SessionInfo` | `SessionInfo.getDumpTimeStamp()` |

`counters` present iff `CoverageAnalyzer.ensureAnalyzed(session)`
returned successfully (it always runs synchronously when called —
either reads bridge cache or invokes `SessionAnalyzer.processSession`).

`jacocoSessionInfos` length comes from
`SessionInfoStore.getInfos()` after `session.accept(execStore,
sessionInfoStore)`. Per kind:
- `live` per single dump (selected via `coverageId` or `coverageId:N`):
  one `SessionInfo` (`RuntimeData.collect(...)` builds exactly one
  `new SessionInfo(sessionId, startTimeStamp, currentTimeMillis())`
  and emits it via `sessionInfoVisitor.visitSessionInfo(info)` per
  invocation)
- `merged`: sum of input dumps' `SessionInfo`s — `mergeSessions`
  accumulates via `session.accept(memory, memory)` per input
  (`SessionManager.java:178`), so each input's `SessionInfo` ends
  up in the merged source's `SessionInfoStore`
- `imported`: whatever the imported `.exec` file contains, parsed
  by `URLExecutionDataSource.accept` via
  `ExecutionDataReader.read()`

### `GET /coverage/session/stream`

Bridge-only streaming variant of `GET /coverage/session` —
follows state transitions until terminal state without blocking
the caller.

Params:
- `coverageId` (required)

Streams JSONL transition events. One line per event. Stream closes
when `terminated == true && analysisLoading == false` (analysis
finished or never started).

Replay: emits current snapshot first, then live events.

Events:
```jsonl
{"event":"snapshot","coverageId":"MyTest:1777078913423","terminated":false,"dataReceived":false,"analysisLoading":false,"analysisReady":false,"dumpCount":0}
{"event":"dumped","coverageId":"MyTest:1777078913423","dumpIndex":1,"dumpTimestamp":1777078918000}
{"event":"analysisLoading","coverageId":"MyTest:1777078913423","dumpIndex":1}
{"event":"analysisReady","coverageId":"MyTest:1777078913423","dumpIndex":1,"counters":{...}}
{"event":"dumped","coverageId":"MyTest:1777078913423","dumpIndex":2,"dumpTimestamp":1777078921000}
{"event":"analysisLoading","coverageId":"MyTest:1777078913423","dumpIndex":2}
{"event":"analysisReady","coverageId":"MyTest:1777078913423","dumpIndex":2,"counters":{...}}
{"event":"terminated","coverageId":"MyTest:1777078913423","terminatedAt":1777078925000}
```

`analysisLoading` event corresponds to
`JavaCoverageLoader.coverage = IJavaModelCoverage.LOADING` transition;
`analysisReady` event corresponds to `coverage` becoming a populated
`JavaModelCoverage` instance (both detected via
`IJavaCoverageListener.coverageChanged()`).

If the launch terminates without any dump (`AgentServer.dataReceived
== false`):
```jsonl
{"event":"snapshot","coverageId":"MyTest:1777078913423","terminated":false,"dataReceived":false,"analysisLoading":false,"analysisReady":false,"dumpCount":0}
{"event":"terminated","coverageId":"MyTest:1777078913423","terminatedAt":...,"dataReceived":false}
```

If `LoadSessionJob` was cancelled mid-flight (e.g. by another
activation, `Job.getJobManager().cancel(LOADJOB)`):
```jsonl
{"event":"failed","coverageId":"MyTest:1777078913423","dumpIndex":1,"reason":"analysis-cancelled"}
```

The bridge filters `IJavaCoverageListener.coverageChanged` events to
the subscribed `coverageId` only — listeners on other sessions are
not woken up.

### `GET /coverage/active`

Eclipse: `sessionManager.getActiveSession()`; description shown in
Coverage View title bar via `setContentDescription`.

Response:
```json
{ "activeCoverageId": "MyTest:1777078913423" }
```

Or `{"activeCoverageId": null}` if no active session.

### `POST /coverage/activate`

Eclipse: `selectActiveSession` (`SelectActiveSessionHandler`) /
`SelectActiveSessionsItems` radio menu →
`sessionManager.activateSession(session)`.

Body:
```json
{ "coverageId": "MyTest:1777078913423" }
```

Resolves to latest dump's `ICoverageSession`, calls
`sessionManager.activateSession(session)`. Triggers
`JavaCoverageLoader` to schedule `LoadSessionJob` for the new active.

Response:
```json
{
  "ok": true,
  "activeCoverageId": "MyTest:1777078913423",
  "previousActiveCoverageId": "OtherRun:1777078815046"
}
```

### `POST /coverage/merge`

Eclipse: `mergeSessions` (`MergeSessionsHandler`) →
`MergeSessionsDialog` → `sessionManager.mergeSessions(...)`.

Body:
```json
{
  "coverageIds": ["MyTest:1777078913423", "OtherTest:1777078815046"],
  "description": "Combined run"
}
```

Constraints:
- `coverageIds.size() >= 2` — else `coverage-merge-too-few-inputs`
- All IDs resolve — else `coverage-not-found` with the missing ID
  in `:context/missing`

Resolves each `coverageId` to the run's flattened
`List<ICoverageSession>` (all dumps), then
`sessionManager.mergeSessions(allSessions, description, monitor)`.

`description` defaults to the same template Eclipse uses in the
Merge Sessions dialog: `MergeSessionsDialogDescriptionDefault_value`
from `uimessages.properties:59` =
`Merged ({0,date,medium} {0,time,medium})`, formatted via
`MessageFormat.format(template, new Date())` at request time on the
Eclipse host — locale-dependent (e.g. `Merged (Apr 25, 2026 03:14:25 AM)`
in en-US, `Merged (25 апр. 2026 г. 07:22:12)` in ru-RU). Mirrors
`MergeSessionsHandler.execute():56-57`.

EclEmma's merge:
- Unions `Set<IPackageFragmentRoot>` from inputs
- Accumulates execution data via `MemoryExecutionDataSource` →
  `executionDataFiles.newFile(memory)`
- Adopts `launchConfiguration` only if all inputs share exactly one
  unique value (else `null`)
- `addSession(merged, activate=true, launch=null)` then
  `removeSession(input)` for each input

Old `coverageId`s become `coverage-not-found` immediately after.

Response:
```json
{
  "ok": true,
  "mergedCoverageId": "merged:1777079000000",
  "consumedCoverageIds": ["MyTest:1777078913423", "OtherTest:1777078815046"],
  "active": true
}
```

### `POST /coverage/remove`

Eclipse: `removeActiveSession` (`RemoveActiveSessionHandler`) /
`removeAllSessions` (`RemoveAllSessionsHandler`).

Body — one of:
```json
{}
{ "all": true }
```

Empty body: `sessionManager.removeSession(sessionManager.getActiveSession())`.
Enabled only when `getActiveSession() != null` — else
`coverage-no-active-session`.

`all: true`: `sessionManager.removeAllSessions()`.

Response:
```json
{
  "ok": true,
  "removedCoverageIds": ["MyTest:1777078913423"]
}
```

## Counter shape

Single canonical shape for `ICounter` across every endpoint that
emits counters. Field names mirror JaCoCo's `ICounter` API one-to-one
— no abbreviation, no synthesized fields beyond what JaCoCo exposes.

```json
{
  "coveredCount": 12345,
  "missedCount": 678,
  "totalCount": 13023,
  "coveredRatio": 0.948,
  "missedRatio": 0.052,
  "coverageStatus": "PARTLY_COVERED"
}
```

| Field | Source | Notes |
|---|---|---|
| `coveredCount` | `ICounter.getCoveredCount()` | int |
| `missedCount` | `ICounter.getMissedCount()` | int |
| `totalCount` | `ICounter.getTotalCount()` | int, equals `coveredCount + missedCount` |
| `coveredRatio` | `ICounter.getCoveredRatio()` | double, `null` JSON when JaCoCo returns `NaN` (i.e. `totalCount == 0`) |
| `missedRatio` | `ICounter.getMissedRatio()` | double, same NaN→null rule |
| `coverageStatus` | `ICounter.getStatus()` mapped to constant name | one of `EMPTY`, `NOT_COVERED`, `FULLY_COVERED`, `PARTLY_COVERED` (= `NOT_COVERED \| FULLY_COVERED`, bit-flag in JaCoCo) |

`coverageStatus` value is the literal name of the constant — it
disambiguates from `coverageState` (run lifecycle) and any other
"status" in the surface.

## Plugin classes

| Class | Role |
|---|---|
| `CoverageHandler` | `/coverage/run`, `/coverage/dump`, `/coverage/refresh`, `/coverage/relaunch`. |
| `CoverageSessionHandler` | `/coverage/runs`, `/coverage/session`, `/coverage/active`, `/coverage/activate`, `/coverage/merge`, `/coverage/remove`. |
| `CoverageProgressStreamer` | `/coverage/session/stream`. Filters `ISessionListener` and `IJavaCoverageListener` events by `coverageId`. |
| `CoverageTracker` | `Map<coverageId, CoverageRun>`. Implements `ISessionListener` + `ILaunchesListener2`. |
| `CoverageAnalyzer` | Wraps `SessionAnalyzer.processSession` with cache `IdentityHashMap<ICoverageSession, CachedAnalysis>`. Entry point `ensureAnalyzed(session)`. |
| `CoverageTypes` | Computes the supported launch type ID set by querying `LaunchManager.getLaunchConfigurationType(typeId).getDelegates(Set.of("coverage"))` for each of the 9 EclEmma-registered candidates. |

All six classes load lazily — the bridge imports no EclEmma class at
plugin start.

## EclEmma absence handling

`org.eclipse.eclemma.core` is declared `Require-Bundle` with
`resolution:=optional` in the bridge `MANIFEST.MF`.

Coverage classes live in a separate package
`io.github.kaluchi.jdtbridge.coverage` that gets loaded only through
a single guarded entry in `HttpServer.dispatch`:

```java
case "/coverage/run", "/coverage/dump", "/coverage/refresh",
     "/coverage/relaunch",
     "/coverage/runs", "/coverage/session", "/coverage/session/stream",
     "/coverage/active", "/coverage/activate",
     "/coverage/merge", "/coverage/remove" ->
    CoverageBridge.isAvailable()
        ? CoverageBridge.dispatch(path, params, body)
        : Response.json(coverageNotInstalledError());
```

`CoverageBridge.isAvailable()`:
`Platform.getBundle("org.eclipse.eclemma.core") != null`, cached
after first call. Loads no EclEmma class.

`/coverage/*` and `/test/run?coverage=true` are the only routes
gated by this check.

## Test integration: `coverage` flag on `/test/run`

`GET /test/run?class=<fqn>&...&coverage=true`

When `coverage=true` is present:
- `TestHandler.prepareLaunch(...)` runs unchanged (config find-or-create
  by 4 attributes, PDE-headless attributes for plugin tests). See
  [bridge-test-spec § Config reuse](bridge-test-spec.md#config-reuse).
- The launch type is validated against `CoverageTypes.SUPPORTED` —
  if not supported, `coverage-mode-not-supported` error.
- `pl.config().launch("coverage", null, true)` instead of `RUN_MODE`.
- Response includes `coverageId` and `launchMode: "coverage"` alongside
  the usual `testRunId`, `launchId`, `configId`. `coverageId` and
  `testRunId` are byte-identical strings (same `ATTR_LAUNCH_TIMESTAMP`).

```json
{
  "ok": true,
  "configId": "MyTest",
  "launchId": "MyTest:6408",
  "testRunId": "MyTest:1777078913423",
  "coverageId": "MyTest:1777078913423",
  "reused": true,
  "project": "my-project",
  "runner": "JUnit 5",
  "launchMode": "coverage",
  "processPid": "6408"
}
```

The `launchMode` field is omitted when `coverage=false` (i.e. the
existing `/test/run` shape is unchanged for the no-coverage path).

## Errors

| kind | thrown | When |
|---|---|---|
| `coverage-not-installed` | `CoverageNotInstalled` | EclEmma bundle absent |
| `coverage-config-not-found` | `CoverageConfigNotFound` | `configId` missing in `LaunchManager` |
| `coverage-mode-not-supported` | `CoverageModeNotSupported` | launch type lacks coverage delegate; `:supportedTypeIds` in context |
| `coverage-not-found` | `CoverageNotFound` | `coverageId` missing in `CoverageTracker` |
| `coverage-dump-not-found` | `CoverageDumpNotFound` | `coverageId:N` with N out of range |
| `coverage-launch-terminated` | `CoverageLaunchTerminated` | `dump` on terminated `CoverageRun` |
| `coverage-launch-not-live` | `CoverageLaunchNotLive` | `dump` / `relaunch` on session whose `coverageSessionKind != "live"` (merged or imported has no `ILaunch`) |
| `coverage-no-active-session` | `CoverageNoActiveSession` | `refresh` / `relaunch` / `remove` (no body) when `getActiveSession() == null` |
| `coverage-merge-too-few-inputs` | `CoverageMergeTooFewInputs` | `/coverage/merge` with `<2` IDs |
| `coverage-no-data` | `CoverageNoData` | live launch terminated with `AgentServer.hasDataReceived() == false` (no dumps ever arrived from agent) |
| `coverage-analysis-failed` | `CoverageAnalysisFailed` | `SessionAnalyzer.processSession` threw |
| `coverage-analysis-cancelled` | `CoverageAnalysisCancelled` | `LoadSessionJob` cancelled mid-flight (e.g. by activation switch) |
| `coverage-agent-jar-missing` | `CoverageAgentJarMissing` | maps EclEmma's `NO_LOCAL_AGENTJAR_ERROR` (5000) — JaCoCo agent jar not extractable |
| `coverage-exec-file-error` | `CoverageExecFileError` | maps `EXEC_FILE_CREATE_ERROR` (5004) / `EXEC_FILE_READ_ERROR` (5005) |

EclEmma `EclEmmaStatus` codes (5000-5008, 5011-5014, 5101 — see
`EclEmmaStatus.java:69-149`) are mapped into the categories above
where they bubble up to API. Codes that stay internal to EclEmma's
UI (prompts, dialogs) do not surface.

## Constraints

- **`ICoverageSession` is identity-only.** `CoverageSession.equals` is
  not overridden. The bridge's `Map<coverageId, CoverageRun>` is the
  only stable identifier surface.

- **Sessions are session-scoped.** Restarting Eclipse clears
  `SessionManager.getSessions()`. Bridge tracker rebuilds empty —
  no persistence of `coverageId` across Eclipse restarts. (`.exec`
  files in `<plugin-state>/.execdata/` are deleted on plugin start
  and stop, see `ExecutionDataFiles.deleteTemporaryFiles`.)

- **Method coverage resolution is lazy.**
  `JavaModelCoverage.getCoverageFor(IMethod)` triggers
  `MethodLocator(parentType)` on first hit per type. Not thread-safe
  (`HashMap`, no synchronization). Bridge serializes `CoverageAnalyzer`
  access per session to avoid races.

- **Activation cancels in-flight analysis.**
  `JavaCoverageLoader.sessionListener.sessionActivated` calls
  `Job.getJobManager().cancel(LOADJOB)` before scheduling a new
  `LoadSessionJob`. The previous active session's analysis is
  partial — bridge re-runs it through `CoverageAnalyzer.ensureAnalyzed`
  if a query for it arrives, since the bridge cache entry was
  populated only on `ready` event.

- **`coverageScope` wire format is JDT handle identifiers.**
  `IPackageFragmentRoot.getHandleIdentifier()` returns strings like
  `=MyProject/src\\/main\\/java`. CLI rendering converts to human
  paths via `JavaCore.create(handleId).getResource().getLocation()`.

- **`coverageScope` is `getConfiguredScope`, not `getOverallScope`.**
  `ScopeUtils.getConfiguredScope(config)` applies either the
  explicit `ATTR_SCOPE_IDS` selection or `DefaultScopeFilter` from
  preferences (`getDefaultScopeSourceFoldersOnly` filters out binary
  roots by default). The wire `coverageScope` field reflects what
  EclEmma actually instruments, not the broader `getOverallScope` set.

## Files

  coverage/CoverageBridge.java          — guard + dispatch
  coverage/CoverageHandler.java         — `/coverage/run`, `/dump`, `/refresh`, `/relaunch`
  coverage/CoverageSessionHandler.java  — `/coverage/runs`, `/session`, `/active`, `/activate`, `/merge`, `/remove`
  coverage/CoverageProgressStreamer.java — `/coverage/session/stream`
  coverage/CoverageTracker.java         — `Map<coverageId, CoverageRun>`
  coverage/CoverageAnalyzer.java        — analysis cache
  coverage/CoverageTypes.java           — supported launch type IDs
  HttpServer.java                       — dispatch for `/coverage/*`
  TestHandler.java                      — `coverage=true` flag
  MANIFEST.MF                           — `org.eclipse.eclemma.core;resolution:=optional`
