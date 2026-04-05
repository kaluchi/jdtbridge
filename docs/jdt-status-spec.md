# jdt status — Design Spec

## Overview

`jdt status` is a composite dashboard — a CLI screenshot of the running
Eclipse IDE. One command returns everything an agent or developer needs
to orient: git state, open files, compilation errors, launch configs,
running processes, test results, and project list.

Each section maps to a standalone command. The dashboard composes them
into a single output with markdown headers.

## Sections

| Section | Standalone command | What it shows |
|---|---|---|
| `intro` | `jdt status intro` | Context paragraph for AI agents |
| `git` | `jdt git list --no-files` | Repos, branches, dirty state |
| `editors` | `jdt editors` | Open editor tabs (active first) |
| `problems` | `jdt problems --json` | IMarker.PROBLEM markers (JSON for zero = `[]`) |
| `launch-configs` | `jdt launch configs` | Saved launch configurations (configId, type, project, target) |
| `launches` | `jdt launch list` | Running/terminated launches |
| `tests` | `jdt test runs` | Recent test runs with results |
| `projects` | `jdt projects` | Workspace projects with repo mapping |
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

## Output modes

### Text (default)

Multiple sections: each wrapped in `## Title` + `` ```bash `` code block.
Each data section includes a description before the code fence — Eclipse-
specific context (view names, key concepts). Descriptions are suppressed
by `-q`. Single section (`jdt status problems`): bare output, no header,
description, or fence.

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

### JSON (`--json`)

Composite JSON object with section names as keys. Each key maps to the
raw JSON from the standalone command. Meta-sections (intro, guide) are
excluded — they have no data representation.

```json
{
  "git": [...],
  "editors": [...],
  "problems": [...],
  "launch-configs": [...],
  "launches": [...],
  "tests": [...],
  "projects": [...]
}
```

## Architecture

### Compositor pattern

```
SECTION_NAMES          ordered list of 10 section identifiers
RENDERERS              section name → async renderer function
JSON_COMMANDS          section name → "jdt <cmd> --json" string
formatSection()        wraps { title, cmd, body, description } into markdown
```

Each renderer calls the standalone CLI command via `execSync` and returns
`{ title, cmd, body, description }`. The compositor assembles them with
`formatSection(section, { bare, quiet })`:
- `bare` = single section: body only, no header/fence/description
- `quiet` = suppress description text

Descriptions provide Eclipse-specific context: view names, shortcuts,
domain identifiers (CONFIGID, TestRunId, FQMN). They anchor agents
to high-entropy Eclipse terms so they connect CLI output to IDE concepts.

This means `jdt status` is always consistent with standalone commands —
it literally runs them and composites the output.

### Adding a new section

1. Add name to `SECTION_NAMES` array (position = display order)
2. Add renderer to `RENDERERS` map (async fn → `{ title, cmd, body, description }`)
3. Add JSON command to `JSON_COMMANDS` map
4. Add description with Eclipse-specific terms (view name, key identifiers)
5. Update `help` string — section list
6. Update test — section count

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
`jdt status problems` shows exactly what `jdt problems --json` returns.
This keeps the dashboard honest and makes sections independently
refreshable.

## Design decisions

- **`-q` = pure screenshot.** Without `-q`, status is screenshot +
  onboarding (intro explains jdt, guide teaches discovery). With `-q`,
  it's a clean data-only screenshot — no teaching, just state. First
  call: no `-q`. Subsequent refreshes: `-q`.

- **Single section = bare output.** `jdt status problems` returns just the
  problems output, no `##` header or code fence. This makes single-section
  calls drop-in replacements for the standalone command.

- **Problems use JSON.** Unlike other sections that show text tables,
  problems renders `--json` output. An empty `[]` is clearer than
  "(no problems)" for programmatic consumption and agent context.

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
  FQMN synthesis described in the launch spec.
- **[jdt-spec](jdt-spec.md)** — `--json` output principles and
  per-command JSON shapes.
