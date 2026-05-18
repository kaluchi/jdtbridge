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
  makeTagKeyword,
  isErrorValue,
  makeErrorValue,
} from '@kaluchi/qlang-core';
import { bindIoOperands } from '@kaluchi/qlang-cli/io-operands';
import { bindFormatOperands } from '@kaluchi/qlang-cli/format-operands';
import { bindParseOperands } from '@kaluchi/qlang-cli/parse-operands';
import { createImpls as createGraphImpls } from '../../lib/jdt/graph.impl.mjs';
import { createImpls as createCoverageImpls } from '../../lib/jdt/coverage.impl.mjs';
import { bindJdtRenderOperands } from '../../lib/jdt/render.impl.mjs';
import { BridgeNotRunningError, isConnectionError } from '../client.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, '..', '..', 'lib');

function createLocator() {
  const implFactories = {
    'jdt/graph':    createGraphImpls,
    'jdt/coverage': createCoverageImpls,
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
    ['offset', pos.offset],
    ['line',   pos.line],
    ['column', pos.column],
  ]);
}

function locationMap(loc) {
  return new Map([
    ['start', positionMap(loc.start)],
    ['end',   positionMap(loc.end)],
  ]);
}

function parseErrorToValue(err, uri) {
  const descriptor = new Map([
    ['kind',    makeTagKeyword(err.name || 'ParseError')],
    ['origin',  keyword('qlang/parse')],
    ['message', err.message || String(err)],
  ]);
  if (err.location) descriptor.set('location', locationMap(err.location));
  if (err.uri || uri) descriptor.set('uri', err.uri || uri);
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
    ['kind',    makeTagKeyword('UsageError')],
    ['origin',  keyword('jdt/cli')],
    ['message', message],
    ['usage',   usage],
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
  const tagName = err instanceof BridgeNotRunningError
    ? 'BridgeNotRunning'
    : isConnectionError(err)
      ? 'BridgeNotResponding'
      : (err.name || 'JdtCliError');
  const descriptor = new Map([
    ['kind',    makeTagKeyword(tagName)],
    ['origin',  keyword('jdt/cli')],
    ['message', err.message || String(err)],
  ]);
  if (err.code) descriptor.set('code', err.code);
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
        + "`jdt q ':<name> | spec'`.\n");
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
    const cellEntry = await session.evalCell(
        `use(:jdt/aliases) | use(:jdt/graph) | use(:jdt/coverage) | ${querySource}`);

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
Exit is always 0; errors land on stdout as \`::TagName!{:field …}\`
values — route with \`!|\`. Per-site identity rides on the head tag
(\`!| type\` returns it as \`::TagKeyword\`); the broad bucket lands
on \`:category\` (\`!| type | spec | /category\`).

───── fqn — fully qualified name ─────

  fqn identifies a workspace element. Format depends on element kind:

  <Type>      pkg.Class                         inner: pkg.Outer.Inner
  <Method>    pkg.Class#name(ParamType,…)       signature optional — required only to disambiguate overloads
  <Field>     pkg.Class#name
  <Package>   pkg.sub
  <Project>   eclipse project name (NOT a path)
  <File>      absolute filesystem path

  Interfaces / annotations / enums / records share the <Type> format.

───── Cookbook ─────

  # Find types by wildcard
  jdt q '"*Service" | @types * /fqn'

  # Read source — Type, Method, or Field; library sources too when a JAR has source attachment
  jdt q '"<Method>" | @source'

  # Markdown card — header + code fence + outgoing / incoming refs
  jdt q '"<Method>" | @sourceCard'

  # Callers of a method
  jdt q '"<Method>" | @callers * /fqn'

  # Outgoing calls of a method
  jdt q '"<Method>" | @calls * /fqn'

  # Supertype chain of a type
  jdt q '"<Type>" | @ancestors * /fqn'

  # Subtypes of a type (all descendants), or implementations of an abstract/interface method
  jdt q '"<Type>" | @implementors * /fqn'

  # Compile errors in a project (drop filter or use /warning to see warnings instead)
  jdt q '"<Project>" | @problems | filter(/error) * {:file /location/file :line /location/startLine :msg /message}'

  # Types in a project carrying an annotation (annotation is a <Type>)
  jdt q '"<Project>" | @typesInProject | filter(@annotatedWith("<Type>")) * /fqn'

  # Tests that exercise a type
  jdt q '"<Type>" | @tests * /fqn'

  # Existence check — node-Map on success, ::TypeNotFound tag on miss
  jdt q '"<Type>" | @type !| type'

  # Hotspots — biggest methods in a type
  jdt q '"<Type>" | @methods | sortWith(desc(/location/lineCount)) | take(5) * {:fqn /fqn :lines /location/lineCount}'

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
  "text"       String              :keyword  Keyword     ::Tag  TagKeyword
  as(:name)    snapshot pipeValue under :name
  :name body | :name [:p] body                BindStep — names a binding (conduit if param)

───── Axes (seeds + navigation) ─────

Seeds (string → node, or nullary):
  "fqn"                | @type  @method  @field  @package  @project  @file
  "*pattern*"          | @types
  @projects            @problems

Containment:
  type    | @members  @methods  @fields  @innerTypes
  package | @typesInPackage
  file    | @typesInFile
  project | @packagesInProject  @typesInProject
  project | @classpath
  node    | @containingType  @containingPackage
            @containingFile  @containingProject

Hierarchy:
  type   | @supers  @subtypes  @ancestors  @descendants  @implementors
  method | @overrides  @overloads  @implementors

References (nullary, subject from pipeValue):
  node   | @incomingRefs          default refKind by subject kind
  node   | @incomingRefs(:all)    modifier WIDENS — every refKind
  field  | @incomingRefs(:read|:write)
  member | @outgoingRefs          what the body touches

Sugar conduits:
  method  | @callers  @testCallers  @productionCallers
  field   | @readers  @writers
  member  | @calls  @typeUses
  type    | @dependsOn  @usedBy
  type    | @tests                  test-scope callers across type + members
  node    | @detail                 lift skeleton → detail
  element | @annotatedWith(fqn)  @deprecated  @untested   — predicates, use inside filter/every/any
  type    | @publicOrphans  @deadCode
  Vec     | @sourceOnly  @overview  @inProject(projName)

Coverage axes (need an active or pinned coverage session):
  node    | @coverage                /coverage/node Map: counters + lines (when ISourceNode)
  node    | @coverage("MyTest:1234") pin to a specific coverageId
  @activeCoverageId                  String id of the active session, or null
  element | @uncovered  @partial  @fullyCovered  — predicates over instruction status
  ISourceNode | @coveredLines  @uncoveredLines  — Vec<int> line numbers
  ISourceNode | @partialLines        Vec<entry> with branch counts

Markdown cards (return a String — pipe to a file):
  "pkg.Foo#bar()" | @sourceCard      header + code fence + outgoing / incoming refs
  "pkg.Foo"       | @hierarchyCard   ↑ supers / ↓ subtypes
  "pkg.Foo"       | @outlineCard     fields / methods / inner types
  "pkg.Foo"       | @coverageCard    counters table + Lines section
  Vec<:reference> | mdRefs           grouped by refKind

Host-bound I/O + format (from qlang-cli):
  @in / @out / @err / @tap                  stdio passthrough
  parseJson / parseTjson                    String → qlang value
  pretty / tjson / template(t)              value → String

───── Discovery — the catalog is itself qlang data ─────

  manifest | count                              all ops
  manifest | filter(/effectful) * /name         every axis name
  :@members | spec                              descriptor (kind, subject, returns, throws)
  :@members | docs                              prose from the attached doc-prefix
  :@members | examples                          executable ~{…} examples
  :@callers | source                            verbatim BindStep source
  :@members | runExamples                       run examples against the live session

───── Debug — errors are data ─────

  expr !| type                    ::TagKeyword — per-site identity (::TypeNotFound, ::AmbiguousMatch …)
  expr !| type | spec / docs      definition + prose for the tag
  expr !| /category               broad bucket (:type-error, :jdt/coverage …)
  expr !| /context                structured context — operand-specific fields
  expr !| /trail * /text          which steps deflected

───── Paths ─────

Paths in response Maps (:path, :file, :rootPath, :outputLocation)
are absolute on the Eclipse host; \`jdt setup remote\` rewrites
them to local mount-point paths for sandboxed agents. :fqn is an
identifier — round-trip it to the API verbatim.

Full qlang reference:
  https://github.com/kaluchi/qlang/blob/master/docs/qlang-spec.md`;
