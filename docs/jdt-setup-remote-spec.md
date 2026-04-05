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
     `(no --token, auto-generated and written)`
   - Explicitly provided fields show value only, no annotation
   - Never show unchanged fields in update mode

3. **All paths are jdt-host absolute paths.** Agents work with
   absolute paths on the machine where jdt CLI runs. Eclipse-side
   paths (Windows drive letters, different mount points) must never
   reach agent output. Every path returned by jdt commands is
   translated to a jdt-host absolute path using project mappings.
   This is why project mapping exists — to convert between Eclipse
   filesystem and jdt-host filesystem. Without mapping, FQMN
   commands work but file-path output shows untranslated Eclipse
   paths which agents cannot use.

## Syntax

```bash
jdt setup remote                                                              status / onboarding
jdt setup remote --bridge-socket <host>:<port>                                configure (auto-token)
jdt setup remote --bridge-socket <host>:<port> --token <token>                configure with token
jdt setup remote --bridge-socket <host>:<port> --workspace <path>             Eclipse workspace (.metadata)
jdt setup remote --bridge-socket <host>:<port> --repo <path>                  repository (.project scan)
jdt setup remote --bridge-socket <host>:<port> --map-project <eclipse-project-name>=<path>    map single project
jdt setup remote --bridge-socket <host>:<port> --unmap-project <eclipse-project-name>         unmap project
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
  --workspace /mnt/workspace \
  --repo /mnt/extra-repo \
  --map orphan-project=/mnt/orphan \
  --check
```

Project mapping — three sources, each with its own key:

**`--workspace <path>`** — Eclipse workspace with `.metadata`.
CLI reads `.location` files to build project mapping.
Embedded projects: local path = `<workspace>/<project-name>`.
Linked projects: Eclipse-side path from `.location` URI — local
path unknown, shown in `--check` table for user to `--map-project`.

Error if `.metadata` not found:
```
✗ /mnt/dev is not an Eclipse workspace (no .metadata directory)

If this is a repository with project sources, use --repo:
  jdt setup remote --bridge-socket host:7777 --repo /mnt/dev

If this is a single project, use --map-project:
  jdt setup remote --bridge-socket host:7777 --map-project <name>=/mnt/dev
```

**`--repo <path>`** — scans directory for `.project` files, reads
`<name>`, matches against Eclipse project list. Multiple allowed.

**`--map-project <eclipse-project-name>=<path>`** — map single project
by its Eclipse project name. For linked projects outside workspace
or projects without `.project`.

**`--unmap-project <eclipse-project-name>`** — remove project mapping.

All composable in one call. Not specified = not touched on update.

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
   - STATUS — ✓ mapped, ✗ unmapped, source (.metadata, .project/repo, map)

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
    --repo /mnt/myapp --check

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
  myapp-core       D:\git\myapp\myapp-core     /mnt/myapp/myapp-core        ✓ repo
  myapp-server     D:\git\myapp\myapp-server   /mnt/myapp/myapp-server      ✓ repo
  infra            D:\git\infra                —                            ✗ linked
  deploy-tools     D:\git\deploy-tools         —                            ✗ linked

  Map linked projects:
    --map-project infra=<local-path-to-D:\git\infra>
    --map-project deploy-tools=<local-path-to-D:\git\deploy-tools>
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
   `--map`, `--map-project` from flags.

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
     `--map`, `--map-project`.
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

Keys match CLI flags. File `~/.jdtbridge/instances/remote-<hash>.json`:

```json
{
  "bridge-socket": "host.docker.internal:7777",
  "token": "abc123",
  "mount-points": ["/mnt/workspace", "/mnt/m8", "/mnt/automation"]
}
```

`mount-points` — directories where CLI scans for `.project` files.
Set via `--map-workspace`, `--map-repo`, or derived from
`--map-project` parent directories.

Existing local instance files (written by Eclipse plugin):
```json
{
  "port": 7777,
  "token": "70a491b0730b087abbbbeb272e797af4",
  "host": "127.0.0.1",
  "pid": 39488,
  "workspace": "D:\\eclipse-workspace-jdtbridge",
  "version": "2.5.0.202604050531",
  "location": "reference:file:plugins/io.github.kaluchi.jdtbridge_2.5.0.202604050531.jar"
}
```

Both read by `discoverInstances()`. Remote files have `host`
field with non-loopback address. No `pid` (no local process).

## Path resolution

### Problem

Agents work with absolute paths on the jdt-host. Eclipse returns
paths in its own filesystem format (`D:\git\myapp\src\Foo.java`).
Commands that accept file paths (`jdt refresh`, `jdt format`)
receive jdt-host paths (`/mnt/m8/myapp-core/src/Foo.java`).
Both directions need translation.

### Project path cache

File: `~/.jdtbridge/project-path-cache.json`

```json
{
  "scannedAt": 1775367354940,
  "mount-points": ["/mnt/workspace", "/mnt/m8"],
  "projects": {
    "myapp-core": "/mnt/m8/myapp-core",
    "myapp-server": "/mnt/m8/myapp-server",
    "inside": "/mnt/workspace/inside",
    "automation": "/mnt/automation"
  }
}
```

Maps Eclipse project name → jdt-host absolute path.
Built by scanning `mount-points` for `.project` files,
reading `<name>` from each.

### Cache lifecycle

**Populate:** scan mount-points recursively (limited depth),
find `.project` files, parse `<name>`, record directory path.
Atomic write (temp file + rename) for concurrency safety.

**Invalidate and rescan on:**
- `--check` requested
- mount-points in instance file changed
- Cache miss (path doesn't match any cached project)
- Cache file doesn't exist (first run)

**No TTL.** Invalidation is event-driven only.

### Resolution algorithm

**Eclipse path → jdt-host path** (output translation):

For each path in Eclipse response (refs, source, projects):
1. Extract project name (first segment of workspace-relative path)
2. Lookup in cache → replace Eclipse project root with local path
3. Miss → rescan mount-points → retry
4. Second miss → leave path untranslated

**jdt-host path → workspace-relative path** (input translation):

When `jdt refresh /mnt/m8/myapp-core/src/Foo.java`:
1. Lookup cache by longest prefix match → project `myapp-core`
   at `/mnt/m8/myapp-core`
2. Strip prefix → `src/Foo.java`
3. Send workspace-relative `/myapp-core/src/Foo.java` to server
4. Server resolves via `IWorkspaceRoot.findMember()`
5. Cache miss → rescan → retry → second miss → error

### Concurrency

Multiple agents calling `jdt` commands in parallel:
- Read cache: always consistent (atomic write guarantees)
- Write cache: last writer wins, same data (same `.project` files)
- Simultaneous rescan: redundant work, not incorrect

### Performance

Scan cost depends on mount point type:
- Local SSD: ~60ms for 30 projects (measured)
- Network mount / Docker bind over network: may be seconds
- Cache eliminates repeated scans — one scan per new/changed project

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
