// jdt q <qlang-query> — evaluate a qlang pipeline against the JDT search module.

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createSession } from '@kaluchi/qlang-core/session';
import { printValue } from '@kaluchi/qlang-core';
import { keyword, isErrorValue } from '@kaluchi/qlang-core';
import { createImpls } from '../../lib/jdt/search.impl.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const MODULE_LIB = join(__dirname, '..', '..', 'lib');

function createLocator() {
  const implFactories = {
    'jdt/search': createImpls
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
  const cellEntry = await session.evalCell(`use(:jdt/search) | ${querySource}`);

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

export const help = `Evaluate a qlang pipeline against the JDT search module.

Usage:  jdt q <qlang-query> [--json]

The query runs with use(:jdt/search) pre-loaded. Available operands:
  @find(<kind>)    search workspace for declarations (default: :type)
  @callers         find all call sites of an FQMN
  @orphans         shorthand: methods with no callers

Examples:
  jdt q '@find(:method, "process") | count'
  jdt q '@find("*Controller*") * /fqn'
  jdt q '@orphans("MyService") * /fqn'
  jdt q '@callers("com.example.Foo#bar") | count'

All 69 qlang builtins are available (filter, sort, count, groupBy, etc.).
Use "jdt q '@find(:type, \"Name\") | reify(:@find)'" to inspect descriptors.`;
