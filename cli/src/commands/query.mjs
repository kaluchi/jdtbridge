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
import { BridgeNotRunningError, isConnectionError } from '../client.mjs';

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

/**
 * Wrap a runtime transport failure into a qlang error value. The
 * always-exit-0 contract of `jdt q` requires every terminal
 * outcome — including a dead Eclipse or a dropped socket mid-RPC —
 * to travel on stdout as an `!{}` value, so sibling parallel tool
 * calls in an agent harness are never cancelled by a non-zero exit.
 */
function runtimeErrorToValue(err) {
  const kind = err instanceof BridgeNotRunningError
    ? 'bridge-not-running'
    : isConnectionError(err)
      ? 'bridge-not-responding'
      : 'jdt-cli-error';
  const thrown = err.name || 'Error';
  const descriptor = new Map([
    [keyword('kind'),    keyword(kind)],
    [keyword('origin'),  keyword('jdt/cli')],
    [keyword('thrown'),  keyword(thrown)],
    [keyword('message'), err.message || String(err)],
  ]);
  if (err.code) descriptor.set(keyword('code'), err.code);
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
        + 'single positional argument. For descriptor lookup use '
        + "`jdt q 'reify(:<name>)'`.\n");
  }
  const positional = args.filter(a => !a.startsWith('--'));
  const querySource = positional[0];
  if (!querySource) {
    printQueryResult(usageErrorValue(
      'jdt q requires a qlang pipeline as its first positional argument.',
      'jdt q <qlang-query>'
    ));
    return;
  }
  // Everything from session bootstrap onward runs inside the
  // always-exit-0 envelope: any failure — bridge not running,
  // socket dropped mid-RPC, locator throw, operand binding
  // mishap — surfaces as a qlang `!{}` value on stdout so sibling
  // parallel tool calls in an agent harness never see a non-zero
  // exit.
  let didExplicitStdoutEffect = false;
  try {
    const session = await createSession({ locator: createLocator() });
    // Bind qlang-cli's standard host operands so jdt q has the same
    // composable I/O + format toolkit as plain `qlang`:
    //   @in / @out / @err / @tap     stdio
    //   pretty / tjson / template    value → string formatters
    //   parseJson / parseTjson       string → value parsers
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
  } catch (err) {
    const errorValue = runtimeErrorToValue(err);
    if (didExplicitStdoutEffect) {
      process.stderr.write(printValue(errorValue) + '\n');
    } else {
      printQueryResult(errorValue);
    }
  }
}

export const help = `jdt q — pipeline query over the Eclipse JDT semantic graph.

Usage:  jdt q <qlang-pipeline>

Pipeline = SEED | step | step … . Seed is a String (fqn, or
wildcard like "*Service") or a nullary axis (@projects, @problems).
Exit is always 0; errors land on stdout as \`!{:kind … :message …}\`
values — route with \`!|\`.

───── Cookbook — copy, swap the FQN, run ─────

  # 1. Find types by wildcard
  jdt q '"*Service" | @types * /fqn'

  # 2. Full card for a method — source + refs + hierarchy, markdown
  jdt q '"pkg.Foo#bar()" | @sourceCard' > bar.md

  # 3. Who calls a method (distinct FQNs)
  jdt q '"pkg.Foo#bar" | @callers * /fqn | distinct'

  # 4. Full supertype chain of a type
  jdt q '"pkg.Foo" | @ancestors * /fqn'

  # 5. Compilation state, flat table
  jdt q '@problems * {:sev /severity :file /location/file :line /location/startLine :msg /message} | table'

  # 6. Deletion candidates — public methods with zero callers
  jdt q '"pkg.Foo" | @methods | filter(/modifiers | any(eq("public"))) | filter(@callers | empty) * /fqn'

  # 7. Hotspots — biggest methods in a type, ranked
  jdt q '"pkg.Foo" | @methods | sortWith(desc(/location/lineCount)) | take(5) * {:fqn /fqn :lines /location/lineCount} | table'

  # 8. Project-wide dead code — every type's public orphans
  jdt q '"my.project" | @typesInProject * @publicOrphans | flat * /fqn'
  -- pattern: Vec-of-nodes | * conduit | flat * /fqn  — distribute,
  -- flatten the per-type Vecs, project fqn. Reuse for @tests,
  -- @untested, @typeUses, etc. across a project.

───── Volume estimate — size a result before reading it ─────

  Vec | count                              just the number
  Vec | @overview                          {:count N :head[5] :tail[5]}
  Vec | as(:v) | {:n v | count :fqns v * /fqn}
                                           count + every fqn, inline
  String | split("\\n") | count             line count of @source output

Each step returns a qlang value; \`| count\` on a 10k-node Vec is
one HTTP round-trip. No wc / head / tail.

───── qlang grammar ─────

  a | b        apply b to a
  a * b        distribute: b per element of Vec a
  a >> b       flatten one level, then apply b
  a !| b       fail-track: fire b only on error value
  /key         Map projection; /a/b = /a | /b
  [a b]        Vec literal         {:k v}    Map literal
  #{a b}       Set literal         !{:k v}   Error literal
  "text"       String              :keyword  Keyword
  as(:name)    snapshot pipeValue under :name
  let(:name, body) | let(:name, [:p], body)   conduit

───── Axes (seeds + navigation) ─────

Seeds (string → node, or nullary):
  "fqn"                | @type  @method  @field  @package  @project  @file
  "*pattern*"          | @types
  @projects            @problems            @problems(:project|:file)

Containment:
  type    | @members  @methods  @fields  @innerTypes
  package | @typesInPackage
  file    | @typesInFile
  project | @packagesInProject  @typesInProject
  node    | @classpath  @containingType  @containingPackage
            @containingFile  @containingProject

Hierarchy:
  type   | @supers  @subtypes  @ancestors  @descendants  @implementors
  method | @overrides  @overloads

References (nullary, subject from pipeValue):
  node   | @incomingRefs          default refKind by subject kind
  node   | @incomingRefs(:all)    modifier WIDENS — every refKind
  field  | @incomingRefs(:read|:write)
  member | @outgoingRefs          what the body touches

Sugar conduits:
  method  | @callers  @testCallers  @productionCallers
  field   | @readers  @writers
  member  | @calls  @typeUses  @dependsOn  @usedBy
  type    | @tests                  test-scope callers across type + members
  node    | @detail                 lift skeleton → detail
  element | @annotated(fqn)  @deprecated  @testMethods
            @untested  @publicOrphans  @deadCode
  Vec     | @sourceOnly  @overview  @inProject(projName)

Markdown cards (return a String — pipe to a file):
  "pkg.Foo#bar()" | @sourceCard      header + code + refs + hierarchy
  "pkg.Foo"       | @hierarchyCard   ↑ supers / ↓ subtypes
  "pkg.Foo"       | @outlineCard     fields / methods / inner types
  Vec<:reference> | mdRefs           grouped by refKind

Host-bound I/O + format (from qlang-cli):
  @in / @out / @err / @tap                  stdio passthrough
  parseJson / parseTjson                    String → qlang value
  pretty / tjson / template(t)              value → String

───── Discovery — the catalog is itself qlang data ─────

  manifest | count                              all ops
  manifest | filter(/effectful) * /name         every axis name
  reify(:@members)                              descriptor
  reify(:@members) | runExamples | table        verify docs
  reify(:@callers) | /source                    read a conduit's body

───── Debug — errors are data ─────

  expr !| /kind                   :type-error, :unresolved-identifier …
  expr !| /thrown                 per-site class (:TypeNotFound …)
  expr !| /message                human-readable
  expr !| /context/candidates     AmbiguousMatch — pick and retry
  expr !| /trail * /text          which steps deflected

───── Paths ─────

Paths in response Maps (:path, :file, :rootPath, :outputLocation)
are absolute on the Eclipse host; \`jdt setup remote\` rewrites
them to local mount-point paths for sandboxed agents. :fqn is an
identifier — round-trip it to the API verbatim.

Full qlang reference:
  https://github.com/kaluchi/qlang/blob/master/docs/qlang-spec.md`;
