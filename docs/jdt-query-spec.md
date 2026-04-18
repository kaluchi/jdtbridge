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
| **canonical header** | The five fields every node-Map carries: `:fqn`, `:kind`, `:origin`, `:location`, `:containingProject`. Readable from any node-Map without kind-awareness. |
| **skeleton** | A node-Map carrying the canonical header plus a small set of cheap per-kind identity fields (`:name`, `:signature`, `:modifiers`, `:containingType`, `:returnType`, …). Axes that fan out (`@members`, `@subtypes`, `@refs`) return skeletons. Cheap to produce in bulk. |
| **detail** | A node-Map carrying the canonical header plus the full per-kind payload (javadoc, type parameters, interfaces, source ranges, classpath entries, …). Seed operands (`@type`, `@method`, `@field`, `@project`, `@package`, `@file`) return detail. |
| **seed** | An operand that produces a starting `pipeValue` without needing one — either nullary (`@projects`, `@problems`) or taking a String/Map subject that identifies a single node (`@type`, `@method`, `@types("*Pat*")`). Seeds begin a pipeline. |
| **axis** | An operand that navigates from an existing node to one or more related nodes (`@members`, `@supers`, `@refs`, `@containingType`). Axes consume a subject from `pipeValue`. |
| **conduit** | A `let`-bound qlang fragment living inside the `:jdt/graph` module, composed from primitive axes (`@callers`, `@ancestors`, `@descendants`, `@publicOrphans`, `@asNode`, `@detail`). Conduits are documented and introspectable via `reify`. |

### Identity

| Term | Meaning |
|---|---|
| **fqn** | Fully qualified name — the single identity String of a node. One key `:fqn` across all kinds; the format is dictated by `:kind`. |
| **fqn format by kind** | `:type` → `pkg.Class` (inner classes: `pkg.Outer.Inner`); `:method` → `pkg.Class#method(ParamFqn,…)`; `:field` → `pkg.Class#fieldName`; `:package` → `pkg.sub`; `:project` → project name (not a path); `:file` → absolute filesystem path; `:problem` → composite `{file}:{line}:{col}` key. |
| **subject polymorphism** | Every axis accepts either a node-Map subject (skeleton or detail) or a String `fqn` subject. `@asNode` lifts a String to its corresponding seed node under the hood. `@members("pkg.Foo")` and `nodeMap \| @members` are equivalent. |

### Enumerated fields

Closed value sets. These appear in node-Maps, operand `:modifiers`,
and filter predicates.

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
| **captured arg** | Expression inside `()` after an operand name — for graph axes, these are *modifiers* only (e.g. `@refs(:call)`). Subject identifiers flow through `pipeValue`. |
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

## Modifier convention — widen at modifier, narrow in pipeline

A captured-arg modifier on any `:jdt/graph` operand **widens the
volume of data the server returns**. Narrowing is done downstream
in the pipeline via core qlang — `filter`, `inter`, `when`,
projection. One rule, all operands.

| Direction | Mechanism | Example |
|---|---|---|
| Widen (more data) | Captured-arg modifier | `@refs(:all)` — every refKind returned |
| Narrow (subset) | qlang pipeline step | `@refs(:all) \| filter(/refKind \| eq("call"))` |

The LLM composing a query holds one mental rule: "to get more I
pass a modifier, to get less I filter".

### Admissible modifier shapes

- **Scope widening** — `@refs(:all)` widens beyond the `:call`
  default to every refKind. `@problems(:workspace)` widens past
  the narrower `:project` / `:file` scopes.
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
| `@sourceCard` | `{:node @detail :text @source :outgoing @outgoingRefs :incoming @refs(:all)} \| mdSource` |
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
