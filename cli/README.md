# @kaluchi/jdtbridge — CLI reference

CLI for [JDT Bridge](../README.md). Requires Eclipse running with the jdtbridge plugin installed.

## Install

```bash
cd cli
npm install
npm link    # registers `jdt` and `jdtbridge` global commands
```

## Plugin setup

```bash
jdt setup                       # build + install into Eclipse
jdt setup --check               # diagnostic: show status of all components
jdt setup --skip-build          # reinstall last build
jdt setup --clean               # clean build (mvn clean verify)
jdt setup --remove              # uninstall plugin from Eclipse
jdt setup --eclipse <path>      # specify Eclipse path (saved to config)
jdt setup --claude              # configure Claude Code for this project (permissions + hooks)
jdt setup --remove-claude       # remove JDT Bridge hooks from Claude Code settings
```

Eclipse must be stopped for install/update/remove operations. If running, you will be prompted to stop it. After install, Eclipse restarts automatically with the same workspace.

## Commands

Run `jdt help <command>` for detailed flags and examples. Most commands have short aliases.

All data-returning commands support `--json` for structured output (JSON snapshot, or JSONL when streaming with `-f`).

### Dashboard

```bash
jdt status [sections...] [-q]                          # workspace overview (start here)
```

### Search & navigation

```bash
jdt find <Name|*Pattern*|pkg> [--source-only]          # find types by name, wildcard, or package
jdt references <FQMN> [--field <name>]                 # (alias: refs) references to type/method/field
jdt implementors <FQN>[#method]                        # (alias: impl) type or method implementors
jdt hierarchy <FQN>                                    # (alias: hier) supers + interfaces + subtypes
jdt type-info <FQN>                                    # (alias: ti) class overview (fields, methods)
jdt source <FQMN> [<FQMN> ...]                         # (alias: src) source code (project + libraries)
```

### Testing & building

```bash
jdt build [--project <name>] [--incremental]           # (alias: b) build project (default: clean)
jdt test run <FQN>[#method] [--project <name>] [-f]    # launch tests (non-blocking)
jdt test run --project <name> [--package <pkg>] [-f]   # run tests in project
jdt test status <testRunId> [-f] [--all] [--ignored]   # show test progress/results
jdt test runs                                          # list test runs
```

With `-f`, streams test progress until completion. `-q` suppresses the onboarding guide.
`--all` includes passed tests, `--ignored` shows skipped.

All commands auto-refresh from disk. `build` is the only command that triggers explicit builds.

### Diagnostics

```bash
jdt problems [--file <path>] [--project <name>]        # (alias: err) IMarker.PROBLEM markers
jdt problems --warnings --all                          # include warnings and all marker types
jdt refresh <file> [<file> ...] [-q]                   # (alias: r) notify Eclipse of file changes
jdt refresh --project <name>                           # refresh entire project
jdt refresh                                            # refresh entire workspace
```

`problems` file paths are workspace-relative: `my-app/src/main/java/.../Foo.java`.
`refresh` accepts absolute paths (converted to workspace resources automatically).

A PostToolUse hook (`jdt setup --claude`) calls `jdt refresh` automatically
after every Edit/Write — Eclipse stays in sync without manual intervention.

### Maven

```bash
jdt maven update                                       # (alias: up) update all Maven projects (Alt+F5)
jdt maven update --project m8-server                   # update specific project
jdt maven update -f                                    # wait for auto-build, show error count
jdt maven update --force --offline                     # force snapshots, offline mode
jdt maven update --no-config --no-clean --no-refresh   # skip config/clean/refresh steps
jdt maven up -q                                        # quiet mode, suppress guide
```

### Refactoring

```bash
jdt organize-imports <file>                            # (alias: oi) organize imports
jdt format <file>                                      # (alias: fmt) format code (Eclipse settings)
jdt rename <FQN> <newName>                             # rename type
jdt rename <FQMN> <newName>                            # rename method (FQMN includes method)
jdt rename <FQN> <newName> --field <old>               # rename field
jdt move <FQN> <target.package>                        # move type to another package
```

### Launches

```bash
jdt launch configs                                     # list saved configurations (CONFIGID)
jdt launch config <configId> [--xml] [--json]          # show configuration details
jdt launch run <configId> [-f] [-q]                    # launch a configuration
jdt launch debug <configId> [-f] [-q]                  # launch in debug mode
jdt launch list                                        # list launches (LAUNCHID, CONFIGID)
jdt launch logs <launchId> [-f] [--tail N]             # show console output
jdt launch stop <launchId>                             # stop a running launch
jdt launch clear [launchId]                            # remove terminated launches
jdt launch config --import <path> [--configid <name>]  # import .launch file
jdt launch config --delete <configId>                  # delete a configuration
```

`-f` streams output in real-time until the process terminates.
Without `-f`, `launch run` prints an onboarding guide with available commands (`-q` to suppress).
Console output persists in Eclipse and is available via `launch logs` at any time.

### Editor

```bash
jdt open <FQMN>                                        # open in Eclipse editor
```

### Workspace detail

```bash
jdt projects                                           # list workspace projects (name, location, repo)
jdt project-info <name> [--lines N]                    # (alias: pi) project overview (adaptive detail)
jdt editors                                            # (alias: ed) open editors (FQN, project, path)
jdt git [list] [repo...] [--no-files] [--limit N]      # git repos, branches, dirty state
```

### Agents

```bash
jdt agent run <provider> <agent> [--name <id>]         # launch agent session
jdt agent list                                         # running agent sessions
jdt agent stop <name>                                  # stop by session ID
jdt agent logs <name> [-f]                             # stream agent output
jdt agent providers                                    # available providers
```

Providers: `local` (system terminal with bridge env vars), `sandbox` (Docker with bridge connectivity).

### Instance management

```bash
jdt use                                                # list known workspaces
jdt use <N|alias|path>                                 # pin terminal to workspace
jdt use <N|alias> --alias <name>                       # set alias for workspace
jdt use --delete <N|alias>                             # remove workspace from registry
```

## Instance discovery

The CLI reads `~/.jdtbridge/instances/*.json` to find running Eclipse instances. Each file contains port, auth token, PID, and workspace path.

With multiple instances, `jdt use` pins the current terminal to a specific workspace via terminal session ID detection (`WT_SESSION`, `ITERM_SESSION_ID`, etc.). See [docs/jdt-use-spec.md](../docs/jdt-use-spec.md).

Override the home directory with `JDTBRIDGE_HOME` environment variable.

## Color output

Auto-detected from TTY. Override:

- `--color` / `--no-color` flags
- `FORCE_COLOR=1` / `NO_COLOR=1` env
- `JDTBRIDGE_COLOR=1` env

## Development

```bash
npm test              # run tests
npm run test:watch    # watch mode
```
