# jdt setup — Design Spec

## Overview

`jdt setup` manages the connection between the CLI and Eclipse.
Plugin installation via p2 director, prerequisite checking, and
Claude Code integration. Works with Eclipse IDE and Eclipse-based
products (Spring Tools / STS).

Remote setup is a separate subcommand:
[jdt-setup-remote-spec](jdt-setup-remote-spec.md).

## Modes

```
jdt setup                    build + install plugin + restart Eclipse
jdt setup --check            verify all components (diagnostic)
jdt setup --skip-build       install from last build, skip Maven
jdt setup --eclipse <path>   specify Eclipse installation
jdt setup --clean            clean build (mvn clean verify)
jdt setup --remove           uninstall plugin from Eclipse
jdt setup --claude           configure Claude Code hooks + agents
jdt setup --remove-claude    remove JDT Bridge hooks from Claude Code
```

## Prerequisites

Checked by `jdt setup --check` and before install:

| Prerequisite | Required version | Check |
|---|---|---|
| Node.js | >= 20 | `process.versions.node` |
| Java | >= 21 | `java -version` |
| Maven | >= 3.9 | `mvn --version` |
| Eclipse | any | `eclipsec(.exe)` or `.eclipseproduct` marker |

Maven is required only for builds (`jdt setup` without `--skip-build`).

## Eclipse discovery

`findEclipsePath()` resolves the Eclipse installation:

1. Config file (`~/.jdtbridge/config.json` → `eclipse` key)
2. Well-known paths: `D:/eclipse`, `C:/eclipse` (Windows),
   `/usr/local/eclipse`, `/opt/eclipse`, `~/eclipse` (Unix)
3. `--eclipse <path>` flag (saved to config for future runs)

### Eclipse-based products (STS)

`isEclipseInstall()` accepts two markers:
- `eclipsec(.exe)` — standard Eclipse (headless launcher)
- `.eclipseproduct` — any Eclipse-based product (Spring Tools, etc.)

When `eclipsec` is not present (branded products), the p2 director
runs via the Equinox launcher JAR (`org.eclipse.equinox.launcher_*.jar`)
with `java -jar`. This covers STS and other products that ship a
branded launcher instead of `eclipsec`.

### Profile detection

`detectProfile()` scans `<eclipse>/p2/org.eclipse.equinox.p2.engine/profileRegistry/`
for `.profile` directories. Prefers `epp.package.*` profiles.

Examples: `epp.package.jee`, `epp.package.java`, `SpringToolSuite4`.

## Install flow (`jdt setup`)

```
1. Check prerequisites (Node, Java, Maven)
2. Find Eclipse installation
3. Save eclipse path to config
4. Detect p2 profile
5. Check if plugin already installed (version from JAR filename)
6. Build plugin:
   a. Generate jdtbridge.target → points Eclipse at local install
   b. Detect JAVA_HOME from eclipse.ini (-vm entry)
   c. Run: mvn verify (or mvn clean verify with --clean)
   d. Output: site/target/repository/ (p2 site)
7. Capture all running workspaces (from instance files, before stop)
8. Stop Eclipse (prompt if running)
9. Remove old plugin version if present (p2 uninstall)
10. Install via p2 director from local repository
11. Restart all workspaces that were running (one process per workspace)
12. Wait for bridge on each (poll instance files + /projects health check, 120s timeout)
```

### `jdtbridge.target` — local target platform

Tycho builds need a target platform pointing to the Eclipse
installation. `generateTargetPlatform()` writes `jdtbridge.target`
at the repo root:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?pde version="3.8"?>
<target name="eclipse-local" sequenceNumber="1">
    <locations>
        <location path="D:/eclipse" type="Directory"/>
    </locations>
</target>
```

This file is gitignored (`*.target` in `.gitignore`,
`!jdtbridge-ci.target` exempts CI). Each developer's file points
to their local Eclipse — different paths, same build output.

Without it, Tycho cannot resolve OSGi dependencies and the build
fails. CI uses `jdtbridge-ci.target` which downloads Eclipse from
the update site.

### `--skip-build`

Skips Maven entirely. Installs whatever is in `site/target/repository/`
from the last build. Use after `mvn clean verify` or
`jdt launch run jdtbridge-verify` to avoid building twice.

### JAVA_HOME from eclipse.ini

`getEclipseJavaHome()` extracts the JDK path from Eclipse's `-vm`
entry in `eclipse.ini`. Passes it as `JAVA_HOME` to Maven so the
build uses the same JDK as Eclipse. Returns null if no `-vm` found.

## Check flow (`jdt setup --check`)

Five sections, each with pass/fail/info indicators:

```
Prerequisites
  ✓ CLI 2.6.0
  ✓ Node.js 22.18.0
  ✓ Java 21.0.4
  ✓ Maven 3.9.9

Eclipse
  ✓ D:/eclipse (4.39.0) — config
  ✓ Profile: epp.package.jee
  ✓ Running

Bridge
  ✓ D:\eclipse-workspace-jdtbridge ← active
  ✓ PID 36708, plugin 2.6.0.202604051223
  ✓ local  127.0.0.1:60853, token ******06dae
  ✓ remote 0.0.0.0:7777, token ******d3e30

  ✓ D:\eclipse-workspace
  ✓ PID 28172, plugin 2.6.0.202604051223
  ✓ local  127.0.0.1:58742, token ******2245c

Plugin source
  ✓ Repo: D:\git\eclipse-jdt-search
  · Not built yet

Claude Code
  ✓ PreToolUse hook: installed
  ✓ PostToolUse hook: installed
  ✓ Explore agent: installed
  ✓ Plan agent: installed
```

### Bridge section details

Each live instance is a block: workspace path, PID + plugin version,
local socket (bind address, port, masked token), optional remote
socket. Instances separated by blank line.

`← active` marks the instance this terminal is connected to, resolved
via `resolveInstance()` (same 4-step chain as regular commands: env
vars → ppid pin → terminal pin → discovery). Reacts to `jdt use`
switching.

When 2+ instances found and no pin exists, shows hint:
`N instances found. Use jdt use to pin this terminal.`

Remote instances (from `~/.jdtbridge/remote-instances/`) shown in
a separate Remote section with connection probe (connected / refused).

Bridge section probes each discovered instance via HTTP (`/status`)
to verify it's live — stale instance files are skipped.

Claude Code section only appears when cwd has `.claude/` and `.git/`.

### Instance file fields

Plugin writes `~/.jdtbridge/instances/<hash>.json`:

```json
{
  "port": 60853,
  "token": "233f6739351ce4c7e0de77ef53e06dae",
  "host": "127.0.0.1",
  "remotePort": 7777,
  "remoteToken": "5896a10e6e3000a722540d303dcd3e30",
  "pid": 36708,
  "workspace": "D:\\eclipse-workspace-jdtbridge",
  "version": "2.6.0.202604051223",
  "location": "reference:file:plugins/io.github.kaluchi.jdtbridge_2.6.0.202604051223.jar"
}
```

`remotePort` and `remoteToken` are present only when the remote
socket is enabled in preferences. Written by `Activator.writeBridgeFile()`.

Tokens must never be shown in full in CLI output — masked as
`******e06dae` (last 5 chars). Instance files store the full value
(needed for auth), but `--check` and any diagnostic output must mask.

## Remove flow (`jdt setup --remove`)

1. Find Eclipse, detect profile
2. Check installed version
3. Stop Eclipse (prompt)
4. p2 uninstall feature

## Claude Code integration (`jdt setup --claude`)

Writes to `.claude/settings.local.json` (project-level, gitignored):

### Permission rule
- `Bash(jdt *)` — auto-allow all jdt commands

### Hooks

| Hook | Matcher | Purpose |
|---|---|---|
| PreToolUse | `Bash` | Auto-allow `jdt *` commands without prompting |
| PostToolUse | `Edit\|Write` | `jdt refresh <file>` after file edits — notifies Eclipse of external changes |
| SubagentStart | (all) | Injects context telling subagents to skip AGENTS.md and environment checks |

### Agent definitions

Copies `cli/agents/*.md` to `.claude/agents/`:
- `Explore.md` — fast exploration agent with jdt semantic search
- `Plan.md` — planning agent with jdt code navigation

Files are tagged with `<!-- jdtbridge-managed -->` marker. Only
overwrites files that have the marker — user-created agents are
never touched.

### Migration

If jdt hooks exist in `.claude/settings.json` (committed, shared),
they are removed and moved to `settings.local.json` (local, gitignored).
Prevents merge conflicts when multiple developers have different setups.

## p2 director

`runDirector()` runs Eclipse's headless p2 director for install/uninstall.

Two modes:
- **eclipsec** — `eclipsec -nosplash -application org.eclipse.equinox.p2.director ...`
- **Launcher JAR** — `java -jar org.eclipse.equinox.launcher_*.jar ...` (STS fallback)

Operations:
- `p2Install(eclipse, profile, repoPath, featureIU)` — install from local file:// repo
- `p2Uninstall(eclipse, profile, featureIU)` — uninstall by feature ID

Feature IU: `io.github.kaluchi.jdtbridge.feature.feature.group`
Bundle ID: `io.github.kaluchi.jdtbridge`

## Eclipse lifecycle

- `isEclipseRunning()` — `tasklist` (Windows) or `pgrep` (Unix)
- `stopEclipse()` — graceful kill, wait 30s, force kill if needed
- `startEclipse(path, workspace)` — detached spawn, returns PID
- `waitForBridge(discoverFn, pid, 120s)` — poll instance files for
  matching PID, then `/projects` health check

Eclipse must be stopped for install/update/remove. If running,
user is prompted interactively. Non-TTY (piped) skips the prompt
and uses defaults.

## Files

CLI:
  commands/setup.mjs      — modes: check, install, remove, claude
  eclipse.mjs             — Eclipse discovery, lifecycle, p2 operations
  claude-setup.mjs        — hooks, permissions, agent file management
  home.mjs                — config read/write (~/.jdtbridge/config.json)

Data:
  ~/.jdtbridge/config.json           — eclipse path, saved on first setup
  ~/.jdtbridge/instances/<hash>.json — bridge instance files (plugin-written)
  jdtbridge.target                   — local target platform (gitignored)
  jdtbridge-ci.target                — CI target platform (committed)
  .claude/settings.local.json        — Claude Code hooks (gitignored)
  .claude/agents/*.md                — agent definitions (jdtbridge-managed)
