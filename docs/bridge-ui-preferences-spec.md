# Bridge UI Preferences — Design Spec

Two sockets: local (loopback, always on) and remote (all interfaces,
optional). Each with its own port and token.

See [kaluchi/jdtbridge#93](https://github.com/kaluchi/jdtbridge/issues/93).

## Preferences

Stored per-workspace via Eclipse `InstanceScope` preferences.
Plugin reads via `Activator.getInstance()` for live values,
`ServerPreferences` for preference keys.

| Preference | Key | Default | Description |
|---|---|---|---|
| `localPort` | `localPort` | `0` | Local socket port (0 = auto) |
| `localRegenerateToken` | `localRegenerateToken` | `true` | Regenerate local token on restart |
| `localToken` | `localToken` | `""` | Persisted local token (when regenerate off) |
| `remoteEnabled` | `remoteEnabled` | `false` | Enable remote socket |
| `remotePort` | `remotePort` | `0` | Remote socket port |
| `remoteRegenerateToken` | `remoteRegenerateToken` | `false` | Regenerate remote token on restart |
| `remoteToken` | `remoteToken` | `""` | Persisted remote token |

## Preference page UI

Window > Preferences > JDT Bridge:

```
JDT Bridge settings for AI agent integration.

Terminal command:  [wt.exe]

┌ Local 127.0.0.1 [x] ──────────────────────────────────┐
│ Port:   [auto → :52311 listening ]  Copy Replace Check │
│ Token:  [******b7173             ]  Copy Replace...    │
│ [x] Regenerate token on Eclipse restart                │
└────────────────────────────────────────────────────────┘

┌ Remote 0.0.0.0 [ ] ───────────────────────────────────┐
│ Port:   [8888 listening          ]  Copy Replace Check │
│ Token:  [******c123              ]  Copy Replace...    │
│ [ ] Regenerate token on Eclipse restart                │
│                                                        │
│ ⚠ Binds to all interfaces. Traffic is not encrypted.  │
│   Safe: Docker containers on this machine.             │
│   Unsafe: connections over network.                    │
│   For network access, keep this disabled and use SSH   │
│   port forwarding from the remote machine to this      │
│   Eclipse's local port.                                │
└────────────────────────────────────────────────────────┘
```

### Port field

Read-only text field doubles as status display. Shows configured
port, live port (when different), and server state in one line.

Format by state:

| Configured | Live state | Field text |
|------------|------------|------------|
| `0` (auto) | running on 52311 | `auto → :52311 listening` |
| `0` (auto) | not started | `auto` |
| `8888` | running on 8888 | `8888 pinned, listening` |
| `8888` | not started | `8888 pinned` |
| `8888` | check failed | `8888 pinned, connection refused` |
| any | remote disabled | `8888 disabled` |

Populated on page open from `Activator.getInstance()`.
Updated after Check button probe.

- **Copy** — copies the actual running port (from Activator),
  not the configured value. Useful when port is 0 (auto).
- **Replace...** — opens InputDialog with validation:
  - Digits only
  - Local: 0 (auto) or 1024–65535
  - Remote: 1024–65535 only (0 not allowed)
  - Must differ from the other socket's port
  After OK, field updates with new configured value + refreshed status.
- **Check** — HTTP `GET /status` to the server's actual port on the
  socket's bind address. Verifies the server is reachable through the
  OS network stack. Updates the port field text with result.

### Token field

Read-only. Masked display (******xxxxx, last 5 chars).

- **Copy** — copies full unmasked token to clipboard
- **Replace...** — opens InputDialog. Empty input = auto-generate.

### Group headers

No status label in headers — all status is in the port field.

- **Local** — checkbox always checked, disabled (can't turn off)
- **Remote** — checkbox toggles section. When unchecked, all
  controls grayed out, port field shows `<port> disabled`

### Per-section behavior

**Local section:**
- Always on (checkbox disabled, checked)
- Regenerate checkbox on by default

**Remote section:**
- Checkbox enables/disables entire section
- When disabled: all controls grayed out, port field shows "disabled"
- Regenerate checkbox off by default (fixed token for containers)
- Warning text about security

## Dual socket architecture

Plugin `Activator` starts two `HttpServer` instances:

```
Activator.start()
  │
  ├─ localServer = new HttpServer()
  │  token = resolveLocalToken()
  │  start(127.0.0.1, localPort)
  │
  ├─ if remoteEnabled:
  │  remoteServer = new HttpServer()
  │  token = resolveRemoteToken()
  │  start(0.0.0.0, remotePort)
  │
  └─ writeBridgeFile() → <hash>.json (both sockets)
```

Single instance file `~/.jdtbridge/instances/<hash>.json` with both sockets:
`port`, `token`, `host` (local) + `remotePort`, `remoteToken` (when enabled).

Token resolution per socket:
1. Regenerate on restart = true → generate new
2. Persisted token in preferences → reuse
3. Nothing → generate and persist

`Activator.getInstance()` exposes live state:
- `getLocalPort()`, `getLocalToken()`
- `getRemotePort()`, `getRemoteToken()`
- `isRemoteRunning()`

## Hot rebind

`PreferenceChangeListener` coalesces rapid changes (100ms debounce),
then on background thread:

- Local: rebind to new port
- Remote enable: start new HttpServer on 0.0.0.0
- Remote disable: stop remote HttpServer, delete remote instance file
- Remote rebind: rebind to new port

Instance files rewritten after each rebind.

## Running agents warning

Before applying, preference page checks for running JDT Bridge Agent
launches. If any non-terminated: warning dialog. Agents have
`JDT_BRIDGE_PORT/TOKEN` baked into env at launch time — need restart.

## Validation

- Remote port = 0 → error (auto-assign not supported for remote)
- Remote port = local port → error (must differ)
- Port conflict at startup → fallback to auto-assigned with log warning

## Files

Plugin:
  Activator.java               — dual socket lifecycle, getInstance()
  HttpServer.java              — start(), rebind(), getPort(), getBindAddress()
  ServerPreferences.java       — preference keys and resolution

UI:
  preferences/PreferenceConstants.java   — delegates to ServerPreferences
  preferences/PreferenceInitializer.java — defaults
  preferences/BridgePreferencePage.java  — dual socket preference page
