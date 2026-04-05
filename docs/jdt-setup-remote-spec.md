# jdt setup remote — Design Spec

## Overview

`jdt setup remote` configures CLI to connect to a remote Eclipse
instance. Creates instance files in `~/.jdtbridge/remote-instances/`.

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
   translated to a jdt-host absolute path using project path cache.
   Without cache, FQMN commands work but file-path output shows
   untranslated Eclipse paths which agents cannot use.

## Syntax

```bash
jdt setup remote                                                              status / onboarding
jdt setup remote --bridge-socket <host>:<port>                                configure (auto-token)
jdt setup remote --bridge-socket <host>:<port> --token <token>                configure with token
jdt setup remote --bridge-socket <host>:<port> --add-mount-point <path>       add scan directory
jdt setup remote --bridge-socket <host>:<port> --remove-mount-point <path>    remove scan directory
jdt setup remote --delete --bridge-socket <host>:<port>                       remove remote
jdt setup remote --json                                                       all remotes as JSON
jdt setup remote --bridge-socket <host>:<port> --json                         specific remote as JSON
jdt setup remote --bridge-socket <host>:<port> --check --json                 check results as JSON
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

Without `--check`: write config + scan mount points.
With `--check`: additionally probe TCP, verify token, compare
cached projects against Eclipse project list.

One-shot example:
```bash
jdt setup remote --bridge-socket host.docker.internal:7777 --token abc123 \
  --add-mount-point /mnt/workspace \
  --add-mount-point /mnt/m8 \
  --add-mount-point /mnt/automation \
  --check
```

## Mount points

`--add-mount-point <path>` — adds a directory to scan for
`.project` files. Multiple allowed. Idempotent — adding an
already-present mount point rescans it without duplicating.

`--remove-mount-point <path>` — removes a directory.
Idempotent — removing an absent mount point is a no-op.
Invalidates project path cache.

Operations applied in argument order. Mixed add/remove in one call:
```bash
--add-mount-point /a --remove-mount-point /b --add-mount-point /c
```
Executes: add /a → remove /b → add /c.
`--add-mount-point /x --remove-mount-point /x` = not present (remove wins).
`--remove-mount-point /x --add-mount-point /x` = present (add wins).

On `--add-mount-point`: CLI immediately scans the directory,
finds `.project` files, reads `<name>`, populates project path
cache, outputs result table.

## Token display

Tokens are masked in output: `******b7173` (last 5 characters).

Exception: when a token is auto-generated, it is shown in full
exactly once so the user can copy it:

```
  token: e240be6743978f011bfd326c9d3c392d (auto-generated, shown once)
```

Subsequent calls show masked: `******c392d`.

No clipboard API — Docker containers have no GUI.
User copies from terminal output.

## Output: new instance

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token abc123 \
    --add-mount-point /mnt/workspace --add-mount-point /mnt/m8

Wrote ~/.jdtbridge/remote-instances/a1b2c3.json:
  bridge-socket: host.docker.internal:7777
  token:         ******bc123

Scanning mount points for .project files...

  PROJECT          LOCAL_PATH                   MOUNT_POINT
  inside           /mnt/workspace/inside        /mnt/workspace
  myapp-core       /mnt/m8/myapp-core           /mnt/m8
  myapp-server     /mnt/m8/myapp-server         /mnt/m8
  myapp-shared     /mnt/m8/myapp-shared         /mnt/m8

4 projects cached.
```

```
$ jdt setup remote --bridge-socket host.docker.internal:7777

Wrote ~/.jdtbridge/remote-instances/a1b2c3.json:
  bridge-socket: host.docker.internal:7777
  token:         e240be6743978f011bfd326c9d3c392d (no --token, auto-generated, shown once)
```

## Output: add mount point to existing

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 \
    --add-mount-point /mnt/automation

Updated ~/.jdtbridge/remote-instances/a1b2c3.json:
  mount-points: added /mnt/automation

Scanning /mnt/automation for .project files...

  PROJECT      LOCAL_PATH         MOUNT_POINT
  automation   /mnt/automation    /mnt/automation

1 project cached. Total: 5 projects.
```

## Output: remove mount point

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 \
    --remove-mount-point /mnt/automation

Updated ~/.jdtbridge/remote-instances/a1b2c3.json:
  mount-points: removed /mnt/automation

Removed from cache:

  PROJECT      LOCAL_PATH         MOUNT_POINT
  automation   /mnt/automation    /mnt/automation

1 project removed. Total: 4 projects.
```

## Output: update token

Only changed fields:

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token new-token

Updated ~/.jdtbridge/remote-instances/a1b2c3.json:
  token: ******token (was: ******bc123)
```

## Output: no arguments (onboarding / status)

No instance files:
```
$ jdt setup remote

No remote instances configured.

To connect to a remote Eclipse:

  1. On the Eclipse host:
     Window > Preferences > JDT Bridge
     - Enable Remote socket
     - Set a fixed port
     - Copy the remote token

  2. Here, configure the connection and mount points where your
     project sources are accessible:

     jdt setup remote \
       --bridge-socket <eclipse-host>:<eclipse-port> \
       --token <paste-token-from-step-1> \
       --add-mount-point <mounted-directory> \
       --add-mount-point <another-mounted-directory>

     <eclipse-host> — hostname or IP where Eclipse is running.
       For Docker on the same machine: host.docker.internal
       For SSH tunnel: localhost
       For remote machine: IP or hostname

     <eclipse-port> — the fixed port you set in Eclipse preferences.

     <paste-token-from-step-1> — the remote token from Eclipse preferences.

     <mounted-directory> — each directory on this machine that
       contains Eclipse project sources (with .project files).
       Add as many --add-mount-point flags as you have mount points.
       These are scanned for .project files to build path mappings.

     Examples:

     Docker container, Eclipse on same machine, one project dir:
       jdt setup remote \
         --bridge-socket host.docker.internal:7777 \
         --token e240be6743978f011bfd326c9d3c392d \
         --add-mount-point /workspace

     Docker container, multiple project directories:
       jdt setup remote \
         --bridge-socket host.docker.internal:7777 \
         --token e240be6743978f011bfd326c9d3c392d \
         --add-mount-point /mnt/workspace \
         --add-mount-point /mnt/libs \
         --add-mount-point /mnt/tools

     Remote machine via SSH tunnel (tunnel set up separately):
       jdt setup remote \
         --bridge-socket localhost:7777 \
         --token e240be6743978f011bfd326c9d3c392d \
         --add-mount-point /home/user/projects

     After configuring, verify everything works:
       jdt setup remote --check
```

Instance files exist — full status per remote, project table,
available operations:

```
$ jdt setup remote

── host.docker.internal:7777 ──────────────────────────────────

  SETTING        VALUE
  token          ******c392d
  mount-points   /mnt/workspace, /mnt/m8

  PROJECT          LOCAL_PATH                   MOUNT_POINT
  inside           /mnt/workspace/inside        /mnt/workspace
  myapp-core       /mnt/m8/myapp-core           /mnt/m8
  myapp-server     /mnt/m8/myapp-server         /mnt/m8
  myapp-shared     /mnt/m8/myapp-shared         /mnt/m8

  File: ~/.jdtbridge/remote-instances/a1b2c3.json

── 192.168.1.100:8888 ─────────────────────────────────────────

  SETTING        VALUE
  token          ******54d2f
  mount-points   /home/user/projects

  PROJECT          LOCAL_PATH                        MOUNT_POINT
  webapp           /home/user/projects/webapp        /home/user/projects

  File: ~/.jdtbridge/remote-instances/d4e5f6.json

────────────────────────────────────────────────────────────────

Verify connection and project mapping against Eclipse:
  jdt setup remote --check

Switch between remote instances:
  jdt use

Update token:
  jdt setup remote --bridge-socket <host>:<port> --token <new-token>

Add projects from another directory:
  jdt setup remote --bridge-socket <host>:<port> --add-mount-point <path>

Remove a remote instance:
  jdt setup remote --delete --bridge-socket <host>:<port>
```

## `--check` mode

Verification phase. When combined with config flags (`--token`,
`--add-mount-point`), config is written first, then check runs.
Check rescans mount points and persists updated cache.

### Algorithm

1. Read instance file — bridge-socket, token, mount-points.

2. Rescan mount-points — find `.project` files, parse `<name>`,
   build fresh project set A (project name → local path).
   Persist to cache file.

3. TCP probe — connect to bridge-socket.
   Failure → report, skip remaining steps for this remote.

4. Auth + project list — `GET /projects` with `Authorization: Bearer <token>`.
   401 → report token rejected, skip project comparison.
   200 → token accepted, response contains project list for step 5.

5. Compare sets (using project list from step 4):
   - `A ∩ B` — mapped and verified (project in both cache and Eclipse)
   - `B \ A` — in Eclipse but not mapped (server knows it, no local .project)
   - `A \ B` — cached but not in Eclipse (local .project exists, server doesn't list it)

6. Report — per remote: check results, three project tables,
   instance file path, actionable hints.

### Output

```
$ jdt setup remote --check

── host.docker.internal:7777 ──────────────────────────────────

  CHECK          STATUS
  TCP            ✓ connected
  Token          ✓ ******c392d accepted
  Plugin         ✓ 2.5.0

  SETTING        VALUE
  token          ******c392d
  mount-points   /mnt/workspace, /mnt/m8

  Mapped and verified against Eclipse:
  PROJECT          LOCAL_PATH                   MOUNT_POINT      VERIFIED
  inside           /mnt/workspace/inside        /mnt/workspace   ✓
  myapp-core       /mnt/m8/myapp-core           /mnt/m8          ✓
  myapp-server     /mnt/m8/myapp-server         /mnt/m8          ✓
  myapp-shared     /mnt/m8/myapp-shared         /mnt/m8          ✓

  In Eclipse but not mapped locally:
  PROJECT          ECLIPSE_PATH
  deploy-tools     D:\git\deploy-tools
  infra            D:\git\infra

  Cached but no longer in Eclipse:
  PROJECT          LOCAL_PATH
  old-project      /mnt/workspace/old-project

  File: ~/.jdtbridge/remote-instances/a1b2c3.json

── 192.168.1.100:8888 ─────────────────────────────────────────

  CHECK          STATUS
  TCP            ✗ connection refused

  SETTING        VALUE
  token          ******54d2f
  mount-points   /home/user/projects

  Cannot verify projects (Eclipse offline).

  PROJECT          LOCAL_PATH                        MOUNT_POINT
  webapp           /home/user/projects/webapp        /home/user/projects

  File: ~/.jdtbridge/remote-instances/d4e5f6.json

────────────────────────────────────────────────────────────────

Fix unmapped projects by adding their mount point:
  jdt setup remote --bridge-socket host.docker.internal:7777 \
    --add-mount-point <directory-containing-deploy-tools-and-infra>

Remove stale cached projects by removing their mount point:
  jdt setup remote --bridge-socket host.docker.internal:7777 \
    --remove-mount-point <mount-point-of-old-project>

Fix connection:
  - Is Eclipse running on 192.168.1.100?
  - Remote socket enabled in Eclipse preferences?
  - Port open in firewall?
  - For SSH tunnel: is the tunnel running?
```

## `--json` mode

All output as JSON. Composable with `--check` and no-args status.

`jdt setup remote --json` — all remotes:
```json
[
  {
    "bridge-socket": "host.docker.internal:7777",
    "file": "~/.jdtbridge/remote-instances/a1b2c3.json",
    "token": "******c392d",
    "mount-points": ["/mnt/workspace", "/mnt/m8"],
    "projects": [
      { "project": "inside", "localPath": "/mnt/workspace/inside", "mountPoint": "/mnt/workspace" },
      { "project": "myapp-core", "localPath": "/mnt/m8/myapp-core", "mountPoint": "/mnt/m8" },
      { "project": "myapp-server", "localPath": "/mnt/m8/myapp-server", "mountPoint": "/mnt/m8" },
      { "project": "myapp-shared", "localPath": "/mnt/m8/myapp-shared", "mountPoint": "/mnt/m8" }
    ]
  },
  {
    "bridge-socket": "192.168.1.100:8888",
    "file": "~/.jdtbridge/remote-instances/d4e5f6.json",
    "token": "******54d2f",
    "mount-points": ["/home/user/projects"],
    "projects": [
      { "project": "webapp", "localPath": "/home/user/projects/webapp", "mountPoint": "/home/user/projects" }
    ]
  }
]
```

`jdt setup remote --check --json` — with connection checks and
Eclipse comparison:
```json
[
  {
    "bridge-socket": "host.docker.internal:7777",
    "file": "~/.jdtbridge/remote-instances/a1b2c3.json",
    "check": {
      "tcp": true,
      "token": true,
      "plugin": "2.5.0"
    },
    "token": "******c392d",
    "mount-points": ["/mnt/workspace", "/mnt/m8"],
    "mapped": [
      { "project": "inside", "localPath": "/mnt/workspace/inside", "mountPoint": "/mnt/workspace" },
      { "project": "myapp-core", "localPath": "/mnt/m8/myapp-core", "mountPoint": "/mnt/m8" },
      { "project": "myapp-server", "localPath": "/mnt/m8/myapp-server", "mountPoint": "/mnt/m8" },
      { "project": "myapp-shared", "localPath": "/mnt/m8/myapp-shared", "mountPoint": "/mnt/m8" }
    ],
    "unmapped": [
      { "project": "deploy-tools", "eclipsePath": "D:\\git\\deploy-tools" },
      { "project": "infra", "eclipsePath": "D:\\git\\infra" }
    ],
    "stale": [
      { "project": "old-project", "localPath": "/mnt/workspace/old-project" }
    ]
  },
  {
    "bridge-socket": "192.168.1.100:8888",
    "file": "~/.jdtbridge/remote-instances/d4e5f6.json",
    "check": {
      "tcp": false,
      "tcpError": "connection refused"
    },
    "token": "******54d2f",
    "mount-points": ["/home/user/projects"],
    "cached": [
      { "project": "webapp", "localPath": "/home/user/projects/webapp", "mountPoint": "/home/user/projects" }
    ]
  }
]
```

`jdt setup remote --bridge-socket host:port --json` — single remote,
same structure but single object (not array).

JSON project list key depends on context:
- `--json` (no check): `"projects"` — from cache, no verification
- `--check --json` online: `"mapped"` — verified against Eclipse
- `--check --json` offline: `"cached"` — from cache, not verified

## Instance file format

File `~/.jdtbridge/remote-instances/<hash>.json`:

```json
{
  "bridge-socket": "host.docker.internal:7777",
  "token": "abc123",
  "mount-points": ["/mnt/workspace", "/mnt/m8", "/mnt/automation"]
}
```

Keys match CLI flags. Hash = SHA-256 of bridge-socket string,
first 6 bytes as hex (12 chars). Same algorithm as plugin's
workspace hash in `Activator.workspaceHash()`.

## Project path cache

Eclipse returns workspace-relative paths (`/myapp-core/src/Foo.java`),
but the jdt host sees projects at mount-point paths
(`/mnt/m8/myapp-core/src/Foo.java`). The project path cache maps
Eclipse project names to their jdt-host absolute paths so the CLI
can translate between the two.

Built by scanning `mount-points` directories for `.project` files
and reading `<name>` from each. Stored in a dedicated subdirectory,
one file per remote instance (same hash as the instance file):

`~/.jdtbridge/remote-instances/project-paths/<hash>.json`

```json
{
  "bridge-socket": "host.docker.internal:7777",
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

### Cache lifecycle

**Populate:** on `--add-mount-point` — scan the added directory
recursively (limited depth), find `.project` files, parse `<name>`,
record directory path. Atomic write (temp file + rename).

**Invalidate and rescan on:**
- `--add-mount-point` or `--remove-mount-point`
- `--check` requested
- Cache miss (path doesn't match any cached project)
- Cache file doesn't exist (first run)

**No TTL.** Invalidation is event-driven only.

### Path resolution

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

## Multiple remote instances

Each bridge-socket creates a separate instance file.
`jdt use` lists them all.

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token aaa
$ jdt setup remote --bridge-socket 192.168.1.100:8888 --token bbb

$ jdt use
#  ALIAS  WORKSPACE   STATUS   PINNED  HOST                    PORT   PLUGIN
1         /mnt/dev    remote   pinned  host.docker.internal    7777   2.5.0
2         /mnt/stage  remote           192.168.1.100           8888   2.4.0

WORKSPACE for remote instances = mount-points from config,
one per line (multiline cell). If no mount-points, shows
bridge-socket.
```

## Token sources

1. **From Eclipse preferences.** Remote token generated or set
   in Window > Preferences > JDT Bridge. Persistent, survives
   Eclipse restarts.

2. **Auto-generated by CLI.** When no `--token` and no existing
   instance file. Must be registered in Eclipse preferences
   to be accepted by the server.

3. **Reused from existing instance file.** When updating an
   already-configured remote without `--token`.

## Relationship to other specs

- **[jdt-setup-spec](jdt-setup-spec.md)** — parent spec.
- **[jdt-use-spec](jdt-use-spec.md)** — lists and switches
  instances including remote.
- **[bridge-ui-preferences-spec](bridge-ui-preferences-spec.md)** —
  Eclipse preferences for local/remote sockets and tokens.

## Files

CLI:
  commands/setup.mjs         — `remote` subcommand
  discovery.mjs              — reads instance files (local + remote-instances)
  paths.mjs                  — path translation using project path cache

Plugin:
  HttpServer.java            — dual socket, auth
  ServerPreferences.java     — preference keys
  Activator.java             — dual socket lifecycle

UI:
  preferences/               — local/remote socket management
