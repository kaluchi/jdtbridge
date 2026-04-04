# jdt launch config operations — Design Spec

## Overview

Operations on Eclipse launch configurations beyond listing and
inspection. Covers importing configurations from files (for sharing
via VCS), and future operations like delete, duplicate, and edit.

See [jdt-launch-spec.md](jdt-launch-spec.md) for the core launch
commands (`configs`, `config`, `run`, `debug`, `logs`, `stop`, `clear`).

## Import

### Problem

Launch configurations live in workspace metadata
(`<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/`).
They are not visible in VCS. When a developer clones a repo and opens
it in a fresh workspace, no launch configs exist — they must recreate
them manually or copy files between workspaces.

### Command

```bash
jdt launch config --import <path>
jdt launch config --import <path> --configid <name>
```

Imports a `.launch` file into the current workspace. The CLI reads
the file from disk, sends its **content** (not the path) to the
plugin. This works regardless of where the file lives — repo,
temp directory, another workspace, network mount.

- `<path>` — path to a `.launch` file on disk
- `--configid <name>` — override the configuration name.
  Default: derived from filename (without `.launch` extension).

If a configuration with the same configId already exists, the
command fails with an error. No silent overwrite.

### Examples

```bash
# Import from repo
jdt launch config --import launches/jdtbridge-verify.launch

# Import with custom name
jdt launch config --import launches/jdtbridge-verify.launch --configid my-verify

# Fails — already exists
$ jdt launch config --import launches/jdtbridge-verify.launch
Error: Launch configuration "jdtbridge-verify" already exists.
Use --configid to import with a different name.
```

### Storage in VCS

Recommended directory: `launches/` in project root, tracked in git.

```
eclipse-jdt-search/
  launches/
    jdtbridge-verify.launch
    jdtbridge-package.launch
```

These are standard Eclipse `.launch` XML files. They reference
launch type IDs (requires matching plugins installed), Maven goals,
and working directories via `${workspace_loc}` variables.
Portable across machines if Eclipse has the same plugins and the
workspace contains the referenced projects.

### Protocol

```
POST /launch/import?configId=jdtbridge-verify
Content-Type: application/xml

<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<launchConfiguration type="org.eclipse.m2e.Maven2LaunchConfigurationType">
    ...
</launchConfiguration>
```

- **configId**: query parameter, required.
- **Body**: raw `.launch` file XML content.
- **Response 200**: `{ "configId": "jdtbridge-verify", "imported": true }`
- **Response 200 error**: `{ "error": "Launch configuration \"...\" already exists." }`

ConfigId is validated: no path separators (`/`, `\`) or `..` allowed.

### Plugin implementation

1. Read XML content from request body.
2. Check if configId already exists in `LaunchManager`.
3. Write to `<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/<configId>.launch`.
4. Call `LaunchManager.getLaunchConfigurations()` to refresh
   (or use `ILaunchManager` API to create from XML).
5. Return success with configId.

### CLI implementation

```javascript
// commands/launch.mjs — launchImport()
const launchFileContent = readFileSync(filePath, "utf8");
const configId = flags.configid || basename(filePath, ".launch");
const importResult = await post(
    `/launch/import?configId=${encodeURIComponent(configId)}`,
    launchFileContent,
);
```

The CLI has no knowledge of launch configuration internals.
It reads the file as opaque XML and sends it to the server.
The server validates and installs.

## Delete

```bash
jdt launch config --delete <configId>
```

Deletes a saved launch configuration from the workspace.
Already implemented in `LaunchHandler.handleConfigDelete()`.

## Duplicate (future)

```bash
jdt launch config --duplicate <configId> --configid <newName>
```

Creates a copy of an existing configuration with a new name.
Useful for creating variants (e.g., different Maven goals,
different test classes). Requires `--configid` for the new name.
Not implemented yet.

## Edit (future)

Key-value editing of launch configuration attributes:

```bash
jdt launch config <configId> --set <key>=<value>
```

Would require knowledge of attribute types (string, boolean, int,
list). Complex — deferred until concrete use cases emerge.

## Relationship to other specs

- **[jdt-launch-spec](jdt-launch-spec.md)** — core launch commands.
  Import adds a new subcommand to the `jdt launch` namespace.
  `launch configs` and `launch config` continue to list and inspect.
- **[ui-integration-spec](ui-integration-spec.md)** — Eclipse UI
  creates launch configs via Run Configurations dialog. Import
  is the CLI equivalent for headless/VCS workflows.

## Files

CLI:
  commands/launch.mjs        — launchImport() added to existing dispatch
  client.mjs                 — post() helper (new, for XML POST with reconnect)

Plugin:
  LaunchHandler.java         — handleImport() endpoint

Data:
  launches/                  — shared .launch files in VCS (project root)
