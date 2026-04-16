// jdt q <qlang-query> — evaluate a qlang pipeline against the :jdt/graph module.

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createSession } from '@kaluchi/qlang-core/session';
import { printValue } from '@kaluchi/qlang-core';
import { keyword, isErrorValue } from '@kaluchi/qlang-core';
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

export async function query(args) {
  const jsonFlag = args.includes('--json');
  const queryParts = args.filter(a => !a.startsWith('--'));
  const querySource = queryParts[0];
  if (!querySource) {
    console.error('Usage: jdt q <qlang-query> [--json]');
    process.exit(1);
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
    console.error(cellEntry.error.message);
    process.exit(1);
  }

  const queryResult = cellEntry.result;
  if (jsonFlag) {
    const { toTaggedJSON } = await import('@kaluchi/qlang-core/codec');
    console.log(JSON.stringify(toTaggedJSON(queryResult), null, 2));
  } else if (isErrorValue(queryResult)) {
    const desc = queryResult.descriptor;
    const thrown = desc.get(keyword('thrown'));
    const msg = desc.get(keyword('message'));
    console.error(`Error: ${thrown?.name ?? 'unknown'} — ${msg ?? ''}`);
    process.exit(1);
  } else if (didExplicitStdoutEffect) {
    // The pipeline already pushed bytes to stdout via @out — stay
    // silent on the auto-print to avoid double-output.
  } else if (typeof queryResult === 'string') {
    // Strings print raw (no quotes/escapes) — `@source` returns the
    // file's source text, which should land on stdout exactly as it
    // appears on disk for `jdt q '"X" | @source' > X.java` workflows.
    console.log(queryResult);
  } else {
    console.log(printValue(queryResult));
  }
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
  -- enumerate the full graph axis catalog.`;
