# jdt setup — Design Spec

## Overview

`jdt setup` manages the connection between the CLI and Eclipse.
Local installation (plugin, hooks) and remote connection.

## Local setup

```bash
jdt setup                          install plugin + restart Eclipse
jdt setup --check                  verify prerequisites and connection
jdt setup --skip-build             install from existing p2 site
jdt setup --eclipse <path>         specify Eclipse installation
jdt setup --remove                 uninstall plugin
jdt setup --claude                 install Claude Code hooks + agents
```

CLI and Eclipse on the same machine. Plugin generates a token on
every startup, writes instance file to
`~/.jdtbridge/instances/<hash>.json`, CLI discovers automatically.

## Remote setup

`jdt setup remote` — configures CLI to connect to a remote Eclipse
instance. Separate subcommand with its own spec.

See [jdt-setup-remote-spec.md](jdt-setup-remote-spec.md).
