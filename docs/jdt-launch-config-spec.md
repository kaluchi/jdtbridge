# jdt launch config operations — Design Spec

## Overview

Operations on Eclipse launch configurations beyond listing and
inspection: import from file, delete, and future operations
(duplicate, edit).

See [jdt-launch-spec.md](jdt-launch-spec.md) for the core launch
commands (`configs`, `config`, `run`, `debug`, `logs`, `stop`, `clear`).

## Import

### Problem

Eclipse stores launch configurations in workspace metadata
(`<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/`),
outside the project directory. A fresh workspace has no launch
configs — the developer recreates them manually or copies `.launch`
files from another workspace.

### What it does

Reads a `.launch` file from disk (source) and imports it into the
current Eclipse workspace (destination) via the plugin's HTTP API.

**Source**: any `.launch` file on disk — from the project directory,
from another workspace's `.metadata/`, from anywhere.
The CLI reads the file and sends its XML content to the plugin.

**Destination**: the currently connected Eclipse workspace. The plugin
uses `LaunchManager.importConfigurations()` to copy the file into
`<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/`
and register it with the launch infrastructure.

### Command

```bash
jdt launch config --import <path>
jdt launch config --import <path> --configid <name>
```

- `<path>` — path to a `.launch` file on disk
- `--configid <name>` — override the configuration name.
  Default: derived from filename (without `.launch` extension).

If a configuration with the same configId already exists, the
command fails with an error. No silent overwrite.

### Examples

```bash
jdt launch config --import /path/to/my-build.launch
jdt launch config --import /path/to/my-build.launch --configid custom-name

# Fails — already exists
$ jdt launch config --import my-build.launch
Error: Launch configuration "my-build" already exists.
Use --configid to import with a different name.
```

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

1. Validate configId — reject path separators and `..`.
2. Check if configId already exists (LaunchManager cache + file on disk).
3. Write XML to temp file named `<configId>.launch`.
4. Call `LaunchManager.importConfigurations(File[])` — Eclipse's
   built-in import API. Copies file to
   `<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/`,
   registers with LaunchManager, fires change notifications.
5. Clean up temp file in `finally` block.
6. Return success with configId.

## Delete

```bash
jdt launch config --delete <configId>
```

Deletes a launch configuration from
`<workspace>/.metadata/.plugins/org.eclipse.debug.core/.launches/`.

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
  Import and delete are flags on `jdt launch config` (alongside
  `--xml` and `--json`).
- **[bridge-ui-spec](bridge-ui-spec.md)** — Eclipse UI
  creates launch configs via Run Configurations dialog. Import
  is the CLI equivalent for headless workflows.

## Files

CLI:
  commands/launch.mjs        — launchImport() added to existing dispatch
  client.mjs                 — post() helper (new, for XML POST with reconnect)

Plugin:
  LaunchHandler.java         — handleImport() endpoint

