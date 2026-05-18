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
example, and reshape literal in the codebase uses these terms.
Generic names (`name`, `value`, `data`, `result`, `item`, `path`)
are not admissible outside their native scope.

### Graph concepts

| Term | Meaning |
|---|---|
| **node-Map** | An immutable qlang Map representing one entity of the graph (a type, method, field, package, project, file, or compilation problem). Every node-Map carries the canonical header and at least one identity field. |
| **canonical header** | The five fields a code-element node-Map carries: `:fqn`, `:kind`, `:origin`, `:location`, `:containingProject`. `:problem` and `:reference` records omit `:fqn` (they have no stable identifier); `:project` / `:package` / `:file` omit `:location` or carry only its `:file` component. Every node still carries `:kind` and `:origin`. |
| **skeleton** | A node-Map carrying the canonical header plus a small set of cheap per-kind identity fields (`:name`, `:signature`, `:modifiers`, `:containingType`, `:returnType`, …). Axes that fan out (`@members`, `@subtypes`, `@incomingRefs`) return skeletons. Cheap to produce in bulk. |
| **detail** | A node-Map carrying the canonical header plus the full per-kind payload (javadoc, type parameters, interfaces, source ranges, classpath entries, …). Seed operands (`@type`, `@method`, `@field`, `@project`, `@package`, `@file`) return detail. |
| **seed** | An operand that produces a starting `pipeValue` without needing one — either nullary (`@projects`, `@problems`) or taking a String/Map subject that identifies a single node (`@type`, `@method`, `@types("*Pat*")`). Seeds begin a pipeline. |
| **axis** | An operand that navigates from an existing node to one or more related nodes (`@members`, `@supers`, `@incomingRefs`, `@containingType`). Axes consume a subject from `pipeValue`. |
| **conduit** | A qlang fragment declared via a `BindStep` (`:@name body`) inside the `:jdt/graph` module, composed from primitive axes (`@callers`, `@ancestors`, `@descendants`, `@publicOrphans`, `@asNode`, `@detail`). Each conduit's source, docs, and runnable examples surface through the introspection axes — `:@name | source`, `:@name | docs`, `:@name | examples` (and `:@name | spec` for the manifest-shape descriptor). |

### Identity

| Term | Meaning |
|---|---|
| **fqn** | Fully qualified name — the single identity String of a node. One key `:fqn` across all kinds; the format is dictated by `:kind`. |
| **fqn format by kind** | `:type` → `pkg.Class` (inner classes: `pkg.Outer.Inner`); `:method` → `pkg.Class#method(ParamFqn,…)`; `:field` → `pkg.Class#fieldName`; `:package` → `pkg.sub`; `:project` → project name (not a path); `:file` → absolute filesystem path; `:classpathEntry` → composite `project#entryKind#path`. `:problem` and `:reference` have no `:fqn` — they are identified by location + structural tuple. |
| **subject polymorphism** | Every axis accepts either a node-Map subject (skeleton or detail) or a String `fqn` subject. `@asNode` lifts a String to its corresponding seed node under the hood. `@members("pkg.Foo")` and `nodeMap \| @members` are equivalent. |

### Enumerated fields

Closed value sets. These appear in node-Maps, operand `:modifiers`,
and filter predicates.

| Field | Values | Source |
|---|---|---|
| `:kind` | `"type"`, `"method"`, `"field"`, `"package"`, `"project"`, `"file"`, `"problem"` | every node-Map |
| `:origin` | `"source"`, `"binary"` | every node-Map (workspace source vs classpath JAR) |
| `:refKind` | `"call"`, `"read"`, `"write"`, `"typeUse"`, `"all"` | `@incomingRefs` modifier and `/refKind` on reference records |
| `:typeKind` | `"class"`, `"interface"`, `"enum"`, `"annotation"`, `"record"` | `:type` detail |
| `:severity` | `"error"`, `"warning"`, `"info"` | `:problem` detail |

### Location

Code-element node-Maps (`:type`, `:method`, `:field`) carry
`:location` as a sub-Map:

```
{:file       <absolute path>
 :startLine  <int, 1-based, inclusive>
 :endLine    <int, 1-based, inclusive>
 :lineCount  <int, endLine - startLine + 1>
 :nameStart  <int, UTF-16 offset of the name span>
 :nameEnd    <int, UTF-16 offset of the name span end>}
```

`:lineCount` is pre-computed to keep `sortWith(desc(/location/lineCount))`
a one-liner instead of the `add(m | /location/endLine | sub(m |
/location/startLine), 1)` reshape.

`:problem` carries only `:file :startLine :endLine` (markers are
line-granular, no name span). `:project`, `:package`, `:file`
omit `:location` entirely — they are containers, not code
positions.

Binary-origin nodes carry an attachment-source path or `null`.

### Pipeline idioms

| Term | Meaning |
|---|---|
| **pipeValue** | The current value flowing through the pipeline (qlang core concept). Axes read their subject from `pipeValue`. |
| **captured arg** | Expression inside `()` after an operand name — for graph axes, these are *modifiers* only (e.g. `@incomingRefs(:call)`). Subject identifiers flow through `pipeValue`. |
| **seed fqn** | A String fqn used as the initial `pipeValue` of a pipeline (`"pkg.Foo" \| @subtypes`). |

## Commands

### `jdt q <qlang-pipeline>`

Output is qlang-literal via `printValue`. Strings print raw
(unquoted) so `jdt q '"X" | @source' > X.java` stays byte-faithful.

Read-only — exits 0 on every outcome. Parse errors, unresolved
identifiers, CLI-argument errors, and server fail-track values
all print on stdout as qlang error values (`!{:kind …
:message … :trail …}`). Non-zero exit would cancel sibling
parallel tool calls in agent harnesses; errors travel as data,
never as exit status.

## Node-Map schemas

The canonical header — `:fqn`, `:kind`, `:origin`, `:location`,
`:containingProject` — appears on every code-element node-Map.
`:problem` omits `:fqn` (markers have no stable identifier).
`:project` / `:package` / `:file` omit `:location` or carry a
partial form; sub-field tables below show exactly which fields
are present on each kind.

### `:type`

| Field | Skeleton | Detail | Notes |
|---|---|---|---|
| `:fqn` `:kind` `:origin` `:location` `:containingProject` | ✓ | ✓ | canonical header |
| `:containingPackage` | ✓ | ✓ | package fqn |
| `:typeKind` | ✓ | ✓ | `"class"` / `"interface"` / `"enum"` / `"annotation"` / `"record"` |
| `:modifiers` | ✓ | ✓ | Vec of strings |
| `:annotations` | ✓ | ✓ | Vec of FQN strings |
| `:isTestScope` | ✓ | ✓ | boolean |
| `:typeParameters` |   | ✓ | Vec of `{:name :bound?}` |
| `:interfaces` |   | ✓ | Vec of FQN strings |
| `:superclass` |   | ✓ | FQN string (absent for interfaces) |
| `:isAnonymous` |   | ✓ | boolean, omitted when false |
| `:isDeprecated` |   | ✓ | boolean, omitted when false |
| `:javadocSummary` |   | ✓ | first-sentence string |

### `:method`

| Field | Skeleton | Detail | Notes |
|---|---|---|---|
| canonical header | ✓ | ✓ | — |
| `:name` `:signature` | ✓ | ✓ | signature is `name(ParamFqn,…)` |
| `:modifiers` `:annotations` `:isTestScope` | ✓ | ✓ | — |
| `:containingType` | ✓ | ✓ | FQN string |
| `:returnType` | ✓ | ✓ | FQN string (absent on constructors) |
| `:parameters` |   | ✓ | Vec of `{:name :type}` |
| `:typeParameters` |   | ✓ | Vec of `{:name :bound?}` |
| `:throws` |   | ✓ | Vec of FQN strings |
| `:isConstructor` `:isAbstract` `:isDefault` `:isDeprecated` |   | ✓ | boolean, each omitted when false |
| `:javadocSummary` |   | ✓ | first-sentence string |

### `:field`

| Field | Skeleton | Detail | Notes |
|---|---|---|---|
| canonical header | ✓ | ✓ | — |
| `:name` | ✓ | ✓ | local name |
| `:containingType` | ✓ | ✓ | FQN string |
| `:modifiers` `:annotations` `:isTestScope` | ✓ | ✓ | — |
| `:type` | ✓ | ✓ | field type FQN |
| `:isConstant` |   | ✓ | true iff static+final, omitted when false |
| `:isDeprecated` |   | ✓ | boolean, omitted when false |
| `:javadocSummary` |   | ✓ | first-sentence string |

### `:package`

| Field | Skeleton | Detail | Notes |
|---|---|---|---|
| `:fqn` `:kind` `:origin` | ✓ | ✓ | no `:location` (packages are not a code position) |
| `:containingProject` | ✓ | ✓ | — |
| `:typeCount` | ✓ | ✓ | top-level types |
| `:sourceRoot` | ✓ | ✓ | relative path of the source root housing the package |

### `:project`

| Field | Skeleton | Detail | Notes |
|---|---|---|---|
| `:fqn` `:kind` `:origin` | ✓ | ✓ | `:fqn` = project name, not a path |
| `:rootPath` | ✓ | ✓ | absolute filesystem path |
| `:natures` | ✓ | ✓ | Vec of short strings (`"java"`, `"maven"`, `"pde"`, `"CheckstyleNature"`, …) |
| `:isTestScope` | ✓ | ✓ | true only when the project hosts tests exclusively |
| `:repo` `:branch` | ✓ | ✓ | present when EGit-managed |
| `:classpathEntries` |   | ✓ | Vec of `:classpathEntry` skeletons |
| `:dependencies` |   | ✓ | Vec of required-project names |
| `:sourceRoots` |   | ✓ | Vec of relative source-root paths |
| `:outputLocation` |   | ✓ | build output path |

### `:file`

| Field | Skeleton | Detail | Notes |
|---|---|---|---|
| `:fqn` `:kind` `:origin` | ✓ | ✓ | `:fqn` = absolute path |
| `:containingProject` | ✓ | ✓ | — |
| `:language` | ✓ | ✓ | `"java"` for compilation units |
| `:charset` `:modificationTime` | ✓ | ✓ | file metadata |

### `:problem`

| Field | Present | Notes |
|---|---|---|
| `:kind` `:origin` | ✓ | no `:fqn` — markers have no stable identifier |
| `:location` | ✓ | `:file :startLine :endLine` — `:startLine` and `:endLine` carry the same value (markers are line-granular) |
| `:containingProject` | ✓ | — |
| `:severity` | ✓ | `"error"` / `"warning"` (default scope filters to error) |
| `:message` | ✓ | marker text |
| `:markerType` | ✓ | currently `"jdt"` only |

### `:reference`

Reference records are ephemeral node-Maps produced by
`@incomingRefs` / `@outgoingRefs`. No `:fqn` — identified by the
`(from, to, refKind, location)` tuple. The `:from` / `:to` slots
carry regular skeletons a downstream axis or renderer consumes.

| Field | Present | Notes |
|---|---|---|
| `:kind` | ✓ | always `"reference"` |
| `:direction` | ✓ | `"incoming"` from `@incomingRefs`, `"outgoing"` from `@outgoingRefs`. Drives `mdRefs` side selection; lets downstream qlang distinguish the two sources even after concat. |
| `:origin` | ✓ | matches the side visible in source |
| `:refKind` | ✓ | `"call"` / `"read"` / `"write"` / `"typeUse"` |
| `:from` | ✓ | skeleton of the source-side member |
| `:to` | ✓ | skeleton of the target (incoming refs reiterate the query subject here) |
| `:location` | (`@incomingRefs` only) | call-site location on the from-side |
| `:containingProject` | ✓ | mirrors the from-side's project |

### `:classpathEntry`

Returned by `@classpath` and nested inside `:project` detail under
`:classpathEntries`. The server returns a **resolved** classpath —
container entries (`JRE_CONTAINER`, M2E `MAVEN2_CLASSPATH_CONTAINER`)
are expanded into their constituent library / source / project
entries before the client sees them. Maven dependencies appear as
individual JARs with absolute filesystem paths; JRE modules the
same.

| Field | Present | Notes |
|---|---|---|
| `:fqn` | ✓ | composite `project#entryKind#path` |
| `:kind` | ✓ | always `"classpathEntry"` |
| `:origin` | ✓ | `"source"` for source roots, `"binary"` for library / project entries |
| `:containingProject` | ✓ | — |
| `:entryKind` | ✓ | `"source"` / `"library"` / `"project"` (container / variable kinds never appear on the wire — they are resolved away) |
| `:path` | ✓ | **absolute filesystem path** on the Eclipse host, host-native format (Windows `D:\…`, Linux `/…`). CLI remaps host→sandbox for the caller's runtime. |
| `:outputLocation` | source entries | absolute filesystem path, same format as `:path` |
| `:isTest` | test source roots | boolean |
| `:isExported` | when true | boolean |

## Modifier convention — widen at modifier, narrow in pipeline

A captured-arg modifier on any `:jdt/graph` operand **widens the
volume of data the server returns**. Narrowing is done downstream
in the pipeline via core qlang — `filter`, `inter`, `when`,
projection. One rule, all operands.

| Direction | Mechanism | Example |
|---|---|---|
| Widen (more data) | Captured-arg modifier | `@incomingRefs(:all)` — every refKind returned |
| Narrow (subset) | qlang pipeline step | `@incomingRefs(:all) \| filter(/refKind \| eq("call"))` |

The LLM composing a query holds one mental rule: "to get more I
pass a modifier, to get less I filter".

### Admissible modifier shapes

- **Scope widening** — `@incomingRefs(:all)` widens beyond the `:call`
  default to every refKind.
- **Payload widening** — a modifier attaches extra fields to the
  returned node. Defaults return the minimum useful payload.
- **Inclusion widening** — a modifier includes additional members
  beyond the default set (inherited, transitive).

### Inadmissible modifier shapes

- **Narrowing modifiers.** A modifier that hides data from the
  default response. Narrowing is the pipeline's job.
- **Field-hiding modifiers.** If the server knows a field, the
  response carries it. Reshape / projection decides what to show.

### Review rule

A new modifier's `:docs` answers one question: "does this cause
MORE data or LESS?" Less → reject, implement as a qlang filter or
conduit. More → accept, document the exact delta.

## Markdown rendering

`:jdt/graph` axes return node-Maps — structured data. Dense
markdown cards for the common read operations are provided as
host-bound render operands installed into the session alongside
the I/O / format / parse operand packs. Each render operand
consumes a bundle Map (or a Vec of reference records) from
pipeValue and returns a markdown String.

### Renderers

| Operand | Bundle shape | Output |
|---|---|---|
| `mdSource` | `{:node :text :outgoing :incoming [:supers :subtypes]}` | header + location + code fence + Outgoing Calls + Incoming Calls (+ Hierarchy for types) |
| `mdHierarchy` | `{:node :supers :subtypes}` | type header + ↑ Supertypes + ↓ Subtypes |
| `mdOutline` | `{:node :members}` | type header + Fields + Methods + Inner types, each grouped with modifiers and line ranges |
| `mdRefs` | Vec of `:reference` records | grouped by `:refKind` (Calls / Reads / Writes / Type uses) |

### Conduit shortcuts

Every renderer has a conduit in `:jdt/graph` that collects the
bundle through existing axes and pipes it through the
renderer — one-shot from an fqn or node subject:

| Conduit | Expands to |
|---|---|
| `@sourceCard` | `{:node @detail :text @source :outgoing @outgoingRefs :incoming @incomingRefs(:all) :supers @supers :subtypes @subtypes} \| mdSource` |
| `@hierarchyCard` | `{:node @detail :supers @supers :subtypes @subtypes} \| mdHierarchy` |
| `@outlineCard` | `{:node @detail :members @members} \| mdOutline` |

### Rendering contract

1. **Server exhaustive, client formats.** The plugin returns all
   metadata on a node-Map (fqn, modifiers, javadoc, resolved
   bounds, override targets, implementors, incoming callers). The
   render operand decides presentation.
2. **Deterministic output.** Every section either appears (when
   data exists) or is omitted (when empty). Never collapsed,
   summarized, or flag-gated.
3. **Zero-modification navigation.** Every fqn in the output is a
   valid subject for `jdt q '"<fqn>" | @type'` (or `@method` /
   `@field` — dispatched via `@asNode`). Copy-paste is enough.
4. **Badge-link separation.** Badges (`[M]`, `[C]`, …) are visual
   prefixes outside the fqn backticks, never part of the fqn
   string.
5. **Full qualification.** Packages and parameter fqns in fqn
   links are never truncated. Return types render as full fqn
   (`java.lang.StringBuilder`, not `StringBuilder`).
6. **Contextual metadata.** Annotations like `(static)`,
   `(inherited)`, `→ ReturnType` follow the link, never break it.
7. **No self-references.** The viewed type/method is not listed
   in its own refs section.
8. **Byte-exact source.** Code inside the java fence is
   byte-for-byte identical to the file on disk — same tabs, line
   breaks, indentation.
9. **Source order.** Outgoing calls listed in source-appearance
   order.
10. **Resolve type parameter bounds.** Generic return types
    resolved to upper bound at call site. Bound `Object` →
    `→ ?`.
11. **Resolve `@Override`.** When a method has `@Override`, the
    render resolves and shows the declaring supertype as a
    navigable fqn.
12. **Flat calls.** No chain-call nesting. Chains are visible
    through source order — consecutive calls = likely chain.
13. **Same-domain implementation display.** Server resolves ALL
    implementations. Renderer filters by domain: when the viewed
    member's `:origin` is `"source"`, dependency-scope interface
    implementations are hidden; when `:origin` is `"binary"`, all
    implementations show.
14. **Incoming calls are links, not locations.** Callers render
    as navigable fqn only — no line numbers. The caller's source
    is one `"caller-fqn" | @method | @source | mdSource` away;
    a bare line number without file context is noise.

### Badge legend

```
[M] method    [C] class       [I] interface    [E] enum
[F] field     [K] constant    [A] annotation   [R] record
```

Badges are inferred from `:kind` (and `:typeKind` for types).
Never part of fqn strings; always a visual prefix separated by a
space.

## Files

CLI:
  commands/query.mjs             — `jdt q` command, session bootstrap
  lib/jdt/graph.qlang            — `:jdt/graph` module source
  lib/jdt/graph.impl.mjs         — JS impls for graph axes
  lib/jdt/render.impl.mjs        — host-bound render operands

Plugin (server):
  plugin/src/io/github/kaluchi/jdtbridge/GraphHandler.java  — request dispatch
  plugin/src/io/github/kaluchi/jdtbridge/NodeBuilder.java   — node-Map assembly

## Relationship to other specs

- **[bridge-session-spec](bridge-session-spec.md)** — server-side
  HTTP endpoints, response JSON contract, session scope, project
  filtering.
- **[jdt-spec](jdt-spec.md)** — top-level CLI surface, connection
  resolution.
- **[jdt-status-spec](jdt-status-spec.md)** — dashboard consumer
  of `@projects` / `@problems` seeds.
