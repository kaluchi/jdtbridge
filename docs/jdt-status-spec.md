# jdt status — Design Spec

## Overview

`jdt status` is a composite dashboard — a CLI screenshot of the running
Eclipse IDE. One command returns everything an agent or developer needs
to orient: git state, open files, compilation errors, launch configs,
running processes, test results, and project list.

Each section maps to a standalone command. The dashboard composes them
into a single output with markdown headers.

## Requirements

This chapter defines what `jdt status` must satisfy as the sole
onboarding surface for LLM agents working with jdt against an
Eclipse workspace. Each requirement names a property the output
must hold for the artifact to be considered correct. Together
they form the contract the rest of this spec implements.

Framing principle: any knowledge an LLM needs in order to use
jdt must be obtainable from a single read of `jdt status` output.
No external prose (CLAUDE.md, AGENTS.md, README, docs/) participates
in agent onboarding. When a fact is needed by agents but absent
from `jdt status`, the gap is in `jdt status`, not in the agent.

### R1. Purpose

`jdt status` exists to bootstrap a cold LLM into productive pair
work with a developer who is using Eclipse on a Java project,
via the jdt CLI.

### R2. Cold-start assumption

The model has no prior knowledge of Eclipse, of the workspace's
contents, of the developer's project, or of jdt itself before
this read. The artifact carries the full informational burden
of bootstrap on its own. Onboarding content previously placed
in CLAUDE.md, AGENTS.md, README, or any other file does not
participate in agent onboarding.

### R3. Pair-work orientation

The model is configured for pair programming with a present
developer, not for autonomous operation. The developer is at
the keyboard, looking at the same workspace, and remains the
primary decision-maker. The agent augments the developer's
work, it does not substitute for the developer's judgment.
Signals about what the developer is currently doing — open
editors, uncommitted changes, recently active projects, current
branch — are therefore as load-bearing as information about
the codebase itself, because they ground the agent in the
developer's present focus.

### R4. Active artifact, not passive snapshot

`jdt status` is a system prompt, not a dashboard. Its content
is selected to shape what the model does next. A line is
included not because it might be informative, but because its
presence changes what the model would otherwise do. Lines that
do not change agent behavior are noise and must be removed
even if they are factually true about the IDE.

### R5. The agent acts as a developer in this IDE, not as a CLI generalist

The model behaves like a developer who has joined this project in
this IDE. It uses the IDE's capabilities to the degree the
developer does, and does not retreat to the lowest-common-denominator
shell tooling that would work in any unfamiliar codebase. The
workspace arrives already configured; the agent's job is to use
that configuration, not to ignore it or recreate it.

### R6. Do not bypass the IDE when jdt routes the same operation

Build, test, format, refactor, navigate, open in editor — these
have jdt equivalents that route through the running Eclipse. The
model uses the jdt path. It does not invoke `mvn`, `npm`,
`gradle`, `javac`, language servers, or similar tools directly
when their jdt equivalent exists. Going through the IDE keeps the
developer's view — open editors, problem markers, console output,
test results, debug sessions — synchronized with the agent's
actions; bypassing the IDE splits state and breaks pair work.

### R7. Reuse existing launch configurations

The workspace's launch configurations encode operations the
developer has already set up — full project builds, focused
test runs, application launches, build-tool goals (Maven,
Gradle, npm), debug configurations. When such a configuration
fits the task, the model invokes it via `jdt launch run` or
`jdt test run`, not by synthesizing an equivalent shell command
or creating a new one-off configuration. The default disposition
is *use what is there*; new configurations are created only when
nothing existing fits the task.

### R8. Non-destructive operations on the developer's environment

The agent does not invoke commands that destroy Eclipse's
incrementally-maintained state or modify the developer's
working environment. `mvn clean`, `mvn clean verify`,
`gradle clean`, and similar whole-world rebuilds invalidate
Eclipse's caches and force minutes of recomputation that
Eclipse has already done incrementally. `git stash`,
`git stash pop`, `git checkout` of files, `git reset`, and
similar tree-modifying operations alter the developer's
working state and may conflict with their in-progress work.
The agent uses Eclipse's incremental builds and operations
via jdt; when a destructive operation appears genuinely
necessary, it asks the developer first.

### R9. Efficient participation, not observation

The agent is invited to do programming work — editing code,
running tests, navigating the codebase, refactoring — not to
observe or repeatedly re-orient itself. Each action it takes
must be economical along three axes: wall-clock time, tokens
spent, and inference iterations consumed.

Economy comes from reusing what Eclipse has already computed:
the resolved type system, the cross-reference index, the
parsed ASTs, the resolved classpaths, the source-bundle
attachments, the marker cache, the launch configurations. A
single jdt query that returns the answer is preferred over
multiple Read/Grep iterations that reconstruct it. Eclipse's
resolved type is consulted rather than re-parsed from source.
An existing launch configuration is invoked rather than its
shell equivalent reassembled. Re-deriving information Eclipse
already maintains is the opposite of programming work — it is
wasted motion the developer would not accept from a teammate.

### R10. Information that exists only in the running Eclipse

Eclipse maintains, through dozens of orchestrated processes
(compiler, validators, indexers, plugin analyzers, debugger,
test runner), a live incremental model of the workspace and
its execution — updates ordered, computations parallelized,
caches kept warm. The artifact must convey to the model the
categories of information held in that model that bash either
cannot reach at all (live process state) or can only reach by
invoking whole-world rebuild tools that wipe Eclipse's
incremental caches and force minutes of wasted recomputation.

- **Resolved cross-references** — for any method, field, or
  type, the exact set of code locations that call, read,
  write, or otherwise reference it; resolution accounts for
  overloads, generics, shadowing, and inheritance, across
  both source and binary deps.
- **Resolved type hierarchy** — supertypes, transitive
  subtypes, implementors of interfaces, override chains;
  across source and binary deps.
- **Symbol resolution at use-site** — for any expression in
  source, the type it has and the exact declaration it binds
  to, accounting for imports, generics, and inheritance.
- **Live marker cache** — Eclipse's current Problems and
  Coverage view contents: compilation diagnostics with
  in-progress unsaved-edit reflection, plugin analyzer
  findings (Checkstyle, SpotBugs, SonarLint), EclEmma coverage
  gaps. Maintained incrementally as files change.
- **Quick fix proposals** — the resolutions Eclipse computes
  for each current marker (add import, implement abstract
  methods, unboxing, narrowing or widening conversions,
  convert to lambda).
- **Code completion candidates** — valid symbols at the
  cursor with their signatures and Javadoc, ranked by
  context.
- **Live editor state** — open tabs, active tab, selection
  (offset, range, FQN of element under cursor), cursor
  positions in inactive tabs, dirty state per tab, recent
  type and file opens within this session.
- **Live launch runtime** — running launches with their PIDs
  and accumulated console output as it streams; exit codes of
  recently terminated launches; build state per project
  (whether a build is running, queued, or up-to-date).
- **Live debug runtime** — active debug sessions, threads,
  current stack frames, variable values at the current frame,
  breakpoint hit states.
- **In-memory test runner state** — JUnit view contents:
  per-method pass/fail of the most recent runs with full
  stack traces, live progress of any in-flight run, ordered
  run history within this Eclipse session.
- **Recent navigation queries** — Search view, Type Hierarchy
  view, Call Hierarchy view — last queries the developer ran
  and their results.

### R11. qlang is mechanism, not capability

The onboarding goal is not qlang fluency. The goal is for the
model to know which questions about the Java codebase and the
IDE state can be answered through jdt, and to use qlang as the
means of asking. qlang is the mechanism; question-to-answer
translation is the capability. A model that recites qlang
grammar but cannot pose useful questions about the codebase
has not been onboarded; a model that poses the right questions
and reaches the right axes through trial and error has been
onboarded, even if its qlang is rough.

### R12. Model-agnostic artifact

The artifact and its generation make no assumptions about the
specific LLM consuming it. No optimization for any one vendor's
prompt cache, context format, tool-use schema, or training-
specific behavior. The same artifact serves Claude, GPT, Gemini,
local models, and any other LLM capable of reading text on
equal terms.

### R13. No shadow state

The generation of `jdt status` reads from Eclipse's runtime and
the filesystem; it writes no persistent state of its own. No
bespoke metadata files, no per-session caches, no pinning
records, no "last computed" breadcrumbs. If data must be stable
across calls, stability comes from determinism against state
that already exists — not from a new state store introduced for
the purpose.

### R14. Intent-driven instance resolution

`jdt status` selects its target Eclipse instance by a chain of
explicit developer-intent signals — environment variables,
per-terminal pin, current working directory inside a workspace's
project root, single live instance. Silent fallback to the "first
available" instance is forbidden.

When no signal resolves the selection (multiple instances, no
pin, cwd outside any workspace's project tree), the artifact
embeds the output of `jdt use` as the instance picker, and
instructs the developer to pin via `jdt use <N> && jdt status`.
Data sections are omitted in this state; the artifact still
carries the instance-agnostic onboarding (intro, help, guide) so
the model is not left in the dark. Any additional columns the
picker should expose (last-active timestamp, project count, cwd
match indicator) are added to `jdt use` itself, not duplicated
here.

When resolution succeeds, the selected workspace and the signal
that resolved it are reported in the artifact's opening content
(Intro section) — so the model's first read establishes where it
is and how it got there.

### R15. Output is valid Markdown

The artifact is a valid Markdown document — headings properly
nested, code fences balanced, tables well-formed, lists
consistently indented. A standard Markdown renderer produces a
rendering faithful to the authored structure. This makes the
artifact legible both as raw text (for LLM consumption) and as
rendered Markdown (for human inspection in a terminal pager,
IDE preview, or web view).

### R16. Impersonal voice

The artifact speaks of entities and actions, not to readers.
No second-person pronouns ("you", "your", "yourself"), no
first-person pronouns ("I", "we", "us", "our"). When a party
needs to be named, it is named explicitly — "the developer",
"the agent", "the workspace", "jdt CLI" — so the text reads
identically whether the consumer is the LLM agent, the
developer at the terminal, or a third party reviewing the
output.

## Sections

| Section | Standalone command | What it shows |
|---|---|---|
| `intro` | `jdt status intro` | Context paragraph for AI agents |
| `git` | `jdt git list --no-files` | Repos, branches, dirty state |
| `editors` | `jdt editors` | Open editor tabs (active first) |
| `problems` | `jdt q "@problems * {:severity /severity :file /location/file :line /location/startLine :message /message} \| table"` | IMarker.PROBLEM markers |
| `launch-configs` | `jdt launch configs` | Saved launch configurations (configId, type, project, target) |
| `launches` | `jdt launch list` | Running/terminated launches |
| `tests` | `jdt test runs` | Recent test runs with results |
| `projects` | `jdt q "@projects * inter(#{:fqn :rootPath :repo :branch}) \| table"` | Workspace projects with repo mapping |
| `help` | `jdt help` | Full command reference (dynamic) |
| `guide` | `jdt status guide` | Hints and patterns |

`intro`, `help`, and `guide` are meta-sections — shown by default but
suppressed by `-q` or when specific sections are requested.

## Section order

```
intro → git → editors → problems → launch-configs → launches → tests → projects → help → guide
```

The order follows a workflow narrative:
1. **Context** (intro) — what is this?
2. **Code state** (git, editors, problems) — what's being worked on?
3. **Execution** (launch-configs, launches, tests) — what's configured, running, tested?
4. **Structure** (projects) — what exists in the workspace?
5. **Reference** (help) — full command list
6. **Patterns** (guide) — how to use effectively?

`launch-configs` precedes `launches` because configs are the "what can run"
and launches are the "what is running" — definition before state.

## Output

Markdown only. Multiple sections: each wrapped in `## Title` + `` ```bash ``
code block. Each data section includes a description before the code fence —
Eclipse-specific context (view names, key concepts). Descriptions are
suppressed by `-q`. Single section (`jdt status problems`): bare output, no
header, description, or fence.

```
## Git

\`\`\`bash
$ jdt git list --no-files
REPO                STATUS  PATH                       BRANCH
eclipse-jdt-search  clean   D:/git/eclipse-jdt-search  master
\`\`\`

## Launch Configs

\`\`\`bash
$ jdt launch configs
CONFIGID          CONFIGTYPE        PROJECT    TARGET
jdtbridge-verify  Maven Build                  clean verify
ObjectMapperTest  JUnit             my-server  com.example.ObjectMapperTest
\`\`\`
```

Machine-readable access goes through the underlying command directly
— `jdt git --json`, `jdt launch configs --json`, `jdt q '@problems'`,
`jdt q '@projects'`, `jdt test runs --json`. `jdt status` has no
`--json` flag; the dashboard is a view, not a transport.

## Architecture

### Compositor pattern

```
SECTION_NAMES          ordered list of 10 section identifiers
RENDERERS              section name → async renderer function
formatSection()        wraps { title, cmd, body, description } into markdown
```

Each renderer calls the standalone CLI command via `execSync` and returns
`{ title, cmd, body, description }`. The compositor assembles them with
`formatSection(section, { bare, quiet })`:
- `bare` = single section: body only, no header/fence/description
- `quiet` = suppress description text

Descriptions provide Eclipse-specific context: view names, shortcuts,
domain identifiers (CONFIGID, TestRunId, FQN). They anchor agents
to high-entropy Eclipse terms so they connect CLI output to IDE concepts.

This means `jdt status` is always consistent with standalone commands —
it literally runs them and composites the output.

### Adding a new section

1. Add name to `SECTION_NAMES` array (position = display order)
2. Add renderer to `RENDERERS` map (async fn → `{ title, cmd, body, description }`)
3. Add description with Eclipse-specific terms (view name, key identifiers)
4. Update `help` string — section list
5. Update test — section count

## Design principles

### 1. CLI screenshot + agent bootstrap

`jdt status` serves two roles simultaneously:

- **Screenshot** — live snapshot of the IDE for orientation.
- **Bootstrap** — the first (and often only) command an agent sees.
  It must teach the agent what jdt is, what it can do, and how to
  discover more — all within the output of a single command.

The intro section explains the tool's purpose. The help section
embeds the full `jdt help` output — always current, zero drift.
The guide section shows patterns and hints. Section headers contain
the standalone command (`$ jdt git list --no-files`). The agent
learns the CLI vocabulary by reading the dashboard — no external
documentation needed.

### 2. Self-documenting over static docs

The interface evolves constantly. Static documentation (CLAUDE.md,
AGENTS.md) drifts out of sync. `jdt status` is the **live** source
of truth — it always reflects the current state of the tool. The
developer should not need to maintain agent instructions that describe
how to use jdt commands; the commands describe themselves.

The intro teaches what jdt is. The help section provides the full
command reference (dynamically generated via `jdt help`). The guide
teaches patterns. `jdt help <command>` provides per-command details.
The agent self-discovers capabilities through the CLI itself, not
through documentation files that may be stale.

### 3. Token budget awareness

The output goes into an agent's context window. Every irrelevant
token displaces reasoning. This drives several decisions:
- `-q` suppresses intro/help/guide and section descriptions
- Sections can be selected individually for focused refresh
- Git uses `--no-files` (summary, not full file list)
- Data is tabular and dense, not verbose prose

### 4. Narrative section order

Sections follow a workflow story, not alphabetical order:
context → code state → execution → structure → help.
This gives agents a mental model of the workspace in reading order.
Users control which sections appear, not where they appear.

### 5. Sections = standalone commands

Every data section is literally a standalone command run via
`execSync`. No dashboard-only data, no special aggregation.
`jdt status problems` shows exactly what `jdt q '@problems | table'`
returns. This keeps the dashboard honest and makes sections
independently refreshable.

## Design decisions

- **`-q` = pure screenshot.** Without `-q`, status is screenshot +
  onboarding (intro explains jdt, guide teaches discovery). With `-q`,
  it's a clean data-only screenshot — no teaching, just state. First
  call: no `-q`. Subsequent refreshes: `-q`.

- **Single section = bare output.** `jdt status problems` returns just the
  problems output, no `##` header or code fence. This makes single-section
  calls drop-in replacements for the standalone command.

- **Problems section uses a reshape + table.** `@problems` returns
  node-Maps whose `:location` is a sub-Map `{file,startLine,endLine}`.
  A bare `| table` renders the column as `[object Object]`, so the
  renderer uses a reshape — `@problems * {:severity /severity :file
  /location/file :line /location/startLine :message /message} | table`
  — which also acts as an idiom example for every other section that
  wants file/line columns.

## Constraints

- **Speed.** Status is the first command an agent runs. Each section
  should be sub-second. Sections run sequentially via `execSync` —
  total time is the sum of all sections.

- **No caching.** Every call produces fresh data. Guarantees accuracy
  but costs time. Acceptable because agents cache in context and
  refresh selectively.

## Relationship to other specs

- **[jdt-launch-spec.md](jdt-launch-spec.md)** — `launch-configs` section
  shows the same data as `jdt launch configs`. TARGET column uses the
  FQN synthesis described in the launch spec.
- **[jdt-spec](jdt-spec.md)** — `--json` output principles and
  per-command JSON shapes.
