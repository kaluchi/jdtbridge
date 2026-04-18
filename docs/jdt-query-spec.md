# jdt q — Design Spec

## Overview

`jdt q` evaluates a qlang pipeline against the Eclipse JDT semantic
graph. The graph is exposed as the `:jdt/graph` qlang module — a
catalog of operand axes (seeds, containment, hierarchy, references,
source, diagnostics) that all consume and return node-Maps and
compose through standard qlang combinators (`|`, `*`, `>>`, `!|`).

Server-side protocol: [bridge-session-spec](bridge-session-spec.md).
Language reference: qlang-spec (in the `qlang` sibling repo).

## Glossary

Strict domain vocabulary. Every operand descriptor, doc-string,
example, and reshape literal in the codebase MUST use these terms.
Generic names (`name`, `value`, `data`, `result`, `item`, `path`)
are not admissible outside their native scope.

### Graph concepts

| Term | Meaning |
|---|---|
| **node-Map** | An immutable qlang Map representing one entity of the graph (a type, method, field, package, project, file, or compilation problem). Every node-Map carries the canonical header and at least one identity field. |
| **canonical header** | The five fields every node-Map carries: `:fqn`, `:kind`, `:origin`, `:location`, `:containingProject`. Readable from any node-Map without kind-awareness. |
| **skeleton** | A node-Map carrying the canonical header plus a small set of cheap per-kind identity fields (`:name`, `:signature`, `:modifiers`, `:containingType`, `:returnType`, …). Axes that fan out (`@members`, `@subtypes`, `@refs`) return skeletons. Cheap to produce in bulk. |
| **detail** | A node-Map carrying the canonical header plus the full per-kind payload (javadoc, type parameters, interfaces, source ranges, classpath entries, …). Seed operands (`@type`, `@method`, `@field`, `@project`, `@package`, `@file`) return detail. |
| **seed** | An operand that produces a starting `pipeValue` without needing one — either nullary (`@projects`, `@problems`) or taking a String/Map subject that identifies a single node (`@type`, `@method`, `@types("*Pat*")`). Seeds begin a pipeline. |
| **axis** | An operand that navigates from an existing node to one or more related nodes (`@members`, `@supers`, `@refs`, `@containingType`). Axes consume a subject from `pipeValue`. |
| **conduit** | A `let`-bound qlang fragment living inside the `:jdt/graph` module, composed from primitive axes (`@callers`, `@ancestors`, `@descendants`, `@publicOrphans`, `@asNode`, `@detail`). Conduits are documented and introspectable via `reify`. |

### Identity

| Term | Meaning |
|---|---|
| **fqn** | Fully qualified name — the single identity String of a node. One key `:fqn` across all kinds; the format is dictated by `:kind` (see [Node-Map schemas](#node-map-schemas)). `fqn` subsumes the older distinction between `FQN` and `FQMN` in neighbouring specs: a method's `fqn` IS what those specs called an FQMN. |
| **fqn format by kind** | `:type` → `pkg.Class` (inner classes: `pkg.Outer.Inner`); `:method` → `pkg.Class#method(ParamFqn,…)`; `:field` → `pkg.Class#fieldName`; `:package` → `pkg.sub`; `:project` → project name (not a path); `:file` → absolute filesystem path; `:problem` → composite `{file}:{line}:{col}` key. |
| **subject polymorphism** | Every axis accepts either a node-Map subject (skeleton or detail) or a String `fqn` subject — `@asNode` lifts a String to its corresponding seed node under the hood. `@members("pkg.Foo")` and `nodeMap \| @members` are equivalent. |

### Enumerated fields

Closed value sets. These appear in node-Maps, operand `:modifiers`,
and filter predicates. They are authored as Strings in node-Maps for
wire-compatibility with the server JSON; filters must therefore read
`/kind | eq("method")`, not `eq(:method)`. *(See [Open questions](#open-questions)
— this is one of the outstanding decisions.)*

| Field | Values | Source |
|---|---|---|
| `:kind` | `"type"`, `"method"`, `"field"`, `"package"`, `"project"`, `"file"`, `"problem"` | every node-Map |
| `:origin` | `"source"`, `"binary"` | every node-Map (workspace source vs classpath JAR) |
| `:refKind` | `"call"`, `"read"`, `"write"`, `"typeUse"`, `"all"` | `@refs` modifier and `/refKind` on reference records |
| `:typeKind` | `"class"`, `"interface"`, `"enum"`, `"annotation"`, `"record"` | `:type` detail |
| `:severity` | `"error"`, `"warning"`, `"info"` | `:problem` detail |
| `:problems` scope modifier | `:workspace`, `:project`, `:file` | `@problems` keyword modifier |

### Location

Every node-Map carries `:location` as a sub-Map with a fixed shape:

```
{:file       <absolute path>
 :startLine  <int, 1-based, inclusive>
 :endLine    <int, 1-based, inclusive>
 :nameStart  <int, UTF-16 offset of the name span>
 :nameEnd    <int, UTF-16 offset of the name span end>}
```

Binary-origin nodes carry an attachment-source path or `null`.

### Pipeline idioms

| Term | Meaning |
|---|---|
| **pipeValue** | The current value flowing through the pipeline (qlang core concept). Axes read their subject from `pipeValue`. |
| **captured arg** | Expression inside `()` after an operand name — for graph axes, these are always *modifiers* (e.g. `@refs(:call)`), never subject identifiers. Passing an fqn as a captured arg is an architectural mistake — that's RPC, not pipeline. |
| **seed fqn** | A String fqn used as the initial `pipeValue` of a pipeline (`"pkg.Foo" \| @subtypes`). |

## Node-Map schemas

*(To be filled in after the glossary is agreed. One sub-section per
`:kind` with the exact field list for skeleton and detail forms.
Drafts will read directly from `bridge-session-spec.md` to stay in
sync with the server JSON contract.)*

## Commands

### `jdt q <qlang-pipeline> [--json]`

*(Signature, flags, bootstrap behaviour, error mapping. To be
filled in.)*

## Modifier convention — widen at modifier, narrow in pipeline

A captured-arg modifier on any `:jdt/graph` operand (builtin axis,
conduit, or host-bound render operand) **always widens the volume
of data the server returns**. Narrowing is done downstream in the
pipeline via core qlang — `filter`, `inter`, `when`, projection.
One rule, all operands.

| Direction | Mechanism | Example |
|---|---|---|
| Widen (more data) | Captured-arg modifier | `@refs(:all)` — every refKind returned |
| Narrow (subset) | qlang pipeline step | `@refs(:all) \| filter(/refKind \| eq("call"))` |

**Rationale.** The LLM composing a query holds one mental rule:
"to get more I pass a modifier, to get less I filter". Without
this rule, every operand accretes flag-style knobs and the catalog
devolves into an ad-hoc configuration vocabulary instead of a
pipeline surface.

### What this rules in

- **Scope widening** — `@refs(:all)` widens beyond the `:call`
  fast-path to every refKind. `@problems(:workspace)` admissible
  only because `:workspace` would be prohibitively expensive as a
  default; `@problems(:project)` / `@problems(:file)` are
  server-side narrower scopes that exist for cost reasons, not
  ergonomic ones.
- **Payload widening** — a hypothetical `@source(:withJavadoc)`
  would attach extra fields to the returned `:source` node. Default
  operands return the minimum useful payload; modifiers enrich it.
- **Inclusion widening** — a hypothetical `@methods(:inherited)`
  would include inherited members alongside declared ones. Client
  narrows back via `filter(/containingType | eq(…))` when needed.

### What this rules out

- **Narrowing modifiers.** Any modifier that hides data from the
  default response is rejected by construction. The pipeline does
  that job.
- **`@types(:sourceOnly)` as a modifier** — narrows by excluding
  binary types. Superseded by the `@sourceOnly` conduit
  (`filter(/origin | eq("source"))`). The correct idiom is
  `@types("*Pat*") | @sourceOnly`; the `:sourceOnly` keyword
  modifier on `@types` is slated for removal.
- **Field-hiding modifiers.** If the server knows a field, it
  always returns it. Reshape / projection in the pipeline decides
  what to show.

### Review rule

When adding a modifier to any `:jdt/graph` operand, the author
answers one question in the operand's `:docs`: "does this cause
MORE data or LESS?" If less → reject, implement as a qlang
filter or conduit. If more → accept, document the exact delta.
When auditing the existing module, every modifier on every
operand is classified against this rule; narrowing modifiers are
deletion candidates.

## Operand catalog

*(Full catalog grouped by role — seed / containment / hierarchy /
references / detail / diagnostics / conduits. Each entry: signature,
subject polymorphism, modifiers (classified as widening per the
[modifier convention](#modifier-convention--widen-at-modifier-narrow-in-pipeline)),
return kind, throws, one illustrative example using domain-correct
vocabulary. To be filled in.)*

## Pipeline recipes

*(90% use-case recipes, grouped by task. Find / Navigate / Read /
Audit / After-edit / Reflect. Each recipe: one-line pipeline +
explanation + expected shape of the result. To be filled in.)*

## Markdown rendering

The `:jdt/graph` axes return node-Maps — structured data, not
display. Humans and LLMs alike need dense, information-per-byte
markdown cards for the common read operations. These are provided
as **host-bound render operands** — JS implementations installed
into the session alongside the I/O / format / parse operand packs
in `cli/src/commands/query.mjs`. Every render operand consumes a
detail node-Map (or the result of `@source`) as its subject and
returns a markdown String.

### Catalog

*(Final operand names are open — see [Open questions](#open-questions)
#7. Candidate shape below.)*

| Operand | Subject | Returns |
|---|---|---|
| `mdSource` | result of `@source` on a type / method / field | markdown card: header, location, byte-exact code fence, `#### Outgoing Calls:` / `#### Incoming Calls:` (for members) or `#### Hierarchy:` (for types) |
| `mdHierarchy` | type detail with resolved supertypes/subtypes | markdown tree with `↑` / `↓` arrows, badges, per-entry `file:line-range` |
| `mdOutline` | type detail with member skeletons | structural tree grouped by kind, with modifiers / return types / signatures |
| `mdRefs` | result of `@refs` | refs grouped by declaring type, with badges and optional javadoc inline |
| `mdImplementors` | type / method detail with resolved implementors | flat list with badges + per-entry location |

### Rendering contract

The contract below is inherited verbatim from the legacy
`jdt source` renderer (see [jdt-source-spec](jdt-source-spec.md),
which will be deleted once fully migrated). These rules govern
every `md*` operand; LLM consumers rely on them unchanged.

1. **Server exhaustive, client formats.** The plugin returns all
   metadata on a node-Map (fqn, badges, modifiers, javadoc, resolved
   bounds, override targets, implementors, incoming callers). The
   markdown operands decide how to present it.
2. **Deterministic output.** Every section either appears (when
   data exists) or is omitted (when empty). Never collapsed,
   summarized, or flag-gated. The output shape is a contract.
3. **Zero-modification navigation.** Every fqn in the output is a
   valid subject for `jdt q '"<fqn>" | @type'` (or `@method` /
   `@field` — dispatched via `@asNode`). Copy-paste is enough.
4. **Badge-link separation.** Badges (`[M]`, `[C]`, …) are visual
   prefixes outside the fqn backticks, never part of the fqn
   string.
5. **Full qualification.** Never truncate packages or parameter
   fqns in fqn links. Return types render as full fqn
   (`java.lang.StringBuilder`, not `StringBuilder`).
6. **Contextual metadata.** Annotations like `(static)`,
   `(inherited)`, `→ ReturnType` follow the link, never break it.
7. **No self-references.** Don't list the viewed type/method in its
   own refs section.
8. **Byte-exact source.** Code inside the java fence is
   byte-for-byte identical to the file on disk — same tabs, line
   breaks, indentation.
9. **Source order.** Outgoing calls listed in source-appearance
   order.
10. **Resolve type parameter bounds.** Generic return types resolved
    to upper bound at call site. Bound `Object` → show `→ ?`.
11. **Resolve `@Override`.** When method has `@Override`, resolve
    and show the declaring supertype as a navigable fqn.
12. **Flat calls.** No chain-call nesting. Chains visible through
    source order — consecutive calls = likely chain.
13. **Same-domain implementation display.** Server resolves ALL
    implementations (exhaustive). Renderer filters by domain: when
    viewed member's `:origin` is `"source"`, dependency-scope
    interface implementations are hidden (no 5-way SLF4J Logger
    spam); when `:origin` is `"binary"`, all implementations show.
14. **Incoming calls are links, not locations.** Callers render as
    navigable fqn only — no line numbers. The caller's source is
    one `"caller-fqn" | @method | @source | mdSource` away; a bare
    line number without file context is noise.

### Badge legend

```
[M] method    [C] class       [I] interface    [E] enum
[F] field     [K] constant    [A] annotation   [R] record
```

Badges are inferred from `:kind` (and `:typeKind` for types).
Never part of fqn strings; always a visual prefix separated by a
space.

### Example — `mdSource` for a method

```
#### [M] io.github.kaluchi.jdtbridge.Json#put(String, String)
`D:\...\Json.java:32-43`

​```java
Json put(String key, String value) {
    comma();
    appendKey(key);
    if (value == null) {
        sb.append("null");
    } else {
        sb.append('"').append(escape(value)).append('"');
    }
    return this;
}
​```

#### Outgoing Calls:
[C] `io.github.kaluchi.jdtbridge.Json`
[M] `io.github.kaluchi.jdtbridge.Json#comma()` → `void`
[M] `io.github.kaluchi.jdtbridge.Json#appendKey(String)` → `void`
[F] `io.github.kaluchi.jdtbridge.Json#sb` → [C] `java.lang.StringBuilder`
[M] `io.github.kaluchi.jdtbridge.Json#escape(String)` → [C] `java.lang.String` (static) — JSON string escaping.

#### Incoming Calls:
[M] `io.github.kaluchi.jdtbridge.Activator#writeBridgeFile(int, String, String, String)`
[M] `io.github.kaluchi.jdtbridge.SourceReport#toJson(…)`
```

## CLI output

*(Text mode via `printValue` for non-String results, raw stdout
for String results; `--json` mode via `toTaggedJSON`. To be filled
in with worked examples.)*

## Bridge session

*(How `jdt q` bootstraps: `createSession` + locator, I/O / format /
parse operand binding, `use(:jdt/graph)` prefix, error-to-exit
mapping. To be filled in.)*

## Design decisions

### Read-only, therefore exit 0 on every outcome

`jdt q` is a read-only command. It MUST exit 0 regardless of
what happens — parse error, unresolved identifier, runtime
fail-track, server-side `:TypeNotFound` / `:MethodNotFound` —
everything. Errors travel on stdout as payload, never as exit
status.

**Why.** Claude Code (and similar agent harnesses) cancel every
pending parallel tool call in a batch when any sibling call
returns non-zero. A parallel fan-out of five `jdt q` queries with
one typo in one of them loses the other four mid-flight — a
reliability tax paid by the whole batch for a single-caller
mistake.

**How.**

1. **Parse error** (`peggy` failure inside `session.evalCell`) →
   convert to an error value with the qlang-native shape
   (`:kind :parse-error`, `:origin :qlang/parse`, `:message`,
   `:location`) and print on stdout via `printValue` (text mode)
   or `toTaggedJSON` (`--json` mode). Exit 0.
2. **Error value as `pipeValue`** (`isErrorValue(result)`) →
   print exactly like any other qlang value. Exit 0. Callers who
   care route with `!|` inside the pipeline.
3. **CLI argument error** (missing positional, malformed flag
   combination) → print an error-value-shaped descriptor on
   stdout. Exit 0.
4. **Server-side failures** already come through `:jdt/graph`
   operands as structured fail-track values — nothing special.

This rule governs `jdt q`, `jdt status`, `jdt editors`, `jdt git
list`, `jdt launch list / logs / configs / config`, `jdt test
runs / status`, and every other read-only command in the CLI.
Mutation commands (`jdt build`, `rename`, `test run`, `launch
run`, `setup`, `agent run`, …) keep conventional non-zero exit
on failure — they change state, and batches are not the same
parallelism concern.

**Current violations** (to fix alongside the onboarding work):

- `cli/src/commands/query.mjs` lines 62-64 — `process.exit(1)`
  on `cellEntry.error`. Breaks parallel `jdt q` batches on any
  parse typo.
- `cli/src/commands/query.mjs` lines 70-75 — `process.exit(1)`
  on `isErrorValue(queryResult)`. Breaks any pipeline that
  legitimately ends on the fail-track (e.g.
  `"no.such.Type" | @type !| /thrown`).

*(More decisions to be added as the surface stabilises —
operand-catalog ordering, md-operand ownership, FQN / FQMN split,
etc.)*

## Open questions

Decisions still outstanding — each one affects the operand catalog
and the reshape vocabulary in every downstream example:

1. **`:kind` / `:origin` / `:refKind` — String or Keyword?**
   Currently Strings (wire-compatible with server JSON). Keywords
   would be idiomatic qlang (`eq(:method)` vs `eq("method")`).
   Conversion would live in the dispatch layer. Trade-off:
   ergonomics vs one extra transformation per response.

2. **Single `:fqn` key, or split into `:fqn` + `:fqmn`?**
   Neighbouring specs (`jdt-source-spec.md`) treat FQN and FQMN as
   distinct terms. The `:jdt/graph` module currently uses one
   polymorphic `:fqn` key. The draft above leans toward the
   single-key model (with format dictated by `:kind`) because it
   makes axes like `@refs * /from/fqn` uniform across kinds.
   Counter-argument: explicit `:fqmn` reads more strictly and
   matches the older CLI vocabulary humans already know.

3. **Operand `:examples` are all empty.** Every builtin in
   `graph.qlang` ships with `:examples []`. This costs the model
   two things: bare-name `reify` shows nothing, and `runExamples`
   can't verify the catalog. Populating examples is a prerequisite
   for the catalog to work as an onboarding surface, not just a
   lookup table.

4. **Regression inventory.** Which grouped renderers, scope
   filters, and table shapes from the pre-qlang CLI
   (`jdt find / refs / hier / outline / source / search`) no
   longer have a one-line equivalent in the `:jdt/graph` surface?
   The answer defines the minimum gap to close before the
   qlang integration is a net win.

5. **`@usages(type)` conduit.** `@callers` exists for methods,
   `@readers`/`@writers` for fields, but there is no composed
   conduit that returns "everything that references this type"
   (subtypes ∪ typeUse refs ∪ field declarations ∪ parameters).
   Worth a named conduit if the use-case is common.

6. **Markdown rendering — host-bound operands vs qlang conduits vs
   hybrid.** The [Markdown rendering](#markdown-rendering) section
   assumes host-bound JS operands (option A). Alternatives: (B)
   pure qlang conduits inside `:jdt/graph` using `template` /
   `prepend` / `append`, or (C) a hybrid where simple renderers
   are conduits and complex ones (`mdSource` refs-grouping,
   `mdProjectInfo` adaptive tiers) stay host-bound. Option (A)
   recommended on perf and readability grounds — the old
   `source.mjs` was 308 lines of imperative grouping, badge
   dispatch, and impl-index logic that doesn't translate cleanly
   into a `let` body.

7. **Operand naming — `mdSource` / `mdHierarchy` / …** Candidate
   names. Alternative: single overloaded `md(:source)` /
   `md(:hierarchy)` / `md(:outline)` discriminated by a captured
   keyword. Trade-off: one operand vs kind-specific names. Separate
   names are more discoverable through `manifest`; single operand
   is less clutter.

8. **Migration plan for `jdt-source-spec.md`.** The principles in
   that spec are incorporated into [Markdown rendering](#markdown-rendering).
   Deletion of `jdt-source-spec.md` follows once the `md*` operands
   are implemented and their `:examples` cover the cases currently
   illustrated there.

## Relationship to other specs

- **[bridge-session-spec](bridge-session-spec.md)** — server-side
  HTTP endpoints, response JSON contract, session scope, project
  filtering.
- **[jdt-spec](jdt-spec.md)** — top-level CLI surface, `--json`
  contract, connection resolution.
- **[jdt-source-spec](jdt-source-spec.md)** — legacy vocabulary
  (FQN vs FQMN, badges, outgoing/incoming refs) and markdown
  rendering contract. Principles migrated into
  [Markdown rendering](#markdown-rendering); full spec scheduled
  for deletion once `md*` operands land.
- **[jdt-status-spec](jdt-status-spec.md)** — dashboard consumer
  of `@projects` / `@problems` seeds.

## Files

CLI:
  commands/query.mjs             — `jdt q` command, session bootstrap,
                                   binds I/O + format + parse + render operand packs
  lib/jdt/graph.qlang            — `:jdt/graph` module source (descriptors, conduits)
  lib/jdt/graph.impl.mjs         — JS impls for every builtin descriptor
  lib/jdt/render.impl.mjs        — (planned) `mdSource` / `mdHierarchy` /
                                   `mdOutline` / `mdRefs` / `mdTypes` /
                                   `mdImplementors` host-bound render operands

Plugin (server):
  plugin/src/io/github/kaluchi/jdtbridge/GraphHandler.java  — request dispatch
  plugin/src/io/github/kaluchi/jdtbridge/NodeBuilder.java   — node-Map assembly

Docs:
  docs/jdt-query-spec.md     — this file
  docs/bridge-session-spec.md — server-side protocol contract
