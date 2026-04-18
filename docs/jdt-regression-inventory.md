# Regression inventory: pre-qlang CLI → :jdt/graph gaps

This is a working document. It catalogs exactly what the legacy
`jdt find` / `references` / `implementors` / `hierarchy` / `outline`
/ `source` / `projects` / `problems` commands produced before commit
`c5ff9ab "Delete legacy graph CLI commands; rename plugin endpoints
to clean names"`, and matches each against the closest `:jdt/graph`
pipeline available today. Every "Gap" section is a concrete
deliverable for the feature/jdt-q cleanup — either a markdown render
operand (see [jdt-query-spec § Markdown rendering](jdt-query-spec.md#markdown-rendering)),
a missing widening modifier, or a conduit to fill.

`jdt project-info` is not covered here — the legacy renderer's
adaptive tier logic was not useful in practice and no successor is
planned.

The document is scheduled for deletion when every Gap is closed
and `jdt-source-spec.md` is retired.

## Source recovery

The deleted sources are recoverable verbatim from the master
tip just before the deletion commit:

```bash
git show c5ff9ab^:cli/src/commands/find.mjs
git show c5ff9ab^:cli/src/commands/references.mjs
git show c5ff9ab^:cli/src/commands/implementors.mjs
git show c5ff9ab^:cli/src/commands/hierarchy.mjs
git show c5ff9ab^:cli/src/commands/outline.mjs
git show c5ff9ab^:cli/src/commands/source.mjs
git show c5ff9ab^:cli/src/commands/projects.mjs
git show c5ff9ab^:cli/src/commands/problems.mjs
git show c5ff9ab^:cli/src/format/hierarchy.mjs
git show c5ff9ab^:cli/src/format/references.mjs
```

## jdt find

**Legacy signature.** `jdt find <Name|*Pattern*|pkg.name> [--source-only] [--json]`

Wildcard pattern search + package listing. Flags: `--source-only`
(exclude binary/library types); `--json` (raw objects `[{kind,
fqn, binary, file, origin}]`).

**Legacy text output.** Flat list, one type per line:

```
[C] com.example.Foo
[I] com.example.Bar
[C] com.example.Baz  (binary)
```

**Current equivalent.** `@types("*Pattern*")` — works as a seed.
`@sourceOnly` conduit narrows to workspace source.

```bash
jdt q '"*Service" | @types * /fqn'
jdt q '"*Service" | @types | @sourceOnly * /fqn'
```

**Gaps.**

1. No `[C]` / `[I]` / `[E]` badge in text output — `table` prints
   columns `fqn | kind | origin | …` but LLM-readable inline
   `[badge] fqn` format is gone. Covered by the planned `mdTypes`
   render operand (badge inferred from `:typeKind`).
2. Package listing (`jdt find com.example.service`) — currently
   `"com.example.service" | @typesInPackage`, but the legacy form
   also accepted bare pkg names with auto-routing. Not a real gap;
   `@typesInPackage` is the successor.
3. `:sourceOnly` narrowing modifier on `@types` remains as a
   deprecated knob — see
   [jdt-query-spec § Modifier convention](jdt-query-spec.md#modifier-convention--widen-at-modifier-narrow-in-pipeline).

## jdt references

**Legacy signature.** `jdt references <FQN>[#method[(params)]] [--field name] [--json]`

Workspace references to a type / method / field. JSON shape
`[{file, line, in, content}]` (ref sites with the 1-line source
context).

**Legacy text output.**

```
[M] `com.example.Caller#invoke(String)`
  at MyCaller.java:42
    someService.process(input);
```

Grouped by declaring type via `format/references.mjs` (41 lines).

**Current equivalent.** `@refs` returns reference records (skeleton
nodes with `:refKind :from :location :source`-line). `@callers`
/ `@readers` / `@writers` conduits pre-filter by refKind.

```bash
jdt q '"com.example.Caller#invoke(String)" | @callers * /fqn | distinct'
jdt q '"com.example.Caller" | @refs(:typeUse)'
```

**Gaps.**

1. **No grouped markdown renderer.** `format/references.mjs`
   grouped refs by declaring type and printed one-line source
   context. Covered by the planned `mdRefs` render operand.
2. **`--field <name>` flag for separate field paths.** The legacy
   CLI accepted `jdt references pkg.Foo --field bar` when FQMN
   form was inconvenient; in `:jdt/graph` use `"pkg.Foo#bar" |
   @readers` / `@writers` directly.

## jdt implementors

**Legacy signature.** `jdt implementors <FQN>[#method[(params)]] [--json]`

Interface / abstract-method implementations. Dispatched between
type and method based on the FQN shape.

**Legacy text output.**

```
[C] com.example.FooImpl
[C] com.example.BarImpl
```

**Current equivalent.** `@implementors` axis — polymorphic by
subject kind (type → subtypes, method → override methods).

```bash
jdt q '"com.example.HasId" | @implementors * /fqn'
jdt q '"com.example.HasId#getId()" | @implementors * /fqn'
```

**Gaps.**

1. No badge / per-impl location inline in text mode. Covered by
   the planned `mdImplementors` render or folded into the broader
   `mdHierarchy`.

## jdt hierarchy

**Legacy signature.** `jdt hierarchy <FQN> [--json]`

Full type hierarchy: supertypes (↑) + subtypes (↓). JSON shape
`{type, supertypes[], interfaces[], subtypes[]}`.

**Legacy text output.** Tree with ↑ / ↓ arrows, `[C]` / `[I]` /
`[E]` / `[A]` badges, depth-indented, `file:line-range` under each
entry (from `format/hierarchy.mjs`, 49 lines):

```
#### com.example.Foo

#### Supertypes:
- ↑ [I] `com.example.HasId`
  `src/HasId.java:12-18`
- ↑ [C] `com.example.AbstractFoo`
  `src/AbstractFoo.java:8-42`

#### Subtypes:
- [C] `com.example.FooImpl`
  `src/FooImpl.java:15-40`
```

**Current equivalent.** `@supers` / `@subtypes` (direct) and
`@ancestors` / `@descendants` (transitive, recursive conduits).

```bash
jdt q '"com.example.Foo" | @supers * /fqn'
jdt q '"com.example.Foo" | @ancestors * /fqn'
jdt q '"com.example.Foo" | @descendants * /fqn'
```

**Gaps.**

1. **Tree rendering is lost.** No depth-indented markdown with
   ↑ / ↓ arrows and per-entry location. Covered by the planned
   `mdHierarchy` render operand — the headline regression from
   `c5ff9ab` for type-navigation UX.
2. No single pipeline delivers both supers and subs in one call
   today; two separate axes + manual composition. A dedicated
   conduit `@hierarchy` = `{:supers @ancestors :subs @descendants}`
   would let `mdHierarchy` consume one value.

## jdt outline

**Legacy signature.** `jdt outline <FQN> [--no-fields] [--no-static] [--public] [--no-local] [--sort] [--json] [-q]`

Eclipse Outline-View equivalent: structural tree of fields /
methods / inner types with modifiers, return types, signatures.
JSON shape `{fqn, file, kind, children: [{kind, name, …}]}`.

**Legacy text output.** Tree grouped by member kind, with badges
and signatures:

```
#### [C] com.example.MyService
src/MyService.java

[F] staffCache : List<Staff> (private)  12-12
[F] logger : Logger (private, static)  14-14
[M] MyService(DataSource) (12-24)
[M] getStaff() : List<Staff> (public)  26-30
[M] process(String) : void (public)  32-50
[C] Inner (static nested)  55-80
```

Flags filtered visibility tiers — `--public`, `--no-fields`,
`--no-static`, `--no-local` — and `--sort` toggled alphabetical vs
source order.

**Current equivalent.** `@members` (all children), or
`@methods` / `@fields` / `@innerTypes` (selective axes).

```bash
jdt q '"com.example.MyService" | @members | table'
jdt q '"com.example.MyService" | @methods | filter(/modifiers | any(eq("public"))) * /signature'
```

**Gaps.**

1. **No structural tree rendering.** Flat table works, but the
   legacy grouped-by-kind with indented signatures was the LLM's
   preferred way to digest an unknown type. Covered by the
   planned `mdOutline` render operand.
2. **`--sort` and per-kind visibility filters** map cleanly to
   qlang filters now (`sort(/name)`, `filter(/modifiers | any(eq("public")))`,
   `filter(/modifiers | not(any(eq("static"))))`), but have no
   one-line idiom. Easy to recover via conduits (`@public`,
   `@instance`) — see [jdt-query-spec § Open questions](jdt-query-spec.md#open-questions).

## jdt source

**Legacy signature.** `jdt source <FQMN> [<FQMN> ...] [--json]`

The richest legacy renderer. `source.mjs` was 308 lines. Per
viewed member: header `#### [badge] fqmn`, location
`` `file:start-end` ``, byte-exact `java` code fence, and refs
split into `#### Outgoing Calls:` / `#### Incoming Calls:` grouped
by declaring type, with return-type badge, `(static)`/`(inherited)`
annotations, first-sentence javadoc inline after ` — `, and nested
`  → [M] fqmn` for interface-method implementation refs. Type-level
emitted `#### Hierarchy:` with ↑ / ↓ and depth.

Covered in full by [jdt-source-spec](jdt-source-spec.md) — its
**Core Principles** (Server Exhaustive / Client Formats;
Deterministic Output; Zero-Modification Navigation; Badge-Link
Separation; Full Qualification; Byte-Exact Source; Same-Domain
Implementation Display; Incoming Calls Are Links Not Locations;
Resolve Type Parameter Bounds; Resolve @Override; Flat Calls) are
the rendering contract.

**Current equivalent.** `@source` returns `{:node :text}` — the
source bytes and the detail node-Map. Nothing else.

```bash
jdt q '"com.example.Foo#bar()" | @source | /text'
```

**Gaps.**

1. **The entire markdown card is missing.** Header / location
   / outgoing / incoming / implementations / hierarchy sections —
   none of it. This is the single largest regression from
   `c5ff9ab`. Covered by the planned `mdSource` render operand;
   the full principle set from `jdt-source-spec.md` has already
   been migrated into [jdt-query-spec § Markdown rendering
   contract](jdt-query-spec.md#rendering-contract).

## jdt projects

**Legacy signature.** `jdt projects [--json]`

Workspace project list with repo mapping. JSON shape
`[{name, location, repo}]`.

**Legacy text output.** Plain table `NAME | LOCATION | REPO`.

**Current equivalent.** `@projects | table`, and the shape
`{:fqn :kind :origin :rootPath :repo :branch}` of the canonical
node-Map — **richer** than the legacy shape (`:branch` is new,
`:kind` and `:origin` come from the canonical header).

Used by `jdt status` via:

```
@projects * inter(#{:fqn :rootPath :repo :branch}) | table
```

**Gaps.** None. Regression closed by commit `3e808cb`.

## jdt problems

**Legacy signature.** `jdt problems [--file <path>] [--project <name>] [--warnings] [--all] [--json]`

Eclipse IMarker.PROBLEM markers. JSON shape
`[{file, line, col, severity, message}]`. Default scope: errors
only; `--warnings` included warnings; `--all` included every
marker type (jdt + checkstyle + maven + …).

**Legacy text output.** `file:line:col: severity: message` per
marker.

**Current equivalent.** `@problems` — nullary, with optional
scope modifier `:workspace` / `:project` / `:file`.

```
@problems | table
```

Used by `jdt status` in both text and JSON modes.

**Gaps.**

1. **Severity filter.** Legacy default was errors-only;
   `@problems` currently returns every severity. Recover via
   `filter(/severity | eq("error"))`, or a conduit `@errorsOnly`.
   Decision pending — see [jdt-query-spec § Open questions
   #1](jdt-query-spec.md#open-questions) (string vs keyword for
   `:severity`).
2. **Marker-type widening (`--all`).** Legacy `--all` included
   non-jdt markers (checkstyle, maven). No equivalent yet; an
   `:allMarkerTypes` widening modifier on `@problems` would fit
   the modifier convention.
3. **Scope narrowing at `--file`/`--project`.** Already present
   as modifiers `:file` / `:project` / `:workspace` — admissible
   narrowing because `:workspace` is prohibitively expensive as
   a default.

## JSON removal — landed

**Decision (2026-04-18, implemented).** The minimum-viable rule:

1. **qlang-sourced output stays qlang** — no conversion to tagged
   or plain JSON on the CLI side. `jdt q` has no `--json` flag.
2. **Plain-JSON passthrough commands keep `--json`** unchanged —
   `jdt git / editors / launch list / launch configs / launch
   config / test runs / agent list / use / setup remote` all
   operate as before. JSONL streaming (`jdt test run -f / test
   status -f`) unchanged.
3. **Composite "mixed" commands drop `--json` entirely** —
   `jdt status --json` removed. The dashboard is markdown-only;
   machine-readable access goes through the individual source
   commands.

This was the narrow, zero-risk subset of the broader inventory
below — it eliminated the three-way-formalism confusion without
breaking any existing plain-JSON consumer. Full scope analysis
preserved below for reference.

## JSON removal scope (original full analysis)

Exploratory inventory of the `--json` surface across the CLI. Goal:
assess the cost of eliminating JSON as an output format entirely and
adopting qlang-literal (via `printValue`) as the universal raw-data
form for every command. Markdown stays the human-rendered form; text
tables stay; JSON is the one that goes.

### Why consider it

- Three formalisms live in parallel today — qlang-literal (`jdt q`
  default), tagged JSON (`jdt q --json`), plain JSON (everything
  else via `output.mjs` / `printJson`). Consumers must know which
  command emits which.
- Tagged JSON is not a human format (`{"$keyword": "foo"}`,
  `{"$map": [[…]]}`) — it exists only as a bridge between
  processes; the human reads `printValue` output.
- Plain JSON is lossy — keyword / Set / Error identity is erased;
  `fromPlain(toPlain(v))` is identity only when `v` carries no
  lossy shape.
- qlang-literal round-trips losslessly (`parse + evalQuery`), is
  LLM-readable as-is, and composes natively: `jdt editors | jdt q
  'filter(/active)'` works without `jq` bridging.

### Commands currently emitting JSON

Read-only (candidates for JSON removal):

| Command | JSON form | Shape |
|---|---|---|
| `jdt q` | tagged JSON | `toTaggedJSON(result)` — `{"$map": …}`, `{"$keyword": …}` |
| `jdt status` | composite plain JSON | `{ git: [...], editors: [...], problems: [...], … }` (mixed sources; qlang sections post-processed via `fromTaggedJSON + toPlain`) |
| `jdt git` | plain JSON via `output()` | `[{ name, path, branch, dirty, files }]` |
| `jdt editors` | plain JSON via `output()` | `[{ fqn, project, path, active }]` |
| `jdt launch list` | plain JSON via `output()` | `[{ launchId, configId, configType, mode, pid, terminated }]` |
| `jdt launch configs` | plain JSON via `output()` | `[{ configId, configType, project?, class?, goals? }]` |
| `jdt launch config <id>` | plain JSON via `output()` | `{ configId, type, file, attributes }` (+ `--xml` orthogonal) |
| `jdt test runs` | plain JSON via `output()` | `[{ configId, testRunId, state, total, passed, failed }]` |
| `jdt agent list` | plain JSON via `output()` | agent sessions |
| `jdt use` | plain JSON via `printJson` | `[{ index, alias, workspace, status, pinned, port }]` |
| `jdt use --pins` | plain JSON via `printJson` | pin-file listing |
| `jdt setup remote` | plain JSON via `printJson` | instance config + cached projects |

Streaming (special — JSONL, not static JSON; orthogonal concern):

| Command | Shape |
|---|---|
| `jdt test run -f --json` | JSONL events (per-test-case start/end) |
| `jdt test status -f --json` | JSONL events |

### Common infrastructure

| File | Role | After removal |
|---|---|---|
| `cli/src/output.mjs` | Strategy dispatcher with `json` as a built-in format + `text` per-command renderer | Simplify to text-only; drop `BUILT_IN.json` branch |
| `cli/src/json-output.mjs` | `printJson` + `remapJsonPaths` (sandbox path remap) | Delete; sandbox-path remap either migrates to text renderers or is dropped for qlang-native paths (node-Map `/location/file` already absolute) |
| `cli/src/commands/query.mjs` | `toTaggedJSON` branch | Delete; qlang-literal always |
| `cli/src/commands/status.mjs` | `JSON_COMMANDS` + `fromTaggedJSON + toPlain` bridge | Delete; status becomes markdown-only dashboard |

### Test surface

`--json` occurrences across 8 test files (50 total):
- `cli/test/commands.json.test.mjs` — 22 (centralized JSON assertions for every command)
- `cli/test/setup-remote.test.mjs` — 8
- `cli/test/use.test.mjs` — 4
- `cli/test/test-commands.test.mjs` — 4
- `cli/test/agent.test.mjs` — 4
- `cli/test/status.test.mjs` — 4
- `cli/test/commands.launch.test.mjs` — 2
- `cli/test/query.test.mjs` — 2

Most tests delete outright with the feature. A handful (that assert
on table rendering when `--json` is absent) keep their default-mode
branch; JSON-mode branches delete. `commands.json.test.mjs` — the
whole file likely retires.

### Doc surface

`--json` mentions across 10 spec files (54 total):
- `jdt-setup-remote-spec.md` — 10
- `jdt-regression-inventory.md` — 12 (this doc — meta)
- `jdt-spec.md` — 7 (including the top-level `Commands with --json` table)
- `jdt-launch-spec.md` — 6
- `jdt-status-spec.md` — 6
- `jdt-test-spec.md` — 5
- `jdt-query-spec.md` — 4
- `jdt-use-spec.md` — 2
- `jdt-launch-config-spec.md` — 1
- `jdt-agent-spec.md` — 1

Single pass updates the `Commands with --json` table in
`jdt-spec.md` (deleted) and every per-command `--json` line in
neighbouring specs (deleted).

### Breakdown by decision

**Clearly retires with qlang-as-output:**

- `--json` flag and the whole `output.mjs` JSON strategy.
- `json-output.mjs` — including `remapJsonPaths` unless a text
  renderer needs the same path conversion.
- `toTaggedJSON` usage in `query.mjs`.
- `JSON_COMMANDS` in `status.mjs`.
- `commands.json.test.mjs` and the JSON-branch of every other test.
- `Commands with --json` table in `jdt-spec.md`.

**Kept, separate concern:**

- `jdt launch config --xml` — XML is a different formalism
  (Eclipse native .launch XML dump), not part of the JSON triage.
- JSONL **streaming** for `jdt test run -f` / `jdt test status -f` —
  per-event real-time feed for agents. Likely migrates to
  line-delimited qlang-literals (each event a Map printed on one
  line via `printValue`); same byte-wire discipline, new payload
  shape. Separate PR.
- `toTaggedJSON` / `fromTaggedJSON` in `@kaluchi/qlang-core` itself
  — still needed for session serialization, not a CLI concern.

**Open question after grep:**

- `jdt use --json` and `jdt setup remote --json` are read-only but
  run outside the `jdt/graph` surface. Same treatment (kill `--json`,
  emit qlang-literal), or special-case as connection-management
  commands where a shell-friendly plain form stays? User input
  needed.

### Estimated scope

A single PR covering only commands 1-7 from the table above (the
core graph read-only path): roughly 12-15 files touched in `cli/src`,
3-4 test files trimmed or deleted, 4-5 specs updated. Medium PR.
Commands 8-12 (test / agent / use / setup-remote / launch-streaming)
can follow in a second PR once the pattern is settled.

## Cross-cutting gaps

1. **Markdown render operands.** Six distinct renderers were
   lost: `mdTypes`, `mdRefs`, `mdHierarchy`, `mdOutline`,
   `mdSource`, `mdImplementors`. All tracked in
   [jdt-query-spec § Markdown rendering](jdt-query-spec.md#markdown-rendering).
2. **Badge legend** (`[M]` / `[C]` / `[I]` / `[E]` / `[F]` /
   `[K]` / `[A]` / `[R]`) was consistent across every renderer —
   must stay consistent after the host-bound render operands are
   implemented. Legend lives in the spec; each operand references
   it.
3. **No pipeline equivalent of the legacy `--json` plain-JSON
   output**. Today `jdt q --json` emits tagged JSON (`{"$map":
   [[{"$keyword": …}, …]]}`). Callers that used `jq` on the
   legacy output cannot use the same query unmodified. `jdt
   status --json` bridges this by applying `fromTaggedJSON` +
   `toPlain` internally for qlang-sourced sections — but there
   is no CLI-level plain-JSON flag on `jdt q` itself. Open
   question.
4. **Conduit catalog for common narrowings.** Idiomatic helpers
   like `@public` (= `filter(/modifiers | any(eq("public")))`),
   `@instance` (no `:static`), `@errorsOnly` (severity = error)
   would eliminate boilerplate in every pipeline recipe. None
   exist yet.
