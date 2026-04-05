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

Wrote ~/.jdtbridge/instances/remote-a1b2c3.json:
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

Wrote ~/.jdtbridge/instances/remote-a1b2c3.json:
  bridge-socket: host.docker.internal:7777
  token:         e240be6743978f011bfd326c9d3c392d (no --token, auto-generated, shown once)
```

## Output: add mount point to existing

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 \
    --add-mount-point /mnt/automation

Updated ~/.jdtbridge/instances/remote-a1b2c3.json:
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

Updated ~/.jdtbridge/instances/remote-a1b2c3.json:
  mount-points: removed /mnt/automation

Cache invalidated. Total: 4 projects.
```

## Output: update token

Only changed fields:

```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --token new-token

Updated ~/.jdtbridge/instances/remote-a1b2c3.json:
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

  2. Here:
     jdt setup remote --bridge-socket <host>:<port> --token <token>
     jdt setup remote --bridge-socket <host>:<port> --add-mount-point <path>
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

Connects to Eclipse, gets project list, compares with cached
projects. Shows two categories:
- In Eclipse but not cached (mount point missing or .project absent)
- Cached but not in Eclipse (project removed or closed)

Output per remote:

```
$ jdt setup remote --check

host.docker.internal:7777
  ✓ TCP
  ✓ Token ******a1b2c
  ✓ Plugin 2.5.0

  Mapped and verified:
  PROJECT                     LOCAL_PATH                   STATUS
  inside                      /mnt/workspace/inside        ✓
  myapp-core                  /mnt/m8/myapp-core           ✓
  myapp-server                /mnt/m8/myapp-server         ✓
  automation                  /mnt/automation              ✓

  In Eclipse but not mapped:
  PROJECT                     ECLIPSE_PATH
  deploy-tools                D:\git\deploy-tools
  infra                       D:\git\infra

  Cached but not in Eclipse:
  PROJECT                     LOCAL_PATH
  old-project                 /mnt/workspace/old-project
```

Check specific remote:
```
$ jdt setup remote --bridge-socket host.docker.internal:7777 --check

host.docker.internal:7777
  ✓ TCP
  ✓ Token ******a1b2c
  ✓ Plugin 2.5.0

  Mapped and verified:
  PROJECT          LOCAL_PATH                    STATUS
  myapp-core       /mnt/m8/myapp-core           ✓
  myapp-server     /mnt/m8/myapp-server         ✓

  In Eclipse but not mapped:
  PROJECT          ECLIPSE_PATH
  infra            D:\git\infra

  Add mount point:
    jdt setup remote --bridge-socket host.docker.internal:7777 \
      --add-mount-point <local-path-containing-infra>
```

Offline remote:
```
192.168.1.100:8888
  ✗ TCP — connection refused
  (cannot verify projects)
```

## Instance file format

File `~/.jdtbridge/instances/remote-<hash>.json`:

```json
{
  "bridge-socket": "host.docker.internal:7777",
  "token": "abc123",
  "mount-points": ["/mnt/workspace", "/mnt/m8", "/mnt/automation"]
}
```

Keys match CLI flags. Hash derived from bridge-socket value.

## Project path cache

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
1         /mnt/dev    online   pinned  host.docker.internal    7777   2.5.0
2         /mnt/stage  online           192.168.1.100           8888   2.4.0
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
  discovery.mjs              — reads instance files with host field
  paths.mjs                  — path translation using project path cache

Plugin:
  HttpServer.java            — dual socket, auth
  ServerPreferences.java     — preference keys
  Activator.java             — dual socket lifecycle

UI:
  preferences/               — local/remote socket management
