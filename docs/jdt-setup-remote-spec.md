# jdt setup remote — Design Spec

## Overview

`jdt setup remote` configures CLI to connect to a remote Eclipse
instance. Creates instance files in `~/.jdtbridge/instances/`.

## Principles

1. **One-shot configuration.** Any remote setup achievable in a
   single command. All flags composable in one call.

2. **Output = operation report.** Command output shows exactly
   what was written to disk. Every field that was created or
   changed is shown. Unchanged fields are not shown.
   - New file → `Wrote <path>:` + all fields
   - Updated file → `Updated <path>:` + only changed fields
   - Changed fields show `(was: <old value>)`
   - Fields where a default was applied explain why:
     `(no --workspace, written from process working directory)`
     `(no --token, auto-generated and written)`
   - Explicitly provided fields show value only, no annotation
   - Never show unchanged fields in update mode

## Syntax

```bash
jdt setup remote                                                              status / onboarding
jdt setup remote --bridge-socket <host>:<port>                                configure (auto-token)
jdt setup remote --bridge-socket <host>:<port> --token <token>                configure with token
jdt setup remote --bridge-socket <host>:<port> --workspace <path>             set workspace root
jdt setup remote --bridge-socket <host>:<port> --token <t> --workspace <p>    full one-shot
jdt setup remote --delete --bridge-socket <host>:<port>                       remove remote
```

Token resolution: `--token` flag → existing token in instance
file → auto-generate.

`--bridge-socket <host>:<port>` is the primary key.
All mutations require it.

Validation:
```bash
jdt setup remote --check                                                      check all remotes
jdt setup remote --bridge-socket <host>:<port> --check                        check specific
jdt setup remote --bridge-socket <host>:<port> --token <t> --check            configure + check
```

Without `--check`: write config only, no network access.
With `--check`: probe TCP, verify token, discover projects,
validate project mappings.

One-shot example:
```bash
jdt setup remote --bridge-socket host.docker.internal:7777 --token abc123 \
  --workspace /mnt/dev \
  --map-root D:\\git\\myapp=/mnt/myapp \
  --map infra=/mnt/infra \
  --check
```

Project mapping:
```bash
jdt setup remote --bridge-socket <host>:<port> --map <project>=<local-path>
jdt setup remote --bridge-socket <host>:<port> --unmap <project>
jdt setup remote --bridge-socket <host>:<port> --map-root <eclipse-root>=<local-root>
```

`--map-root` maps all projects under an Eclipse-side root to a
local mount point. Example: Eclipse has 20 projects under
`D:\git\myapp\*`, all mounted to `/mnt/myapp`:

```bash
jdt setup remote --bridge-socket host.docker.internal:7777 \
  --map-root D:\\git\\myapp=/mnt/myapp
```

Maps `myapp-core` → `/mnt/myapp/myapp-core`, etc.
Project locations known from the probe response.

## Output: new instance

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token abc123

Wrote ~/.jdtbridge/instances/remote-a1b2c3.json:
  port:      7777
  host:      host.docker.internal
  token:     ***c123
  workspace: /mnt/dev (no --workspace, written from process working directory)
```

```
$ jdt setup remote --bridge-socket host.docker.internal:7777

Wrote ~/.jdtbridge/instances/remote-a1b2c3.json:
  port:      7777
  host:      host.docker.internal
  token:     ***f4a8 (no --token, auto-generated and written)
  workspace: /mnt/dev (no --workspace, written from process working directory)
```

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 \
    --token abc123 --workspace /opt/project

Wrote ~/.jdtbridge/instances/remote-a1b2c3.json:
  port:      7777
  host:      host.docker.internal
  token:     ***c123
  workspace: /opt/project
```

## Output: update instance

Only changed fields shown:

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token new-token-xyz

Updated ~/.jdtbridge/instances/remote-a1b2c3.json:
  token:     ***n-xyz (was: ***f4a8)
```

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --workspace /opt/other

Updated ~/.jdtbridge/instances/remote-a1b2c3.json:
  workspace: /opt/other (was: /mnt/dev)
```

## Output: no arguments (onboarding / status)

No instance files:
```
$ jdt setup remote

No remote instances configured.

To connect to a remote Eclipse:

  1. On the Eclipse host:
     Window > Preferences > JDT Bridge
     - Bind address: All interfaces
     - Set a fixed port
     - Generate a remote token and copy it

  2. Here:
     jdt setup remote --bridge-socket <host>:<port> --token <token>
```

Instance files exist:
```
$ jdt setup remote

2 remote instances configured.
Run with --check to verify, or jdt use to switch.

  jdt setup remote --check
  jdt setup remote --bridge-socket <host>:<port> --check
  jdt use
```

## `--check` mode

Output structure per remote:

1. `<host>:<port>` header line
2. Check lines (✓/✗ each):
   - TCP — port reachable
   - Token — auth accepted/rejected
   - Plugin — version
   - .metadata / .project scan status
3. Project table (indented under the remote):
   - PROJECT — Eclipse project name
   - ECLIPSE_PATH — path on Eclipse host (from server or .metadata)
   - LOCAL_PATH — mapped local path, or `—` if unmapped
   - STATUS — ✓ mapped, ✗ unmapped, source (.metadata, .project,
     map-root, map)

When remote is offline: check lines show failure, project table
shows all known projects with STATUS ✗ offline.

Check all remotes:
```
$ jdt setup remote --check

host.docker.internal:7777
  ✓ TCP
  ✓ Token ***a1b2
  ✓ Plugin 2.5.0

  PROJECT                              LOCAL_PATH                 STATUS
  io.github.kaluchi.jdtbridge          /mnt/dev/plugin            ✓
  io.github.kaluchi.jdtbridge.tests    /mnt/dev/plugin.tests      ✓
  io.github.kaluchi.jdtbridge.ui       /mnt/dev/ui                ✓
  infra                                —                          ✗

192.168.1.100:8888
  ✓ TCP
  ✗ Token ***d4e5 rejected (401)

  PROJECT                              LOCAL_PATH                 STATUS
  myapp-core                           /mnt/myapp/myapp-core      ✗ offline
  myapp-server                         /mnt/myapp/myapp-server    ✗ offline

staging.local:8888
  ✗ TCP — connection refused

  PROJECT                              LOCAL_PATH                 STATUS
  batch-jobs                           /mnt/batch                 ✗ offline
```

Check specific remote:
```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --check

host.docker.internal:7777
  ✓ TCP
  ✓ Token ***a1b2
  ✓ Plugin 2.5.0

  PROJECT          ECLIPSE_PATH                LOCAL_PATH                    STATUS
  myapp-core       D:\git\myapp\myapp-core     /mnt/myapp/myapp-core        ✓
  myapp-server     D:\git\myapp\myapp-server   /mnt/myapp/myapp-server      ✓
  myapp-shared     D:\git\myapp\myapp-shared   /mnt/myapp/myapp-shared      ✓
  infra            D:\git\infra                —                            ✗
  deploy-tools     D:\git\deploy-tools         —                            ✗
```

Configure + check in one call:
```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token abc123 \
    --map-root D:\\git\\myapp=/mnt/myapp --check

Wrote ~/.jdtbridge/instances/remote-a1b2c3.json:
  port:      7777
  host:      host.docker.internal
  token:     ***c123
  workspace: /mnt/dev (no --workspace, written from process working directory)

host.docker.internal:7777
  ✓ TCP
  ✓ Token ***c123
  ✓ Plugin 2.5.0

  PROJECT          ECLIPSE_PATH                LOCAL_PATH                    STATUS
  myapp-core       D:\git\myapp\myapp-core     /mnt/myapp/myapp-core        ✓ map-root
  myapp-server     D:\git\myapp\myapp-server   /mnt/myapp/myapp-server      ✓ map-root
  infra            D:\git\infra                —                            ✗
  deploy-tools     D:\git\deploy-tools         —                            ✗
```

With `--workspace` pointing to a mounted Eclipse workspace:
```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token abc123 \
    --workspace /mnt/workspace --check

Wrote ~/.jdtbridge/instances/remote-a1b2c3.json:
  port:      7777
  host:      host.docker.internal
  token:     ***c123
  workspace: /mnt/workspace

host.docker.internal:7777
  ✓ TCP
  ✓ Token ***c123
  ✓ Plugin 2.5.0
  ✓ .metadata found in /mnt/workspace

  PROJECT          ECLIPSE_PATH                LOCAL_PATH                        STATUS
  myapp-core       D:\git\myapp\myapp-core     /mnt/workspace/myapp-core        ✓ .metadata
  myapp-server     D:\git\myapp\myapp-server   /mnt/workspace/myapp-server      ✓ .metadata
  infra            D:\git\infra                —                                ✗
  deploy-tools     D:\git\deploy-tools         —                                ✗
```

Without `.metadata` — scan `.project` files:
```
$ jdt setup remote --bridge-socket host.docker.internal:7777 \
    --workspace /mnt/dev --check

host.docker.internal:7777
  ✓ TCP
  ✓ Token ***c123
  ✓ Plugin 2.5.0
  ✗ no .metadata in /mnt/dev, scanning .project files...

  PROJECT                              LOCAL_PATH              STATUS
  io.github.kaluchi.jdtbridge          /mnt/dev/plugin         ✓ .project
  io.github.kaluchi.jdtbridge.tests    /mnt/dev/plugin.tests   ✓ .project
  io.github.kaluchi.jdtbridge.ui       /mnt/dev/ui             ✓ .project
  io.github.kaluchi.jdtbridge.parent   /mnt/dev                ✓ .project
  myapp-core                           —                       ✗
  myapp-server                         —                       ✗
  myapp-shared                         —                       ✗
```

No `.metadata` and no `.project` files:
```
$ jdt setup remote --bridge-socket host.docker.internal:7777 \
    --workspace /mnt/dev --check

host.docker.internal:7777
  ✓ TCP
  ✓ Token ***c123
  ✓ Plugin 2.5.0
  ✗ no .metadata in /mnt/dev
  ✗ no .project files in /mnt/dev
```

## Algorithm

1. **Parse** — extract `--bridge-socket`, `--token`, `--workspace`,
   `--map`, `--map-root` from flags.

2. **Check existing** — scan `~/.jdtbridge/instances/` for file
   matching this bridge-socket.
   - Found → update (reuse existing token if `--token` not provided).
   - Not found → create.

3. **Write** — instance file
   `~/.jdtbridge/instances/remote-<hash>.json` where hash is
   derived from bridge-socket. Contains host, port, token,
   workspace, project path mappings.

4. **Report** — `Wrote`/`Updated` with fields (see Output sections).

5. **If `--check`:**
   - **Probe** — TCP connect.
   - **Auth** — `GET /status` with `Authorization: Bearer <token>`.
   - **Discover** — from response: plugin version, project list.
   - **Map projects** — match against `.metadata`, `.project` files,
     `--map`, `--map-root`.
   - **Report** — check lines + project table.

## Token sources

1. **Generate in Eclipse preferences.** Window > Preferences >
   JDT Bridge > Generate remote token. Persistent, survives
   Eclipse restarts. One token per remote agent — revocable.

2. **Auto-generated by CLI.** When no `--token` and no existing
   instance file — CLI generates a token. Must be registered
   in Eclipse preferences to be accepted.

3. **Reused from existing instance file.** When updating an
   already-configured remote without `--token` — existing
   token is kept.

## Multiple remote instances

Each bridge-socket creates a separate instance file.
`jdt use` lists them all.

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token aaa
$ jdt setup remote --bridge-socket 192.168.1.100:8888 --token bbb
$ jdt setup remote --bridge-socket 10.0.0.5:9999 --token ccc

$ jdt use
#  ALIAS  WORKSPACE        STATUS   PINNED  HOST                    PORT   PLUGIN
1  dev    /mnt/dev         online   pinned  host.docker.internal    7777   2.5.0
2  stage  /mnt/staging     online           192.168.1.100           8888   2.4.0
3  prod   /mnt/production  offline          10.0.0.5                9999
```

## Update and replace

Same bridge-socket = update existing instance file:

```bash
# Replace token
jdt setup remote --bridge-socket host.docker.internal:7777 --token new-token

# Re-check without changes
jdt setup remote --bridge-socket host.docker.internal:7777 --check
```

Different bridge-socket = new instance:

```bash
jdt setup remote --bridge-socket other-host:7777 --token xyz
```

## Instance file format

Based on existing instance file structure. Remote adds `host`
field and optional `projectMappings`:

```json
{
  "port": 7777,
  "token": "abc123",
  "host": "host.docker.internal",
  "workspace": "/mnt/dev",
  "remote": true,
  "projectMappings": {
    "myapp-core": {
      "eclipsePath": "D:\\git\\myapp\\myapp-core",
      "localPath": "/mnt/myapp/myapp-core"
    },
    "myapp-server": {
      "eclipsePath": "D:\\git\\myapp\\myapp-server",
      "localPath": "/mnt/myapp/myapp-server"
    }
  }
}
```

Existing local instance files (written by Eclipse plugin):
```json
{
  "port": 7777,
  "token": "70a491b0730b087abbbbeb272e797af4",
  "pid": 39488,
  "workspace": "D:\\eclipse-workspace-jdtbridge",
  "version": "2.5.0.202604040656",
  "location": "reference:file:plugins/io.github.kaluchi.jdtbridge_2.5.0.202604040656.jar"
}
```

Both read by the same `discoverInstances()`. Remote files have
`host` field (local files default to `127.0.0.1`). Remote files
have `remote: true` flag. No `pid` (no local process).

## Path handling

Most commands use FQMN — platform-independent, no path issues.

Eclipse returns absolute paths in its filesystem format in
responses. CLI translates for display via `toSandboxPath()`.

Commands that accept file paths (`jdt refresh`, `jdt format`)
use project mappings to convert:

When `jdt refresh /mnt/myapp/myapp-core/src/Foo.java` is called:
1. CLI finds project `myapp-core` by matching localPath prefix
2. Strips prefix: `src/Foo.java`
3. Sends workspace-relative: `/myapp-core/src/Foo.java`
4. Server resolves via `IWorkspaceRoot.findMember()`

Projects not mapped locally: FQMN commands work. File-path
commands skip with warning.

## Relationship to other specs

- **[jdt-setup-spec](jdt-setup-spec.md)** — parent spec for
  `jdt setup`. Local setup and overview.
- **[jdt-use-spec](jdt-use-spec.md)** — lists and switches
  between instances including remote.
- **[bridge-ui-spec](bridge-ui-spec.md)** — Eclipse preferences
  for bind address, fixed port, remote token generation.

## Files

CLI:
  commands/setup.mjs         — `remote` subcommand
  discovery.mjs              — reads instance files with host field
  paths.mjs                  — path translation using project mappings

Plugin:
  HttpServer.java            — auth accepts registered tokens
  ServerPreferences.java     — registered tokens storage

UI:
  preferences/               — remote token generation and management
