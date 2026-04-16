// jdt q <qlang-query> — evaluate a qlang pipeline against the :jdt/graph module.

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createSession } from '@kaluchi/qlang-core/session';
import { printValue } from '@kaluchi/qlang-core';
import { keyword, isErrorValue } from '@kaluchi/qlang-core';
import { createImpls } from '../../lib/jdt/search.impl.mjs';
import { createImpls as createGraphImpls } from '../../lib/jdt/graph.impl.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, '..', '..', 'lib');

function createLocator() {
  const implFactories = {
    'jdt/search': createImpls,
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

Examples:
  jdt q '@types("*Service") * /fqn'
  jdt q '@type("test.model.Dog") | @members | filter(/modifiers | has(:public))'
  jdt q '@subtypes("test.model.Animal") * /fqn'
  jdt q '@refs("test.model.Dog#bark()") * /from/fqn | distinct'
  jdt q '@types("*") | as(:all) | all * @methods | filter(/modifiers | has(:public)) | filter(@callers | empty) * /fqn'

All 69 qlang builtins are available (filter, sort, count, groupBy, * fan-out, !| fail-track).
Bare-name reify on any operand without args = its descriptor:
  jdt q '@subtypes'   shows :docs/:examples/:throws for the operand.
  jdt q 'manifest | filter(/category | eq(:jdt/graph)) * /name'
  -- enumerate the full graph axis catalog.`;
