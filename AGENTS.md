# JDT Bridge — Agent Instructions

## Prerequisites

Required tools (verified by `jdt setup --check`):
- **Node.js** >= 20, **npm** — CLI runtime
- **Java** >= 21, **Maven** >= 3.9 — plugin build (Tycho)
- **Eclipse IDE** — running, with JDT Bridge plugin installed
- **git**, **gh** (GitHub CLI) — version control, PRs, releases

### Eclipse source bundles (recommended)

`jdt source` needs source bundles to read Eclipse Platform/JDT API source
and javadoc. Without them, `jdt source org.eclipse.debug.core.model.IStreamMonitor`
returns "Source not available". Install once (Eclipse must be closed):

```bash
D:/eclipse/eclipsec.exe -nosplash \
  -application org.eclipse.equinox.p2.director \
  -repository "https://download.eclipse.org/eclipse/updates/4.39/" \
  -installIU "org.eclipse.debug.core.source,org.eclipse.jdt.core.source,org.eclipse.core.resources.source,org.eclipse.debug.ui.source,org.eclipse.jdt.launching.source,org.eclipse.jdt.ui.source,org.eclipse.ui.console.source" \
  -destination "D:/eclipse" \
  -profile "epp.package.jee"
```

Adjust the update site URL to match your Eclipse version (e.g. `4.39`)
and `-destination` to your Eclipse path.

## Environment check (REQUIRED)

**Run at the start of every conversation, before any other work.**

1. `jdt setup --check` — verifies CLI, Node, Java, Maven, Eclipse, bridge.
   - `jdt: command not found` → `cd cli && npm install && npm link`
   - Plugin not installed → `jdt setup --eclipse <path>`
   - Eclipse not running → start it, re-run check

2. `jdt help` — read available commands. Use `jdt help <command>` for details.

3. `jdt projects` — verify `io.github.kaluchi.jdtbridge` and
   `io.github.kaluchi.jdtbridge.tests` are listed (imported into Eclipse
   workspace). If missing: Eclipse → File → Import → Existing Projects
   into Workspace → select repo root.
   Note: `jdt projects` only shows Java/plugin projects, not branding/feature/site.

4. Verify `jdtbridge.target` exists in repo root (gitignored, per-developer).
   Without it, Tycho builds fail. If missing, create it pointing to the
   local Eclipse directory:
   ```xml
   <?xml version="1.0" encoding="UTF-8" standalone="no"?>
   <?pde version="3.8"?>
   <target name="eclipse-local" sequenceNumber="1">
       <locations>
           <location path="/path/to/eclipse" type="Directory"/>
       </locations>
   </target>
   ```

## Use `jdt` for Java analysis

**Prefer `jdt` over grep/glob for Java-specific queries.** Grep returns
string matches; `jdt` returns semantic results from Eclipse's compiler index.

Run `jdt help` for the full command list. Run `jdt help <command>` for
detailed usage of any command. Key examples:

```bash
jdt refs <FQMN>                  # find call sites (not string matches)
jdt source <FQMN>               # source + resolved references (hypertext navigation)
jdt type-info <FQN>              # class overview without reading 600 lines
jdt test <FQN>#<method>          # run one test method (not full Maven lifecycle)
jdt errors --project <name>      # instant compilation check
jdt build --project <name>       # incremental build (1-3s, not 40s Maven)
```

### `jdt source` — hypertext navigation

`jdt source` returns markdown with source code and resolved references.
Each reference is a ready FQMN for the next `jdt source` call.

References are grouped by:
- **Same-class members** — with javadoc (saves a hop)
- **Project source** — with absolute paths and javadoc
- **Dependencies** — bare FQMNs

All paths are absolute filesystem paths, usable with Read/Edit tools.

### FQMN (Fully Qualified Method Name)

Commands that accept methods support FQMN — class and method in one argument:

```
pkg.Class#method              any overload
pkg.Class#method()            zero-arg overload
pkg.Class#method(String)      specific signature
pkg.Class.method(String)      Eclipse Copy Qualified Name style
```

Types can be simple (`String`) or FQN (`java.lang.String`).
Generics are stripped: `List<String>` matches `List`.

### Pipe composability

```bash
jdt ti io.github.kaluchi.jdtbridge.SearchHandler | grep handle   # 26 methods → 8 handlers
jdt refs io.github.kaluchi.jdtbridge.JdtUtils#findMethod | wc -l # count, not 51 lines
jdt errors --project my-server | head -5                         # one error at a time
jdt src org.springframework.jdbc.core.JdbcTemplate#query | grep -n throw  # throws in library code
```

## Project structure

See [README.md](README.md) for full overview. Key directories:
- `cli/src/` — Node.js CLI (ESM, `.mjs`)
- `plugin/src/` — Eclipse plugin (Java, OSGi)
- `plugin.tests/src/` — Plugin tests (JUnit 5, Fragment-Host of plugin)
- `scripts/release.mjs` — release automation

## Development workflow

### `jdt build` vs full verify

| | `jdt build` | `jdt launch run jdtbridge-verify` |
|---|---|---|
| What | Eclipse incremental compiler | Tycho full build (via Eclipse) |
| Speed | 1-3 seconds | 40-60 seconds |
| Tests | No | Unit + integration |
| Output | Compiled classes in Eclipse | p2 site in `site/target/` |
| When | Quick iteration while coding | Before commit, before `jdt setup` |

Console output is captured by Eclipse and available via `jdt launch logs`.
Use `-f` to stream live, or inspect afterwards with `--tail`. Example:
```bash
jdt launch run jdtbridge-verify           # launch (prints guide first time)
jdt launch logs jdtbridge-verify -f | tail -20  # wait + last 20 lines
jdt launch logs jdtbridge-verify --tail 30      # inspect after completion
```
For CI without local Eclipse: `mvn clean verify -Pci`.

### Making changes

1. **Create a branch** — never commit directly to master
2. **Write code + tests** — every change needs tests
3. **Build incrementally** — plugin first, tests second:
   ```bash
   jdt build --project io.github.kaluchi.jdtbridge
   jdt build --project io.github.kaluchi.jdtbridge.tests
   jdt test --project io.github.kaluchi.jdtbridge.tests
   ```
4. **Full Tycho build** — `jdt launch run jdtbridge-verify` then
   `jdt launch logs jdtbridge-verify -f | tail -20` to wait
5. **Install and verify live** — `jdt setup --skip-build`, then test
6. **Check ALL docs** if CLI syntax or API changed:
   - `cli/src/cli.mjs` (`jdt help` output)
   - `cli/src/commands/*.mjs` (per-command help)
   - `README.md`, `cli/README.md`, `plugin/README.md`, this file
7. **Commit → push → PR → squash merge → delete branch**

### Important details

- **New Java files invisible until build.** `jdt test` says "Type not found"
  → run `jdt build` for the project first.
- **Build order matters.** `plugin.tests` is a Fragment-Host of `plugin` —
  sees package-private members, but must build after plugin.
- **`jdt setup --skip-build` restarts Eclipse.** Port changes. `jdt` commands
  auto-discover the new port, but raw `curl` URLs break.
- **`jdt setup` without `--skip-build` runs Maven internally.**
  After a full build, always add `--skip-build` — never build twice.

### Test infrastructure

- **CLI tests:** `cd cli && npm test` (vitest)
- **Plugin unit tests:** `jdt test --project io.github.kaluchi.jdtbridge.tests`
- **Integration tests:** full Tycho build only (`jdt launch run jdtbridge-verify`) — use `@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")`
- **Test fixture:** `TestFixture.java` creates a project with known classes —
  `test.model.Animal`, `Dog`, `Cat`, `test.edge.Calculator` (overloads),
  `Repository` (generics). Add new test types there.

## Releasing

```bash
node scripts/release.mjs 1.4.0          # bump + build + tag + push
node scripts/release.mjs 1.4.0 --dry    # bump + build only
node scripts/release.mjs 1.4.0 --bump   # bump versions only
```

Requires: clean working tree, master branch, tag must not exist.
CI deploys automatically: npm publish, p2 site, GitHub Release.

## Conventions

- Java: Eclipse formatter (`jdt format <file>`)
- JavaScript: ESM (`.mjs`), no TypeScript
- Commits: imperative mood ("Add X", "Fix Y"), Co-Authored-By in message
- PRs: no "Generated with Claude Code" in body — Co-Authored-By is enough
