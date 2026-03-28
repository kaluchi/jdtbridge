# JDT Bridge Plugin — HTTP API

The Eclipse plugin exposes JDT functionality over a loopback HTTP server. All endpoints accept GET requests with query parameters and return JSON (`application/json`) unless noted otherwise.

The CLI (`jdt`) wraps these endpoints — you don't need to call them directly unless building your own client.

## Search & Navigation

### `GET /projects`

List all Java projects in the workspace.

```json
["my-app-server","my-app-shared","my-app-client"]
```

### `GET /project-info?project=<name>[&members-threshold=N]`

Project overview with adaptive detail. Returns source roots, packages, types, and optionally method signatures (included when `totalTypes <= members-threshold`, default 200).

### `GET /find?name=<Name>[&source]`

Find type declarations by name. Supports wildcards (`*Controller*`, `Find*`). Add `&source` to exclude binary/library types.

```json
[
  {"fqn":"com.example.service.UserService","file":"/my-app-server/src/main/java/..."}
]
```

### `GET /references?class=<FQN>[&method=<name>][&field=<name>][&paramTypes=<types>]`

Find all references to a type, method, or field. Filters out inaccurate matches and javadoc references. Returns file, line, enclosing member, and source line content.

`paramTypes` is a comma-separated list of parameter types for disambiguating overloaded methods (e.g., `paramTypes=String,int`). Supports simple names and FQN; generics are stripped for matching.

### `GET /subtypes?class=<FQN>`

Find all direct and indirect subtypes/implementors of a type.

### `GET /hierarchy?class=<FQN>`

Full type hierarchy: superclass chain, all super interfaces, and all subtypes.

### `GET /implementors?class=<FQN>&method=<name>[&paramTypes=<types>]`

Find implementations of an interface method across all implementing classes.

### `GET /type-info?class=<FQN>`

Class overview: kind, superclass, interfaces, fields (with modifiers and types), methods (with full signatures), and line numbers.

### `GET /source?class=<FQN>[&method=<name>][&paramTypes=<types>]`

Returns JSON with source code and AST-resolved references:
`{fqmn, file, startLine, endLine, source, refs[{fqmn, kind, scope, type, line, doc, file}]}`.
References are grouped by scope: `class` (same class), `project` (workspace source),
`dependency` (libraries). Multiple overloads return a JSON array.
All file paths are absolute filesystem paths.

## Testing

### `GET /test?class=<FQN>[&method=<name>][&timeout=<sec>][&no-refresh]`

### `GET /test?project=<name>[&package=<pkg>][&timeout=<sec>][&no-refresh]`

Run JUnit tests via Eclipse's built-in runner. By default, refreshes the project from disk and waits for auto-build before launching. Returns summary and failure details.

## Building

### `GET /build?[project=<name>][&clean]`

Trigger a project build. Always refreshes from disk first.

| Parameter | Description |
|-----------|-------------|
| `project` | Project to build (omit for workspace-wide incremental) |
| `clean` | Clean + full rebuild (requires `project`) |

Returns `{"errors": N}` with the count of compilation errors after build.

## Diagnostics

### `GET /errors?[file=<path>][&project=<name>][&warnings][&all]`

Compilation diagnostics. Refreshes from disk and waits for auto-build before reading markers.

| Parameter | Description |
|-----------|-------------|
| `file` | Workspace-relative path to filter by specific file |
| `project` | Project name to filter by |
| `warnings` | Include warnings (default: errors only) |
| `all` | All marker types (jdt + checkstyle + maven + ...) |

### `GET /refresh?[file=<path>][&project=<name>]`

Explicitly notify Eclipse that files changed on disk. No build wait, no markers. Automatic for `.java` edits when the PostToolUse hook is installed (`jdt setup --claude`).

| Parameter | Description |
|-----------|-------------|
| `file` | Absolute filesystem path to refresh (DEPTH_ZERO) |
| `project` | Project name to refresh (DEPTH_INFINITE) |
| _(none)_ | Refresh entire workspace (DEPTH_INFINITE) |

Returns `{"refreshed":true, ...}` or `{"refreshed":false, "reason":"not in workspace"}`.

## Refactoring

### `GET /organize-imports?file=<path>`

Organize imports using Eclipse project settings. Returns `{"added":N,"removed":N}`.

### `GET /format?file=<path>`

Format a Java file using Eclipse project formatter settings. Returns `{"modified":true/false}`.

### `GET /rename?class=<FQN>&newName=<name>[&method=<old>][&field=<old>][&paramTypes=<types>]`

Rename a type, method, or field. Updates all references across the workspace.

### `GET /move?class=<FQN>&target=<package>`

Move a type to another package. Creates the target package if it doesn't exist. Updates all references.

## Editor

### `GET /editors`

Returns all open editors as a JSON array. Active editor first, then tab order. Files are absolute OS paths.

### `GET /open?class=<FQN>[&method=<name>][&paramTypes=<types>]`

Open a type or method in the Eclipse editor.

## Launches

### `GET /launch/list`

Returns all registered launches (running and terminated) as a JSON array. Most recent first.

### `GET /launch/configs`

Returns all saved launch configurations (Run → Run Configurations).

### `GET /launch/console?name=<config-name>[&tail=<N>][&stream=stdout|stderr]`

Returns console output (stdout + stderr) of a launch as JSON.
`tail` limits to last N lines. `stream` filters to stdout or stderr only.
Returns `{name, terminated, output}`.

### `GET /launch/console/stream?name=<config-name>[&tail=<N>][&stream=stdout|stderr]`

Streams console output as `text/plain`. Connection stays open until the
process terminates. Writes accumulated content first, then live output.
Used by `jdt launch logs -f`.

### `GET /launch/run?name=<config-name>[&debug]`

Launch a saved configuration. Returns immediately.
Add `&debug` for debug mode.
Returns `{ok, name, mode, type, pid, cmdline, workingDir}` — metadata
fields (`pid`, `cmdline`, `workingDir`) are present when the process
has started.

### `GET /launch/stop?name=<name>`

Terminate a running launch.

### `GET /launch/clear[?name=<config-name>]`

Removes terminated launches and their console output. Without `name`,
removes all terminated. With `name`, removes only that one.

## Connection details

On startup, the plugin writes a JSON file to `~/.jdtbridge/instances/<hash>.json`:

```json
{
  "port": 52847,
  "token": "a1b2c3...",
  "pid": 12345,
  "workspace": "/home/user/my-workspace"
}
```

Every request must include an `Authorization: Bearer <token>` header. The server binds to `127.0.0.1` only.
