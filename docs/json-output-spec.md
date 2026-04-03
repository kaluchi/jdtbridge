# `--json` Output — Design Spec

## Problem

jdt CLI outputs human-readable text (tables, markdown, colors). This works
for terminal use but is fragile for machine consumption — agents parse
ANSI-colored tables with regex, which breaks on format changes.

CLI-Anything project (HKUDS/CLI-Anything) enforces `--json` on every command
as a hard rule. Their experience with 30+ CLI harnesses confirms: structured
output is the single most impactful feature for agent integration.

`jdt source --json` already exists as a precedent. This spec extends the
pattern to all data-returning commands.

## Design Principles

1. **Flag, not mode.** `--json` is a per-invocation flag, not a config setting.
   Default output stays human-readable. Agents opt in.

2. **Server data, not rendered data.** `--json` returns the data structure
   from the Eclipse bridge (or assembled by the command), not a serialization
   of the rendered table. No ANSI codes, no padding, no badges.

3. **Early exit pattern.** Check `--json` before rendering. Return raw data
   and skip all formatting. Same pattern as `source.mjs`:
   ```js
   const data = await get(url);
   if (jsonFlag) { console.log(JSON.stringify(data, null, 2)); return; }
   // ... render for humans
   ```

4. **Stable contract.** JSON field names are the API. Once shipped, don't
   rename or remove fields without a major version bump.

5. **Paths use `toSandboxPath()`.** File paths in JSON go through the same
   path remapping as human output (Docker sandbox compatibility).

6. **Single object or array.** One result = object. Multiple = array.
   Never wrap in `{ "results": [...] }` unless there's metadata to add.

7. **No `--json` on action commands.** Commands that only print status
   messages (setup, refresh, build, agent run/stop) don't need it.
   Only data-returning commands.

## Scope — commands to cover

### Tier 1 — high value, clean data path (HTTP → JSON → render)

These commands already fetch JSON from Eclipse and transform to rows.
Adding `--json` is mechanical — early exit before `formatTable`.

| Command | Data source | Current output | JSON shape |
|---------|-------------|----------------|------------|
| `find` | `GET /find` | table | `[{kind, fqn, binary, file, origin}]` |
| `references` | `GET /references` | markdown | `[{file, line, in, content}]` |
| `implementors` | `GET /implementors` | list | `[{fqn, fqmn?, file, project, startLine, endLine, binary?, library?}]` |
| `hierarchy` | `GET /hierarchy` | tree | `{type, supertypes[], interfaces[], subtypes[]}` |
| `type-info` | `GET /type-info` | formatted text | `{fqn, kind, fields[], methods[], supertypes[]}` |
| `problems` | `GET /problems` | table | `[{file, line, col, severity, message}]` |
| `projects` | `GET /projects` | table | `[{name, location, repo}]` |
| `editors` | `GET /editors` | table | `[{fqn, project, path, active}]` |
| `test sessions` | `GET /test/sessions` | table | `[{session, label, total, passed, failed, time, status}]` |
| `test status` | `GET /test/status` | tree | `{session, tree: [{name, status, time, children[]}]}` |

### Tier 2 — mixed data sources

| Command | Complexity | Notes |
|---------|-----------|-------|
| `git` | subprocess + HTTP | Combines `/projects` data with `git status/log` subprocess. JSON = structured repo objects |
| `launch list` | HTTP | `GET /launch/list` → `[{name, type, mode, status, pid}]` |
| `launch configs` | HTTP | `GET /launch/configs` → `[{name, type}]` |
| `project-info` | HTTP | `GET /project-info` — complex adaptive rendering, JSON = raw server response |
| `source` | HTTP | **Already done** — `jdt source --json` |

### Out of scope

| Command | Why |
|---------|-----|
| `status` | Compositor of other commands — each section has its own `--json` |
| `build` | Action, not query |
| `refresh` | Action, not query |
| `setup` | Action, not query |
| `agent *` | Lifecycle, not query |
| `maven update` | Action, not query |
| `refactoring` | Action (rename, move, organize-imports, format) |
| `open` | Action (open editor) |

## Implementation pattern

### Typical command (find.mjs style)

```js
import { parseFlags } from "../args.mjs";

export async function find(args) {
  const flags = parseFlags(args);
  // ... build URL, fetch data
  const data = await get(url);

  if (flags.json) {
    console.log(JSON.stringify(data, null, 2));
    return;
  }

  // ... existing render logic unchanged
}
```

### Commands with data transformation

Some commands transform server data before rendering (dedup, enrich,
aggregate). The `--json` output should return the **transformed** data,
not raw server response — the transformation is the command's value-add.

```js
// references.mjs — groups by caller
const data = await get(url);        // flat list from server
const grouped = groupByCaller(data); // command's logic

if (flags.json) {
  console.log(JSON.stringify(grouped, null, 2));
  return;
}
```

### `parseFlags` update

`parseFlags()` in `args.mjs` already handles `--flag` parsing. `--json`
needs no special treatment — `flags.json` will be `true` when present.
Verify this works and add `--json` to each command's help string.

## Help text updates

Every command that gains `--json` needs:
1. `[--json]` in the Usage line
2. `--json    output as JSON` in the Options section
3. Example: `jdt find Foo --json`

Also update `jdt help` (cli.mjs) — no per-command change needed there,
the global help just shows command signatures.

## Testing strategy

### CLI tests (vitest)

For each command with `--json`:
1. Mock HTTP response (existing pattern)
2. Call command with `--json` flag
3. Assert stdout is valid JSON (`JSON.parse(stdout)`)
4. Assert key fields are present
5. Assert no ANSI codes in output

Example:
```js
it("--json outputs valid JSON", async () => {
  mockGet("/find?name=Foo", [{ kind: "C", fqn: "com.example.Foo" }]);
  const out = await runCommand(["find", "Foo", "--json"]);
  const data = JSON.parse(out);
  expect(data).toBeInstanceOf(Array);
  expect(data[0].fqn).toBe("com.example.Foo");
});
```

### Smoke test (live Eclipse)

```bash
# Every command should produce parseable JSON
jdt find Animal --json | node -e "JSON.parse(require('fs').readFileSync(0,'utf8'))"
jdt refs test.model.Dog#bark --json | node -e "..."
jdt problems --json | node -e "..."
```

## Rollout plan

1. **Add `--json` to Tier 1 commands** — mechanical, one command at a time
2. **Update help strings** — Usage, Options, Examples per command
3. **CLI tests** — one `--json` test per command
4. **Tier 2 commands** — git, launch, project-info
5. **Update AGENTS.md** — mention `--json` as available on all query commands
6. **Bump minor version** — new capability, not breaking

## Prior art

- `jdt source --json` — existing implementation in this project
- CLI-Anything HARNESS.md — mandates `--json` on every command
- `gh` (GitHub CLI) — `--json` with field selection (`gh pr list --json number,title`)
- `kubectl` — `-o json` for structured output
- `docker inspect` — JSON by default

## Future considerations (not in scope now)

- **Field selection** (`--json --fields fqn,file`) — like `gh --json`
- **JSONL streaming** for large result sets (test status, launch logs)
- **`jdt status --json`** — composite JSON of all sections
- **`--output` flag** with `json|table|csv` — overkill for now, `--json` is enough
