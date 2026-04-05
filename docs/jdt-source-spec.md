# jdt source — Design Spec

## Core Principles

1. **Server Exhaustive, Client Formats**: The plugin computes ALL metadata exhaustively — for every reference: FQMN, type kind, file path, line range, javadoc, modifiers, resolved bounds, override targets, implementors, incoming callers. The CLI decides how to render it. Server never omits data; client experiments with presentation.

2. **Deterministic Output**: The format is a contract. Every section either appears (when data exists) or doesn't (when data is empty). Never "collapsed", never "summarized", never behind a flag. The tool is predictable.

3. **Zero-Modification Navigation**: Every FQMN in the output is a valid argument for `jdt source`. Copy-paste, no editing.

4. **Badge-Link Separation**: Badges (`[M]`, `[C]`, ...) are visual prefixes, not part of the FQMN string.

5. **Full Qualification**: Never truncate packages or params in FQMN links. Return types shown as full FQN (`java.lang.StringBuilder`, not `StringBuilder`).

6. **Contextual Metadata**: Annotations like `(static)`, `(inherited)`, `→ ReturnType` follow the link, never break it.

7. **No Self-References**: Don't list the viewed type/method in its own refs.

8. **Byte-Exact Source**: Code inside ``` must be byte-for-byte identical to the file on disk — same tabs, line breaks, indentation.

9. **Source Order**: Outgoing calls listed in source-appearance order.

10. **Resolve Type Parameter Bounds**: Generic return types resolved to upper bound via `IMethodBinding.getReturnType()` at call site. When bound is `Object` → show `→ ?`.

11. **Resolve @Override**: When method has `@Override`, resolve and show the declaring supertype/interface as a navigable FQMN.

12. **Flat Calls**: No chain-call nesting. Chains are visible through source order — consecutive calls = likely chain.

13. **Each Command Has One Job**: `jdt source` = source code + references. `jdt type-info` = compact structural overview. Don't mix them.

14. **Same-Domain Implementation Display**: Server resolves ALL implementations (exhaustive). CLI filters by domain: when `viewScope` is `"project"`, dependency interface implementations are hidden. This prevents library noise (e.g. 5 SLF4J Logger impls) while keeping the data available for alternative views.

15. **Incoming Calls Are Links, Not Locations**: Incoming callers are shown as navigable FQMNs only — no line numbers. The caller's source is one `jdt source` away; a bare line number without file context is noise.

## Badge Legend

```
[M] method    [C] class       [I] interface    [E] enum
[F] field     [K] constant    [A] annotation
```

---

# Server JSON Contract

The plugin returns source + flat array of refs. The client does all grouping, ordering, formatting.

Implementation: `ReferenceCollector` (outgoing refs via AST), `SearchHandler.collectIncomingRefs` (incoming refs via SearchEngine), `SourceReport.toJson` (JSON assembly).

## Top-level response

```json
{
  "fqmn":      "pkg.Class#method(Param)",
  "file":      "D:\\...\\Class.java",
  "startLine": 42,
  "endLine":   55,
  "source":    "..byte-exact source..",
  "overrideTarget": "interface pkg.Interface#method(Param)",
  "viewScope":  "project",
  "refs": [ ...ref objects... ]
}
```

For type-level (no `#method`), `refs` is replaced by `supertypes`, `subtypes`, and optionally `enclosingType`.

## Ref object

Every ref in the `refs` array carries all metadata. Client never needs a second query.

```json
{
  "fqmn":           "pkg.Other#doStuff(String)",
  "direction":      "outgoing",
  "kind":           "method",
  "typeKind":       "class",
  "scope":          "project",
  "file":           "D:\\...\\Other.java",
  "line":           100,
  "endLine":        115,
  "type":           "Customer",
  "returnTypeFqn":  "pkg.model.Customer",
  "returnTypeKind": "class",
  "typeBound":      "Customer",
  "static":         false,
  "inherited":      true,
  "inheritedFrom":  "pkg.Ancestor",
  "implementationOf": null,
  "doc":            "First sentence of javadoc."
}
```

### Key fields

- **`direction`**: `outgoing` (AST visitor over method body) or `incoming` (SearchEngine workspace query).
- **`typeKind`**: kind of the DECLARING type. Enables `[C]`/`[I]`/`[E]`/`[A]` badges.
- **`type`** + **`returnTypeFqn`**: return type resolved at call site via `IMethodBinding.getReturnType()`, not from declaration. Full FQN always present (including `java.*`).
- **`returnTypeKind`**: enables badge on return type (e.g. `→ [I] org.eclipse.jdt.core.IType`).
- **`typeBound`**: resolved upper bound when return type is a type parameter. Null when concrete or bound is Object.
- **`inherited`** + **`inheritedFrom`**: method declared in ancestor, called on subtype. Detected by comparing receiver type with declaring class at call site.
- **`implementationOf`**: links implementation ref back to the interface method FQMN. Null for non-implementation refs.
- **`overrideTarget`**: top-level field (not per-ref). Format: `"interface pkg.Type#method()"` or `"class pkg.Type#method()"`. Resolved via supertype hierarchy walk.
- **`viewScope`**: top-level field. `"project"` when the viewed member is workspace source, `"dependency"` when binary/library. Used by CLI for domain-scoped filtering (e.g. hiding dependency interface implementations).

### Implementation resolution

For each outgoing ref where `typeKind: "interface"` and `kind: "method"`, the server resolves implementations via `IType.newTypeHierarchy(null)` → `getAllSubtypes()`. JDK interfaces filtered by `isJdkType`. No cardinality caps.

**Domain scoping** (server-exhaustive, client-filters): the server always resolves ALL implementations — no filtering, no caps. The response includes `viewScope` (`"project"` or `"dependency"`) so the CLI can decide what to show. Default CLI behavior: when `viewScope` is `"project"`, implementations for dependency-scope interface refs are hidden (e.g. `org.slf4j.Logger` impls). When `viewScope` is `"dependency"`, all implementations are shown. This keeps implementation data available for future use (e.g. filtering by JAR, showing on demand) while keeping default output clean.

### Server responsibilities

- Collects outgoing refs (AST visitor in `ReferenceCollector`)
- Collects incoming refs (SearchEngine in `SearchHandler.collectIncomingRefs`)
- Resolves implementations for interface method calls (domain-scoped)
- Resolves @Override target
- Resolves type hierarchy for type-level requests
- No grouping, no ordering, no filtering, no formatting

---

# Method-level Output

`jdt source "io.github.kaluchi.jdtbridge.Json#put(String,String)"`

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
[M] `io.github.kaluchi.jdtbridge.Activator.writeBridgeFile(int, String, String, String)`
[M] `io.github.kaluchi.jdtbridge.SourceReport.toJson(...)`
...
```

### Format details

- **Header**: `#### [M] FQMN` — badge inferred from FQMN (`#` = method, no `#` = type)
- **Override**: `overrides [M] \`pkg.Interface#method()\`` — shown when `overrideTarget` present
- **Location**: `` `file:startLine-endLine` ``
- **Source**: byte-exact code block
- **Outgoing Calls**: grouped by declaring type, flat list (no indentation). Javadoc inline after ` — `. Implementations shown inline with `→` prefix after the interface method they implement.
- **Incoming Calls**: flat list of callers, grouped by declaring type.

### Implementation inline example

```
[I] `org.eclipse.jdt.core.IType`
[M] `org.eclipse.jdt.core.IType#isAnonymous()` → `boolean` — Returns whether this type represents an anonymous type.
  → [M] `org.eclipse.jdt.internal.core.LambdaExpression#isAnonymous()`
  → [M] `org.eclipse.jdt.internal.core.BinaryType#isAnonymous()`
  → [M] `org.eclipse.jdt.internal.core.SourceType#isAnonymous()`
```

---

# Type-level Output

`jdt source "org.eclipse.jdt.core.IType"`

Full source of the type (byte-exact, with `file:from-to`), followed by hierarchy — the only information NOT visible in the source code.

No outgoing calls, no incoming calls — those are method-level concerns.

```
#### [C] org.eclipse.jdt.core.IType
`D:\eclipse\...\IType.java:1-850`

​```java
public interface IType extends IMember, IAnnotatable {
    ...full source...
}
​```

#### Hierarchy:
↑ [I] `org.eclipse.jdt.core.IMember`
↑ [I] `org.eclipse.jdt.core.IAnnotatable`
↓ [C] `org.eclipse.jdt.internal.core.BinaryType`
↓ [C] `org.eclipse.jdt.internal.core.SourceType`
```

### Hierarchy fields

- **Supertypes** (↑): resolved from `ITypeHierarchy.getSuperclass()` + `getSuperInterfaces()`. `java.lang.Object` filtered.
- **Subtypes** (↓): direct subtypes from `ITypeHierarchy.getSubtypes()`. Anonymous types filtered.
- **Enclosing Type**: for nested/inner types, links to parent via `IType.getDeclaringType()`.

### JSON for type-level

```json
{
  "fqmn": "org.eclipse.jdt.core.IType",
  "file": "...",
  "startLine": 1,
  "endLine": 850,
  "source": "...full source...",
  "supertypes": [
    { "fqn": "org.eclipse.jdt.core.IMember", "kind": "interface" },
    { "fqn": "org.eclipse.jdt.core.IAnnotatable", "kind": "interface" }
  ],
  "subtypes": [
    { "fqn": "org.eclipse.jdt.internal.core.BinaryType", "kind": "class" },
    { "fqn": "org.eclipse.jdt.internal.core.SourceType", "kind": "class" }
  ]
}
```

---

# Future considerations

1. **Package-level** — `jdt source "com.example.shared.model"` could show types in package + package-info.java javadoc.

2. **Inner class navigation** — sibling inner classes: show "Also nested in Outer: [E] Color, [C] Builder"?
