# JDT Bridge — Agent Instructions

## Environment check (REQUIRED)

**MUST run these checks at the start of every conversation, before any other work.** Do not skip this even if the user's request seems unrelated — the `jdt` CLI is central to this project and must be verified working.

1. **Check if `jdt` CLI is available:** run `jdt setup --check`
   - If the command is not found: `cd cli && npm install && npm link`
   - If the plugin is not installed or Eclipse path is unknown: `jdt setup --eclipse <path>`
   - If Eclipse is not running: start it, then run `jdt setup --check` again
2. **Check if `jdtbridge.target` exists** in the repo root. If missing, create it pointing to the local Eclipse installation:
   ```xml
   <?xml version="1.0" encoding="UTF-8" standalone="no"?>
   <?pde version="3.8"?>
   <target name="eclipse-local" sequenceNumber="1">
       <locations>
           <location path="/path/to/eclipse" type="Directory"/>
       </locations>
   </target>
   ```
   This file is gitignored per-user — each developer points it to their own Eclipse.
3. **Verify bridge is live:** `jdt projects` should list workspace projects.

If all checks pass, you have full semantic Java analysis available — use it.

## Use `jdt` for Java analysis

The `jdt` CLI gives you the same semantic understanding of Java code that a developer has in Eclipse. **Prefer `jdt` over grep/glob for Java-specific queries:**

| Task | Use `jdt` | Not grep |
|------|-----------|----------|
| Find call sites | `jdt refs <FQN>#<method>` | grep returns string matches, comments, identically-named methods |
| Check compilation | `jdt errors --project <name>` | Maven takes 30-90s, jdt is instant |
| Trigger build | `jdt build --project <name> [--clean]` | `mvn compile` has 30s+ overhead |
| Read library source | `jdt source <FQN>` | Library sources are inside JARs — grep can't reach them |
| Type hierarchy | `jdt hierarchy <FQN>` | grep for "extends/implements" misses transitive hierarchy |
| Run single test | `jdt test <FQN>#<method>` | Maven Surefire has 30s+ lifecycle overhead |
| Class overview | `jdt type-info <FQN>` | Reading a 600-line file wastes context |

### FQMN (Fully Qualified Method Name)

Commands that accept methods support FQMN notation — class and method in one argument:

```
pkg.Class#method              any overload
pkg.Class#method()            zero-arg overload
pkg.Class#method(String)      specific signature
pkg.Class.method(String)      Eclipse Copy Qualified Name style
```

Parameter types can be simple names (`String`) or FQN (`java.lang.String`).
Generics are stripped for matching: `List<String>` matches `List`.

### Key commands

```
jdt projects                              list workspace projects
jdt project-info <name>                   project overview (packages, types, methods)
jdt find <Name|*Pattern*|pkg> [--source-only] find types by name, wildcard, or package
jdt refs <FQMN> [--field <name>]          references to type/method/field
jdt subtypes <FQN>                        all subtypes/implementors
jdt hierarchy <FQN>                       supers + interfaces + subtypes
jdt impl <FQMN>                           implementations of interface method
jdt type-info <FQN>                       class overview (fields, methods, signatures)
jdt source <FQMN>                         type or method source code (project + libraries)
jdt build [--project <name>] [--clean]    build project (incremental or clean)
jdt test <FQMN>                           run JUnit test
jdt errors [--project <name>]             compilation errors
jdt format <file>                         format with Eclipse settings
jdt organize-imports <file>               organize imports
jdt rename <FQMN> <newName>               rename type/method/field
jdt open <FQMN>                           open in Eclipse editor
```

Run `jdt help <command>` for detailed flags and examples.

### Pipe composability

`jdt` output flows through the shell — filter it to save context:

```bash
jdt type-info com.example.dao.FileRepository | grep -i folder   # 65 methods → just folder ops
jdt refs com.example.core.Event dispatch | wc -l                 # count, not 200 lines
jdt errors --project my-server | head -5                         # one error at a time
jdt source com.example.util.StringHelper normalize | grep throw  # find throws without reading all
```

## Project structure

```
├── cli/                   # Node.js CLI (jdt command)
│   ├── src/               # CLI source (ESM)
│   └── test/              # CLI tests (vitest)
├── plugin/                # Eclipse plugin (Java, OSGi bundle)
├── plugin.tests/          # Plugin tests (JUnit 5 Jupiter, Fragment-Host)
├── feature/               # Eclipse feature for p2
├── site/                  # Eclipse update site
├── pom.xml                # Parent POM (Tycho 5.0.2 reactor)
├── jdtbridge.target       # Local target platform (gitignored, per-developer)
└── jdtbridge-ci.target    # CI target platform (p2, download.eclipse.org)
```

## Building

```bash
# Local build (uses your Eclipse as target platform)
mvn clean verify

# CI build (downloads Eclipse from p2 — no local Eclipse needed)
mvn clean verify -Pci

# CLI tests only
cd cli && npm test
```

### Installing plugin into Eclipse

After `mvn clean verify` has built the p2 site, install without rebuilding:

```bash
jdt setup --skip-build          # uses last mvn build, skips redundant rebuild
```

**IMPORTANT:** `jdt setup` (without `--skip-build`) triggers a full Maven build
internally. If you already ran `mvn clean verify`, always use `--skip-build`
to avoid building twice.

### Target platform

- `jdtbridge.target` — points to your local Eclipse directory. Each developer creates their own. Gitignored.
- `jdtbridge-ci.target` — points to `download.eclipse.org/eclipse/updates/4.39/`. Used by CI profile (`-Pci`).
- The `ci` Maven profile switches between them via `${target.file}` property.

## Testing

### Plugin tests (JUnit 5 Jupiter)

- **Unit tests** (5 classes): run everywhere — Tycho, Eclipse, `jdt test`
- **Integration tests** (7 classes, `*IntegrationTest`): gated by `@EnabledIfSystemProperty(named="jdtbridge.integration-tests")`, only run in `mvn verify` (Tycho sets the property)

```bash
# All tests via Tycho (unit + integration)
mvn clean verify

# Unit tests only via jdt (fast, no build needed)
jdt test --project io.github.kaluchi.jdtbridge.tests
```

### CLI tests (vitest)

```bash
cd cli && npm test
```

## Code style

- Java: Eclipse formatter settings (use `jdt format <file>` to auto-format)
- JavaScript: ESM modules (.mjs), no TypeScript
- Commit messages: imperative mood, concise ("Add X", "Fix Y", not "Added X")
