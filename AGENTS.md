# JDT Bridge — Agent Instructions

## Prerequisites

Required tools (verified by `jdt setup --check`):
- **Node.js** >= 20, **npm** — CLI runtime
- **Java** >= 21, **Maven** >= 3.9 — plugin build (Tycho)
- **Eclipse IDE** — running, with JDT Bridge plugin installed
- **git**, **gh** (GitHub CLI) — version control, PRs, releases

### Eclipse source bundles (recommended)

The `@source` axis needs source bundles to read Eclipse Platform/JDT API
source and javadoc. Without them,
`jdt q '"org.eclipse.core.runtime.CoreException" | @source'` returns
"Source not available". Install all Eclipse source bundles once
(Eclipse must NOT be running):

```bash
# List available source bundles, filter out tests, install all
BUNDLES=$(D:/eclipse/eclipsec.exe -nosplash \
  -application org.eclipse.equinox.p2.director \
  -repository "https://download.eclipse.org/eclipse/updates/4.39/" \
  -list 2>&1 | grep "^org\.eclipse\..*\.source=" | grep -v "tests\.\|examples\." \
  | sed 's/=.*//' | tr '\n' ',' | sed 's/,$//')

D:/eclipse/eclipsec.exe -nosplash \
  -application org.eclipse.equinox.p2.director \
  -repository "https://download.eclipse.org/eclipse/updates/4.39/" \
  -installIU "$BUNDLES" \
  -destination "D:/eclipse" \
  -profile "epp.package.jee"
```

Adjust the update site URL to match your Eclipse version (e.g. `4.39`)
and paths to your Eclipse installation.

## Environment check (REQUIRED)

**Run at the start of every conversation, before any other work.
Subagents (Explore, Plan, etc.) skip this section — jdt is already
verified by the main agent.**

1. `jdt setup --check` — verifies CLI, Node, Java, Maven, Eclipse, bridge.
   - `jdt: command not found` → `cd cli && npm install && npm link`
   - Plugin not installed → `jdt setup --eclipse <path>`
   - Eclipse not running → start it, re-run check

2. `jdt status` — **workspace dashboard**. Shows git repos, open editors,
   compilation errors, running launches, test results, and project list
   in one call. This is your orientation command — run it first to
   understand what the developer is working on.
   - If `jdtbridge-verify` or `jdtbridge-package` are missing from
     launch configs, import them:
     ```bash
     jdt launch config --import launches/jdtbridge-verify.launch
     jdt launch config --import launches/jdtbridge-package.launch
     ```

3. Verify `jdtbridge.target` exists in repo root (gitignored, per-developer).
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

`jdt status` shows the full workspace state — including a `help` section
with the full command reference. `jdt help <command>` for detailed usage
of any command.

### `jdt q` — graph query language

`jdt q '<qlang-pipeline>'` evaluates a qlang pipeline against the
Eclipse semantic graph. The graph is navigated through operand axes
that consume a node-Map (skeleton or detail) OR a fqn string
from `pipeValue`. Output is qlang-literal; fqn strings in the result
are ready subjects for the next step.

Full axis catalog, sugar conduits, and modifier syntax live in
`jdt help q` (runtime truth) and `docs/jdt-query-spec.md`.

### FQN — member form

Every node carries one identifier field, `:fqn`. Per kind:

```
pkg.Class                     type
pkg.Class#fieldName           field or unambiguous method by name
pkg.Class#method()            zero-arg method
pkg.Class#method(String)      method with erased parameter signature
pkg.Class.method(String)      Eclipse Copy Qualified Name — also accepted
```

A bare `pkg.Class#method` without parens is ambiguous when the
type has multiple overloads; the error descriptor carries
`:candidates` so the caller can pick and retry. Types can be
simple (`String`) or qualified (`java.lang.String`); generics
are stripped — `List<String>` matches `List`.

### Pipeline composability

All of the below return a single qlang-literal value — ready for
pipe-chaining with standard shell tools or further `jdt q` calls.

```bash
jdt q '"io.github.kaluchi.jdtbridge.SearchHandler" | @members * /name'
jdt q '"io.github.kaluchi.jdtbridge.JdtUtils#findMethod" | @callers | count'
jdt q '"my-server" | @problems * /message'
jdt q '"org.springframework.jdbc.core.JdbcTemplate#query" | @source' | grep -n throw
jdt q '"io.github.kaluchi.jdtbridge.HttpServer" | @methods | filter(@untested) * /fqn'
```

### Subagents (Explore, Plan)

Custom jdt-aware agents are installed in `.claude/agents/` by
`jdt setup --claude`. They have `jdt` commands in their system prompt
and auto-allow hooks — no setup needed.

**When to use subagents:**
- `Explore` — finding types, tracing references, understanding
  hierarchies, reading source. Fast (haiku model). Use for research
  that doesn't require writing code.
- `Plan` — designing implementation strategy with code understanding.
  Inherits parent model. Read-only.

**When NOT to use subagents:**
- Writing code, editing files — do it yourself, not subagents.
- Simple `jdt` queries (one command) — call `jdt` directly, don't
  spawn a subagent for `jdt q '"Foo#bar" | @callers'`.
- Non-Java exploration — subagents add jdt overhead, use plain
  Grep/Glob for config files, scripts, docs.

Subagents skip environment check (CLAUDE.md switch). They assume
jdt is already verified by the main agent.

## Project structure

See [README.md](README.md) for full overview. Key directories:
- `cli/src/` — Node.js CLI (ESM, `.mjs`)
- `cli/src/commands/` — command implementations (status, find, source, git, etc.)
- `cli/src/format/` — output formatters (table, hierarchy, references, etc.)
- `cli/test/` — CLI tests (vitest, 515+ tests)
- `plugin/src/` — Eclipse plugin (Java, OSGi, EGit, JGit)
- `plugin.tests/src/` — Plugin tests (JUnit 6, Fragment-Host of plugin)
- `plugin.tests.ui/src/` — UI-runtime tests (JUnit 6, needs workbench)
- `tests.support/src/` — Shared test support (TestFixture, TestAwait)
- `ui/` — Eclipse UI contributions (toolbar, preferences)
- `branding/` — Plugin branding (about info, icons)
- `feature/` — Eclipse feature (bundles plugin + ui + branding)
- `site/` — p2 update site
- `launches/` — Shared Eclipse launch configs (committed to repo)
- `scripts/release.mjs` — release automation

## Development workflow

### `jdt build` vs full verify

| | `jdt build` | `jdt launch run jdtbridge-package` | `jdt launch run jdtbridge-verify` |
|---|---|---|---|
| What | Eclipse clean+full build (or `--incremental`) | Tycho build, no tests | Tycho full build + tests |
| Speed | 3-10 seconds (incremental: 1-3s) | 30-40 seconds | ~5 minutes |
| Tests | No | No | Unit + integration |
| Output | Compiled classes in Eclipse | p2 site in `site/target/` | p2 site in `site/target/` |
| When | Quick iteration while coding | Before `jdt setup --skip-build` | Before commit |

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
3. **Build** — plugin first, tests second (default is clean build):
   ```bash
   jdt build --project io.github.kaluchi.jdtbridge
   jdt build --project io.github.kaluchi.jdtbridge.tests
   jdt test run io.github.kaluchi.jdtbridge.tests --coverage -f
   ```
4. **Full Tycho build** — `jdt launch run jdtbridge-verify` then
   `jdt launch logs jdtbridge-verify -f | tail -20` to wait
5. **Install and verify live** — `jdt setup --skip-build`, then test
6. **Check ALL docs** if CLI syntax or API changed:
   - `cli/src/cli.mjs` (`jdt help` output)
   - `cli/src/commands/*.mjs` (per-command help)
   - `README.md`, `cli/README.md`, `plugin/README.md`, this file
7. **Commit → push → PR → squash merge → delete branch**
8. **After merge** — switch to master, pull, clean build:
   ```bash
   git checkout master && git pull
   jdt build --clean
   ```

### Pull requests

Use `gh pr create` / `gh pr edit` / `gh pr merge`. **Always pass `--body`
via HEREDOC** to avoid shell escaping issues. Do NOT use markdown formatting
characters (`*`, `#`, `` ` ``) in `--body` — they break `gh` argument parsing.
Use plain text or `- [x]` checklists only.

```bash
# Create
gh pr create --title "Short title" --body "$(cat <<'EOF'
Summary line.

- [x] Tests pass
- [x] Docs updated
EOF
)"

# Edit (ignore GraphQL Projects Classic deprecation warning)
gh pr edit 55 --body "new body text" 2>&1 | grep -v GraphQL

# Merge + cleanup
gh pr merge 55 --squash --delete-branch

# Wait for CI checks (no sleep — use gh pr checks --watch)
gh pr checks 55 --watch
```

**Waiting for CI:** use `gh pr checks <N> --watch` — it blocks until
all checks complete, then exits with 0 (all pass) or 1 (failures).
Never use `sleep` loops to poll CI status.

**Bot review comments.** After CI, automated reviewers
(`gemini-code-assist[bot]`, etc.) leave a review summary plus
line-level comments. `gemini-code-assist` first puts an 👀 reaction
on the PR description when it picks up the PR; the actual review
lands ~1–2 min later. Watch for both signals:

```bash
gh api repos/kaluchi/jdtbridge/issues/<N>/reactions  # 👀 = bot reading
gh api repos/kaluchi/jdtbridge/pulls/<N>/reviews
gh api repos/kaluchi/jdtbridge/pulls/<N>/comments    # line-level
gh api repos/kaluchi/jdtbridge/issues/<N>/comments   # PR-level
```

Every concrete bot finding must be resolved before merge — either
fix it in a follow-up commit, or reply on the comment with a clear
reason for rejection. Do not leave bot comments unanswered: the
human reviewer expects each one closed by the time they look.

After replying, also **mark the conversation resolved** so the PR
page no longer shows it as outstanding. The REST comments API can
post replies; resolving the conversation is GraphQL-only:

```bash
# Reply on the diff line
gh api repos/kaluchi/jdtbridge/pulls/<N>/comments/<comment-id>/replies \
  -X POST -f body="…"

# Look up review-thread IDs (PRRT_*) for the PR
gh api graphql -f query='query {
  repository(owner: "kaluchi", name: "jdtbridge") {
    pullRequest(number: <N>) {
      reviewThreads(first: 50) {
        nodes { id isResolved
          comments(first: 1) { nodes { databaseId path } } } } } } }'

# Resolve a thread by its PRRT_* id
gh api graphql -f query='mutation {
  resolveReviewThread(input: {threadId: "PRRT_…"}) {
    thread { id isResolved } } }'
```

### Important details

- **Clean build after branch switch or merge.** `git checkout`, `git pull`,
  squash merge — all rewrite `.class` files that Eclipse's incremental
  builder may not invalidate. Run `jdt build --clean` to avoid phantom
  errors and stale bytecode. This also applies after `git pull` on master.
- **New Java files invisible until build.** `jdt test run` says "Type not found"
  → run `jdt build` for the project first.
- **Build order matters.** `plugin.tests` is a Fragment-Host of `plugin` —
  sees package-private members, but must build after plugin.
- **`jdt setup --skip-build` restarts Eclipse.** Port changes. `jdt` commands
  auto-discover the new port, but raw `curl` URLs break.
- **`jdt setup` without `--skip-build` runs Maven internally.**
  After a full build, always add `--skip-build` — never build twice.

### Test infrastructure

**Prefer Eclipse launch configurations over direct CLI commands.**
The developer works in Eclipse — launch configs give them history,
Console output, and a shared vocabulary between agent and IDE.
Use `jdt launch run` and `jdt test run` instead of `cd cli && npm test`
or `mvn verify`. When a launch config exists for the task, use it.

If you must call `npm`/`mvn` directly (e.g. no matching launch config,
CI context, or specific flags not expressible via launch), explain why.

- **CLI tests:** `jdt launch run npm-test -f` (preferred) or
  `cd cli && npm test` (fallback). Single file:
  `jdt launch run npm-test -f -- test/paths.test.mjs`
- **Plugin unit tests:** `jdt test run io.github.kaluchi.jdtbridge.tests --coverage -f`
- **Integration tests:** full Tycho build only (`jdt launch run jdtbridge-verify`) — use `@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")`
- **Test fixture:** `TestFixture.java` creates a project with known classes —
  `test.model.Animal`, `Dog`, `Cat`, `test.edge.Calculator` (overloads),
  `Repository` (generics), `test.edge.Color` (enum), `test.edge.Marker`
  (annotation). Add new test types there.

### Running plugin tests

**Prefer running individual test classes or methods**, not the full suite.
Full suite (832 plugin + 28 UI = 860 tests) takes ~2 minutes and runs
in a separate Eclipse runtime. Individual tests take 5-15 seconds.

```bash
# Single test method — fastest feedback
jdt test run com.example.FooTest#myMethod --coverage -f -q

# Single test class
jdt test run com.example.FooTest --coverage -f -q

# Full plugin suite (headless PDE runtime, no workbench)
jdt test run io.github.kaluchi.jdtbridge.tests --coverage -f -q

# Full UI suite (workbench PDE runtime — editors, coverage)
jdt test run io.github.kaluchi.jdtbridge.tests.ui --coverage -f -q
```

**UI tests must run via the full suite**, not by class FQN.
`jdt test run <FQN>` against a class in `plugin.tests.ui` synthesises
a headless launch config — `PlatformUI.getWorkbench()` is null in
that runtime and the test hangs on the first `Display.syncExec`.
Use `jdt test run io.github.kaluchi.jdtbridge.tests.ui` so the
shared `*.tests.ui.launch` (workbench PDE) picks up the test.

Both suites use shared launch configs from `launches/`. If a config
is missing or broken, restore from the repo:
```bash
jdt launch config --delete "io.github.kaluchi.jdtbridge.tests"
jdt launch config --import launches/io.github.kaluchi.jdtbridge.tests.launch

jdt launch config --delete "io.github.kaluchi.jdtbridge.tests.ui"
jdt launch config --import launches/io.github.kaluchi.jdtbridge.tests.ui.launch
```

Important:
- **Plugin tests run in a PDE test runtime** with workspace bundles
  (not the installed plugin). `jdt build` is enough — no `jdt setup`
  needed before running tests.
- **But `jdt setup` IS needed** to test live behavior (`jdt q` axes,
  `jdt launch`, `jdt open`, etc.) because those go through the
  installed plugin's HTTP server.
- **Build before testing:** `jdt build --project io.github.kaluchi.jdtbridge.tests`
  — otherwise new test classes won't be found.
- **TestFixture lifecycle:** each test class needs `@BeforeAll static void
  setUp() throws Exception { TestFixture.create(); }`. The fixture is
  idempotent but must be created before `JdtUtils.findType()` calls.
  If a `@Nested` class has `@AfterAll` with `TestFixture.destroy()`,
  subsequent nested classes in the same file won't find fixture types.
- **Check results:** `jdt test runs` shows all runs with relative time.
  `jdt test status <testRunId> -f` for details on failures.

### Running CLI tests

```bash
cd cli && npm test                    # full suite (~13s, 676 tests)
cd cli && npx vitest run test/paths.test.mjs   # single file
jdt launch run npm-test -f            # same suite via Eclipse (output in Console)
```

CLI tests mock the HTTP server — no Eclipse needed. They run on both
Windows and Linux (CI). Path conversion tests mock `process.platform`.

### Plugin changes → live testing

Plugin tests run in a PDE test runtime (workspace bundles) — `jdt build`
is enough. But live commands (`jdt q`, `jdt launch config --delete`,
`jdt refactor`, `jdt open`, `jdt test run`) go through the **installed**
plugin's HTTP server. After changing plugin code:

```bash
jdt build --project io.github.kaluchi.jdtbridge        # compile
jdt launch run jdtbridge-package -f | tail -5           # Tycho build
jdt setup --skip-build                                  # install + restart Eclipse
```

Only then will live commands use the new code.

### Shared launch configurations

`launches/` contains `.launch` files committed to the repo. Eclipse
auto-discovers them from the project directory. They are NOT in
workspace metadata — `jdt launch config --delete` does not touch them.
`--delete` only removes configs from
`<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/`.

To add a new shared launch config: create `.launch` XML in `launches/`,
`jdt refresh` the project. Eclipse picks it up automatically.
Use `${system_path:node}`, `${workspace_loc:...}`, and
`${system_property:java.io.tmpdir}` variables for cross-platform
paths — no absolute paths in committed .launch files.

Available launch configs in `launches/`:

| File | Type | What |
|---|---|---|
| `jdtbridge-verify.launch` | Maven Build | `clean verify` — full Tycho build + tests |
| `jdtbridge-package.launch` | Maven Build | `clean package` — Tycho build, no tests |
| `npm-test.launch` | Program | CLI vitest suite |
| `io.github.kaluchi.jdtbridge.tests.launch` | JUnit Plug-in Test | Plugin tests (832 tests, headless PDE runtime) |
| `io.github.kaluchi.jdtbridge.tests.ui.launch` | JUnit Plug-in Test | UI tests (28 tests, workbench PDE runtime) |

PDE test launch configs carry explicit bundle lists for the test
runtime. Key constraints for the UI config:
- `org.eclipse.debug.ui`, `org.eclipse.eclemma.ui`,
  `org.eclipse.help.ui` must be `@default:false` (not auto-started) —
  their `start()` methods call `PlatformUI.getWorkbench()` which
  throws before the workbench is created.
- JUnit 5 bundles (`*5.14.3`, `*1.14.3`) are required alongside
  JUnit 6 — `TestFixture` creates projects with `JUNIT_CONTAINER/5`.

To restore a launch config from the repo:
```bash
jdt launch config --delete "io.github.kaluchi.jdtbridge.tests.ui"
jdt launch config --import launches/io.github.kaluchi.jdtbridge.tests.ui.launch
```

## Releasing

```bash
node scripts/release.mjs 1.4.0          # bump + build + tag + push
node scripts/release.mjs 1.4.0 --dry    # bump + build only
node scripts/release.mjs 1.4.0 --bump   # bump versions only
```

Requires: clean working tree, master branch, tag must not exist.
CI deploys automatically: npm publish, p2 site, GitHub Release.

After CI creates the GitHub Release, replace the auto-generated body
with human-written release notes. Show the draft to the user for
approval before publishing. Use `gh release edit vX.Y.Z --notes "..."`.
See previous releases for style (feature paragraphs, not bullet lists
of commits).

## Test requirements

Tests are code. Same quality bar as production — no fallbacks,
no dead branches, no defensive patterns "just in case."

**100% test-file coverage.** Every line and branch inside a test
file must be exercised. If JaCoCo shows a missed branch in your
test, you wrote a branching construct that doesn't belong there.

**No branching in tests.** A test is a deterministic sequence:
set up → call → assert. No `if`, no `for` with filter predicates,
no `try/catch`, no `stream().filter().findFirst().orElseThrow()`.
Each of these creates branches JaCoCo must cover — and the
"other" branch is unreachable by design, tanking coverage.

- Direct index access (`entries[0]`) not loop + filter
- Direct cast (`(IType) children[0]`) not `instanceof` scan
- `JsonArray.contains(new JsonPrimitive("x"))` not loop + flag
- `assertNotNull(x)` then use `x` — not `if (x != null)`

**No conditional test execution.** No `if (!available()) return`,
no `@EnabledIf`, no `Assumptions.assumeTrue`. A test either runs
its assertions or fails loud. If a test needs a runtime capability
(UI bundle, specific launch type), put it in the correct suite
from the start — `plugin.tests` (headless) vs `plugin.tests.ui`
(workbench).

**No null from test helpers.** A helper that looks something up
either finds it or throws `AssertionError`. Never return null
and check at the call site.

**Shared infrastructure goes in `tests.support`.** TestFixture,
TestAwait, and any reusable lookup/factory helpers belong in the
`tests.support` module — not duplicated across test files.

**No defensive cleanup.** `cu.delete(true, null)` not
`if (cu.exists()) cu.delete(...)`. The test created the resource;
it must exist. If it doesn't, the test is broken — let it fail.

## Conventions

- Java: Eclipse formatter (`jdt format <FQN>`)
- JavaScript: ESM (`.mjs`), no TypeScript
- Commits: imperative mood ("Add X", "Fix Y"), Co-Authored-By in message
- PRs: no "Generated with Claude Code" in body — Co-Authored-By is enough
