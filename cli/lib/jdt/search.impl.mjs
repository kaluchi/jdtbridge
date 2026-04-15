// Host implementations for jdt/search module.
//
// Each export is a dispatch-wrapped function value that the locator
// patches onto the corresponding :qlang/impl field. The impls call
// client.mjs HTTP endpoints — already async, works natively with
// qlang's async eval chain.

import { nullaryOp, overloadedOp } from '@kaluchi/qlang-core/dispatch';
import { keyword } from '@kaluchi/qlang-core';
import { get } from '../../src/client.mjs';
import { parseFqmn } from '../../src/args.mjs';

// Build the class[&method[&paramTypes]] query-string tail that
// /references and /source both accept. Parses FQMN (Javadoc
// `Class#method(params)` and Eclipse `Class.method(params)` styles)
// so specific overloads route correctly.
function fqmnToQuery(fqmn) {
  const parsed = parseFqmn(fqmn);
  if (!parsed.className) return null;
  let qs = `class=${encodeURIComponent(parsed.className)}`;
  if (parsed.method) qs += `&method=${encodeURIComponent(parsed.method)}`;
  if (parsed.paramTypes) {
    qs += `&paramTypes=${encodeURIComponent(parsed.paramTypes.join(','))}`;
  }
  return qs;
}

// Recursively convert plain JS values from HTTP JSON into qlang
// shapes: objects → Maps with keyword keys, arrays → Vecs (still
// JS arrays — qlang treats arrays as Vecs). Scalars pass through.
// Used by every impl so projections like /kind and distribute * /name
// work through nested :children without further ceremony.
function jsonToQlang(jsonVal) {
  if (Array.isArray(jsonVal)) return jsonVal.map(jsonToQlang);
  if (jsonVal !== null && typeof jsonVal === 'object') {
    const qlangMap = new Map();
    for (const [jsonKey, nestedVal] of Object.entries(jsonVal)) {
      qlangMap.set(keyword(jsonKey), jsonToQlang(nestedVal));
    }
    return qlangMap;
  }
  return jsonVal;
}

async function searchByName(namePattern, sourceOnly) {
  let url = `/find?name=${encodeURIComponent(namePattern)}`;
  if (sourceOnly) url += '&source';
  const results = await get(url, 30_000);
  if (results.error) return [];
  return results.map(jsonToQlang);
}

// @find — search Eclipse workspace for Java type declarations.
// Subject-first: pipeValue is the name/pattern string.
//   "pattern" | @find                 → all types matching pattern
//   "pattern" | @find(:source-only)   → workspace source only
const findImpl = overloadedOp('@find', 1, {
  0: async (namePattern) => searchByName(namePattern, false),
  1: async (namePattern, flagLambda) => {
    const flagVal = await flagLambda(namePattern);
    return searchByName(namePattern, flagVal?.name === 'source-only');
  }
});

// @callers — find all references to a type or method.
// Subject is an FQMN string: "pkg.Class", "pkg.Class#method",
// or a specific overload "pkg.Class#method(Param1,Param2)".
const callersImpl = nullaryOp('@callers', async (fqmn) => {
  const qs = fqmnToQuery(fqmn);
  if (!qs) return [];
  const results = await get(`/references?${qs}`, 30_000);
  if (results.error) return [];
  return results.map(jsonToQlang);
});

// @source — full composite for a type or method: source text, line
// range, outgoing/incoming refs, implementations, hierarchy. Returns
// the Map as-is so qlang pipelines can project/filter/reshape freely.
// See @outgoing / @incoming conduits in search.qlang for common cuts.
const sourceImpl = nullaryOp('@source', async (fqmn) => {
  const qs = fqmnToQuery(fqmn);
  if (!qs) return new Map();
  const data = await get(`/source?${qs}`, 30_000);
  return jsonToQlang(data);
});

// @members — list members of a Java type (Eclipse Outline View).
// Returns the top-level children as a Vec so filter/sort/distribute
// apply directly. Inner types keep their own nested :children Vec.
const membersImpl = nullaryOp('@members', async (typeFqn) => {
  const url = `/outline?class=${encodeURIComponent(typeFqn)}`;
  const outline = await get(url, 30_000);
  if (outline.error) return [];
  const topLevelChildren = outline.children || [];
  return topLevelChildren.map(jsonToQlang);
});

export function createImpls() {
  return {
    '@find': findImpl,
    '@callers': callersImpl,
    '@members': membersImpl,
    '@source': sourceImpl
  };
}
