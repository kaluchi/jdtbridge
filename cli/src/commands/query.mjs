// jdt q <qlang-query> — evaluate a qlang pipeline against the :jdt/graph module.
//
// Read-only command: every outcome (parse error, CLI-argument error,
// server fail-track, legitimate error-value result) exits 0 with the
// error descriptor printed on stdout in the same shape qlang's `!{}`
// literal produces. Non-zero exit would cancel sibling parallel tool
// calls in Claude Code; errors travel as data, not as exit status.

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
import { toTaggedJSON } from '@kaluchi/qlang-core/codec';
import { bindIoOperands } from '@kaluchi/qlang-cli/io-operands';
import { bindFormatOperands } from '@kaluchi/qlang-cli/format-operands';
import { bindParseOperands } from '@kaluchi/qlang-cli/parse-operands';
import { createImpls as createGraphImpls } from '../../lib/jdt/graph.impl.mjs';

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

function printQueryResult(value, jsonFlag) {
  if (jsonFlag) {
    console.log(JSON.stringify(toTaggedJSON(value), null, 2));
  } else if (typeof value === 'string' && !isErrorValue(value)) {
    // Raw string results (e.g. `@source` returning a file's contents)
    // print without quotes/escapes so `jdt q '"X" | @source' > X.java`
    // produces a byte-faithful file.
    console.log(value);
  } else {
    console.log(printValue(value));
  }
}

export async function query(args) {
  const jsonFlag = args.includes('--json');
  const queryParts = args.filter(a => !a.startsWith('--'));
  const querySource = queryParts[0];
  if (!querySource) {
    printQueryResult(
      usageErrorValue(
        'jdt q requires a qlang pipeline as its first positional argument.',
        'jdt q <qlang-query> [--json]'
      ),
      jsonFlag
    );
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
    stdinReader: () => Promise.resolve(''),
    stdoutWrite: (text) => process.stdout.write(text),
    stderrWrite: (text) => process.stderr.write(text),
    recordStdoutEffect: () => { didExplicitStdoutEffect = true; },
  });
  bindFormatOperands(session);
  bindParseOperands(session);
  const cellEntry = await session.evalCell(`use(:jdt/graph) | ${querySource}`);

  if (cellEntry.error) {
    printQueryResult(parseErrorToValue(cellEntry.error, cellEntry.uri), jsonFlag);
    return;
  }

  if (didExplicitStdoutEffect) {
    // The pipeline already pushed bytes to stdout via @out — stay
    // silent on the auto-print to avoid double-output.
    return;
  }
  printQueryResult(cellEntry.result, jsonFlag);
}

export const help = `Evaluate a qlang pipeline against the Eclipse JDT graph.

Usage:  jdt q <qlang-query> [--json]

Runs with use(:jdt/graph) pre-loaded. The graph is navigated through
operand axes that all consume node-Maps OR fqn/fqmn Strings (subject
polymorphism); every operand returns canonical-shape nodes (skeletons
or detail) with five-field headers (:fqn :kind :origin :location
:containingProject) plus per-kind detail fields.

Root queries:
  @types("*Pat*") [:sourceOnly]    workspace pattern search
  @type(fqn)  @method(fqmn)  @field(fqmn)
  @project(name)  @projects  @package(fqn)  @file(absPath)

Containment:
  @containingType(node)  @containingProject(node)
  @members @methods @fields @innerTypes (node)
  @typesInPackage @typesInFile @packagesInProject (node)

Hierarchy:
  @supers @subtypes @implementors (node)
  @overrides(method)  @overloads(method)

References:
  @refs(node) [:call|:read|:write|:typeUse|:all]
  @callers(node) @readers(node) @writers(node)  -- sugar conduits

Detail:
  @detail(skeleton)  -> detail-node    @classpath(project)

Pipelines start with a SEED (literal string or nullary operand)
and chain operands that read pipeValue. Operands never take FQN
as captured args — that's RPC. Examples:

  jdt q '"*Service" | @types * /fqn'
  jdt q '"test.model.Dog" | @type | @members | filter(/modifiers | any(eq("public")))'
  jdt q '"test.model.Animal" | @subtypes * /fqn'
  jdt q '"test.model.Dog#bark()" | @refs * /from/fqn | distinct'
  jdt q '@projects * @members * @methods | filter(/modifiers | any(eq("public"))) | filter(@callers | empty) * /fqn'

All 69 qlang builtins are available (filter, sort, count, groupBy, * fan-out, !| fail-track).
Bare-name reify on any operand = its descriptor:
  jdt q '@subtypes'   shows :docs/:examples/:throws for the operand.
  jdt q 'manifest | filter(/category | eq(:jdt/graph)) * /name'
  -- enumerate the full graph axis catalog.

Exit code is always 0 — errors (parse, usage, fail-track) travel on
stdout as qlang error values (\`!{:kind ... :message ...}\`). Use
\`!|\` inside the pipeline to route around errors.`;
