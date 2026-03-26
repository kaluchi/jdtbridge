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
```

If Eclipse is running, you will be prompted to stop it. After install, Eclipse restarts automatically with the same workspace.

## Commands

Run `jdt help <command>` for detailed flags and examples. Most commands have short aliases.

### Search & navigation

```bash
jdt projects                                           # list workspace projects
jdt project-info <name> [--lines N]                    # (alias: pi) project overview
jdt find <Name|package> [--source-only]                 # find types by name, wildcard, or package
jdt references <FQMN> [--field <name>]                  # (alias: refs) references to type/method/field
jdt subtypes <FQN>                                     # (alias: subt) all subtypes/implementors
jdt hierarchy <FQN>                                    # (alias: hier) supers + interfaces + subtypes
jdt implementors <FQMN>                                # (alias: impl) implementations of interface method
jdt type-info <FQN>                                    # (alias: ti) class overview (fields, methods)
jdt source <FQMN>                                      # (alias: src) source code (project + libraries)
```

### Testing & building

```bash
jdt build [--project <name>] [--clean]                 # (alias: b) build project
jdt test run <FQN> [-f] [-q]                            # launch tests (non-blocking)
jdt test run --project <name> [-f]                     # run tests in project
jdt test status <session> [-f] [--all] [--ignored]     # show test progress/results
jdt test sessions                                       # list test sessions
```

All commands auto-refresh from disk. `build` is the only command that triggers explicit builds.

### Diagnostics

```bash
jdt errors [--project <name>] [--file <path>]          # (alias: err) compilation errors
jdt errors --warnings --all                            # include warnings and all marker types
```

File paths are workspace-relative: `my-app/src/main/java/.../Foo.java`.

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
jdt launch list                                        # list launches (running + terminated)
jdt launch configs                                     # list saved launch configurations
jdt launch run <config> [-f] [-q]                      # launch a configuration
jdt launch debug <config> [-f] [-q]                    # launch in debug mode
jdt launch logs <name> [-f] [--tail N]                 # show console output
jdt launch stop <name>                                 # stop a running launch
jdt launch clear [name]                                # remove terminated launches
```

`-f` streams output in real-time until the process terminates.
Without `-f`, `launch run` prints an onboarding guide with available commands (`-q` to suppress).
Console output persists in Eclipse and is available via `launch logs` at any time.

### Editor

```bash
jdt editors                                             # (alias: ed) list all open editors (absolute paths)
jdt open <FQMN>                                        # open in Eclipse editor
```

## Instance discovery

The CLI reads `~/.jdtbridge/instances/*.json` to find running Eclipse instances. Each file contains port, auth token, PID, and workspace path. Stale instances are filtered by PID liveness.

When multiple instances are running, use `--workspace <hint>` or the CLI picks the first live one.

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
