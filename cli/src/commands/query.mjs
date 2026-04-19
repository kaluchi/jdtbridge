// jdt q <qlang-query> — evaluate a qlang pipeline against the :jdt/graph module.
//
// Read-only command: every outcome (parse error, CLI-argument error,
// server fail-track, error-value result) exits 0 with the error
// descriptor printed on stdout as a qlang `!{}` value. Non-zero
// exit would cancel sibling parallel tool calls in agent harnesses;
// errors travel as data.
//
// Output is qlang-literal via `printValue` — round-trips through
// parse + evalQuery and composes as `jdt X | jdt q '...'`.

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createSession } from '@kaluchi/qlang-core/session';
import {
  printValue,
  keyword,
  isErrorValue,
  makeErrorValue,
} from '@kaluchi/qlang-core';
import { bindIoOperands } from '@kaluchi/qlang-cli/io-operands';
import { bindFormatOperands } from '@kaluchi/qlang-cli/format-operands';
import { bindParseOperands } from '@kaluchi/qlang-cli/parse-operands';
import { createImpls as createGraphImpls } from '../../lib/jdt/graph.impl.mjs';
import { bindJdtRenderOperands } from '../../lib/jdt/render.impl.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, '..', '..', 'lib');

function createLocator() {
  const implFactories = {
    'jdt/graph': createGraphImpls
  };

  return (namespaceName) => {
    const qlangPath = join(MODULE_LIB, ...namespaceName.split('/')) + '.qlang';
    let source;
    try {
      source = readFileSync(qlangPath, 'utf8');
    } catch {
      return null;
    }
    const factory = implFactories[namespaceName];
    const impls = factory ? factory() : undefined;
    return { source, impls };
  };
}

function positionMap(pos) {
  return new Map([
    [keyword('offset'), pos.offset],
    [keyword('line'),   pos.line],
    [keyword('column'), pos.column],
  ]);
}

function locationMap(loc) {
  return new Map([
    [keyword('start'), positionMap(loc.start)],
    [keyword('end'),   positionMap(loc.end)],
  ]);
}

function parseErrorToValue(err, uri) {
  const descriptor = new Map([
    [keyword('kind'),    keyword('parse-error')],
    [keyword('origin'),  keyword('qlang/parse')],
    [keyword('thrown'),  keyword(err.name || 'ParseError')],
    [keyword('message'), err.message || String(err)],
  ]);
  if (err.location) descriptor.set(keyword('location'), locationMap(err.location));
  if (err.uri || uri) descriptor.set(keyword('uri'), err.uri || uri);
  return makeErrorValue(descriptor);
}

/**
 * Drain stdin into a String. The interactive shell (TTY-attached
 * stdin) returns '' immediately so `jdt q '...'` at a prompt
 * doesn't hang; a piped invocation (`echo '{"x":1}' | jdt q
 * '@in | parseJson | /x'`) reads to EOF.
 */
async function readStdin() {
  if (process.stdin.isTTY) return '';
  let data = '';
  process.stdin.setEncoding('utf8');
  for await (const chunk of process.stdin) {
    data += chunk;
  }
  return data;
}

function usageErrorValue(message, usage) {
  const descriptor = new Map([
    [keyword('kind'),    keyword('usage-error')],
    [keyword('origin'),  keyword('jdt/cli')],
    [keyword('thrown'),  keyword('UsageError')],
    [keyword('message'), message],
    [keyword('usage'),   usage],
  ]);
  return makeErrorValue(descriptor);
}

function printQueryResult(value) {
  if (typeof value === 'string' && !isErrorValue(value)) {
    // Raw string results (e.g. `@source` returning a file's contents)
    // print without quotes/escapes so `jdt q '"X" | @source' > X.java`
    // produces a byte-faithful file.
    console.log(value);
  } else {
    console.log(printValue(value));
  }
}

export async function query(args) {
  const flags = args.filter(a => a.startsWith('--'));
  if (flags.length > 0) {
    process.stderr.write(
        `jdt q: ignoring unrecognised flag${
            flags.length > 1 ? 's' : ''} ${flags.join(' ')}\n`
        + 'jdt q takes no flags — pass the qlang pipeline as the '
        + 'single positional argument.\n');
  }
  const queryParts = args.filter(a => !a.startsWith('--'));
  const querySource = queryParts[0];
  if (!querySource) {
    printQueryResult(usageErrorValue(
      'jdt q requires a qlang pipeline as its first positional argument.',
      'jdt q <qlang-query>'
    ));
    return;
  }
  const session = await createSession({ locator: createLocator() });
  // Bind qlang-cli's standard host operands so jdt q has the same
  // composable I/O + format toolkit as plain `qlang`:
  //   @in / @out / @err / @tap     stdio
  //   pretty / tjson / template    value → string formatters
  //   parseJson / parseTjson       string → value parsers
  let didExplicitStdoutEffect = false;
  bindIoOperands(session, {
    stdinReader: readStdin,
    stdoutWrite: (text) => process.stdout.write(text),
    stderrWrite: (text) => process.stderr.write(text),
    recordStdoutEffect: () => { didExplicitStdoutEffect = true; },
  });
  bindFormatOperands(session);
  bindParseOperands(session);
  bindJdtRenderOperands(session);
  const cellEntry = await session.evalCell(`use(:jdt/graph) | ${querySource}`);

  if (cellEntry.error) {
    printQueryResult(parseErrorToValue(cellEntry.error, cellEntry.uri));
    return;
  }

  if (didExplicitStdoutEffect) {
    // Error values route to stderr so they survive alongside an
    // @out redirect without contaminating its stdout.
    if (isErrorValue(cellEntry.result)) {
      process.stderr.write(printValue(cellEntry.result) + '\n');
    }
    return;
  }
  printQueryResult(cellEntry.result);
}

export const help = `Evaluate a qlang pipeline against the Eclipse JDT graph.

Usage:  jdt q <qlang-query>

The :jdt/graph module is pre-loaded. Every @-operand is NULLARY —
it takes its subject from pipeValue, never from a captured arg.
Seed the pipeline with a literal String (FQN / FQMN / pattern) or
a nullary entry-point (@projects, @problems), then chain operands
that read pipeValue. Subject polymorphism: operands accept the
canonical node-Map produced by a prior step OR the raw fqn/fqmn
String that identifies the same node.

Output is qlang-literal (via printValue) — round-trips through
parse + evalQuery and composes as \`jdt q '…' | jdt q '…'\`. Raw
Strings print unquoted so \`jdt q '"Foo" | @source' > Foo.java\`
writes the file byte-faithful.

Pattern search:
  "*Service" | @types                type-name wildcard (workspace-wide)
  "*Handler" | @types | @sourceOnly  exclude binary dependencies

Point lookup (FQN / FQMN as pipeline seed):
  "pkg.Type"                | @type
  "pkg.Type#method(ArgFqn)" | @method
  "pkg.Type#fieldName"      | @field
  "project-name"            | @project
  "pkg.sub"                 | @package
  "/abs/path/to/File.java"  | @file
                              @projects      (nullary)
                              @problems      (nullary, or scoped)

Containment:
  node | @containingType      node | @containingProject
  node | @containingPackage   node | @containingFile
  type | @members             type | @methods
  type | @fields              type | @innerTypes
  package | @typesInPackage   file | @typesInFile
  project | @packagesInProject

Hierarchy:
  type | @supers       type | @subtypes      type | @ancestors
  type | @descendants  type | @implementors
  method | @overrides  method | @overloads

References:
  node   | @incomingRefs           default scope for subject kind
  node   | @incomingRefs(:all)     modifier widens refKind filter
  field  | @incomingRefs(:write)   one of :call :read :write :typeUse :all
  member | @outgoingRefs           what the subject body touches

Sugar conduits:
  method | @callers    field | @readers    field | @writers
  member | @calls      member | @typeUses
  skeleton | @detail   -> detail-node
  project | @classpath -> resolved classpath entries, absolute paths

Reify a descriptor (docs + examples for an operand or conduit):
  reify(:@incomingRefs)
  manifest | filter(/category | eq(:jdt/graph)) * /name
  -- enumerate the full graph axis + conduit catalog

Exit code is always 0 — errors (parse, usage, fail-track) travel
on stdout as qlang error values (\`!{:kind … :message …}\`). Use
\`!|\` inside the pipeline to route around errors.

Paths in responses (:path, :file, :rootPath, :outputLocation) are
absolute filesystem paths on the Eclipse host. The CLI remaps them
to the runtime's filesystem convention via toSandboxPath before
they reach the pipeline, so a Linux-sandboxed agent sees its own
mount paths, not host Windows paths. :fqn is an identifier, never
remapped — round-trip it back to the API verbatim.`;
