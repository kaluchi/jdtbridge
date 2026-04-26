# jdt coverage analyze — Design Spec

Cross-cutting principles, vocabulary contract, and edge-case
constraints for the `:jdt/coverage` qlang axes layered on the
coverage analysis cache. Detail surfaces split across:

- CLI commands and onboarding texts → `jdt-coverage-spec.md`
- qlang axes and conduits → `jdt-query-spec.md`
- HTTP endpoint and node-Map shape → `bridge-coverage-spec.md`

## Context

`CoverageAnalyzer` (this plugin) caches `IJavaModelCoverage` per
`ICoverageSession`, including raw JaCoCo `SessionInfo` and
`ExecutionData`. The `:jdt/coverage` axes expose this cache as a
qlang queryable surface — point lookups, per-line breakdowns,
predicate composition with `:jdt/graph`, no rebuild of session
lifecycle.

## Design principles

### 1. Mirror EclEmma idioms

The user works in Eclipse with full GUI — Coverage View tree,
gutter coloring, session dropdown, every menu including those the
CLI doesn't expose. The CLI surface is for the agent. Where a
choice exists between mirroring an EclEmma idiom and introducing
a new one, the CLI mirrors EclEmma; the user's mental model
carries over from the GUI verbatim.

### 2. Scope inherits from the launch config

The analysis scope (`Set<IPackageFragmentRoot>`) is fixed at launch
time inside the `ILaunchConfiguration` (`ATTR_SCOPE_IDS`) and
rides on `ICoverageLaunch.getScope()`. The CLI does not accept
`--scope`, `--exclude`, or any scope-shaping flag — those belong
in the Eclipse launch config UI. The CLI reads the scope from the
session, surfaces it as `:coverageScope` in the response, and
otherwise takes it as given.

### 3. Agent is the primary consumer

The CLI surface is shaped for an agent reading text outputs and
composing follow-up commands. Pair-work scenarios where the human
runs coverage in Eclipse and then asks the agent to analyze the
result are the dominant case. Agent autonomy (CI bots,
auto-driven coverage gates) is the secondary case. The two share
the same surface; the agent never needs special flags. The
human's path is "click in Eclipse, ask agent" — the CLI is not on
their radar at all.

### 4. Contextual onboarding by lifecycle moment

Analysis-command discovery surfaces at the moment the agent is
in the relevant context. Six lifecycle moments carry onboarding
(§ Lifecycle onboarding): `jdt help` shows only the canonical
happy-path commands; `jdt coverage run` carries an "Analyze
results" section in its guide; `analysisReady` events in `-f`
streams emit a 3-line tail; the full reference lives in
`jdt help coverage analyze`.

### 5. Top-5 user questions resolve in 1-2 commands

The five most frequent intents (changed-files coverage, uncovered
lines in a file, project ratio, untested methods, tests covering
a method) each map to a single qlang pipeline that the onboarding
text shows verbatim. The agent does not assemble these from
primitives — it copies a template, substitutes the fqn, and runs.

### 6. Composition with `:jdt/graph` axes

`:jdt/coverage` axes share `pipeValue` semantics with `:jdt/graph`,
so coverage and graph predicates compose in a single pipeline.
Example: `@methods | filter(@untested) | filter(@callers | empty | not)`
selects methods with zero instruction coverage and at least one
caller — the "hot uncovered" set — in one expression.

## Top-20 user questions

Frequency = probability the question appears at least once in a
typical pair session (commit / PR / triage / debugging). Cluster
classifies the workflow archetype it belongs to (§ Workflow
archetypes).

| # | Question | P | Cluster |
|---|---|---|---|
| 1 | Coverage of files I changed in this session/PR | 0.95 | PR-diff |
| 2 | Uncovered lines in `pkg.Foo` | 0.90 | drill |
| 3 | Untested methods in my project/package | 0.85 | gap-find |
| 4 | Overall project/package ratio | 0.80 | summary |
| 5 | Which tests cover `pkg.Foo#bar` | 0.70 | TIA |
| 6 | Top-N worst-covered classes | 0.65 | triage |
| 7 | Coverage by package — where to invest | 0.60 | drill |
| 8 | Branch / condition coverage of a method | 0.55 | depth |
| 9 | Hot but uncovered (callers > 0, coverage = 0) | 0.50 | risk |
| 10 | Public API surface without tests | 0.50 | API-surface |
| 11 | Did this commit drop coverage? Which lines? | 0.45 | regression |
| 12 | Coverage of branch's new code vs master | 0.45 | branch-diff |
| 13 | Diff between two sessions (unit + integration) | 0.40 | session-diff |
| 14 | Hit count for a specific line | 0.35 | forensics |
| 15 | All `@Service` methods below 80% (custom gate) | 0.30 | annotated-gate |
| 16 | Top untested weighted by cyclomatic complexity | 0.30 | risk-weighted |
| 17 | Coverage excluding generated / boilerplate | 0.30 | filter-noise |
| 18 | What does test X cover (forward, per-test) | 0.25 | test-impact |
| 19 | Markdown card for `OrderController` PR review | 0.25 | reporting |
| 20 | Uncovered but overridden in subclasses (dead?) | 0.15 | dead-code |

The five-question core (1-5) accounts for >70% of all coverage
traffic in a typical session. If the surface covers only this
core, it is already useful. The remaining 15 questions add depth
for legacy-triage and quality-reporting workflows.

## Workflow archetypes

| Archetype | Frequency | Dominant questions |
|---|---|---|
| **TDD inner loop** | 10-30× / day | 1, 2, 5, 18 — "did my new test exercise this method", "what tests already cover it" |
| **Pre-commit / pre-PR** | 3-10× / day | 1, 11, 12 — diff coverage, drop detection |
| **Code review** | 1-5× / day | 1, 12, 19 — visualize PR changes |
| **Sprint planning / refactoring** | 1-2× / week | 3, 6, 7, 9, 10, 16 — where to invest |
| **Debugging "test passes but…"** | ~1× / week | 5, 8, 14 — depth mode |
| **Quality reporting** | 1× / sprint | 4, 15, 17 — governance |

The TDD inner loop is the densest cluster. A 30-second analysis
turn at each iteration outweighs every other coverage activity in
the week — onboarding texts must keep the question→command path
to a single line for these queries.

## Style-of-project × dominant questions

Different codebases foreground different questions:

| Project style | Foreground | Top questions |
|---|---|---|
| Library / framework / SDK | Public API surface, semver-stable points | 10, 3, 5 |
| Service / business logic | Branch coverage, integration vs unit split | 8, 13, 9 |
| Plugin (`jdtbridge`-shape) | Handlers, refactoring tools, gateway classes | hybrid: 1, 2, 9, 10 |
| Pure logic / algorithms | Branch coverage ↑↑, complexity-weighted | 8, 16 |
| DTO / persistence | Often excluded entirely | 17 |
| UI / web | Integration tests only; unit meaningless | 13 |

Team-style further refines: regulated/banking idioms run from
metrics and gates (4, 15, 11); product startups run from
functions and gaps (3, 5, 19); open-source maintainers run from
PR-diffs (1, 12); legacy-rescue teams run from risk-weighted
triage (6, 9, 16).

## EclEmma + JaCoCo data model

The data lives in two stacks. Designs of axes and conduits must
respect what each layer provides natively.

### JaCoCo `org.jacoco.core.analysis`

`ICoverageNode` is the root abstraction. The hierarchy:

```
ICoverageNode
├── ISourceNode (carries getFirstLine, getLastLine, getLine(int))
│   ├── IClassCoverage (id=CRC64, methods, source file name)
│   ├── IMethodCoverage (binary signature)
│   └── ISourceFileCoverage
├── IPackageCoverage (classes, source files)
└── IBundleCoverage (packages)
```

Per-line data is exposed only on `ISourceNode` subtypes —
`IClassCoverage`, `IMethodCoverage`, `ISourceFileCoverage`. Node
types `IPackageCoverage` and `IBundleCoverage` are aggregation
levels with no line breakdown.

`ICounter`: `getCoveredCount`, `getMissedCount`, `getTotalCount`,
`getCoveredRatio` (NaN when total=0), `getStatus` returning bit
flags `EMPTY=0x00`, `NOT_COVERED=0x01`, `FULLY_COVERED=0x02`,
`PARTLY_COVERED=0x03`. Six canonical counter types per node:
`INSTRUCTION`, `BRANCH`, `LINE`, `COMPLEXITY`, `METHOD`, `CLASS`.

`ILine` (returned by `ISourceNode.getLine(int)`):
`getInstructionCounter`, `getBranchCounter`, `getStatus`. Status
on a single line is one of the four flags, derived from the
combined instruction + branch counters.

`ExecutionData` (`org.jacoco.core.data`): per-class probe array,
keyed by CRC64 id (matches `IClassCoverage.getId()`). The CRC64
is the only stable cross-run identity for a class.

### EclEmma adapter — `IJavaModelCoverage`

The bridge between JDT model elements and JaCoCo coverage data.
Single primary method:

```
ICoverageNode getCoverageFor(IJavaElement element)
```

Polymorphic over `IJavaElement` kind:

| IJavaElement kind | Returned ICoverageNode subtype |
|---|---|
| `IJavaProject` | `CoverageNodeImpl` (aggregated across roots) |
| `IPackageFragmentRoot` | `IBundleCoverage` |
| `IPackageFragment` | `IPackageCoverage` |
| `IType` | `IClassCoverage` |
| `IMethod` | `IMethodCoverage` (lazy, resolved on first request) |
| `ICompilationUnit` | `ISourceFileCoverage` |
| `IClassFile` | `IClassCoverage` |
| `IField` | `null` |

`IField` returning `null` is the canonical signal that JaCoCo
does not model field coverage — fields exist as `<init>` /
`<clinit>` byte sequences only. Designs targeting field-level
coverage must accept this constraint and surface it as a typed
error, not a silent miss.

The IMethod path runs lazy: on first `getCoverageFor(method)`
call, EclEmma's `MethodLocator` indexes every method of the
declaring type by name + parameter signature, then maps each
`IMethodCoverage` (carrying JaCoCo's binary `name + descriptor`)
back to its corresponding `IMethod`. `<init>` constructor names
in JaCoCo descriptors map to the type's simple name in IMethod —
EclEmma handles this substitution internally; callers pass
ordinary IMethod handles for constructors.

The signature reconciliation (parameter types in JDT erased
notation versus JaCoCo's JVM descriptors with resolved type
qualifiers, generics-bound substitution) is handled inside
EclEmma's `SignatureResolver`. Callers do not need to convert.

### Multi-session access

EclEmma's `JavaCoverageLoader` holds `IJavaModelCoverage` only
for the active session, refreshed on `sessionActivated`. The
bridge plugin's `CoverageAnalyzer` runs `SessionAnalyzer.processSession`
directly per requested session and caches the result by
`ICoverageSession` identity (`IdentityHashMap`). Queries can
therefore target any tracked session, not only the active one,
without changing the active session in the IDE.

## Identity reconciliation

The CLI's domain key is the `:fqn` String:

```
pkg.Outer                          type
pkg.Outer.Inner                    nested type
pkg.Outer#name(ParamFqn,...)       method (parameter types in
                                   JDT-erased notation)
pkg.Outer#name                     field, or unambiguous method
pkg.Outer#enclose(...).() -> {...} I
                                   lambda (synthetic IType)
pkg.Outer#enclose(...).new I() {...}
                                   anonymous class
```

The plugin's `JdtUtils.resolveElement(String fqn)` resolves any
of these shapes to an `IJavaElement`. Synthetic types (lambda
and anonymous) require AST traversal: lambdas via
`LambdaExpression.resolveMethodBinding().getJavaElement()` →
declaring IType; anonymous via parent member's `getChildren()`
filtered by simple superinterface name.

The fqn → IJavaElement → ICoverageNode chain runs unchanged
through every coverage axis. Conversion to JaCoCo VM names
(slashes, descriptors) is internal to EclEmma; the CLI never
sees it.

Subject polymorphism for axes: every `:jdt/coverage` axis
accepts either a node-Map (skeleton or detail) or a String fqn
in `pipeValue`, mirroring the `:jdt/graph` convention. Resolution
is identical — `JdtUtils.resolveElement(fqn)` for String,
`/fqn` projection then resolve for Map.

## EclEmma vocabulary contract

All user-visible text in the CLI surface — onboarding hints,
markdown card section titles, table column headers, status
labels — uses EclEmma vocabulary verbatim. Source of truth:
`org.eclipse.eclemma.ui/src/.../uimessages.properties`.

### Counter names (six types)

| Wire field | Eclipse Coverage View label | JaCoCo HTML |
|---|---|---|
| `instruction` | `Instructions` | Instructions |
| `branch` | `Branches` | Branches |
| `line` | `Lines` | Lines |
| `method` | `Methods` | Methods |
| `class` | `Types` | Classes |
| `complexity` | `Complexity` | Cxty |

EclEmma uses `Types` (not JaCoCo's `Classes`); the markdown card
follows EclEmma. Wire field stays `class` to match JaCoCo's
`CounterEntity.CLASS`.

### Tree column titles (verbatim)

`Element`, `Coverage`, `Covered Instructions/Branches/Lines/...`
(plus `Missed`, `Total` rows). Property page columns:
`Counter`, `Coverage`, `Covered`, `Missed`, `Total`. The triple
`Covered / Missed / Total` is the canonical rendering of any
counter row; never abbreviate.

### Ratio format

`0.0 %` — one decimal, **space before `%`** (NBSP ` ` in
EclEmma's properties file). Empty string when total = 0.
Decorator suffix on element names: `(0.0 %)` with a leading
space, exactly as EclEmma's `CoverageDecoratorSuffix_label`.
Counter values use locale integer format with thousands
separators (`DecimalFormat.getIntegerInstance()`).

### Status labels

Wire enum maps to colored line states:

| `coverageStatus` | UI annotation | Color |
|---|---|---|
| `FULLY_COVERED` | Full Coverage | green |
| `PARTLY_COVERED` | Partial Coverage | yellow |
| `NOT_COVERED` | No Coverage | red |
| `EMPTY` | (no annotation) | none |

The line-iteration paradigm (verbatim from
`CoverageAnnotationModel.createAnnotations`):

```
for line in [firstLine, lastLine]:
    if ILine.getStatus() != EMPTY:
        emit annotation by status
```

Lines outside `[firstLine, lastLine]` and lines with no
instructions are EMPTY — skipped from per-line breakdown.

### Branch hover text

When a markdown card describes per-line branch coverage, follow
the three NLS templates from EclEmma:

```
All {0} branches missed.
All {0} branches covered.
{0} of {1} branches missed.
```

## Lifecycle onboarding moments

Six positions where the inventory of analysis commands surfaces.
Each is gated on `-q` / silenced after first encounter.

### M1 — Entry: `jdt help`, `jdt help <cmd>`

Minimal. Lists the canonical commands and 2-3 sister commands.
No advanced flags, no analyze examples. The agent never feels
overwhelmed at first contact.

### M2 — After `jdt test run <fqn>`

Single contextual line at the end of the existing test-run
guide, only when `--coverage` was NOT used:

```
Run with coverage instrumentation:
  jdt test run <fqn> --coverage          adds CoverageId/CoverageScope to header
```

### M3 — `jdt coverage run <id>` guide

The run-guide carries five sections: status / logs / manage /
sessions / analyze. The analyze block:

```
**Analyze results** (after analysisReady):
  jdt q '"<class>" | @coverage | /counters'        per-counter ratios
  jdt q '"<class>" | @uncoveredLines'              line-level gaps
  jdt q '"<class>" | @methods | filter(@untested) * /fqn'
                                                    untested methods
  jdt q '"<class>" | @coverageCard'                 md card for PR review

  jdt help coverage analyze                         full reference
```

Analysis targets the active session unless pinned via `M6`.

### M4 — Stream tail after `analysisReady`

Three-line tail in `formatStreamEvent`:

```
[hh:mm:ss] ready #1 — instructions 94.8%, branches 80.7%, ...

Next: jdt q '"<class>" | @uncoveredLines'   line gaps
      jdt q '"<class>" | @coverageCard'     md card
      jdt help coverage analyze             full reference
```

### M5 — `jdt coverage status` snapshot tail

Same 3-line tail in `formatStatusSnapshot` when
`analysisReady=true`. Covers the entry path where the agent
joins a session created in Eclipse without going through M3.

### M6 — `jdt help coverage analyze`

New sub-help. Full catalog of `:jdt/coverage` axes and
conduits, subject polymorphism rules, composition examples with
`:jdt/graph`, edge cases (field=null, per-line=ISourceNode).
This is the depth surface — agent reaches it only when M3/M4/M5
pointed there.

## CLI ergonomics

### Collapsed line ranges

Per-line gap output collapses consecutive line numbers into
ranges: `33-35, 39, 41-50` instead of
`33, 34, 35, 39, 41, 42, 43, ..., 50`. Collapse happens in the
`mdCoverage` renderer; the server emits raw `Vec<int>`.

### Text-summary micro-format

A 4-line block of `Metric : XX% (n/m)` per counter, no per-file
detail. Available as a render mode of `@coverageSummary`.

### Color-coded ratios

Ratio percentages render colored: red <50, yellow 50-80, green
>80. The same `composeStatus` palette used elsewhere applies to
ratio columns in tables.

### TOTAL row pinned at bottom of tables

Per-package and per-class coverage tables carry a synthetic
TOTAL row for the aggregate.

### `@onlyGaps` filter

Conduit `@onlyGaps` keeps elements below 100% coverage,
expressed as `filter(/counters/instruction/coveredRatio | lt(1.0))`.

### Boundaries

- Threshold gates are agent-side qlang predicates plus exit-code
  mapping; no `--fail-under=N` flag.
- Per-test (testwise) attribution comes from running each test
  as its own launch (test-FQN configId) so the session's data
  IS the testwise mapping. No `--show-contexts`-style labeling
  inside a multi-test session — JaCoCo's session model does not
  carry it.
- Diff coverage is a qlang join over `@coverageOf(:idA)` vs
  `@coverageOf(:idB)` composed with git axes. No dedicated
  server endpoint.

## Architectural constraints

### Field coverage = null

`IJavaModelCoverage.getCoverageFor(IField)` returns `null`
unconditionally — JaCoCo's data layer carries no field-level
coverage. The `@coverage` axis on a `:field` subject returns a
typed error value:

```
!{:kind :coverage-no-data-for-element
  :elementKind "field"
  :message "Field-level coverage is not modeled by JaCoCo"
  :fqn "..."}
```

The error rides the fail-track. Filter idiom for a mixed-member
iteration: `... | filter(/kind | eq("field") | not)` before
applying coverage axes.

### Per-line breakdown only for `ISourceNode`

`@coveredLines`, `@uncoveredLines`, `@partialLines` accept only
subjects whose `ICoverageNode` is an `ISourceNode` —
`:type`, `:method`, `:file` (compilation unit). On
`:project`, `:package`, `:packageFragmentRoot` subjects the
axes return:

```
!{:kind :coverage-line-not-supported
  :elementKind "package"
  :message "Per-line breakdown is available on type/method/file only"}
```

Counters are still available at every level via `@coverage`.

### Synthetic types — lambdas and anonymous classes

`JdtUtils.resolveElement` resolves both. EclEmma's
`TypeTraverser.processAnonymousInnerTypes` walks anonymous
inner types via `getChildren()`. JaCoCo emits each lambda /
anonymous class as a separate `IClassCoverage` with a
`$N`-suffixed VM name; EclEmma maps these back to their IType
through the binary-name traversal.

`@coverage` on a synthetic-type fqn returns counters for that
synthetic class.

## Glossary

Strict domain vocabulary. Every operand descriptor, doc-string,
example, and reshape literal in the codebase uses these terms.

| Term | Meaning |
|---|---|
| **counter** | One of six per-node aggregations: `instruction`, `branch`, `line`, `method`, `class`, `complexity`. Each carries `coveredCount`, `missedCount`, `totalCount`, `coveredRatio` (null when total=0), `missedRatio` (null when total=0), `coverageStatus`. |
| **coverageStatus** | Wire enum: `EMPTY`, `NOT_COVERED`, `PARTLY_COVERED`, `FULLY_COVERED`. Maps to EclEmma's annotation IDs (full/partial/no) and gutter colors (green/yellow/red). |
| **coverageScope** | The `Set<IPackageFragmentRoot>` defining what an analysis covers. Set at launch time via the launch config; immutable for the session's life. |
| **session** | One coverage analysis result, identified by `coverageId`. Live (one launch, multiple dumps), merged (combination of input sessions), or imported (loaded from `.exec`). |
| **untested** | Subject-level predicate: `coveredCount` of the `instruction` counter equals zero. Composes with `filter`, `every`, `any` over Vecs of method or type skeletons. |
| **gap** | A line whose status is `NOT_COVERED` or `PARTLY_COVERED`. The "gap" mental model groups partial and missing lines as actionable; fully covered and empty lines are not gaps. |
| **hot uncovered** | A method that has zero instruction coverage (`@untested`) but at least one incoming caller (`@callers | empty | not`). High-priority test targets. |
| **public gap** | An untested method declared `public`. Subset of `@untested` for API-surface auditing. |
| **risk-weighted** | Sorting key combining counter ratios with structural metrics (cyclomatic complexity, line count, caller count). Used for triage of legacy coverage. |
| **diff coverage** | Comparison of two coverage sessions, surfacing lines covered in one but not the other. Implemented as a qlang join over `@coverageOf(:idA)` and `@coverageOf(:idB)`, never as a server primitive. |
| **testwise** | Per-test attribution of covered lines. Achievable only when a test runs as its own launch (test-FQN configId, `--coverage`); then the session's data IS the testwise mapping. Multi-test sessions aggregate and lose per-test attribution. |

## Boundaries of the analyze surface

- **Trend over time** — each `coverageId` is point-in-time;
  trend storage lives in CI artifacts or SonarQube, not in the
  bridge.
- **Threshold gating** — qlang predicates plus agent-side
  exit-code mapping; no `--fail-under=N` CLI flag.
- **Exclude patterns** — EclEmma's `Coverage Runtime`
  preferences (`Includes:`, `Excludes:`, `Exclude classloaders:`)
  shape what the JaCoCo agent instruments. Per-query filtering
  of generated / Lombok / DTO classes goes through qlang
  `filter(...)`.
- **Diff coverage** — qlang join of two `@coverageOf` calls; no
  dedicated server endpoint.
- **HTML report rendering** — Coverage View → Export Session
  emits JaCoCo HTML. The analyze surface emits markdown cards
  (`@coverageCard`) suited to PR comments and agent context.
- **Inline source-annotated view** — `gcov`-style overlay is a
  composition of `@source` plus `@coveredLines` /
  `@uncoveredLines`, expressed as a render conduit when needed.

## References

- `jdt-coverage-spec.md` — CLI commands, identity vocabulary,
  STATUS composition, header format
- `bridge-coverage-spec.md` — server HTTP contract, lifecycle,
  counter shape on the wire
- `jdt-query-spec.md` — qlang query language, node-Map schemas,
  modifier convention, markdown rendering principles
- `jdt-test-spec.md` — `jdt test run --coverage` flag, header
  fields, onboarding guide format
- `jdt-launch-spec.md` — coverage launches in `jdt launch list`,
  console output via `launch logs`
- EclEmma sources (`D:/git/eclemma/`):
  - `org.eclipse.eclemma.core.../IJavaModelCoverage.java`
  - `org.eclipse.eclemma.internal.core.analysis.SessionAnalyzer.java`
  - `org.eclipse.eclemma.internal.core.analysis.JavaModelCoverage.java`
  - `org.eclipse.eclemma.internal.core.analysis.MethodLocator.java`
  - `org.eclipse.eclemma.internal.core.analysis.SignatureResolver.java`
  - `org.eclipse.eclemma.ui.../uimessages.properties`
  - `org.eclipse.eclemma.ui.../annotation/CoverageAnnotation.java`
  - `org.eclipse.eclemma.ui.../annotation/CoverageAnnotationModel.java`
- JaCoCo Javadoc — `org.jacoco.core.analysis`,
  `org.jacoco.core.data`
