# JDT Bridge

Grep finds strings. JDT Bridge understands Java.

Eclipse's JDT compiler builds a deep semantic index of your code — type hierarchies, cross-project references, method overloads, transitive dependencies. JDT Bridge exposes all of that via the `jdt` CLI, giving AI coding agents (Claude Code, OpenCode, Kilo Code, aider) and shell workflows the same understanding of your codebase that a human gets in the IDE.

## Why it matters

```bash
# "Who calls this method?" — grep returns 200 hits including comments,
# identically-named methods in other classes, and string literals.
# JDT returns only the 8 actual call sites, with file and line numbers.
jdt refs "com.example.dao.OrderRepository#save(Order)"

# Read Spring/Hibernate/JDK source code by name.
# Without JDT this is impossible — library sources live inside JARs.
# The agent gets the same "go to definition" power a developer has in the IDE.
jdt src org.springframework.transaction.support.TransactionTemplate#execute

# "Did my edit compile?" — Maven takes 30-90 seconds for a module build.
# Eclipse's incremental compiler already knows the answer. Sub-second response.
jdt err --project my-app-server

# "What classes implement this interface method?" — grep for a common name
# like "save" or "onInit" returns every class that has that method name.
# JDT resolves the type hierarchy and returns only actual implementations.
jdt impl "com.example.core.Repository#save(Order)"

# Understand a 600-line class without reading it. Fields, method signatures,
# supertypes, line numbers — structured overview, not raw source.
jdt ti com.example.web.OrderController

# Run a single test method in seconds via Eclipse's built-in runner.
# Maven Surefire takes 30+ seconds of lifecycle overhead for the same thing.
jdt test com.example.service.OrderServiceTest#testCalculateTotal
```

## Getting started

Prerequisites: Node.js >= 20, Eclipse IDE.

### CLI

```bash
npm install -g @kaluchi/jdtbridge
```

### Plugin

Install from [Eclipse Marketplace](https://marketplace.eclipse.org/content/jdt-cli-bridge-ai-coding-assistants), or drag the button into a running Eclipse:

<a href="https://marketplace.eclipse.org/marketplace-client-intro?mpc_install=7423866" title="Drag to your running Eclipse workspace — requires Eclipse Marketplace Client"><img src="https://marketplace.eclipse.org/modules/custom/eclipsefdn/eclipsefdn_marketplace/images/btn-install.svg" width="80" alt="Install" /></a>

Or add the update site manually — `Help` → `Install New Software…` → `Add…`:

```
https://kaluchi.github.io/jdtbridge/
```

### Test drive

```bash
jdt projects        # list workspace projects
jdt help            # explore available commands
```

Now introduce it to your AI agent:

> We're working together in Eclipse via the `jdt` CLI — you can see the same projects, source code, compilation errors, and test results that I see in the IDE. We share the same workspace. Run `jdt help` to learn what's available and remember it for future use.

## Why CLI, not MCP?

MCP is the natural first thought for connecting an IDE to an AI agent. But JDT Bridge is a CLI — and that's a deliberate choice.

**Pipe composability is the killer feature.** MCP tool results go straight into the agent's context window, unfiltered. CLI output flows through the shell first:

```bash
# 26-method class — only the handler entry points.
# MCP: all 26 methods enter context. CLI: just the 8 that matter.
jdt ti io.github.kaluchi.jdtbridge.SearchHandler | grep handle

# "How many callers?" — a number, not 51 lines of references.
jdt refs io.github.kaluchi.jdtbridge.JdtUtils#findMethod | wc -l

# First 5 compilation errors — fix one at a time.
jdt err --project my-server | head -5

# Where does this Spring method throw or catch?
jdt src org.springframework.jdbc.core.JdbcTemplate#query | grep -n 'throw\|catch'

# All subtypes of a Spring base class — including library internals.
jdt subtypes org.springframework.jdbc.object.SqlOperation
```

An agent's context window is finite. Every irrelevant token displaces useful reasoning. MCP's [own community recognizes this](https://github.com/modelcontextprotocol/modelcontextprotocol/issues/1576) — token bloat from tool schemas and unfiltered results is a known problem, with projects like [model-context-shell](https://github.com/StacklokLabs/model-context-shell) trying to retrofit Unix-style pipes onto MCP.

**Works for everyone.** The same `jdt` command works in your terminal, in shell scripts, in CI, and for any AI agent. MCP requires per-IDE configuration and isn't directly usable by humans. When something goes wrong, you debug by running the same command the agent ran — no log spelunking.

**No lock-in.** Anything that can run a shell command can use `jdt` — Claude Code, OpenCode, Kilo Code, aider, or a plain bash script. No per-IDE configuration, no protocol lock-in.

MCP is the right choice for things like database connections, OAuth flows, or cloud APIs with no CLI equivalent. But for developer tools where output filtering matters and humans benefit too, a CLI wins.

## Commands

Most commands have short aliases for quick typing.

| Command | Alias | What it does |
|---------|-------|-------------|
| `projects` | | List workspace projects |
| `project-info <name>` | `pi` | Project overview (packages, types, methods) |
| `find <Name\|package>` | | Find types by name, wildcard, or package |
| `references <FQMN>` | `refs` | All references to a type, method, or field |
| `subtypes <FQN>` | `subt` | All subtypes and implementors |
| `hierarchy <FQN>` | `hier` | Full type hierarchy (supers + subs) |
| `implementors <FQMN>` | `impl` | Implementations of an interface method |
| `type-info <FQN>` | `ti` | Class overview (fields, methods, signatures) |
| `source <FQMN>` | `src` | Source code — project and library classes |
| `build [--project <name>]` | `b` | Build project (incremental or clean) |
| `test <FQMN>` | | Run JUnit tests |
| `errors [--project <name>]` | `err` | Compilation errors and diagnostics |
| `organize-imports <file>` | `oi` | Organize imports (Eclipse settings) |
| `format <file>` | `fmt` | Format code (Eclipse settings) |
| `rename <FQMN> <new>` | | Rename type, method, or field across workspace |
| `move <FQN> <package>` | | Move type to another package |
| `open <FQMN>` | | Open in Eclipse editor |
| `editors` | `ed` | List all open editors (absolute paths) |
| `launch list` | | List launches (running + terminated) |
| `launch configs` | | List saved launch configurations (MRU order) |
| `launch run <config> [-f]` | | Launch a configuration (`-f` to stream) |
| `launch debug <config> [-f]` | | Launch in debug mode |
| `launch logs <name> [-f]` | | Show console output (`-f` to stream) |
| `launch stop <name>` | | Stop a running launch |
| `launch clear [name]` | | Remove terminated launches |
| `setup [--check\|--remove]` | | Install, check, or remove Eclipse plugin |

Run `jdt help <command>` for detailed flags and options.

## How it works

```
  Terminal / AI agent              Eclipse IDE
+--------------------+         +--------------------+
|                    |  HTTP   |                    |
|   jdt CLI          | ------> |   JDT Bridge       |
|   (Node.js)        | JSON    |   plugin           |
|                    | <------ |   (JDT SearchEngine)|
+--------------------+         +--------------------+
        |                               |
   auto-discovers              writes instance file
        |                               |
        +--- ~/.jdtbridge/instances/ ---+
```

The Eclipse plugin starts a loopback HTTP server on a random port at startup. The CLI auto-discovers running instances via `~/.jdtbridge/instances/` files and routes commands to the right Eclipse.

Multiple Eclipse instances are supported — each writes its own instance file, keyed by workspace path.

**Security**: loopback-only binding (`127.0.0.1`), per-session random token, POSIX `rwx------` permissions on instance files.

## Documentation

- **[CLI reference](cli/README.md)** — all commands, flags, options, instance discovery
- **[HTTP API](plugin/README.md)** — raw endpoint reference for building custom clients

## Building from source

For contributors — clone the repo, link the CLI, build and install the plugin from source:

```bash
git clone https://github.com/kaluchi/jdtbridge.git
cd jdtbridge/cli
npm install && npm link
jdt setup             # builds plugin with Maven, installs into Eclipse
jdt setup --check     # verify everything works
```

After pulling updates, run `jdt setup` again to rebuild and reinstall.

## Known limitations

- **Workspace must be fully built.** JDT search relies on the Eclipse index. If the workspace hasn't been fully indexed, results may be incomplete. Use `jdt build` or `jdt build --clean` to trigger builds.

## License

Apache License 2.0. See [LICENSE](LICENSE).
